package vini.evictmap.commands;

import arc.util.CommandHandler;
import vini.evictmap.data.PlayerDataManager;
import vini.evictmap.core.cmd.Commands;
import vini.evictmap.core.text.Text;

/**
 * {@code /leaderboard} ({@code /top}, {@code /lb}) - the ranked ELO ladder.
 *
 * <p>A player-facing view over data the plugin already stores in
 * {@code evict-players.db}. Built on the command framework and the
 * {@link Text} layer.
 */
public final class LeaderboardCommands {

    private static final int DEFAULT_COUNT = 10;
    private static final int MAX_COUNT = 25;

    private final PlayerDataManager playerData;

    public LeaderboardCommands(PlayerDataManager playerData) {
        this.playerData = playerData;
    }

    public void registerClientCommands(CommandHandler handler) {
        Commands commands = new Commands();

        commands.command("leaderboard").aliases("top", "lb").client()
                .args("count:int?")
                .description("Show the top ranked players by ELO.")
                .run(ctx -> {
                    int requested = ctx.has("count") ? ctx.getInt("count", DEFAULT_COUNT) : DEFAULT_COUNT;
                    int limit = Math.max(1, Math.min(requested, MAX_COUNT));

                    playerData.topRankedByElo(limit, rows -> {
                        if (rows.isEmpty()) {
                            ctx.reply(Text.of().accent("No ranked matches have been played yet."));
                            return;
                        }

                        Text out = Text.of().gold("=== Ranked Leaderboard (Top " + rows.size() + ") ===");
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

                        ctx.reply(out);
                    });
                });

        commands.installClient(handler);
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
