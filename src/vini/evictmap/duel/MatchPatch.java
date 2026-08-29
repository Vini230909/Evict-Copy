package vini.evictmap.duel;

/**
 * An optional rebalance patch a /play match runs with, chosen as the /play
 * command's option argument ({@code /play nerf}). The patch is orthogonal to
 * the {@link MatchMode}: every mode can be played with or without it.
 * <p>
 * The id is the wire format shared between the hub and a worker
 * (duel.properties {@code patch}), so it must stay stable across versions. The
 * patch itself - what it changes about the game's content - lives in
 * {@code vini.evictmap.gameplay.NerfPatch}; this enum is only its identity.
 */
public enum MatchPatch {

    /** Vanilla balance: the game as every other Evict round plays it. */
    NONE("none", "None"),

    /**
     * The overdrive/reconstructor rebalance: dearer and slower overdrive
     * projectors and domes, dearer T5 reconstructors, and toned-down navanax
     * EMP, quasar and vela stats.
     */
    NERF("nerf", "Nerf");

    private final String id;
    private final String label;

    MatchPatch(String id, String label) {
        this.id = id;
        this.label = label;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    /** Whether this is plain vanilla balance, i.e. nothing to apply. */
    public boolean isNone() {
        return this == NONE;
    }

    /** Resolves a wire id; anything unknown is plain vanilla balance. */
    public static MatchPatch fromId(String id) {
        for (MatchPatch patch : values()) {
            if (patch.id.equalsIgnoreCase(id)) {
                return patch;
            }
        }

        return NONE;
    }

    /**
     * Resolves the option word typed after /play, or null when it names no
     * patch at all - the caller answers that with the list of valid options
     * rather than silently starting a vanilla match.
     */
    public static MatchPatch fromOption(String option) {
        if (option == null) {
            return null;
        }

        String trimmed = option.trim();

        if (trimmed.isEmpty()) {
            return NONE;
        }

        for (MatchPatch patch : values()) {
            if (patch.id.equalsIgnoreCase(trimmed)) {
                return patch;
            }
        }

        return null;
    }

    /** The option words /play accepts, for the "unknown option" answer. */
    public static String optionList() {
        StringBuilder options = new StringBuilder();

        for (MatchPatch patch : values()) {
            if (!options.isEmpty()) {
                options.append(", ");
            }

            options.append(patch.id);
        }

        return options.toString();
    }
}
