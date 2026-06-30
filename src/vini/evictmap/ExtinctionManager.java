package vini.evictmap;

import mindustry.game.Team;
import mindustry.gen.Call;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Crown-on-the-hill late-game event.
 * The clock starts immediately when the generated round starts.
 * Timeline:
 * - 01:20:00: first warning; /over becomes unavailable
 * - 01:25:00: five-minute warning
 * - 01:29:00: one-minute warning
 * - 01:30:00: outermost live ring collapses immediately
 * - every 01:30 afterward: the next live ring collapses
 * - when the center plus its six neighbors remain: four-minute center hold
 * - the center owner after four minutes wins, including Fallen
 */
final class ExtinctionManager {

    private static final float WARNING_TEN_MINUTES_TICKS = 80f * 60f * 60f;
    private static final float WARNING_FIVE_MINUTES_TICKS = 85f * 60f * 60f;
    private static final float WARNING_ONE_MINUTE_TICKS = 89f * 60f * 60f;
    private static final float EXTINCTION_START_TICKS = 90f * 60f * 60f;

    private static final float RING_INTERVAL_TICKS = 90f * 60f;
    /**
     * No artificial pause between core hexes. The update loop still processes
     * one hex per server tick, while terrain removal remains separately
     * throttled inside TeamManager.
     */
    private static final float HEX_COLLAPSE_INTERVAL_TICKS = 0f;
    private static final float FINAL_HOLD_TICKS = 4f * 60f * 60f;

    private final TeamManager teamManager;

    private float elapsedTicks = 0f;
    private float forcedTimelineOffsetTicks = 0f;
    private float nextRingCollapseTicks = Float.POSITIVE_INFINITY;
    private float nextHexCollapseTicks = Float.POSITIVE_INFINITY;
    private float finalPhaseEndTicks = Float.POSITIVE_INFINITY;

    private final ArrayDeque<TeamManager.HexSlot> pendingRingSlots =
            new ArrayDeque<>();

    private int collapsingRingDistance = -1;
    private boolean waitingForTerrainDrain = false;

    private boolean warningTenMinutesSent = false;
    private boolean warningFiveMinutesSent = false;
    private boolean warningOneMinuteSent = false;
    private boolean extinctionStarted = false;
    private boolean finalPhase = false;

    ExtinctionManager(TeamManager teamManager) {
        this.teamManager = teamManager;
    }

    void beginRound() {
        elapsedTicks = 0f;
        forcedTimelineOffsetTicks = 0f;
        nextRingCollapseTicks = Float.POSITIVE_INFINITY;
        nextHexCollapseTicks = Float.POSITIVE_INFINITY;
        finalPhaseEndTicks = Float.POSITIVE_INFINITY;

        pendingRingSlots.clear();
        collapsingRingDistance = -1;
        waitingForTerrainDrain = false;

        warningTenMinutesSent = false;
        warningFiveMinutesSent = false;
        warningOneMinuteSent = false;
        extinctionStarted = false;
        finalPhase = false;
        teamManager.setExtinctionActive(false);
    }

    void update() {
        if (!teamManager.isRoundActiveForSystems()) {
            return;
        }

        elapsedTicks = currentTimelineTicks();

        if (
                !warningTenMinutesSent
                        && elapsedTicks >= WARNING_TEN_MINUTES_TICKS
        ) {
            warningTenMinutesSent = true;

            Call.sendMessage(
                    "[scarlet]EXTINCTION begins in 10 minutes. "
                            + "Outer hexes will collapse toward the center. "
                            + "When only the middle area remains, control the middle "
                            + "core to win the round.[]"
            );
        }

        if (
                !warningFiveMinutesSent
                        && elapsedTicks >= WARNING_FIVE_MINUTES_TICKS
        ) {
            warningFiveMinutesSent = true;

            Call.sendMessage(
                    "[scarlet]EXTINCTION begins in 5 minutes.[]"
            );
        }

        if (
                !warningOneMinuteSent
                        && elapsedTicks >= WARNING_ONE_MINUTE_TICKS
        ) {
            warningOneMinuteSent = true;

            Call.sendMessage(
                    "[scarlet]EXTINCTION begins in 1 minute.[]"
            );
        }

        if (!extinctionStarted && elapsedTicks >= EXTINCTION_START_TICKS) {
            startExtinction();
        }

        if (extinctionStarted && !finalPhase) {
            updateRingCollapse();
        }

        if (!finalPhase || !teamManager.isRoundActiveForSystems()) {
            return;
        }

        if (elapsedTicks >= finalPhaseEndTicks) {
            teamManager.finishExtinction(finalCenterWinner());
        }
    }

