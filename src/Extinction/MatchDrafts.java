// Teams, Random Teams and FFA drafts: rosters built through pick menus, then an invite for everyone.
package Extinction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import arc.util.Time;
import mindustry.gen.Call;
import mindustry.gen.Player;
import mindustry.ui.Menus;

public final class MatchDrafts {

    // The Next team button disappears once this many rosters exist; also the largest Random Teams count.
    public static final int MAX_TEAMS = 8;

    private final Matchmaking matchmaking;
    private final Matches matches;

    private final int pickMenuId;
    private final int inviteMenuId;

    // Challenger UUID -> their draft being built or invited.
    private final Map<String, MatchDraft> draftsByChallengerUuid = new HashMap<>();

    // Invitee UUID -> challenger UUID of the draft inviting them.
    private final Map<String, String> draftChallengerByInviteeUuid = new HashMap<>();

    private int pendingSerial = 0;

    public MatchDrafts(Matchmaking matchmaking, Matches matches) {
        this.matchmaking = matchmaking;
        this.matches = matches;
        this.pickMenuId = Menus.registerMenu(this::handlePickSelection);
        this.inviteMenuId = Menus.registerMenu(this::handleInviteResponse);
    }

    // A draft involving the leaver is cancelled entirely because its rosters are broken.
    void handlePlayerLeave(Player player) {
        String uuid = player.uuid();

        draftChallengerByInviteeUuid.remove(uuid);

        for (MatchDraft draft :
                new ArrayList<>(draftsByChallengerUuid.values())) {
            if (draft.involves(uuid)) {
                cancelDraft(
                        draft,
                        PlayerNameFormatter.displayName(player)
                                + "[scarlet] left the server"
                );
            }
        }
    }

    // An invite to answer, or part of a draft whose invites are out.
    boolean isBusy(String uuid) {
        if (draftChallengerByInviteeUuid.containsKey(uuid)) {
            return true;
        }

        for (MatchDraft draft : draftsByChallengerUuid.values()) {
            if (draft.inviting && draft.involves(uuid)) {
                return true;
            }
        }

        return false;
    }

    void begin(Player challenger, MatchMode mode, int teamCount) {
        if (matchmaking.otherOnlinePlayers(challenger).isEmpty()) {
            challenger.sendMessage("[scarlet]No other players are online.[]");
            return;
        }

        // A fresh draft supersedes the challenger's previous one; if its invites are already
        // out it is cancelled properly so the invitees learn their menus are dead.
        MatchDraft previous = draftsByChallengerUuid.get(challenger.uuid());

        if (previous != null && previous.inviting) {
            cancelDraft(
                    previous,
                    PlayerNameFormatter.displayName(challenger)
                            + "[scarlet] started a new match setup"
            );
        } else {
            draftChallengerByInviteeUuid.values()
                    .removeIf(challenger.uuid()::equals);
        }

        MatchDraft draft = new MatchDraft(mode, challenger.uuid(), teamCount);
        draftsByChallengerUuid.put(challenger.uuid(), draft);
        openPickMenu(challenger, draft);
    }

