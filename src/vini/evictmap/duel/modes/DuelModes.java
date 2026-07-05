package vini.evictmap.duel.modes;

import java.util.EnumMap;
import java.util.Map;

import vini.evictmap.duel.MatchMode;

/**
 * Resolves a {@link MatchMode} to its {@link DuelMode} rules strategy. The
 * strategies are stateless singletons, so one instance each is shared.
 */
public final class DuelModes {

    private static final Map<MatchMode, DuelMode> BY_MODE =
            new EnumMap<>(MatchMode.class);

    static {
        register(new OneVsOne());
        register(new Teams());
        register(new Ffa());
        register(new Training());
        register(new Sandbox());

        // Random Teams is a hub-only draft: the pool is shuffled into teams and
        // launched as a regular TEAMS match, so it never reaches a worker under
        // its own id. Point it at the Teams rules so any stray lookup is sane.
        BY_MODE.put(MatchMode.RANDOM_TEAMS, BY_MODE.get(MatchMode.TEAMS));
    }

    private DuelModes() {
    }

    private static void register(DuelMode mode) {
        BY_MODE.put(mode.mode(), mode);
    }

    public static DuelMode of(MatchMode mode) {
        DuelMode duelMode = mode == null ? null : BY_MODE.get(mode);

        // Fall back to plain 1v1 rules for anything unmapped, matching
        // MatchMode.fromId's own default.
        return duelMode != null ? duelMode : BY_MODE.get(MatchMode.ONE_VS_ONE);
    }
}
