package vini.evictmap.discord;

import java.util.List;

/**
 * Everything the Discord status message shows, captured at one instant. Filled
 * on the main thread (gathering touches Mindustry state) and handed to the
 * background thread that renders and sends it, which sees nothing else.
 *
 * <p>{@code online} is false only for the farewell message on shutdown,
 * {@code serverName} is still unescaped, and {@code playerLimit} is 0 when the
 * server is uncapped.
 */
record StatusSnapshot(
        boolean online,
        String serverName,
        int hubPlayers,
        int duelPlayers,
        int playerLimit,
        long roundSeconds,
        long extinctionInSeconds,
        boolean extinctionBegun,
        boolean restartQueued,
        int usedMatchSlots,
        int maxMatchSlots,
        List<Match> matches,
        List<LadderEntry> ladder,
        long timestampSeconds
) {

    int totalPlayers() {
        return hubPlayers + duelPlayers;
    }

    /**
     * One running match. {@code slot} is the 1-based pool slot, deliberately
     * not the port; names in {@code teams} are already cleaned.
     */
    record Match(
            int slot,
            String modeLabel,
            List<List<String>> teams,
            long seconds
    ) {
    }

    /** One ranked ladder row; {@code rank} is 1-based, {@code name} cleaned. */
    record LadderEntry(
            int rank,
            String name,
            int elo
    ) {
    }
}
