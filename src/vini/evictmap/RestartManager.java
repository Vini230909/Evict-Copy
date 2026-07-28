package vini.evictmap;

import arc.util.Align;
import arc.util.Time;
import mindustry.gen.Call;
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
 * (&lt; 10 min, after a 30 s on-screen countdown). Otherwise it waits for the
 * current round to end - or for the last player to leave: {@link #update()}
 * watches an already-queued restart and fires once the hub has been empty for
 * {@link #EMPTY_CONFIRM_MILLIS}, so a long round that simply runs out of players
 * does not hold the update back. Running matches are never killed -
 * {@code evictrestart now} is the only path that kills them, and even it counts
 * down 10 s on the HUD first. {@code evictrestart cancel} aborts either
 * countdown mid-flight.
 */
public final class RestartManager {

    /** A round younger than this is cheap to interrupt (after a short warning). */
    private static final long YOUNG_ROUND_MILLIS = 10L * 60L * 1000L;

    /**
     * How long the hub must stay empty before a queued restart fires on it.
     * Long enough that a single player reconnecting, or the brief gap while the
     * last of two players swaps servers, does not read as an empty server.
     */
    private static final long EMPTY_CONFIRM_MILLIS = 10L * 1000L;

    /** Seconds of visible countdown before a young-round restart fires. */
    private static final int WARNING_COUNTDOWN_SECONDS = 30;

    /** Seconds of visible countdown before {@code evictrestart now} fires. */
    private static final int NOW_COUNTDOWN_SECONDS = 10;

    /**
     * Lifetime of each countdown popup, longer than the 1 s update cadence so
     * everyone gets to see the number even when a tick lags.
     */
    private static final float HUD_SECONDS = 3f;

    /** Shared popup id so each update replaces the previous number. */
    private static final String HUD_ID = "evict-restart-hud";

    /** Bottom padding lifting the center-aligned popup above screen center. */
    private static final int HUD_RAISE = 220;

    private final IntSupplier activeWorkers;
    private final IntSupplier hubPlayers;
    private final LongSupplier roundAgeMillis;
    private final Runnable exitAction;

    private boolean queued;
    private long warningSerial;

    /**
     * When the hub last became empty while a restart was queued, or 0 while it
     * is not empty. Reset by anyone joining, so the wait always measures one
     * uninterrupted empty stretch.
     */
    private long emptySinceMillis;

    /** True while an exit countdown is on screen (warning path or restart now). */
    private boolean exitPending;

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

    /** {@code evictrestart cancel}: drop a queued restart (and any running countdown). */
    public void cancelRestart() {
        if (!queued && !exitPending) {
            PluginLog.info("No restart is queued.");
            return;
        }

        boolean countdownVisible = exitPending;
        queued = false;
        exitPending = false;
        warningSerial++;

        if (countdownVisible) {
            hideCountdownHud();
            Text.of().accent("The server restart was cancelled.").sendAll();
        }

        PluginLog.info("Queued restart cancelled.");
    }

    /** {@code evictrestart now}: announce, count down 10 s, then exit, killing any matches. */
    public void restartNow() {
        PluginLog.warn("Immediate restart requested - exiting in @s.", NOW_COUNTDOWN_SECONDS);
        Text.of()
                .scarlet("The server is restarting for an update in ")
                .accent(NOW_COUNTDOWN_SECONDS + "s")
                .scarlet(". Reconnect in a moment.")
                .sendAll();
        countdownThenExit(NOW_COUNTDOWN_SECONDS, "immediate restart");
    }

    /**
     * Per-tick watch on an already-queued restart, hub only. The other triggers
     * are one-shot (queueing, a round ending), so without this a restart queued
     * during a long round would sit there even after the last player left. Once
     * the hub has been empty for {@link #EMPTY_CONFIRM_MILLIS} with no worker
     * running, there is nobody left to disturb: restart.
     */
    public void update() {
        if (!queued || exitPending) {
            emptySinceMillis = 0L;
            return;
        }

        if (hubPlayers.getAsInt() > 0 || activeWorkers.getAsInt() > 0) {
            emptySinceMillis = 0L;
            return;
        }

        long now = System.currentTimeMillis();

        if (emptySinceMillis == 0L) {
            emptySinceMillis = now;
            return;
        }

        if (now - emptySinceMillis >= EMPTY_CONFIRM_MILLIS) {
            emptySinceMillis = 0L;
            fireExit("hub empty");
        }
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
        Text.of().scarlet("Server restarting for an update in 30 seconds...").sendAll();
        countdownThenExit(WARNING_COUNTDOWN_SECONDS, "30s warning elapsed");
    }

    /**
     * Runs the countdown to the exit. The HUD popup ticks every second (each
     * lives {@link #HUD_SECONDS} and replaces the previous one, so the number
     * stays readable even when a tick lags); chat gets the milestone seconds
     * (every 10 s, then each of the last five), so the countdown is in the
     * persistent chat log without thirty lines of spam. {@code evictrestart
     * cancel} bumps the serial, which drops every pending tick and hides the
     * popup.
     */
    private void countdownThenExit(int seconds, String reason) {
        exitPending = true;
        long serial = ++warningSerial;

        for (int second = seconds; second >= 1; second--) {
            int remaining = second;

            Time.run(Ticks.seconds(seconds - second), () -> {
                if (serial != warningSerial) {
                    return;
                }

                showCountdownHud(remaining);

                if (isChatMilestone(remaining, seconds)) {
                    Text.of()
                            .scarlet("Server restarting in ")
                            .accent(remaining + "s")
                            .scarlet("...")
                            .sendAll();
                }
            });
        }

        Time.run(Ticks.seconds(seconds), () -> {
            if (serial == warningSerial) {
                fireExit(reason);
            }
        });
    }

    /**
     * Which countdown seconds go to chat: every full ten and each of the last
     * five - except the starting number, which the announcement that opened
     * the countdown already carried.
     */
    private static boolean isChatMilestone(int remaining, int total) {
        return remaining != total && (remaining <= 5 || remaining % 10 == 0);
    }

    private void showCountdownHud(int remaining) {
        Call.infoPopup(
                "[accent]Server restarting in [scarlet]" + remaining + "s[]",
                HUD_ID,
                HUD_SECONDS,
                Align.center,
                0,
                0,
                HUD_RAISE,
                0
        );
    }

    private void hideCountdownHud() {
        Call.infoPopup((String) null, HUD_ID, 0f, Align.center, 0, 0, HUD_RAISE, 0);
    }

    private void fireExit(String reason) {
        PluginLog.info("Restarting now (@). The start-script loop will bring the server back up.", reason);
        queued = false;
        exitPending = false;
        exitAction.run();
    }

    private Text waitDescription() {
        int workers = activeWorkers.getAsInt();
        if (workers > 0) {
            return Text.of().add("Waiting for ").num(workers).add(" duel worker(s) to finish.");
        }
        if (roundAgeMillis.getAsLong() >= YOUNG_ROUND_MILLIS) {
            return Text.of().add(
                    "Waiting for the current round to end, or for the hub to be empty for "
                            + (EMPTY_CONFIRM_MILLIS / 1000L) + "s."
            );
        }
        return Text.of().add("Ready - restarting shortly.");
    }
}
