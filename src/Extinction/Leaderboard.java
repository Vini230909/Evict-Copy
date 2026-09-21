// /top: the ranked ELO ladder, read from the player DB.
package Extinction;

import Extinction.core.text.Text;
import Extinction.data.PlayerDataManager;

import mindustry.gen.Player;

public final class Leaderboard {

    public static final int DEFAULT_COUNT = 10;
    private static final int MAX_COUNT = 25;

    private final PlayerDataManager playerData;

    public Leaderboard(PlayerDataManager playerData) {
        this.playerData = playerData;
    }

    // Sends the top `requested` players (clamped to 1-25) to the viewer.
    public void show(Player viewer, int requested) {
        int limit = Math.max(1, Math.min(requested, MAX_COUNT));

        playerData.topRankedByElo(limit, rows -> {
            if (rows.isEmpty()) {
                viewer.sendMessage(Text.of().accent("No 1v1 matches have been played yet.").str());
                return;
            }

            Text out = Text.of().gold("=== 1v1 Leaderboard (Top " + rows.size() + ") ===");
            int rank = 1;
            for (PlayerDataManager.PlayerInfo info : rows) {
                out.add("\n")
                        .lightGray(medal(rank) + " ")
                        .white(info.lastName())
                        .add("  ")
                        .accent(info.elo()).lightGray(" ELO  ")
                        .green(info.rankedWins() + "W")
                        .lightGray("/")
                        .scarlet(info.rankedLosses() + "L");
                rank++;
            }

            viewer.sendMessage(out.str());
        });
    }

    private static String medal(int rank) {
        switch (rank) {
            case 1: return "[gold]1.[]";
            case 2: return "[lightgray]2.[]";
            case 3: return "[orange]3.[]";
            default: return rank + ".";
        }
    }
}
