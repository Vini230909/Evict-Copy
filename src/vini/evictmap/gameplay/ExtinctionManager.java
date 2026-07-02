package vini.evictmap.gameplay;

import arc.util.Log;
import mindustry.gen.Call;
import vini.evictmap.TeamManager;

import java.util.Collections;

/**
 * Handles the Extinction event.
 * <p>
 * The game will last a maximum of 100 minutes. After 90 minutes, every 2 minutes, a ring of hexes will be removed.
 * This happens until there is only 1 ring of hexes remaining (plus the center hex). Then it will take 2 minutes until
 * those hexes are removed. Victory conditions remain the same as normal during this period (hold every active hex).
 * Hex removal is throttled to prevent lag or big packets.
 * </p>
 * <p>
 * Internally this is implemented with a state machine
 * </p>
 */
public final class ExtinctionManager implements GameplayManagerInterface {
    /**
     * State machine used to implement the Extinction event.
     * Various states represent the different stages of progression through the event.
     * State transition is dependent only on time (see {@link State#stateTransitionSeconds}).
     */
    private enum State {
        NO_WARNING_SENT,
        TEN_MINUTES_WARNING_SENT,
        FIVE_MINUTES_WARNING_SENT,
        ONE_MINUTE_WARNING_SENT,
        FIFTH_RING_COLLAPSED,
        FOURTH_RING_COLLAPSED,
        THIRD_RING_COLLAPSED,
        SECOND_RING_COLLAPSED;

        /**
         * Gets the time, in seconds, at which the state should transition to the next.
         *
         * @return A time, as a float, as an offset from the start of the round.
         */
        private float stateTransitionSeconds() {
            return switch (this) {
                case NO_WARNING_SENT -> 80 * 60;
                case TEN_MINUTES_WARNING_SENT -> 85 * 60;
                case FIVE_MINUTES_WARNING_SENT -> 89 * 60;
                case ONE_MINUTE_WARNING_SENT -> 90 * 60;
                case FIFTH_RING_COLLAPSED -> 92f * 60;
                case FOURTH_RING_COLLAPSED -> 94 * 60;
                case THIRD_RING_COLLAPSED -> 96f * 60;
                case SECOND_RING_COLLAPSED -> 100 * 60;
            };
        }
    }

    private final TeamManager teamManager;

    /**
     * Current state
     */
    private State state;

    /**
     * Constructor
     *
     * @param teamManager {@link TeamManager} to be used for future operations
     */
    public ExtinctionManager(TeamManager teamManager) {
        this.teamManager = teamManager;
        this.state = State.NO_WARNING_SENT;
    }

    @Override
    public void beginRound() {
        state = State.NO_WARNING_SENT;
    }

    @Override
    public void update() {
        if (!teamManager.isRoundActiveForSystems()) return;

        float elapsedSeconds = teamManager.roundRuntimeMillis() / 1000f;

        if (elapsedSeconds >= state.stateTransitionSeconds()) {
            switch (state) {
                case NO_WARNING_SENT -> {
                    Call.sendMessage("Extinction begins in 10 minutes.");
                    state = State.TEN_MINUTES_WARNING_SENT;
                }
                case TEN_MINUTES_WARNING_SENT -> {
                    Call.sendMessage("Extinction begins in 5 minutes.");
                    state = State.FIVE_MINUTES_WARNING_SENT;
                }
                case FIVE_MINUTES_WARNING_SENT -> {
                    Call.sendMessage("Extinction begins in 1 minute.");
                    state = State.ONE_MINUTE_WARNING_SENT;
                }
                case ONE_MINUTE_WARNING_SENT -> {
                    Call.sendMessage("[scarlet]Extinction has begun.[]");
                    Call.sendMessage("The fifth ring will collapse.");
                    collapseRing(5);
                    state = State.FIFTH_RING_COLLAPSED;
                }
                case FIFTH_RING_COLLAPSED -> {
                    Call.sendMessage("The fourth ring will collapse.");
                    collapseRing(4);
                    state = State.FOURTH_RING_COLLAPSED;
                }
                case FOURTH_RING_COLLAPSED -> {
                    Call.sendMessage("The third ring will collapse.");
                    collapseRing(3);
                    state = State.THIRD_RING_COLLAPSED;
                }
                case THIRD_RING_COLLAPSED -> {
                    Call.sendMessage("The second ring will collapse.");
                    collapseRing(2);
                    state = State.SECOND_RING_COLLAPSED;
                }
                case SECOND_RING_COLLAPSED -> {
                    Call.sendMessage("The final ring will collapse.");
                    collapseRing(1);
                    state = State.NO_WARNING_SENT;
                }
                default -> Log.err("unreachable statement reached");
            }
        }
    }

    @Override
    public void endRound() {
    }

    /**
     * Collapse a given ring (and all rings before it).
     *
     * @param ringNumber Which ring to collapse (number from 1 to 5).
     */
    private void collapseRing(int ringNumber) {
        for (TeamManager.HexSlot slot : teamManager.slots())
            if (!slot.extinct && teamManager.gridDistanceFromCenter(slot) >= ringNumber)
                teamManager.collapseHexesForExtinction(Collections.singletonList(slot));
    }
}