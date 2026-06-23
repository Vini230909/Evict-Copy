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
 * Worker-side 1v1 referee. Only active when launched as a duel worker
 * (-Devict.duelWorker=true); on the hub this class does nothing.
 *
 * - reads the hub handshake (the two player UUIDs + hub address),
 * - lets the world run for a brief settle window when a duelist joins so their
 *   unit spawns at their core and their client camera snaps onto it, then
 *   freezes the match (an instant freeze leaves the camera stuck at the map
 *   origin until the first unpause, because the spawn resolves on a world tick),
 * - once both are present, runs a 5-second countdown (HUD text), then unfreezes,
 * - if a player disconnects mid-match, pauses and shows a "Xs to rejoin"
 *   countdown; resumes when they return, or after the window if they do not,
 * - on an Evict victory writes a result file, returns both players to the hub,
 * - shuts down once it has sat empty for a grace period,
 * - writes status.properties periodically so the hub's evictduelstatus can show
 *   the live state, game time and connected players.
 *
 * Countdowns, status writes and the empty-shutdown run on a real-time executor,
 * because the game is paused during them and logic-timed tasks would stall.
 */
final class DuelWorker {

    private static final File HANDSHAKE_FILE = new File("duel.properties");
    private static final File RESULT_FILE = new File("result.properties");
    private static final File STATUS_FILE = new File("status.properties");

    private static final int COUNTDOWN_SECONDS = 5;
    private static final int REJOIN_SECONDS = 120;
    private static final int STARTUP_GRACE_SECONDS = 90;
    private static final int EMPTY_GRACE_SECONDS = 60;
    // Once the match is decided there is nothing to wait for, so shut down soon
    // after the players have been sent back. The hub records the result on worker
    // exit, so this is what gets a finished match into /history within seconds.
    private static final int RESOLVED_GRACE_SECONDS = 5;
    private static final int STATUS_INTERVAL_SECONDS = 2;
    private static final float RETURN_DELAY_TICKS = 5f * 60f;
    /**
     * How long the world keeps ticking after a duelist joins before the match is
     * (re-)frozen. The client camera follows its unit even while paused, but on a
     * join that lands during the freeze it never receives the target; a couple of
     * live ticks let the server hand it over so the camera hops onto the player.
     * Kept tiny so the window of free movement is imperceptible (~0.08s at 60tps).
     */
    private static final float CAMERA_SETTLE_TICKS = 5f;

