package Extinction.duel.modes;

import Extinction.duel.MatchMode;

/**
 * Solo practice against the empty world. One participant, a gated start like a
 * real match, but no opponent to beat: it ends only when the player /dies or
 * leaves, and leaves nothing in /history.
 */
public final class Training implements DuelMode {

    @Override
    public MatchMode mode() {
        return MatchMode.TRAINING;
    }

    @Override
    public boolean solo() {
        return true;
    }
}
