// The game modes a /play match runs in: wire id, menu label, and the rules that differ per mode.
package Extinction;

import java.util.EnumSet;
import java.util.Set;

public enum MatchMode {

    // Casual 1v1: gated, unranked, an unranked /history entry.
    ONE_VS_ONE("1v1", "Unranked"),

    // The same match, rated: the only mode that moves ELO. Global chat is the duelists only.
    RANKED("ranked", "1v1", Rule.RANKED, Rule.RESTRICTS_SPECTATOR_CHAT),

    // Several rosters; a wiped team is eliminated and the rest play on.
    TEAMS("teams", "Teams", Rule.RESTORES_FALLEN_CORES, Rule.ELIMINATES_WIPED_TEAMS),

    // Hub-only draft: one pool shuffled into teams, launched and recorded as a regular Teams match.
    RANDOM_TEAMS("random-teams", "Random Teams", Rule.RESTORES_FALLEN_CORES, Rule.ELIMINATES_WIPED_TEAMS),

    // Everyone on their own team; start hexes packed closer so a full lobby fits one worker.
    FFA("ffa", "FFA", Rule.RESTORES_FALLEN_CORES, Rule.REDUCED_START_DISTANCE, Rule.ELIMINATES_WIPED_TEAMS),

    // Solo practice: gated start, ends only with /die or leaving, nothing in /history.
    TRAINING("training", "Training", Rule.SOLO),

    // Training with infinite resources and no gate: a persistent room spectators may /invite into.
    SANDBOX("sandbox", "Sandbox", Rule.UNGATED, Rule.SOLO, Rule.INFINITE_RESOURCES, Rule.ALLOWS_SPECTATOR_INVITES),

    // Pure: vanilla PvP on a real map from config/maps, nothing recorded. See docs/GAMEPLAY.md, Pure matches.
    PURE_TRAINING("pure-training", "Pure Training", Rule.PURE, Rule.SOLO),
    PURE_1V1("pure-1v1", "Pure 1v1 PvP", Rule.PURE),
    PURE_2TEAM("pure-2team", "Pure 2 Team PvP", Rule.PURE),
    PURE_4TEAM("pure-4team", "Pure 4 Team PvP", Rule.PURE, Rule.ELIMINATES_WIPED_TEAMS);

    // What a mode may switch on; everything defaults to the plain competitive answer.
    private enum Rule {
        UNGATED, SOLO, RANKED, INFINITE_RESOURCES, RESTORES_FALLEN_CORES,
        REDUCED_START_DISTANCE, ALLOWS_SPECTATOR_INVITES, ELIMINATES_WIPED_TEAMS, RESTRICTS_SPECTATOR_CHAT, PURE
    }

    // The wire format shared between hub and worker (duel.properties / result.properties): stable.
    private final String id;
    private final String label;
    private final Set<Rule> rules;

    MatchMode(String id, String label, Rule... rules) {
        this.id = id;
        this.label = label;
        this.rules = rules.length == 0 ? EnumSet.noneOf(Rule.class) : EnumSet.of(rules[0], rules);
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    // Gated matches freeze on join, count down, and pause for a rejoin window on a disconnect.
    public boolean gated() {
        return !rules.contains(Rule.UNGATED);
    }

    // One participant, no way to win: ends through /die or everyone leaving.
    public boolean solo() {
        return rules.contains(Rule.SOLO);
    }

    public boolean ranked() {
        return rules.contains(Rule.RANKED);
    }

    public boolean infiniteResources() {
        return rules.contains(Rule.INFINITE_RESOURCES);
    }

    // The match keeps running after a surrender, so the hexes get their Fallen backup cores back.
    public boolean restoresFallenCoresOnSurrender() {
        return rules.contains(Rule.RESTORES_FALLEN_CORES);
    }

    public boolean reducedStartDistance() {
        return rules.contains(Rule.REDUCED_START_DISTANCE);
    }

    public boolean allowsSpectatorInvites() {
        return rules.contains(Rule.ALLOWS_SPECTATOR_INVITES);
    }

    // A wiped team is eliminated without ending the match; otherwise the match ends when a side loses.
    public boolean eliminatesWipedTeams() {
        return rules.contains(Rule.ELIMINATES_WIPED_TEAMS);
    }

    // Global chat is the duelists only; everyone else is routed to the spectators' chat (MatchChat).
    public boolean restrictsSpectatorChat() {
        return rules.contains(Rule.RESTRICTS_SPECTATOR_CHAT);
    }

    // Vanilla PvP on a chosen .msav instead of the generated hex map; never recorded.
    public boolean pure() {
        return rules.contains(Rule.PURE);
    }

    // Pure modes: how many core teams the map must have; 0 = any (Training).
    public int mapTeams() {
        return switch (this) {
            case PURE_1V1, PURE_2TEAM -> 2;
            case PURE_4TEAM -> 4;
            default -> 0;
        };
    }

    // Unknown ids fall back to plain 1v1.
    public static MatchMode fromId(String id) {
        for (MatchMode mode : values()) {
            if (mode.id.equals(id)) {
                return mode;
            }
        }

        return ONE_VS_ONE;
    }
}