    private void openPickMenu(Player challenger, MatchDraft draft) {
        List<String> candidates = new ArrayList<>();

        for (Player candidate : matchmaking.otherOnlinePlayers(challenger)) {
            if (!draft.involves(candidate.uuid())) {
                candidates.add(candidate.uuid());
            }
        }

        draft.candidateUuids = candidates;

        List<String[]> rows = new ArrayList<>();
        List<String> currentRow = new ArrayList<>();

        for (String uuid : candidates) {
            Player candidate = Matchmaking.onlinePlayerByUuid(uuid);
            currentRow.add(
                    candidate == null
                            ? "?"
                            : PlayerNameFormatter.displayName(candidate)
            );

            if (currentRow.size() == Matchmaking.SELECTION_MENU_COLUMNS) {
                rows.add(currentRow.toArray(new String[0]));
                currentRow.clear();
            }
        }

        if (!currentRow.isEmpty()) {
            rows.add(currentRow.toArray(new String[0]));
        }

        // Footer rows: Next team (Teams only, hidden at the cap), Done, Cancel.
        if (showsNextTeamButton(draft)) {
            rows.add(new String[]{"[accent]Next team"});
        }

        rows.add(new String[]{"[green]Done - send invites"});
        rows.add(new String[]{"[red]Cancel"});

        String title = switch (draft.mode) {
            case TEAMS -> "[accent]Teams - Team " + draft.teams.size();
            case RANDOM_TEAMS ->
                    "[accent]Random Teams - " + draft.teamCount + " teams";
            default -> "[accent]FFA";
        };

        String instruction = switch (draft.mode) {
            case TEAMS -> {
                String base = draft.teams.size() == 1
                        ? "Pick extra players for Team 1 (your team)."
                        : "Pick the players of Team " + draft.teams.size()
                        + ". Uneven teams are allowed.";

                yield showsNextTeamButton(draft)
                        ? base + "\nNext team starts Team "
                        + (draft.teams.size() + 1)
                        + "; Done sends the invites."
                        : base + "\nTeam limit reached - Done sends the invites.";
            }
            case RANDOM_TEAMS ->
                    "Pick at least " + (draft.teamCount - 1)
                            + " other players, then press Done. Everyone is"
                            + " shuffled into " + draft.teamCount
                            + " random teams once all accepted.";
            default ->
                    "Pick as many players as you want, then press Done.";
        };

        Call.menu(
                challenger.con,
                pickMenuId,
                title,
                instruction + "\n\n" + rosterSummary(draft),
                rows.toArray(new String[0][])
        );
    }

    // Hidden at the cap, which shifts the Done/Cancel indices up.
    private static boolean showsNextTeamButton(MatchDraft draft) {
        return draft.mode == MatchMode.TEAMS
                && draft.teams.size() < MAX_TEAMS;
    }

    private void handlePickSelection(Player challenger, int option) {
        if (challenger == null) {
            return;
        }

        MatchDraft draft = draftsByChallengerUuid.get(challenger.uuid());

        if (draft == null || draft.inviting) {
            return;
        }

        int candidateCount = draft.candidateUuids.size();
        boolean nextShown = showsNextTeamButton(draft);
        int nextIndex = nextShown ? candidateCount : -1;
        int doneIndex = candidateCount + (nextShown ? 1 : 0);
        int cancelIndex = doneIndex + 1;

        if (option < 0 || option > cancelIndex) {
            return;
        }

        if (option < candidateCount) {
            String pickedUuid = draft.candidateUuids.get(option);

            if (Matchmaking.onlinePlayerByUuid(pickedUuid) == null) {
                challenger.sendMessage(
                        "[scarlet]That player is no longer online.[]"
                );
            } else {
                draft.currentRoster().add(pickedUuid);
            }

            openPickMenu(challenger, draft);
            return;
        }

        if (option == cancelIndex) {
            draftsByChallengerUuid.remove(challenger.uuid());
            challenger.sendMessage("[lightgray]Match setup cancelled.[]");
            return;
        }

        if (option == nextIndex) {
            // A new team may only start once the current one has a player.
            if (draft.currentRoster().isEmpty()) {
                challenger.sendMessage(
                        "[scarlet]Pick at least one player for Team "
                                + draft.teams.size() + " first.[]"
                );
            } else {
                draft.teams.add(new ArrayList<>());
            }

            openPickMenu(challenger, draft);
            return;
        }

        // Done. An empty trailing team was never used - drop it quietly.
        if (
                draft.mode == MatchMode.TEAMS
                        && draft.teams.size() > 1
                        && draft.currentRoster().isEmpty()
        ) {
            draft.teams.remove(draft.teams.size() - 1);
        }

        if (draft.mode == MatchMode.TEAMS && draft.teams.size() < 2) {
            challenger.sendMessage(
                    "[scarlet]Pick at least one player for Team 2.[]"
            );
            openPickMenu(challenger, draft);
            return;
        }

        if (
                draft.mode == MatchMode.FFA
                        && draft.teams.get(0).size() < 2
        ) {
            challenger.sendMessage(
                    "[scarlet]Pick at least one other player for the FFA.[]"
            );
            openPickMenu(challenger, draft);
            return;
        }

        // Every random team needs at least one player.
        if (
                draft.mode == MatchMode.RANDOM_TEAMS
                        && draft.teams.get(0).size() < draft.teamCount
        ) {
            challenger.sendMessage(
                    "[scarlet]Pick at least "
                            + (draft.teamCount - 1)
                            + " other players for "
                            + draft.teamCount + " random teams.[]"
            );
            openPickMenu(challenger, draft);
            return;
        }

        sendDraftInvites(challenger, draft);
    }

