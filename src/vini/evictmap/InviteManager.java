package vini.evictmap;

import arc.util.CommandHandler;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.gen.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * /invite workflow for eliminated and late-join Fallen spectators.
 *
 * - Unclaimed Fallen players can request several active teams.
 * - Only the original team leader can accept requests.
 * - Claimed Fallen players can only be pulled into their claimant's team.
 * - Requests disappear when their sender disconnects.
 * - Claims persist through reconnects for the current round.
 */
final class InviteManager {

    private final TeamManager teamManager;
    private final List<JoinRequest> joinRequests = new ArrayList<>();
    private final Map<String, Long> claimedOrderByPlayerUuid = new HashMap<>();

    private long sequence = 0L;

    InviteManager(TeamManager teamManager) {
        this.teamManager = teamManager;
    }

    void registerClientCommands(CommandHandler handler) {
        handler.<Player>register(
            "invite",
            "[number]",
            "List or use Evict team invitations.",
            (args, player) -> handleInvite(args, player)
        );
    }

    void beginRound() {
        joinRequests.clear();
        claimedOrderByPlayerUuid.clear();
        sequence = 0L;
    }

    void handlePlayerLeave(Player player) {
        if (player == null) {
            return;
        }

        removeRequestsFrom(player.uuid());
    }

    void handleTeamEliminated(
        Team eliminatedTeam,
        Team claimantTeam,
        List<String> newlyEliminatedPlayerUuids
    ) {
        if (eliminatedTeam == null) {
            return;
        }

        joinRequests.removeIf(
            request ->
                request.targetTeamId == eliminatedTeam.id
                    || newlyEliminatedPlayerUuids.contains(request.playerUuid)
        );

        for (String uuid : newlyEliminatedPlayerUuids) {
            if (claimantTeam != null) {
                claimedOrderByPlayerUuid.putIfAbsent(uuid, nextSequence());
            } else {
                claimedOrderByPlayerUuid.remove(uuid);
            }
        }

        /**
         * Existing claims may have been transferred because their claimant was
         * itself eliminated. Preserve their previous list order.
         */
        claimedOrderByPlayerUuid.keySet().removeIf(
            uuid -> teamManager.claimTeamId(uuid) == null
        );
    }

    void handlePlayerJoinedTeam(String playerUuid) {
        if (playerUuid == null) {
            return;
        }

        removeRequestsFrom(playerUuid);
        claimedOrderByPlayerUuid.remove(playerUuid);
    }

    private void handleInvite(String[] args, Player player) {
        if (player == null || !teamManager.isRoundActiveForSystems()) {
            if (player != null) {
                player.sendMessage("[scarlet]No active Evict round.[]");
            }
            return;
        }

        if (teamManager.isFallenPlayer(player)) {
            handleFallenInvite(args, player);
            return;
        }

        if (!teamManager.isLeader(player)) {
            player.sendMessage(
                "[scarlet]Only your team's original leader can manage invites.[]"
            );
            return;
        }

        handleLeaderInvite(args, player);
    }

    private void handleFallenInvite(String[] args, Player player) {
        Integer claimTeamId = teamManager.claimTeamId(player.uuid());

        if (claimTeamId != null) {
            player.sendMessage(
                "[accent]You were claimed by "
                    + teamManager.displayTeam(Team.get(claimTeamId))
                    + "'s team. Only this team can invite you.[]"
            );
            return;
        }

        List<Team> availableTeams = availableTeamsFor(player.uuid());

        if (args.length == 0) {
            showAvailableTeams(player, availableTeams);
            return;
        }

        if (args.length != 1) {
            player.sendMessage("[scarlet]Use: /invite [number][]");
            return;
        }

        Integer selectedIndex = parseSelection(args[0], player);

        if (
            selectedIndex == null
                || selectedIndex < 1
                || selectedIndex > availableTeams.size()
        ) {
            player.sendMessage("[scarlet]That team number is not available.[]");
            return;
        }

        Team targetTeam = availableTeams.get(selectedIndex - 1);
        Player leader = teamManager.onlineLeader(targetTeam);

        if (leader == null) {
            player.sendMessage(
                "[scarlet]That team's leader is currently offline.[]"
            );
            return;
        }

        joinRequests.add(
            new JoinRequest(
                player.uuid(),
                PlayerNameFormatter.displayName(player),
                targetTeam.id,
                nextSequence()
            )
        );

        player.sendMessage(
            "[green]Join request sent to "
                + teamManager.displayTeam(targetTeam)
                + "'s team.[]"
        );

        leader.sendMessage(
            "[accent]"
                + PlayerNameFormatter.displayName(player)
                + " wants to join your team. Use /invite to view pending requests.[]"
        );
    }

    private void handleLeaderInvite(String[] args, Player leader) {
        List<LeaderEntry> entries = leaderEntries(leader.team());

        if (args.length == 0) {
            showLeaderEntries(leader, entries);
            return;
        }

        if (args.length != 1) {
            leader.sendMessage("[scarlet]Use: /invite [number][]");
            return;
        }

        Integer selectedIndex = parseSelection(args[0], leader);

        if (
            selectedIndex == null
                || selectedIndex < 1
                || selectedIndex > entries.size()
        ) {
            leader.sendMessage("[scarlet]That invite number is not available.[]");
            return;
        }

        LeaderEntry entry = entries.get(selectedIndex - 1);

        if (
            !teamManager.joinFallenPlayerToTeam(entry.player, leader.team())
        ) {
            leader.sendMessage(
                "[scarlet]That player can no longer join your team.[]"
            );
            cleanupInvalidRequests();
            return;
        }

        handlePlayerJoinedTeam(entry.player.uuid());

        leader.sendMessage(
            "[green]"
                + PlayerNameFormatter.displayName(entry.player)
                + " joined your team.[]"
        );
    }

