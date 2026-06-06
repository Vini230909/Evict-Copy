package vini.evictmap;

import arc.util.CommandHandler;
import mindustry.game.Team;
import mindustry.gen.Player;

import java.util.HashMap;
import java.util.Map;

/**
 * Player-facing round-ending commands.
 *
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

        handler.register(
            "over",
            "Force early round end if conditions are met",
            this::endEarly
        );

        handler.register(
                "canDoOver",
                "Determine whether the conditions for early round entry are met",
                this::canEndEarly
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

    private void endEarly(String[] args, Player player) {
        if (args.length != 0) {
            player.sendMessage("[scarlet]Use: /over[]");
            return;
        }

        if (!teamManager.isRoundActiveForSystems()) {
            player.sendMessage("[scarlet]No active Evict round.[]");
            return;
        }

        Team team = player.team();

        if (
            team == TeamManager.FALLEN_TEAM
                || !teamManager.isActivePersonalTeam(team.id)
        ) {
            player.sendMessage(
                "[scarlet]Only players in an active personal team can use /over.[]"
            );
            return;
        }

        TeamManager.EarlyEndStatus status =
            teamManager.earlyEndStatus(team);

        if (!status.eligible()) {
            showEarlyEndProblems(player, status);
            return;
        }

        if (!teamManager.endRoundEarly(team)) {
            player.sendMessage(
                "[scarlet]The early round-end conditions changed. Use /over again after checking the remaining requirements.[]"
            );
        }
    }

    private void canEndEarly(
            String[] args,
            Player player
    ) {
        if (args.length != 0) {
            player.sendMessage("[scarlet]Use: /canDoOver[]");
            return;
        }

        if (!teamManager.isRoundActiveForSystems()) {
            player.sendMessage("[scarlet]No active Evict Round.[]");
            return;
        }

        Team team = player.team();

        if (
                team == TeamManager.FALLEN_TEAM
                        || !teamManager.isActivePersonalTeam(team.id)
        ) {
            player.sendMessage(
                    "[scarlet]Only players in an active personal team can use /over.[]"
            );
            return;
        }

        TeamManager.EarlyEndStatus status =
                teamManager.earlyEndStatus(team);

        if (!status.eligible()) {
            showEarlyEndProblems(player, status);
        } else {
            player.sendMessage("[scarlet]You meet the conditions for early round-end.[]");
        }
    }

    private void showEarlyEndProblems(
        Player player,
        TeamManager.EarlyEndStatus status
    ) {
        StringBuilder message = new StringBuilder(
            "[scarlet]You cannot end the round early yet.[]"
        );

        if (status.additionalCoresNeededForHalf() > 0) {
            message.append("\n[lightgray]You need ")
                .append(status.additionalCoresNeededForHalf())
                .append(" more core");

            if (status.additionalCoresNeededForHalf() != 1) {
                message.append("s");
            }

            message.append(" to control at least 50% of the map.[]");
        }

        if (!status.blockers().isEmpty()) {
            message.append("\n[lightgray]You still need to eliminate:[]");

            for (TeamManager.EarlyEndBlocker blocker : status.blockers()) {
                message.append("\n[lightgray]- []")
                    .append(teamManager.displayTeam(blocker.team()))
                    .append("[lightgray]: destroy ")
                    .append(blocker.remainingCores())
                    .append(" remaining core");

                if (blocker.remainingCores() != 1) {
                    message.append("s");
                }

                message.append(".[]");
            }
        }

        player.sendMessage(message.toString());
    }
}
