package vini.evictmap.duel.modes;

import vini.evictmap.duel.MatchMode;

/**
 * Ranked 1v1: the same two-duelist gated match as {@link OneVsOne}, but its
 * result is the only thing that moves a player's ELO (see
 * {@code vini.evictmap.EloCalculator}) and it is stored as a ranked /history
 * entry. Behaviourally identical to a casual 1v1 on the worker; only the hub
 * treats the result differently, keyed off {@link #ranked()}.
 */
public final class Ranked implements DuelMode {

    @Override
    public MatchMode mode() {
        return MatchMode.RANKED;
    }

    @Override
    public boolean ranked() {
        return true;
    }
}
