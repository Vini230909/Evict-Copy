package vini.evictmap;

import arc.Core;
import arc.util.Align;
import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mindustry.core.GameState;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Worker-side 1v1 referee. Only active when the process is launched as a duel
 * worker (-Devict.duelWorker=true); on the hub this class does nothing.
 *
 * - reads the hub handshake (the two player UUIDs + the hub address to return to),
 * - once both players are connected, freezes the game and runs a 5-second
 *   on-screen countdown, then unfreezes ("GO"),
 * - if a player disconnects mid-match, pauses until they return (the hub bounces
 *   a reconnecting player straight back here),
 * - on an Evict victory writes a result file, announces the winner, and sends
 *   both players back to the hub; the worker then empties and self-terminates.
 *
 * The countdown uses a real-time executor (not the logic timer) because the game
 * is paused during it, which would otherwise stall logic-timed tasks.
 */
final class DuelWorker {

    private static final File HANDSHAKE_FILE = new File("duel.properties");
    private static final File RESULT_FILE = new File("result.properties");

    private static final int COUNTDOWN_SECONDS = 5;
    private static final float RETURN_DELAY_TICKS = 5f * 60f;

    private final boolean active;

    private final ScheduledExecutorService countdownExecutor =
        Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "evict-duel-countdown");
            thread.setDaemon(true);
            return thread;
        });

    private String hubIp = "";
    private int hubPort = 6567;
    private String player1Uuid = "";
    private String player2Uuid = "";

    private boolean handshakeLoaded = false;
    private boolean countdownStarted = false;
    private boolean matchStarted = false;
    private boolean pausedForDisconnect = false;
    private boolean resolved = false;

    DuelWorker() {
        this.active = "true".equals(System.getProperty("evict.duelWorker"));
    }

    boolean isActive() {
        return active;
    }

    /** Called once the worker has hosted its round. */
    void begin() {
        if (!active) {
            return;
        }

        loadHandshake();
    }

    void handlePlayerJoin(Player player) {
        if (!active) {
            return;
        }

        if (matchStarted) {
            if (pausedForDisconnect && bothPlayersPresent()) {
                pausedForDisconnect = false;
                resumeGame();
                Call.sendMessage(
                    "[accent]Both players are back. Resuming the 1v1![]"
                );
            }
            return;
        }

        if (!countdownStarted && bothPlayersPresent()) {
            startCountdown();
        }
    }

    void handlePlayerLeave(Player player) {
        if (
            !active
                || !matchStarted
                || resolved
                || pausedForDisconnect
                || player == null
        ) {
            return;
        }

        String uuid = player.uuid();

        if (uuid.equals(player1Uuid) || uuid.equals(player2Uuid)) {
            pausedForDisconnect = true;
            pauseGame();
            Call.sendMessage(
                "[scarlet]A player disconnected. The 1v1 is paused until they return.[]"
            );
        }
    }

    /**
     * Wired in place of the hub's normal round-victory handler when running as
     * a worker. Records the result and returns both players to the hub.
     */
    void handleVictory(Team winner) {
        if (!active || resolved) {
            return;
        }

        resolved = true;

        // A win during a disconnect pause still needs the world running so the
        // return countdown ticks.
        if (pausedForDisconnect) {
            pausedForDisconnect = false;
            resumeGame();
        }

        Player winnerPlayer = Groups.player.find(
            player -> player != null && player.team() == winner
        );

        String winnerUuid = winnerPlayer != null ? winnerPlayer.uuid() : "";
        String loserUuid = otherPlayerUuid(winnerUuid);

        writeResult(winnerUuid, loserUuid);

        String winnerName =
            winnerPlayer != null
                ? PlayerNameFormatter.displayName(winnerPlayer)
                : "The winner";

        Call.sendMessage(
            "[accent]" + winnerName
                + "[accent] won the 1v1. Returning to the lobby in 5 seconds...[]"
        );

        Time.run(RETURN_DELAY_TICKS, this::returnPlayersToHub);

        Log.info(
            "[EvictMapGenerator] Duel result: winner=@ loser=@.",
            winnerUuid.isEmpty() ? "unknown" : winnerUuid,
            loserUuid.isEmpty() ? "unknown" : loserUuid
        );
    }

    private void returnPlayersToHub() {
        if (hubIp == null || hubIp.isBlank()) {
            Log.err(
                "[EvictMapGenerator] Duel worker has no hub address; cannot return players."
            );
            return;
        }

        Groups.player.each(player -> {
            if (player != null) {
                Call.connect(player.con, hubIp, hubPort);
            }
        });

        Log.info(
            "[EvictMapGenerator] Duel worker returned players to the lobby at @:@.",
            hubIp,
            hubPort
        );
    }

    private void startCountdown() {
        countdownStarted = true;
        pauseGame();

        Call.sendMessage("[accent]Both players are here. The 1v1 begins soon![]");

        for (int second = COUNTDOWN_SECONDS; second >= 1; second--) {
            int remaining = second;

            countdownExecutor.schedule(
                () -> Core.app.post(() -> showCountdown(remaining)),
                COUNTDOWN_SECONDS - second,
                TimeUnit.SECONDS
            );
        }

        countdownExecutor.schedule(
            () -> Core.app.post(this::startMatch),
            COUNTDOWN_SECONDS,
            TimeUnit.SECONDS
        );
    }

    private void showCountdown(int remaining) {
        Call.infoPopup(
            "[accent]1v1 starts in [scarlet]" + remaining + "[]",
            1.1f,
            Align.center,
            0,
            0,
            0,
            0
        );
    }

    private void startMatch() {
        matchStarted = true;
        resumeGame();
        Call.infoPopup("[green]GO![]", 2f, Align.center, 0, 0, 0, 0);
        Call.sendMessage("[green]1v1 started. Destroy the enemy core to win![]");
    }

    private void pauseGame() {
        Vars.state.set(GameState.State.paused);
        Log.info("[EvictMapGenerator] Duel worker paused the match.");
    }

    private void resumeGame() {
        Vars.state.set(GameState.State.playing);
        Log.info("[EvictMapGenerator] Duel worker resumed the match.");
    }

    private boolean bothPlayersPresent() {
        if (!handshakeLoaded) {
            return false;
        }

        return isOnline(player1Uuid) && isOnline(player2Uuid);
    }

    private boolean isOnline(String uuid) {
        if (uuid == null || uuid.isEmpty()) {
            return false;
        }

        return Groups.player.find(
            player -> player != null && player.uuid().equals(uuid)
        ) != null;
    }

    private String otherPlayerUuid(String uuid) {
        if (uuid.equals(player1Uuid)) {
            return player2Uuid;
        }

        if (uuid.equals(player2Uuid)) {
            return player1Uuid;
        }

        return "";
    }

    private void loadHandshake() {
        if (!HANDSHAKE_FILE.exists()) {
            Log.warn(
                "[EvictMapGenerator] Duel worker found no handshake file (@); start gate and return disabled.",
                HANDSHAKE_FILE.getPath()
            );
            return;
        }

        Properties properties = new Properties();

        try (FileInputStream input = new FileInputStream(HANDSHAKE_FILE)) {
            properties.load(input);

            hubIp = properties.getProperty("hub.ip", "").trim();
            hubPort = parsePort(properties.getProperty("hub.port"), 6567);
            player1Uuid = properties.getProperty("player1.uuid", "").trim();
            player2Uuid = properties.getProperty("player2.uuid", "").trim();
            handshakeLoaded = true;

            Log.info(
                "[EvictMapGenerator] Duel worker loaded handshake: hub=@:@ players=@ vs @.",
                hubIp,
                hubPort,
                player1Uuid,
                player2Uuid
            );
        } catch (Exception exception) {
            Log.err(
                "[EvictMapGenerator] Duel worker could not read the handshake file.",
                exception
            );
        }
    }

    private void writeResult(String winnerUuid, String loserUuid) {
        Properties properties = new Properties();
        properties.setProperty("winner.uuid", winnerUuid);
        properties.setProperty("loser.uuid", loserUuid);
        properties.setProperty("reason", "victory");

        try (FileOutputStream output = new FileOutputStream(RESULT_FILE)) {
            properties.store(output, "Evict duel result");
        } catch (Exception exception) {
            Log.err(
                "[EvictMapGenerator] Duel worker could not write the result file.",
                exception
            );
        }
    }

    private static int parsePort(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }

        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }
}
