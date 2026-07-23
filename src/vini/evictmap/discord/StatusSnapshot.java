package vini.evictmap.discord;

import java.util.List;

/**
 * Everything the Discord status message shows, captured at one instant.
 *
 * <p>This exists so the two halves of the job stay apart: gathering the numbers
 * touches Mindustry state and therefore has to happen on the main thread, while
 * rendering and sending them is slow (JSON, a network round trip) and must not.
 * {@link DiscordStatusReporter} fills a snapshot on the main thread and hands
 * this immutable value to a background thread, which is the only thing the
 * sender ever sees.
 *
 * @param online           false only for the farewell message on shutdown
 * @param serverName       the host's configured server name, unescaped
 * @param hubPlayers       players in the main lobby round
 * @param duelPlayers      players inside duel workers (duelists and viewers)
 * @param playerLimit      configured slot cap; 0 when the server is uncapped
 * @param roundSeconds     runtime of the current generated round
 * @param extinctionInSeconds seconds until the ring collapse starts
 * @param extinctionBegun  whether Extinction is already collapsing rings
 * @param restartQueued    whether a graceful restart is waiting to fire
 * @param usedMatchSlots   worker slots in use
 * @param maxMatchSlots    worker slots configured
 * @param matches          one entry per running match, ordered by slot
 * @param ladder           the ranked ELO ladder, highest first
 * @param timestampSeconds epoch seconds this snapshot was taken
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
     * One running match.
     *
     * @param slot      the pool slot, 1-based; deliberately not the port, which
     *                  is nobody's business outside the server console
     * @param modeLabel the match mode's display label
     * @param teams     rosters of already-cleaned player names, one list per team
     * @param seconds   how long the match has been running
     */
    record Match(
            int slot,
            String modeLabel,
            List<List<String>> teams,
            long seconds
    ) {
    }

    /**
     * One ranked ladder row.
     *
     * @param rank 1-based position
     * @param name already-cleaned player name
     * @param elo  current rating
     */
    record LadderEntry(
            int rank,
            String name,
            int elo
    ) {
    }
}
