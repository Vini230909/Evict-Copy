package vini.evictmap.duel.modes;

import vini.evictmap.duel.MatchMode;

/**
 * The rules for one duel mode, one file per mode. {@link MatchMode} is only the
 * wire identity (id and label); the behaviour lives here. Every flag defaults
 * to the plain competitive answer; a mode overrides only what differs.
 */
public interface DuelMode {

    /** The wire identity this strategy backs. */
    MatchMode mode();

    /**
     * Gated matches freeze when a duelist joins, run the pre-match countdown,
     * and pause for a rejoin window when someone disconnects. Sandbox is not
     * gated: its world runs continuously and players drop in and out freely.
     */
    default boolean gated() {
        return true;
    }

    /**
     * Solo modes start with a single participant and cannot be won: they end
     * through /die (or everyone leaving), never through an Evict victory.
     */
    default boolean solo() {
        return false;
    }

    /** Only ranked results touch ELO and the ranked counters. */
    default boolean ranked() {
        return false;
    }

    /** Infinite resources (Sandbox). */
    default boolean infiniteResources() {
        return false;
    }

    /**
     * The match keeps running after a team surrenders, so their hexes need
     * their Fallen backup cores restored rather than left derelict.
     */
    default boolean restoresFallenCoresOnSurrender() {
        return false;
    }

    /**
     * Pack many players into one worker by shrinking the spacing between start
     * hexes (FFA), so a full lobby does not exhaust the safe hexes.
     */
    default boolean reducedStartDistance() {
        return false;
    }

    /** Spectators may ask to join with /invite (Sandbox). */
    default boolean allowsSpectatorInvites() {
        return false;
    }

    /**
     * A wiped team is eliminated without ending the match; the survivors play
     * on (FFA and Teams). Non-eliminating modes end the moment a side loses.
     */
    default boolean eliminatesWipedTeams() {
        return false;
    }

    /**
     * Global chat is the two duelists only (Ranked); everyone else is routed to
     * the spectators' chat so they cannot leak information. See
     * {@code vini.evictmap.duel.DuelChat}.
     */
    default boolean restrictsSpectatorChat() {
        return false;
    }
}