    private void sendDraftInvites(Player challenger, MatchDraft draft) {
        List<String> inviteeUuids = new ArrayList<>();

        for (String uuid : draft.allPickedUuids()) {
            if (!uuid.equals(draft.challengerUuid)) {
                inviteeUuids.add(uuid);
            }
        }

        for (String uuid : inviteeUuids) {
            Player invitee = Matchmaking.onlinePlayerByUuid(uuid);

            if (invitee == null) {
                cancelDraft(draft, "a picked player left the server");
                return;
            }

            // Refuse instead of overwriting their pending entry.
            if (matchmaking.isBusy(uuid)) {
                cancelDraft(
                        draft,
                        PlayerNameFormatter.displayName(invitee)
                                + "[scarlet] is already in another match setup"
                );
                return;
            }
        }

        draft.inviting = true;
        draft.inviteSerial = ++pendingSerial;
        draft.pendingInviteeUuids.addAll(inviteeUuids);

        int serial = draft.inviteSerial;
        Time.run(
                Matchmaking.PENDING_RESPONSE_TIMEOUT_TICKS,
                () -> expireDraftInvites(draft, serial)
        );

        String summary = rosterSummary(draft);

        for (String uuid : inviteeUuids) {
            Player invitee = Matchmaking.onlinePlayerByUuid(uuid);
            draftChallengerByInviteeUuid.put(uuid, draft.challengerUuid);

            Call.menu(
                    invitee.con,
                    inviteMenuId,
                    "[accent]" + draft.mode.label() + " invite",
                    PlayerNameFormatter.displayName(challenger)
                            + "[white] invited you to a "
                            + draft.mode.label() + " match.\n\n" + summary,
                    new String[][]{
                            {"[green]Accept"},
                            {"[red]Decline"}
                    }
            );
        }

        challenger.sendMessage(
                "[accent]Invites sent. Waiting for "
                        + inviteeUuids.size()
                        + " player(s) to accept...[]"
        );
    }

    private void handleInviteResponse(Player invitee, int option) {
        if (invitee == null) {
            return;
        }

        String challengerUuid =
                draftChallengerByInviteeUuid.remove(invitee.uuid());

        if (challengerUuid == null) {
            // Only answer an actual click; dismissing a stale menu is silent.
            if (option == Matchmaking.ACCEPT_OPTION) {
                invitee.sendMessage(
                        "[lightgray]That invite is no longer active.[]"
                );
            }

            return;
        }

        MatchDraft draft = draftsByChallengerUuid.get(challengerUuid);

        if (draft == null || !draft.inviting) {
            invitee.sendMessage(
                    "[scarlet]That match was already cancelled.[]"
            );
            return;
        }

        if (option != Matchmaking.ACCEPT_OPTION) {
            cancelDraft(
                    draft,
                    PlayerNameFormatter.displayName(invitee)
                            + "[scarlet] declined"
            );
            return;
        }

        draft.pendingInviteeUuids.remove(invitee.uuid());

        Player challenger = Matchmaking.onlinePlayerByUuid(challengerUuid);

        if (challenger != null && !draft.pendingInviteeUuids.isEmpty()) {
            challenger.sendMessage(
                    "[accent]"
                            + PlayerNameFormatter.displayName(invitee)
                            + "[accent] accepted ("
                            + draft.pendingInviteeUuids.size()
                            + " left).[]"
            );
        }

        if (draft.pendingInviteeUuids.isEmpty()) {
            launchDraft(draft);
        }
    }