    boolean blocksEarlyEnd() {
        return warningTenMinutesSent || elapsedTicks >= WARNING_TEN_MINUTES_TICKS;
    }

    private void startExtinction() {
        extinctionStarted = true;
        teamManager.setExtinctionActive(true);

        Call.sendMessage(
                "[scarlet]EXTINCTION HAS STARTED. "
                        + "The outermost ring is collapsing core by core. "
                        + "Fight toward the middle: controlling the middle core in "
                        + "the final phase wins the round.[]"
        );

        nextRingCollapseTicks = elapsedTicks;
        updateRingCollapse();
    }

    private void updateRingCollapse() {
        if (
                !teamManager.isRoundActiveForSystems()
                        || finalPhase
        ) {
            return;
        }

        if (
                pendingRingSlots.isEmpty()
                        && !waitingForTerrainDrain
                        && elapsedTicks >= nextRingCollapseTicks
        ) {
            beginNextRingCollapse();
        }

        if (
                !pendingRingSlots.isEmpty()
                        && elapsedTicks >= nextHexCollapseTicks
        ) {
            TeamManager.HexSlot slot = pendingRingSlots.removeFirst();

            teamManager.collapseHexesForExtinction(
                    Collections.singletonList(slot)
            );

            nextHexCollapseTicks =
                    elapsedTicks + HEX_COLLAPSE_INTERVAL_TICKS;

            if (pendingRingSlots.isEmpty()) {
                waitingForTerrainDrain = true;
            }
        }

        if (
                waitingForTerrainDrain
                        && !teamManager.hasPendingExtinctionTerrainChanges()
        ) {
            finishCurrentRingCollapse();
        }
    }

    private void beginNextRingCollapse() {
        int outermostDistance = -1;

        for (TeamManager.HexSlot slot : teamManager.slots()) {
            if (!slot.extinct) {
                outermostDistance = Math.max(
                        outermostDistance,
                        teamManager.gridDistanceFromCenter(slot)
                );
            }
        }

        if (outermostDistance <= 1) {
            startFinalPhase();
            return;
        }

        List<TeamManager.HexSlot> collapsing = new ArrayList<>();

        for (TeamManager.HexSlot slot : teamManager.slots()) {
            if (
                    !slot.extinct
                            && teamManager.gridDistanceFromCenter(slot)
                            == outermostDistance
            ) {
                collapsing.add(slot);
            }
        }

        pendingRingSlots.clear();
        pendingRingSlots.addAll(collapsing);
        collapsingRingDistance = outermostDistance;
        waitingForTerrainDrain = false;
        nextHexCollapseTicks = elapsedTicks;

        Call.sendMessage(
                "[scarlet]EXTINCTION: ring "
                        + outermostDistance
                        + " is collapsing core by core.[]"
        );
    }

    private void finishCurrentRingCollapse() {
        waitingForTerrainDrain = false;

        if (!teamManager.isRoundActiveForSystems()) {
            return;
        }

        if (onlyFinalSevenRemain()) {
            startFinalPhase();
            return;
        }

        Call.sendMessage(
                "[scarlet]EXTINCTION: ring "
                        + collapsingRingDistance
                        + " fully collapsed. The next ring collapses in 90 seconds.[]"
        );

        nextRingCollapseTicks = elapsedTicks + RING_INTERVAL_TICKS;
        collapsingRingDistance = -1;
    }

    private boolean onlyFinalSevenRemain() {
        for (TeamManager.HexSlot slot : teamManager.slots()) {
            if (
                    !slot.extinct
                            && teamManager.gridDistanceFromCenter(slot) > 1
            ) {
                return false;
            }
        }

        return true;
    }

    private void startFinalPhase() {
        if (finalPhase || !teamManager.isRoundActiveForSystems()) {
            return;
        }

        finalPhase = true;
        finalPhaseEndTicks = elapsedTicks + FINAL_HOLD_TICKS;

        Call.sendMessage(
                "[accent]FINAL EXTINCTION PHASE: the last outer ring disappeared. "
                        + "Only the middle hex and its six neighbouring hexes remain. "
                        + "You now have 4 minutes to conquer and control the middle "
                        + "core. Its owner when the timer ends wins the round, "
                        + "including Fallen.[]"
        );
    }

    private Team finalCenterWinner() {
        Team centerOwner = teamManager.centerHexOwner();

        if (centerOwner == null || centerOwner == Team.derelict) {
            return TeamManager.FALLEN_TEAM;
        }

        return centerOwner;
    }

    private float currentTimelineTicks() {
        return baseTimelineTicks() + forcedTimelineOffsetTicks;
    }

    private float baseTimelineTicks() {
        return teamManager.roundRuntimeMillis() / 1000f * 60f;
    }
}
