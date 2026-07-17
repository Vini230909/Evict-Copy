package vini.evictmap;

import arc.util.Time;
import vini.evictmap.core.text.Text;
import vini.evictmap.core.util.PluginLog;
import vini.evictmap.core.util.Ticks;

import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * Graceful hub restart for updates.
 *
 * <p>The plugin never relaunches anything itself: an external start script runs
 * the server in a {@code while :} loop (see {@code docs/RESTART_LOOP.md}), so all
 * this does is exit cleanly at a good moment and let the loop bring the server
 * back up on whatever jar is on disk. On restart the hub's
 * {@code refreshWorkerJars()} pulls every duel worker onto the new jar, so one
 * hub restart updates everything.
 *
 * <p>A queued restart fires when <em>no duel worker is running</em> and either
 * the hub round just ended (best case: the fresh process generates the next
 * round so nobody loses progress), the hub is empty, or the round is still young
 * (&lt; 10 min, after a 30 s warning). Otherwise it waits for the current round
 * to end. Running matches are never killed - {@code evictrestart now} is the
 * only path that exits immediately.
 */
public final class RestartManager {

    /** A round younger than this is cheap to interrupt (after a short warning). */
    private static final long YOUNG_ROUND_MILLIS = 10L * 60L * 1000L;

    private final IntSupplier activeWorkers;
    private final IntSupplier hubPlayers;
    private final LongSupplier roundAgeMillis;
    private final Runnable exitAction;

    private boolean queued;
    private long warningSerial;

    public RestartManager(
            IntSupplier activeWorkers,
            IntSupplier hubPlayers,
            LongSupplier roundAgeMillis,
            Runnable exitAction
    ) {
        this.activeWorkers = activeWorkers;
        this.hubPlayers = hubPlayers;
        this.roundAgeMillis = roundAgeMillis;
        this.exitAction = exitAction;
    }

    /** {@code evictrestart}: queue a restart, or report what a queued one waits for. */
    public void requestRestart() {
        if (queued) {
            PluginLog.info("Restart already queued. @", waitDescription());
            return;
        }
        queued = true;
        PluginLog.info("Restart queued.");
        attempt(false);
    }

    /** {@code evictrestart cancel}: drop a queued restart (and any pending warning). */
    public void cancelRestart() {
        if (!queued) {
            PluginLog.info("No restart is queued.");
            return;
        }
        queued = false;
        warningSerial++;
        PluginLog.info("Queued restart cancelled.");
    }

    /** {@code evictrestart now}: announce and exit immediately, killing any matches. */
    public void restartNow() {
        PluginLog.warn("Immediate restart requested - exiting now.");
        Text.of().scarlet("The server is restarting for an update. Reconnect in a moment.").sendAll();
        // A one-second delay lets the announcement flush before the JVM exits.
        Time.run(Ticks.seconds(1), exitAction);
    }

    /** Hook from the hub's round-victory handler: a queued restart fires cleanly here. */
    public void onRoundEnded() {
        if (queued) {
            attempt(true);
        }
    }

    public boolean isQueued() {
        return queued;
    }

    private void attempt(boolean roundJustEnded) {
        if (!queued) {
            return;
        }

        int workers = activeWorkers.getAsInt();
        if (workers > 0) {
            PluginLog.info("Restart waiting: @ duel worker(s) still running.", workers);
            return;
        }

        if (roundJustEnded || hubPlayers.getAsInt() == 0) {
            fireExit(roundJustEnded ? "round ended" : "hub empty");
            return;
        }

        if (roundAgeMillis.getAsLong() < YOUNG_ROUND_MILLIS) {
            warnThenExit();
            return;
        }

        PluginLog.info("Restart waiting: round is past 10 minutes - will restart when it ends.");
    }

    private void warnThenExit() {
        long serial = ++warningSerial;
        Text.of().scarlet("Server restarting for an update in 30 seconds...").sendAll();
        Time.run(Ticks.seconds(30), () -> {
            if (queued && serial == warningSerial) {
                fireExit("30s warning elapsed");
            }
        });
    }

    private void fireExit(String reason) {
        PluginLog.info("Restarting now (@). The start-script loop will bring the server back up.", reason);
        queued = false;
        exitAction.run();
    }

    private Text waitDescription() {
        int workers = activeWorkers.getAsInt();
        if (workers > 0) {
            return Text.of().add("Waiting for ").num(workers).add(" duel worker(s) to finish.");
        }
        if (roundAgeMillis.getAsLong() >= YOUNG_ROUND_MILLIS) {
            return Text.of().add("Waiting for the current round to end.");
        }
        return Text.of().add("Ready - restarting shortly.");
    }
}