    private final boolean active;

    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "evict-duel-worker");
            thread.setDaemon(true);
            return thread;
        });

    private String hubIp = "";
    private int hubPort = 6567;
    private String player1Uuid = "";
    private String player2Uuid = "";

    private boolean handshakeLoaded = false;
    private boolean startFreezeApplied = false;
    private boolean countdownStarted = false;
    private boolean matchStarted = false;
    private boolean pausedForDisconnect = false;
    private boolean resolved = false;

    private long matchStartMillis = 0L;
    private int disconnectSerial = 0;
    private int matchSerial = 0;
    private int settleSerial = 0;
    private boolean settlePending = false;
    private String disconnectedName = "A player";

    /** Shared id so each duel HUD update replaces the previous popup instead of stacking. */
    private static final String HUD_ID = "duel-hud";
    /**
     * How far above screen centre the duel HUD sits, in UI units. This is bottom
     * padding on a centre-aligned popup, so a larger value lifts the text higher.
     * Kept just above centre so it reads above Mindustry's own status text without
     * drifting up to the out-of-focus top of the screen.
     */
    private static final int HUD_RAISE = 220;

    DuelWorker() {
        this.active = "true".equals(System.getProperty("evict.duelWorker"));
    }

    boolean isActive() {
        return active;
    }

    /** True for the two duelists named in the handshake; everyone else spectates. */
    boolean isParticipant(String uuid) {
        return handshakeLoaded
            && uuid != null
            && (uuid.equals(player1Uuid) || uuid.equals(player2Uuid));
    }

    /** Sends a spectator back to the hub this worker was launched from. */
    void returnSpectatorToHub(Player player) {
        if (!active || player == null) {
            return;
        }

        if (hubIp == null || hubIp.isBlank()) {
            player.sendMessage(
                "[scarlet]Cannot find the lobby to return you to.[]"
            );
            return;
        }

        player.sendMessage("[accent]Returning you to the lobby...[]");
        Call.connect(player.con, hubIp, hubPort);
    }

    /** Called once the worker has hosted its round. */
    void begin() {
        if (!active) {
            return;
        }

        loadHandshake();
        scheduleShutdownIfEmpty(STARTUP_GRACE_SECONDS);

        scheduler.scheduleAtFixedRate(
            () -> Core.app.post(this::writeStatus),
            STATUS_INTERVAL_SECONDS,
            STATUS_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        );
    }

    void handlePlayerJoin(Player player) {
        if (!active) {
            return;
        }

        if (matchStarted) {
            if (pausedForDisconnect && bothPlayersPresent()) {
                endDisconnectPause();
            }
            return;
        }

        if (countdownStarted) {
            return;
        }

        if (player != null && isParticipant(player.uuid())) {
            // A duelist arrived: let the world settle their camera before the
            // freeze (see settleCamerasThenFreeze).
            settleCamerasThenFreeze();
            return;
        }

        // A spectator joined while the duel is still gathering. Keep the match
        // frozen and waiting, but don't slam the freeze on mid-settle and strand
        // a duelist's camera at the origin.
        if (!settlePending && !startFreezeApplied) {
            pauseGame();
            startFreezeApplied = true;
        }
        showWaitingHud();
    }

    /**
     * Keeps the world ticking for a brief window so a freshly joined duelist
     * spawns at their core and their client camera snaps onto the unit, then
     * re-freezes the match (or starts the countdown once both duelists are
     * present). Freezing instantly would block the spawn tick, leaving the
     * camera parked at the map origin (0,0) until the first unpause. Each call
     * supersedes the previous one via {@link #settleSerial} so rapid joins do
     * not stack conflicting freeze/start tasks.
     */
    private void settleCamerasThenFreeze() {
        // The first duelist arrives unpaused, but the second joins while the
        // first is frozen and waiting; resume so their camera settles too.
        if (Vars.state.isPaused()) {
            resumeGame();
        }
        startFreezeApplied = false;
        settlePending = true;
        showWaitingHud();

        int serial = ++settleSerial;
        Time.run(CAMERA_SETTLE_TICKS, () -> {
            if (serial != settleSerial) {
                return;
            }
            settlePending = false;

            if (matchStarted || countdownStarted) {
                return;
            }

            if (bothPlayersPresent()) {
                startCountdown();
            } else {
                pauseGame();
                startFreezeApplied = true;
            }
        });
    }

    void handlePlayerLeave(Player player) {
        if (!active) {
            return;
        }

        scheduleShutdownIfEmpty(
            resolved ? RESOLVED_GRACE_SECONDS : EMPTY_GRACE_SECONDS
        );

        if (
            !matchStarted
                || resolved
                || pausedForDisconnect
                || player == null
        ) {
            return;
        }

        String uuid = player.uuid();

        if (uuid.equals(player1Uuid) || uuid.equals(player2Uuid)) {
            beginDisconnectPause(player);
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

        // The world must run for the return countdown to tick.
        if (pausedForDisconnect) {
            pausedForDisconnect = false;
            disconnectSerial++;
            resumeGame();
        }

        hideHud();

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

        // The camera-settle window leaves the world running, so freeze it now to
        // hold both duelists still through the "starts in N" countdown. Harmless
        // if it is already paused (e.g. a commentator /restart).
        pauseGame();
        startFreezeApplied = true;

        int serial = ++matchSerial;

        for (int second = COUNTDOWN_SECONDS; second >= 1; second--) {
            int remaining = second;

            scheduler.schedule(
                () -> Core.app.post(() -> showCountdown(serial, remaining)),
                COUNTDOWN_SECONDS - second,
                TimeUnit.SECONDS
            );
        }

        scheduler.schedule(
            () -> Core.app.post(() -> startMatch(serial)),
            COUNTDOWN_SECONDS,
            TimeUnit.SECONDS
        );
    }

    private void showCountdown(int serial, int remaining) {
        if (serial != matchSerial) {
            return;
        }

        showHud("[accent]1v1 starts in [scarlet]" + remaining + "[]");
    }

    /**
     * Shows the duel HUD text slightly above screen centre. Replaces any existing
     * duel popup (same {@link #HUD_ID}) so repeated calls during the countdown do
     * not stack. Height is controlled by {@link #HUD_RAISE}.
     */
    private void showHud(String text) {
        Call.infoPopup(text, HUD_ID, 3600f, Align.center, 0, 0, HUD_RAISE, 0);
    }

    /** Removes the duel HUD popup. */
    private void hideHud() {
        Call.infoPopup((String) null, HUD_ID, 0f, Align.center, 0, 0, HUD_RAISE, 0);
    }

    private void startMatch(int serial) {
        if (serial != matchSerial) {
            return;
        }

        matchStarted = true;
        startFreezeApplied = false;
        matchStartMillis = System.currentTimeMillis();
        resumeGame();
        showHud("[green]GO![]");

        scheduler.schedule(
            () -> Core.app.post(this::hideHud),
            2,
            TimeUnit.SECONDS
        );
    }

    /**
     * Resets the match back to the pre-countdown state after the world has been
     * regenerated, so a commentator's /restart re-freezes both duelists and runs
     * a fresh countdown. Bumping the serial invalidates any countdown still in
     * flight from the previous match.
     */
    void restartMatch() {
        if (!active) {
            return;
        }

        matchSerial++;
        disconnectSerial++;
        countdownStarted = false;
        matchStarted = false;
        pausedForDisconnect = false;
        resolved = false;
        matchStartMillis = 0L;

        hideHud();
        pauseGame();
        startFreezeApplied = true;
        Call.sendMessage("[accent]The 1v1 was restarted by a commentator.[]");

        if (bothPlayersPresent()) {
            startCountdown();
        } else {
            showWaitingHud();
        }
    }

    private void beginDisconnectPause(Player player) {
        pausedForDisconnect = true;
        disconnectedName = PlayerNameFormatter.displayName(player);
        pauseGame();

        int serial = ++disconnectSerial;

        for (int second = REJOIN_SECONDS; second >= 1; second--) {
            int remaining = second;

            scheduler.schedule(
                () -> Core.app.post(() -> showRejoinCountdown(serial, remaining)),
                REJOIN_SECONDS - second,
                TimeUnit.SECONDS
            );
        }

        scheduler.schedule(
            () -> Core.app.post(() -> expireDisconnectPause(serial)),
            REJOIN_SECONDS,
            TimeUnit.SECONDS
        );
    }

    private void showRejoinCountdown(int serial, int remaining) {
        if (serial != disconnectSerial || !pausedForDisconnect) {
            return;
        }

        showHud(
            "[scarlet]" + disconnectedName
                + "[scarlet] left  -  [accent]" + remaining
                + "s[scarlet] to rejoin, or the match continues[]"
        );
    }

    /**
     * Central HUD shown while the match is frozen waiting for players: before
     * the countdown when not both are present, and after a mid-match leave. Sits
     * in the same HUD slot as the start countdown.
     */
    private void showWaitingHud() {
        StringBuilder names = new StringBuilder();

        Groups.player.each(connected -> {
            if (connected != null) {
                if (names.length() > 0) {
                    names.append("[white], ");
                }
                names.append(PlayerNameFormatter.displayName(connected));
            }
        });

        showHud(
            names.length() > 0
                ? "[accent]Waiting for players\n[white]" + names + "[]"
                : "[accent]Waiting for players...[]"
        );
    }

    private void expireDisconnectPause(int serial) {
        if (serial != disconnectSerial || !pausedForDisconnect) {
            return;
        }

        pausedForDisconnect = false;
        resumeGame();
        hideHud();
        Call.sendMessage(
            "[scarlet]" + disconnectedName
                + "[scarlet] did not return. The match continues.[]"
        );
    }

    private void endDisconnectPause() {
        pausedForDisconnect = false;
        disconnectSerial++;
        resumeGame();
        hideHud();
        Call.sendMessage("[accent]Both players are back. Resuming the 1v1![]");
    }

    private void scheduleShutdownIfEmpty(int seconds) {
        scheduler.schedule(
            () -> Core.app.post(() -> {
                if (Groups.player.size() == 0) {
                    Log.info(
                        "[EvictMapGenerator] Duel worker is empty; shutting down to free the slot."
                    );
                    System.exit(0);
                }
            }),
            seconds,
            TimeUnit.SECONDS
        );
    }

    private void pauseGame() {
        Vars.state.set(GameState.State.paused);
        Log.info("[EvictMapGenerator] Duel worker paused the match.");
    }

    private void resumeGame() {
        Vars.state.set(GameState.State.playing);
        Log.info("[EvictMapGenerator] Duel worker resumed the match.");
    }

    private void writeStatus() {
        Properties properties = new Properties();
        properties.setProperty("state", currentStateName());
        properties.setProperty(
            "elapsedSeconds",
            Long.toString(matchElapsedSeconds())
        );

        StringBuilder players = new StringBuilder();

        Groups.player.each(player -> {
            if (player != null) {
                if (players.length() > 0) {
                    players.append(",");
                }
                players.append(player.plainName()).append("|").append(player.uuid());
            }
        });

        properties.setProperty("players", players.toString());

        try (FileOutputStream output = new FileOutputStream(STATUS_FILE)) {
            properties.store(output, "Evict duel status");
        } catch (Exception ignored) {
            // Status is best-effort; a missed write just shows stale data.
        }
    }

    private String currentStateName() {
        if (resolved) {
            return "finished";
        }

        if (pausedForDisconnect) {
            return "paused";
        }

        if (!matchStarted) {
            return countdownStarted ? "countdown" : "waiting";
        }

        return "running";
    }

    private long matchElapsedSeconds() {
        if (!matchStarted || matchStartMillis <= 0L) {
            return 0L;
        }

        return (System.currentTimeMillis() - matchStartMillis) / 1000L;
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
