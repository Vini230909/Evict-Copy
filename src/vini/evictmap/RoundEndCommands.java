package vini.evictmap;

import arc.util.CommandHandler;
import arc.util.Time;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.gen.Player;

/**
 * Player-facing round-ending commands.
 *
 * Kept separate from EvictCommands because these commands affect the complete
 * match state.
 */
final class RoundEndCommands {

    private static final long SURRENDER_UNLOCK_DELAY_MILLIS =
        10L * 60L * 1000L;
    private static final float SURRENDER_UNLOCK_DELAY_TICKS =
        10f * 60f * 60f;

    private final TeamManager teamManager;
    private final ExtinctionManager extinctionManager;

    RoundEndCommands(
        TeamManager teamManager,
        ExtinctionManager extinctionManager
    ) {
        this.teamManager = teamManager;
        this.extinctionManager = extinctionManager;
    }

    void registerClientCommands(CommandHandler handler) {
        handler.<Player>register(
            "die",
            "Leader only: surrender your complete team after 10 minutes.",
            (args, player) -> surrender(args, player)
        );

        handler.<Player>register(
            "over",
            "End an eligible round immediately.",
            (args, player) -> endEarly(args, player)
        );
    }

    void beginRound() {
        long scheduledRoundSerial = teamManager.roundSerial();

        Time.run(
            SURRENDER_UNLOCK_DELAY_TICKS,
            () -> announceOpeningPeriodEnded(scheduledRoundSerial)
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

        long remainingMillis =
            SURRENDER_UNLOCK_DELAY_MILLIS - teamManager.roundRuntimeMillis();

        if (remainingMillis > 0L) {
            player.sendMessage(
                "[scarlet]Your team cannot surrender during the opening 10 minutes.[]"
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

        if (extinctionManager.blocksEarlyEnd()) {
            player.sendMessage(
                "[scarlet]/over is disabled because EXTINCTION is approaching or already active.[]"
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

    private void announceOpeningPeriodEnded(long scheduledRoundSerial) {
        if (
            !teamManager.isRoundActiveForSystems()
                || scheduledRoundSerial != teamManager.roundSerial()
        ) {
            return;
        }

        String activeMatchPlayers =
            teamManager.activeMatchPlayerNamesSummary();

        if (activeMatchPlayers.isBlank()) {
            return;
        }

        Call.sendMessage(
            "[accent]The opening 10 minutes have passed.[]\n"
                + "[lightgray]Match players: []"
                + activeMatchPlayers
                + "[lightgray].[]"
        );
    }
}
