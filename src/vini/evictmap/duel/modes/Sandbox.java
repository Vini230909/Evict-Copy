package vini.evictmap.duel.modes;

import vini.evictmap.duel.MatchMode;

/**
 * A persistent build room, not a gated match. The world runs from the moment
 * the worker starts and never freezes: there is no join settle-freeze, no
 * start countdown, and a disconnect neither pauses the match nor starts a
 * rejoin timer - players come and go and auto-reconnect into the live world.
 * It plays with infinite resources, ends only via /die (or emptying out), and
 * lets watching spectators /invite themselves in.
 */
public final class Sandbox implements DuelMode {

    @Override
    public MatchMode mode() {
        return MatchMode.SANDBOX;
    }

    @Override
    public boolean gated() {
        return false;
    }

    @Override
    public boolean solo() {
        return true;
    }

    @Override
    public boolean infiniteResources() {
        return true;
    }

    @Override
    public boolean allowsSpectatorInvites() {
        return true;
    }
}
