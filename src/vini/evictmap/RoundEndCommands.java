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

    private static final long SURRENDER_WINDOW_MILLIS = 30_000L;
    private static final int SURRENDER_CONFIRMATIONS_REQUIRED = 3;

    private static final long EARLY_END_WINDOW_MILLIS = 20_000L;
    private static final int EARLY_END_CONFIRMATIONS_REQUIRED = 2;

    private final TeamManager teamManager;
    private final Map<Integer, ConfirmationState> surrenderByTeamId =
        new HashMap<>();
    private final Map<Integer, ConfirmationState> earlyEndByTeamId =
        new HashMap<>();

    RoundEndCommands(TeamManager teamManager) {
        this.teamManager = teamManager;
    }

    void registerClientCommands(CommandHandler handler) {
        handler.<Player>register(
            "die",
            "Leader only: confirm surrender three times within 30 seconds.",
            (args, player) -> surrender(args, player)
        );

        handler.<Player>register(
            "over",
            "Confirm an eligible early round end twice within 20 seconds.",
            (args, player) -> endEarly(args, player)
        );
    }

    void beginRound() {
        surrenderByTeamId.clear();
        earlyEndByTeamId.clear();
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

        int confirmations = registerConfirmation(
            surrenderByTeamId,
            player.team().id,
            SURRENDER_WINDOW_MILLIS
        );

        if (confirmations == 1) {
            player.sendMessage(
                "[scarlet]Are you sure? Use /die two more times within 30 seconds to surrender.[]"
            );
            return;
        }

        if (confirmations == 2) {
            player.sendMessage(
                "[scarlet]Are you really sure? Use /die one more time within 30 seconds to surrender.[]"
            );
            return;
        }

        surrenderByTeamId.remove(player.team().id);

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
            earlyEndByTeamId.remove(team.id);
            showEarlyEndProblems(player, status);
            return;
        }

        int confirmations = registerConfirmation(
            earlyEndByTeamId,
            team.id,
            EARLY_END_WINDOW_MILLIS
        );

        if (confirmations == 1) {
            player.sendMessage(
                "[accent]Early round end is available. Use /over once more within 20 seconds to confirm.[]"
            );
            return;
        }

        earlyEndByTeamId.remove(team.id);

        if (!teamManager.endRoundEarly(team)) {
            player.sendMessage(
                "[scarlet]The early round-end conditions changed. Use /over again after checking the remaining requirements.[]"
            );
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

    private int registerConfirmation(
        Map<Integer, ConfirmationState> confirmationsByTeamId,
        int teamId,
        long windowMillis
    ) {
        long now = System.currentTimeMillis();
        ConfirmationState previous = confirmationsByTeamId.get(teamId);

        if (previous == null || now > previous.deadlineMillis) {
            confirmationsByTeamId.put(
                teamId,
                new ConfirmationState(1, now + windowMillis)
            );

            return 1;
        }

        int confirmations = previous.confirmations + 1;

        confirmationsByTeamId.put(
            teamId,
            new ConfirmationState(confirmations, previous.deadlineMillis)
        );

        return confirmations;
    }

    private record ConfirmationState(
        int confirmations,
        long deadlineMillis
    ) {
    }
}
