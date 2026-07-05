package vini.evictmap.duel.modes;

import vini.evictmap.duel.MatchMode;

/**
 * Classic 1v1: two duelists, gated start, and the only ranked mode - its
 * result feeds the ELO record and /history.
 */
public final class OneVsOne implements DuelMode {

    @Override
    public MatchMode mode() {
        return MatchMode.ONE_VS_ONE;
    }

    @Override
    public boolean ranked() {
        return true;
    }
}
