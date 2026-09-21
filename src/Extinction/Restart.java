// Graceful hub restart for updates: console 'restart', its countdowns, and the clean exit (docs/RESTART_LOOP.md).
package Extinction;

import Extinction.core.text.Text;
import Extinction.core.util.PluginLog;
import Extinction.core.util.Ticks;
import Extinction.round.TeamManager;

import arc.util.Align;
import arc.util.Time;
import mindustry.Vars;
import mindustry.gen.Call;
import mindustry.gen.Groups;

public final class Restart {

    // A round younger than this is cheap to interrupt (after a short warning).
    private static final long YOUNG_ROUND_MILLIS = 10L * 60L * 1000L;

    // How long the hub must stay empty before a queued restart fires; a reconnect or server swap is shorter.
    private static final long EMPTY_CONFIRM_MILLIS = 10L * 1000L;

    private static final int WARNING_COUNTDOWN_SECONDS = 30;
    private static final int NOW_COUNTDOWN_SECONDS = 10;

    // Each countdown popup outlives the 1 s cadence so the number stays readable when a tick lags.
    private static final float HUD_SECONDS = 3f;
    private static final String HUD_ID = "evict-restart-hud";
    private static final int HUD_RAISE = 220;

    // How long the exit's shutdown hooks may take before the halt guard forces the JVM down.
    private static final long EXIT_HALT_GUARD_MILLIS = 10_000L;

    private final Matches matches;
    private final TeamManager teamManager;

    private boolean queued;
    private long warningSerial;

    // When the hub last became empty while a restart was queued, or 0 while it is not empty.
    private long emptySinceMillis;

    // True while an exit countdown is on screen (warning path or restart now).
    private boolean exitPending;

    public Restart(Matches matches, TeamManager teamManager) {
        this.matches = matches;
        this.teamManager = teamManager;
    }

    // 'restart': queue a restart, or report what a queued one waits for.
    public void request() {
        if (queued) {
            PluginLog.info("Restart already queued. @", waitDescription());
            return;
        }
        queued = true;
        PluginLog.info("Restart queued.");
        attempt(false);
    }

    // 'restart cancel': drop a queued restart (and any running countdown).
    public void cancel() {
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

    // 'restart now': announce, count down 10 s, then exit, killing any matches.
    public void now() {
        PluginLog.warn("Immediate restart requested - exiting in @s.", NOW_COUNTDOWN_SECONDS);
        Text.of()
                .scarlet("The server is restarting for an update in ")
                .accent(NOW_COUNTDOWN_SECONDS + "s")
                .scarlet(". Reconnect in a moment.")
                .sendAll();
        countdownThenExit(NOW_COUNTDOWN_SECONDS, "immediate restart");
    }

    // Per tick, hub only: a queued restart fires once the hub has been empty for EMPTY_CONFIRM_MILLIS.
    public void update() {
        if (!queued || exitPending) {
            emptySinceMillis = 0L;
            return;
        }

        if (Groups.player.size() > 0 || activeWorkers() > 0) {
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

    // From the hub's round-victory handler: a queued restart fires cleanly here.
    public void onRoundEnded() {
        if (queued) {
            attempt(true);
        }
    }

    public boolean isQueued() {
        return queued;
    }

    private int activeWorkers() {
        return matches.activeDuels().size();
    }

    private void attempt(boolean roundJustEnded) {
        if (!queued) {
            return;
        }

        int workers = activeWorkers();
        if (workers > 0) {
            PluginLog.info("Restart waiting: @ duel worker(s) still running.", workers);
            return;
        }

        if (roundJustEnded || Groups.player.size() == 0) {
            fireExit(roundJustEnded ? "round ended" : "hub empty");
            return;
        }

        if (teamManager.roundRuntimeMillis() < YOUNG_ROUND_MILLIS) {
            warnThenExit();
            return;
        }

        PluginLog.info("Restart waiting: round is past 10 minutes - will restart when it ends.");
    }

    private void warnThenExit() {
        Text.of().scarlet("Server restarting for an update in 30 seconds...").sendAll();
        countdownThenExit(WARNING_COUNTDOWN_SECONDS, "30s warning elapsed");
    }

    // HUD ticks every second, chat gets the milestones; 'restart cancel' bumps the serial and drops every tick.
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

    // Chat gets every full ten and the last five, minus the starting number the announcement already carried.
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
        exitProcess();
    }

    // Close the net, then System.exit(0) like a worker (Core.app.exit proved unreliable on the host);
    // the halt guard takes the JVM down if a shutdown hook wedges.
    private static void exitProcess() {
        Thread haltGuard = new Thread(() -> {
            try {
                Thread.sleep(EXIT_HALT_GUARD_MILLIS);
            } catch (InterruptedException ignored) {
                return;
            }

            System.err.println(
                    "[EvictMapGenerator] Shutdown hooks hung; halting the JVM."
            );
            Runtime.getRuntime().halt(0);
        }, "evict-exit-halt-guard");
        haltGuard.setDaemon(true);
        haltGuard.start();

        Vars.net.dispose();
        System.exit(0);
    }

    private Text waitDescription() {
        int workers = activeWorkers();
        if (workers > 0) {
            return Text.of().add("Waiting for ").num(workers).add(" duel worker(s) to finish.");
        }
        if (teamManager.roundRuntimeMillis() >= YOUNG_ROUND_MILLIS) {
            return Text.of().add(
                    "Waiting for the current round to end, or for the hub to be empty for "
                            + (EMPTY_CONFIRM_MILLIS / 1000L) + "s."
            );
        }
        return Text.of().add("Ready - restarting shortly.");
    }
}
