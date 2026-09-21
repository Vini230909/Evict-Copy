// The way back to the hub: players returned, spectators returned or hopped, the worker closing when empty.
package Extinction;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import arc.Core;
import arc.util.Align;
import arc.util.Log;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;

public final class WorkerExit {

    // Grace before an empty worker closes: no player ever arrived / everyone left / match decided.
    public static final int STARTUP_GRACE_SECONDS = 90;
    public static final int EMPTY_GRACE_SECONDS = 60;
    // A decided match has nothing to wait for; this is what gets it into /history in seconds.
    public static final int RESOLVED_GRACE_SECONDS = 5;

    // An abandoned worker still being watched counts down out loud: 5 s ticking, 3 s hold at zero.
    private static final int WORKER_CLOSE_COUNTDOWN_SECONDS = 5;
    private static final int WORKER_CLOSE_HOLD_SECONDS = 3;
    private static final float WORKER_CLOSE_HUD_SECONDS = 3f;
    private static final int WORKER_CLOSE_HUD_RAISE = 220;
    private static final String WORKER_CLOSE_HUD_ID = "evict-worker-close";

    private final Referee referee;
    private final ScheduledExecutorService scheduler;

    private String hubIp = "";
    private int hubPort = 6567;
    private boolean shutdownStarted = false;

    public WorkerExit(Referee referee, ScheduledExecutorService scheduler) {
        this.referee = referee;
        this.scheduler = scheduler;
    }

    void setHub(String ip, int port) {
        this.hubIp = ip;
        this.hubPort = port;
    }

    public String hubIp() {
        return hubIp;
    }

    public int hubPort() {
        return hubPort;
    }

    public void returnSpectatorToHub(Player player) {
        if (!referee.isActive() || player == null) {
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

    public List<Referee.SiblingMatch> listOtherOngoingMatches() {
        return referee.isActive() ? WorkerStatus.listSiblings() : new ArrayList<>();
    }

    // Workers share the hub's IP (one machine), so the hub address reaches the sibling too.
    public boolean connectSpectatorToSibling(Player player, int port) {
        if (
                !referee.isActive()
                        || player == null
                        || hubIp == null
                        || hubIp.isBlank()
        ) {
            return false;
        }

        boolean stillOngoing = listOtherOngoingMatches().stream()
                .anyMatch(match -> match.port() == port);

        if (!stillOngoing) {
            return false;
        }

        player.sendMessage(
                "[accent]Connecting you to the next match as a spectator...[]"
        );
        Call.connect(player.con, hubIp, port);
        return true;
    }

    // A map reset clears Time.run tasks; returning players must survive both resets and pauses.
    void scheduleReturnToHub() {
        referee.status.write();
        scheduler.schedule(() -> Core.app.post(this::returnPlayersToHub), 5, TimeUnit.SECONDS);
    }

    void returnPlayersToHub() {
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

    void scheduleShutdownIfEmpty(int seconds) {
        scheduler.schedule(
                () -> Core.app.post(() -> {
                    if (isEmptyOfParticipants()) {
                        beginWorkerShutdown();
                    }
                }),
                seconds,
                TimeUnit.SECONDS
        );
    }

    // No participants left: a resolved match exits at once; an abandoned one counts down first.
    private void beginWorkerShutdown() {
        if (shutdownStarted) {
            return;
        }
        shutdownStarted = true;

        // Final status write, so the hub credits the last seconds of playtime.
        referee.status.write();

        if (referee.resolved() || Groups.player.isEmpty()) {
            Log.info(
                    "[EvictMapGenerator] Duel worker has no participants left; shutting down to free the slot."
            );
            System.exit(0);
            return;
        }

        Log.info(
                "[EvictMapGenerator] Duel worker abandoned with watchers connected; closing in @ s (+@ s hold).",
                WORKER_CLOSE_COUNTDOWN_SECONDS,
                WORKER_CLOSE_HOLD_SECONDS
        );

        // Real-time ticks, not Time.run: an unresumed disconnect pause would never fire them.
        for (int second = WORKER_CLOSE_COUNTDOWN_SECONDS; second >= 1; second--) {
            int remaining = second;
            scheduler.schedule(
                    () -> Core.app.post(() -> showCloseCountdownHud(
                            "[accent]Match server closing in [scarlet]" + remaining + "s[]"
                    )),
                    WORKER_CLOSE_COUNTDOWN_SECONDS - second,
                    TimeUnit.SECONDS
            );
        }

        scheduler.schedule(
                () -> Core.app.post(() -> showCloseCountdownHud(
                        "[scarlet]Match server closing...[]"
                )),
                WORKER_CLOSE_COUNTDOWN_SECONDS,
                TimeUnit.SECONDS
        );

        scheduler.schedule(
                () -> Core.app.post(() -> System.exit(0)),
                WORKER_CLOSE_COUNTDOWN_SECONDS + WORKER_CLOSE_HOLD_SECONDS,
                TimeUnit.SECONDS
        );
    }

    private void showCloseCountdownHud(String message) {
        Call.infoPopup(
                message,
                WORKER_CLOSE_HUD_ID,
                WORKER_CLOSE_HUD_SECONDS,
                Align.center,
                0,
                0,
                WORKER_CLOSE_HUD_RAISE,
                0
        );
    }

    // Spectators never keep a worker alive; before the handshake, the raw player count decides.
    private boolean isEmptyOfParticipants() {
        if (!referee.handshakeLoaded()) {
            return Groups.player.isEmpty();
        }

        for (String uuid : referee.participantUuids()) {
            if (referee.isOnline(uuid)) {
                return false;
            }
        }

        return true;
    }
}
