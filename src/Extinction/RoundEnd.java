// Ending an Extinction round from chat: /die surrenders a team, /over calls a decided round early.
package Extinction;

import Extinction.round.TeamManager;

import arc.util.Time;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.gen.Player;

public final class RoundEnd {

    private static final long SURRENDER_UNLOCK_DELAY_MILLIS = 10L * 60L * 1000L;
    private static final float SURRENDER_UNLOCK_DELAY_TICKS = 10f * 60f * 60f;

    private final TeamManager teamManager;
    private final Referee referee;

    // On a worker /die has no leader or opening-period gate and /over is off: a match ends by owning every core.
    private final boolean duelWorker = "true".equals(System.getProperty("evict.duelWorker"));

    public RoundEnd(TeamManager teamManager, Referee referee) {
        this.teamManager = teamManager;
        this.referee = referee;
    }

    public void beginRound() {
        long scheduledRoundSerial = teamManager.roundSerial();
        Time.run(SURRENDER_UNLOCK_DELAY_TICKS, () -> announceOpeningPeriodEnded(scheduledRoundSerial));
    }

    // /die on the hub or an Extinction worker (a Pure worker has its own, see PureMatch.surrender).
    public void surrender(Player player) {
        if (!teamManager.isRoundActiveForSystems()) {
            player.sendMessage("[scarlet]No active Evict round.[]");
            return;
        }

        // Sandbox /die is owner-only and ends the room; it must not surrender the shared sandbox team.
        if (duelWorker && referee.sandbox.handleDie(player)) {
            return;
        }

        if (!duelWorker) {
            if (!teamManager.isLeader(player)) {
                player.sendMessage("[scarlet]Only your team's original leader can surrender.[]");
                return;
            }

            long remainingMillis = SURRENDER_UNLOCK_DELAY_MILLIS - teamManager.roundRuntimeMillis();
            if (remainingMillis > 0L) {
                player.sendMessage("[scarlet]Your team cannot surrender during the opening 10 minutes.[]");
                return;
            }
        }

        if (!teamManager.surrenderTeam(player.team())) {
            player.sendMessage("[scarlet]Your team can no longer surrender right now.[]");
            return;
        }

        // Training has no opponent left to win: the referee ends the session, nothing is recorded.
        if (duelWorker) {
            referee.handleParticipantSurrender(player);
        }
    }

    // /over: anyone may call a decided round; see docs/GAMEPLAY.md "/over" for why.
    public void endEarly(Player player) {
        if (duelWorker) {
            player.sendMessage("[scarlet]/over is not available in duels.[]");
            return;
        }

        if (!teamManager.isRoundActiveForSystems()) {
            player.sendMessage("[scarlet]No active Evict round.[]");
            return;
        }

        Team team = player.team();

        // A personal team asks about itself; Fallen and newcomers ask about whoever qualifies.
        if (team == TeamManager.FALLEN_TEAM || !teamManager.isActivePersonalTeam(team.id)) {
            team = teamManager.eligibleEarlyEndTeam();
            if (team == null) {
                player.sendMessage("[scarlet]No team meets the conditions to end the round early yet.[]");
                return;
            }
        }

        TeamManager.EarlyEndStatus status = teamManager.earlyEndStatus(team);
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

    private void showEarlyEndProblems(Player player, TeamManager.EarlyEndStatus status) {
        StringBuilder message = new StringBuilder("[scarlet]You cannot end the round early yet.[]");

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

    // Ten minutes in: list the active match players (no counts, no mention of /die).
    private void announceOpeningPeriodEnded(long scheduledRoundSerial) {
        if (!teamManager.isRoundActiveForSystems() || scheduledRoundSerial != teamManager.roundSerial()) {
            return;
        }

        String activeMatchPlayers = teamManager.activeMatchPlayerNamesSummary();
        if (activeMatchPlayers.isBlank()) {
            return;
        }

        Call.sendMessage("[lightgray]Playing: []" + activeMatchPlayers);
    }
}