    private List<Team> availableTeamsFor(String playerUuid) {
        List<Team> result = new ArrayList<>();

        for (Team team : teamManager.activeTeamsWithOnlineLeader()) {
            if (!hasRequest(playerUuid, team.id)) {
                result.add(team);
            }
        }

        return result;
    }

    private List<LeaderEntry> leaderEntries(Team leaderTeam) {
        cleanupInvalidRequests();

        List<LeaderEntry> result = new ArrayList<>();

        for (JoinRequest request : joinRequests) {
            if (request.targetTeamId != leaderTeam.id) {
                continue;
            }

            Player requester = onlinePlayer(request.playerUuid);

            if (
                requester != null
                    && teamManager.isFallenPlayer(requester)
                    && teamManager.claimTeamId(request.playerUuid) == null
            ) {
                result.add(
                    new LeaderEntry(
                        requester,
                        EntryType.REQUEST,
                        request.sequence
                    )
                );
            }
        }

        for (Player player : Groups.player) {
            Integer claimTeamId = teamManager.claimTeamId(player.uuid());

            if (
                player != null
                    && teamManager.isFallenPlayer(player)
                    && claimTeamId != null
                    && claimTeamId == leaderTeam.id
            ) {
                long order = claimedOrderByPlayerUuid.computeIfAbsent(
                    player.uuid(),
                    ignored -> nextSequence()
                );

                result.add(
                    new LeaderEntry(
                        player,
                        EntryType.CLAIMED,
                        order
                    )
                );
            }
        }

        result.sort(Comparator.comparingLong(entry -> entry.sequence));
        return result;
    }

    private void showAvailableTeams(Player player, List<Team> availableTeams) {
        StringBuilder message = new StringBuilder("[accent]Available teams:[]");

        if (availableTeams.isEmpty()) {
            message.append("\n[lightgray]None");
        } else {
            for (int index = 0; index < availableTeams.size(); index++) {
                Team team = availableTeams.get(index);

                message.append("\n[lightgray]")
                    .append(index + 1)
                    .append(". []")
                    .append(teamManager.displayTeam(team));
            }
        }

        List<JoinRequest> requested = requestsFrom(player.uuid());

        if (!requested.isEmpty()) {
            message.append("\n[accent]Requested:[] ");

            for (int index = 0; index < requested.size(); index++) {
                if (index > 0) {
                    message.append("[lightgray], []");
                }

                message.append(
                    teamManager.displayTeam(
                        Team.get(requested.get(index).targetTeamId)
                    )
                );
            }
        }

        player.sendMessage(message.toString());
    }

    private void showLeaderEntries(Player leader, List<LeaderEntry> entries) {
        StringBuilder message = new StringBuilder("[accent]Invites:[]");

        if (entries.isEmpty()) {
            message.append("\n[lightgray]None");
        } else {
            for (int index = 0; index < entries.size(); index++) {
                LeaderEntry entry = entries.get(index);

                message.append("\n[lightgray]")
                    .append(index + 1)
                    .append(". []")
                    .append(PlayerNameFormatter.displayName(entry.player))
                    .append(entry.type == EntryType.REQUEST
                        ? " [accent][request][]"
                        : " [orange][claimed][]");
            }
        }

        leader.sendMessage(message.toString());
    }

    private List<JoinRequest> requestsFrom(String playerUuid) {
        List<JoinRequest> result = new ArrayList<>();

        for (JoinRequest request : joinRequests) {
            if (request.playerUuid.equals(playerUuid)) {
                result.add(request);
            }
        }

        result.sort(Comparator.comparingLong(request -> request.sequence));
        return result;
    }

    private boolean hasRequest(String playerUuid, int teamId) {
        for (JoinRequest request : joinRequests) {
            if (
                request.playerUuid.equals(playerUuid)
                    && request.targetTeamId == teamId
            ) {
                return true;
            }
        }

        return false;
    }

    private void removeRequestsFrom(String playerUuid) {
        joinRequests.removeIf(
            request -> request.playerUuid.equals(playerUuid)
        );
    }

    private void cleanupInvalidRequests() {
        Iterator<JoinRequest> iterator = joinRequests.iterator();

        while (iterator.hasNext()) {
            JoinRequest request = iterator.next();
            Player requester = onlinePlayer(request.playerUuid);

            if (
                requester == null
                    || !teamManager.isFallenPlayer(requester)
                    || teamManager.claimTeamId(request.playerUuid) != null
                    || !teamManager.isActivePersonalTeam(request.targetTeamId)
            ) {
                iterator.remove();
            }
        }
    }

    private Player onlinePlayer(String uuid) {
        for (Player player : Groups.player) {
            if (player != null && player.uuid().equals(uuid)) {
                return player;
            }
        }

        return null;
    }

    private Integer parseSelection(String value, Player player) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            player.sendMessage("[scarlet]Invite number must be a whole number.[]");
            return null;
        }
    }

    private long nextSequence() {
        return ++sequence;
    }

    private enum EntryType {
        REQUEST,
        CLAIMED
    }

    private record JoinRequest(
        String playerUuid,
        String playerName,
        int targetTeamId,
        long sequence
    ) {
    }

    private record LeaderEntry(
        Player player,
        EntryType type,
        long sequence
    ) {
    }
}
