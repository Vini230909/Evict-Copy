package vini.evictmap;

import arc.util.CommandHandler;
import mindustry.gen.Player;


/**
 * Player-facing round-ending commands.
 * Kept separate from EvictCommands because these commands affect the complete
 * match state and need confirmation windows.
 */
final class RoundEndCommands {

    private final TeamManager teamManager;

    RoundEndCommands(TeamManager teamManager) {
        this.teamManager = teamManager;
    }

    void registerClientCommands(CommandHandler handler) {
        handler.register(
            "die",
            "Kill all cores and units",
            this::surrender
        );
    }

    private void surrender(String[] args, Player player) {
        if (args.length != 0) {
            player.sendMessage("[scarlet]Use: /die[]");
            return;
        }

        if (!teamManager.isRoundActiveForSystems()) {
            player.sendMessage("[scarlet]No active Evict round.[]");
            return;
        }

        if (!teamManager.isLeader(player)) {
            player.sendMessage(
                "[scarlet]Only your team's original leader can surrender.[]"
            );
            return;
        }

        if (!teamManager.surrenderTeam(player.team())) {
            player.sendMessage(
                "[scarlet]Your team can no longer surrender right now.[]"
            );
        }
    }
}
