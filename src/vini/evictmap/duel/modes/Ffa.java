package vini.evictmap.duel.modes;

import vini.evictmap.duel.MatchMode;

/**
 * Free-for-all: every player is their own team. An eliminated player is
 * dropped and the rest fight on, so a surrender restores Fallen cores. Because
 * a full lobby can pile into one worker, the start hexes are packed closer
 * together than usual. Stored in /history as an unranked entry listing
 * everyone.
 */
public final class Ffa implements DuelMode {

    @Override
    public MatchMode mode() {
        return MatchMode.FFA;
    }

    @Override
    public boolean restoresFallenCoresOnSurrender() {
        return true;
    }

    @Override
    public boolean reducedStartDistance() {
        return true;
    }

    @Override
    public boolean eliminatesWipedTeams() {
        return true;
    }
}