    private void launchDraft(MatchDraft draft) {
        List<List<Player>> rosters = new ArrayList<>();

        if (draft.mode == MatchMode.TEAMS) {
            for (List<String> team : draft.teams) {
                List<Player> roster = resolveRoster(team);

                if (roster == null) {
                    cancelDraft(draft, "a player left the server");
                    return;
                }

                rosters.add(roster);
            }
        } else if (draft.mode == MatchMode.RANDOM_TEAMS) {
            List<Player> pool = resolveRoster(draft.teams.get(0));

            if (pool == null) {
                cancelDraft(draft, "a player left the server");
                return;
            }

            rosters.addAll(shuffleIntoTeams(pool, draft.teamCount));
        } else {
            for (String uuid : draft.teams.get(0)) {
                Player player = Matchmaking.onlinePlayerByUuid(uuid);

                if (player == null) {
                    cancelDraft(draft, "a player left the server");
                    return;
                }

                rosters.add(List.of(player));
            }
        }

        draftsByChallengerUuid.remove(draft.challengerUuid);

        // Random Teams is a hub-only flavour: once drawn it runs (and is recorded) as Teams.
        MatchMode wireMode = draft.mode == MatchMode.RANDOM_TEAMS
                ? MatchMode.TEAMS
                : draft.mode;

        if (!matches.requestMatch(wireMode, rosters)) {
            for (List<Player> roster : rosters) {
                for (Player player : roster) {
                    player.sendMessage(
                            "[scarlet]All match servers are busy right now. Try again shortly.[]"
                    );
                }
            }
        }
    }

    // Balanced shuffle: the first teams get the extra player, so sizes never differ by more than one.
    private static List<List<Player>> shuffleIntoTeams(
            List<Player> pool,
            int teamCount
    ) {
        List<Player> shuffled = new ArrayList<>(pool);
        Collections.shuffle(shuffled);

        List<List<Player>> teams = new ArrayList<>();
        int baseSize = shuffled.size() / teamCount;
        int extras = shuffled.size() % teamCount;
        int nextIndex = 0;

        for (int team = 0; team < teamCount; team++) {
            int size = baseSize + (team < extras ? 1 : 0);
            teams.add(
                    new ArrayList<>(
                            shuffled.subList(nextIndex, nextIndex + size)
                    )
            );
            nextIndex += size;
        }

        return teams;
    }

    // A roster of UUIDs as online players, or null if anyone left.
    private List<Player> resolveRoster(List<String> uuids) {
        List<Player> players = new ArrayList<>();

        for (String uuid : uuids) {
            Player player = Matchmaking.onlinePlayerByUuid(uuid);

            if (player == null) {
                return null;
            }

            players.add(player);
        }

        return players;
    }

    private void cancelDraft(MatchDraft draft, String reason) {
        draftsByChallengerUuid.remove(draft.challengerUuid);

        draftChallengerByInviteeUuid.values()
                .removeIf(draft.challengerUuid::equals);

        for (String uuid : draft.allPickedUuids()) {
            Player member = Matchmaking.onlinePlayerByUuid(uuid);

            if (member != null) {
                member.sendMessage(
                        "[scarlet]The " + draft.mode.label()
                                + " match was cancelled: " + reason + ".[]"
                );
            }
        }
    }

    private String rosterSummary(MatchDraft draft) {
        if (draft.mode == MatchMode.TEAMS) {
            StringBuilder summary = new StringBuilder();

            for (int index = 0; index < draft.teams.size(); index++) {
                if (index > 0) {
                    summary.append("\n");
                }

                summary.append("Team ")
                        .append(index + 1)
                        .append(": ")
                        .append(namesOf(draft.teams.get(index)));
            }

            return summary.toString();
        }

        if (draft.mode == MatchMode.RANDOM_TEAMS) {
            return "Players: " + namesOf(draft.teams.get(0))
                    + "\n[lightgray]Shuffled into " + draft.teamCount
                    + " random teams.[]";
        }

        return "Players: " + namesOf(draft.teams.get(0));
    }

    private String namesOf(List<String> uuids) {
        if (uuids.isEmpty()) {
            return "[lightgray](nobody yet)[]";
        }

        StringBuilder names = new StringBuilder();

        for (String uuid : uuids) {
            Player player = Matchmaking.onlinePlayerByUuid(uuid);

            if (!names.isEmpty()) {
                names.append("[white], ");
            }

            names.append(
                    player == null
                            ? "[lightgray](left)[]"
                            : PlayerNameFormatter.displayName(player)
            );
        }

        return names.toString();
    }

    // Expires a draft whose invites were not all answered in time.
    private void expireDraftInvites(MatchDraft draft, int serial) {
        if (
                draftsByChallengerUuid.get(draft.challengerUuid) != draft
                        || !draft.inviting
                        || draft.inviteSerial != serial
                        || draft.pendingInviteeUuids.isEmpty()
        ) {
            return;
        }

        cancelDraft(draft, "not everyone accepted in time");
    }
}
