// The start gate of a gated match: freeze on join, camera settle, the 5 s countdown, GO.
package Extinction;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import arc.Core;
import arc.util.Time;
import mindustry.Vars;

public final class StartGate {

    private static final int COUNTDOWN_SECONDS = 5;

    // Live ticks after a duelist joins before the freeze, so their camera lands on their unit.
    // See docs/GAMEPLAY.md, Matches (an instant freeze parks the camera at the map origin).
    private static final float CAMERA_SETTLE_TICKS = 18f;

    private final Referee referee;
    private final ScheduledExecutorService scheduler;

    private boolean startFreezeApplied = false;
    private boolean countdownStarted = false;
    private boolean matchStarted = false;
    private long matchStartMillis = 0L;
    private int matchSerial = 0;
    private int settleSerial = 0;
    private boolean settlePending = false;

    public StartGate(Referee referee, ScheduledExecutorService scheduler) {
        this.referee = referee;
        this.scheduler = scheduler;
    }

    public boolean started() {
        return matchStarted;
    }

    public boolean countingDown() {
        return countdownStarted;
    }

    public long elapsedSeconds() {
        if (!matchStarted || matchStartMillis <= 0L) {
            return 0L;
        }

        return (System.currentTimeMillis() - matchStartMillis) / 1000L;
    }

    // Ungated modes (Sandbox) are a persistent room: no join freeze, no countdown.
    public void startUngated() {
        matchStarted = true;
        matchStartMillis = System.currentTimeMillis();
    }

    // Surrender and match end cancel every pending freeze/countdown before resuming the world.
    public void release() {
        matchSerial++;
        settleSerial++;
        settlePending = false;
        startFreezeApplied = false;
        countdownStarted = false;
        if (!matchStarted) {
            startUngated();
        }
        referee.pause.forceEnd();
        referee.hideHud();
    }

    // A duelist arrived: let the world settle their camera before the freeze.
    public void participantJoined() {
        settleCamerasThenFreeze();
    }

    // A spectator joined while still gathering: keep frozen and waiting, but never mid-settle.
    public void spectatorJoined() {
        if (!settlePending && !startFreezeApplied) {
            referee.pauseGame();
            startFreezeApplied = true;
        }
        showWaitingHud();
    }

    // Lets the world tick briefly so the joiner's camera settles, then re-freezes or counts down.
    // Each call supersedes the previous one, so rapid joins do not stack freeze/start tasks.
    private void settleCamerasThenFreeze() {
        // The second duelist joins while the first is frozen; resume so their camera settles too.
        if (Vars.state.isPaused()) {
            referee.resumeGame();
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

            if (referee.everyonePresent()) {
                startCountdown();
            } else {
                referee.pauseGame();
                startFreezeApplied = true;
            }
        });
    }

    private void startCountdown() {
        countdownStarted = true;

        // The settle window left the world running; freeze it through "starts in N".
        referee.pauseGame();
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

        referee.showHud(
                "[accent]" + referee.matchMode().label()
                        + " starts in [scarlet]" + remaining + "[]"
        );
    }

    private void startMatch(int serial) {
        if (serial != matchSerial) {
            return;
        }

        matchStarted = true;
        startFreezeApplied = false;
        matchStartMillis = System.currentTimeMillis();
        referee.resumeGame();
        referee.showHud("[green]GO![]");

        scheduler.schedule(
                () -> Core.app.post(referee::hideHud),
                2,
                TimeUnit.SECONDS
        );
    }

    // The players still missing - never the ones already here.
    private void showWaitingHud() {
        StringBuilder names = new StringBuilder();

        for (String uuid : referee.participantUuids()) {
            if (referee.isOnline(uuid) || referee.pause.isWaived(uuid)) {
                continue;
            }

            if (!names.isEmpty()) {
                names.append("[white], ");
            }

            names.append(referee.pause.nameOf(uuid));
        }

        referee.showHud(
                !names.isEmpty()
                        ? "[accent]Waiting for\n[white]" + names + "[]"
                        : "[accent]Waiting for players...[]"
        );
    }
}
