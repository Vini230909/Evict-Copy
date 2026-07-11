package vini.evictmap.duel.modes;

import vini.evictmap.duel.MatchMode;

/**
 * Casual 1v1: two duelists and a gated start, but unranked - the result never
 * touches the ELO record. It is still stored as an unranked win/lose /history
 * entry. {@link Ranked} is the rated version of the exact same match.
 */
public final class OneVsOne implements DuelMode {

    @Override
    public MatchMode mode() {
        return MatchMode.ONE_VS_ONE;
    }
}
