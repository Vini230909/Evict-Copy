package vini.evictmap.duel;

import vini.evictmap.duel.modes.DuelMode;
import vini.evictmap.duel.modes.DuelModes;

/**
 * The game modes a /play match can run in. The id is the wire format shared
 * between the hub and a worker (duel.properties / result.properties), so it
 * must stay stable across versions.
 * This enum is only the mode's stable identity (id + label). Each mode's
 * actual rules live in a {@link DuelMode} strategy under
 * {@code vini.evictmap.duel.modes}; call {@link #duel()} to get it.
 */
public enum MatchMode {

    ONE_VS_ONE("1v1", "Unranked"),

    /**
     * The same two-duelist gated match as {@link #ONE_VS_ONE}, but rated: its
     * result is the only thing that moves a player's ELO and it is stored as a
     * ranked /history entry. The only ranked mode.
     */
    RANKED("ranked", "1v1"),

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

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    /**
     * The rules strategy for this mode (whether it freezes, is solo, is
     * ranked, restores cores, and so on). See {@code vini.evictmap.duel.modes}.
     */
    public DuelMode duel() {
        return DuelModes.of(this);
    }

    public static MatchMode fromId(String id) {
        for (MatchMode mode : values()) {
            if (mode.id.equals(id)) {
                return mode;
            }
        }

        return ONE_VS_ONE;
    }
}
