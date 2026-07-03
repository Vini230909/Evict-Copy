package vini.evictmap;

/**
 * The game modes a /play match can run in. The id is the wire format shared
 * between the hub and a worker (duel.properties / result.properties), so it
 * must stay stable across versions.
 */
enum MatchMode {

    ONE_VS_ONE("1v1", "1v1"),
    TEAMS("teams", "Teams"),

    /**
     * Hub-only draft mode: one player pool that is shuffled into a chosen
     * number of teams once everyone accepted. Never sent over the wire - the
     * hub launches the shuffled rosters as a regular {@link #TEAMS} match, so
     * workers and /history treat it exactly like Teams.
     */
    RANDOM_TEAMS("random-teams", "Random Teams"),

    FFA("ffa", "FFA"),
    TRAINING("training", "Training"),
    SANDBOX("sandbox", "Sandbox");

    private final String id;
    private final String label;

    MatchMode(String id, String label) {
        this.id = id;
        this.label = label;
    }

    String id() {
        return id;
    }

    String label() {
        return label;
    }

    /**
     * Solo modes start with a single participant and cannot be won: they end
     * through /die (or everyone leaving), never through an Evict victory.
     */
    boolean solo() {
        return this == TRAINING || this == SANDBOX;
    }

    /**
     * Only 1v1 results count toward the ranked record and /history.
     */
    boolean ranked() {
        return this == ONE_VS_ONE;
    }

    static MatchMode fromId(String id) {
        for (MatchMode mode : values()) {
            if (mode.id.equals(id)) {
                return mode;
            }
        }

        return ONE_VS_ONE;
    }
}
