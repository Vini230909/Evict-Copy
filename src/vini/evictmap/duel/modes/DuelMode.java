package vini.evictmap.duel.modes;

import vini.evictmap.duel.MatchMode;

/**
 * The rules for one duel mode. {@link MatchMode} is only the wire identity of a
 * mode (its id and label); this strategy is where a mode's actual behaviour
 * lives, so each mode can be read and changed in its own file
 * ({@link OneVsOne}, {@link Teams}, {@link Ffa}, {@link Training},
 * {@link Sandbox}) instead of as conditionals scattered across the referee and
 * the hub.
 * Every flag defaults to the plain competitive-match answer; a mode overrides
 * only the ones where it differs.
 */
public interface DuelMode {

    /**
     * The wire identity this strategy backs.
     */
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

    /**
     * Only ranked results count toward the ELO record and appear in /history.
     */
    default boolean ranked() {
        return false;
    }

    /**
     * The match plays with infinite resources (Sandbox).
     */
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

    /**
     * Spectators may ask to join with /invite and be promoted into the match
     * (Sandbox).
     */
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
     * Global chat is limited to the two duelists (Ranked): viewers and casting
     * admins have their normal chat routed to the spectators' chat so they can
     * never leak information to the players, and only a casting admin reaches
     * global - through an inverted /t. Every other mode leaves chat untouched.
     * See {@code vini.evictmap.duel.DuelChat}.
     */
    default boolean restrictsSpectatorChat() {
        return false;
    }
}
