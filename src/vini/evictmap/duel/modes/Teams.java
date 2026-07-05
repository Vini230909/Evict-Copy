package vini.evictmap.duel.modes;

import vini.evictmap.duel.MatchMode;

/**
 * Team vs team. Several rosters, each on its own Mindustry team; a wiped team
 * is eliminated and the rest play on, so a surrender restores that team's
 * Fallen backup cores instead of leaving them derelict. Stored in /history as
 * an unranked entry listing every team.
 */
public final class Teams implements DuelMode {

    @Override
    public MatchMode mode() {
        return MatchMode.TEAMS;
    }

    @Override
    public boolean restoresFallenCoresOnSurrender() {
        return true;
    }

    @Override
    public boolean eliminatesWipedTeams() {
        return true;
    }
}
