package vini.evictmap.commands;

import vini.evictmap.*;
import vini.evictmap.duel.DuelServerManager;
import vini.evictmap.duel.DuelWorker;
import vini.evictmap.duel.MatchMode;

import arc.util.CommandHandler;
import arc.util.Time;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.ui.Menus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * /play (alias /p) match-making.
 * The command first asks for a game mode:
 * - 1v1: pick one opponent, they accept/decline, both go to a worker.
 * - Teams: build two rosters (even or uneven) purely through pick menus, every
 * picked player gets an accept/decline invite, then everyone is sent over.
 * - Random Teams: choose a team count (2-8), pick one pool of players like
 * FFA; once everyone accepted the pool is shuffled into that many balanced
 * teams and launched as a regular Teams match.
 * - FFA: pick any number of players (Done button), invites, everyone plays on
 * their own team.
 * - Training: the requester plays alone; /die ends the session, nothing is
 * recorded.
 * - Sandbox: like Training but with infinite resources, and spectators may
 * /invite to ask to join.
 * This hub server only sends the players to a match worker instance. Map/mode
 * rules and the match itself live on that separate instance, because a single
 * Mindustry server process can only host one game at a time.
 */
public final class DuelCommands {

    private static final int SELECTION_MENU_COLUMNS = 2;
    private static final int ACCEPT_OPTION = 0;

    /**
     * Safety cap for the Teams builder; the Next team button disappears once
     * this many rosters exist. Also the largest Random Teams team count.
     */
    private static final int MAX_TEAMS = 8;

    /**
     * Smallest Random Teams team count.
     */
    private static final int MIN_RANDOM_TEAMS = 2;

    /**
     * How long a 1v1 challenge or a draft invite may sit unanswered before it
     * expires (with a message to both sides). Without this, an invite menu
     * lost client-side left the pending entry behind forever, and that stale
     * state blocked the players involved from any further match-making.
     */
    private static final float PENDING_RESPONSE_TIMEOUT_TICKS = 60f * 60f;

    /**
     * Option indexes of the fixed mode menu (see openModeMenu's rows).
     */
    private static final MatchMode[] MODE_MENU_OPTIONS = {
            MatchMode.ONE_VS_ONE,
            MatchMode.TEAMS,
            MatchMode.RANDOM_TEAMS,
            MatchMode.FFA,
            MatchMode.TRAINING,
            MatchMode.SANDBOX
    };

    private final DuelServerManager duelManager;
    private final DuelWorker worker;
    private final RankManager rankManager;
    private final Runnable restartMatch;

    private final int modeMenuId;
    private final int selectionMenuId;
    private final int challengeMenuId;
    private final int teamCountMenuId;
    private final int pickMenuId;
    private final int inviteMenuId;
    private final int viewMenuId;

    /**
     * Challenger UUID -> ordered opponent UUIDs shown in their 1v1 menu.
     */
    private final Map<String, List<String>> selectionTargetsByChallengerUuid =
            new HashMap<>();

    /**
     * Opponent UUID -> outstanding 1v1 challenge against them. The serial
     * lets the expiry task recognise whether the entry it armed for is still
     * the current one.
     */
    private final Map<String, PendingChallenge> challengeByOpponentUuid =
            new HashMap<>();

    /**
     * Serial for challenge/invite expiry tasks; bumped whenever a new pending
     * challenge or invite round is created.
     */
    private int pendingSerial = 0;

    /**
     * Challenger UUID -> their Teams/FFA draft being built or invited.
     */
    private final Map<String, MatchDraft> draftsByChallengerUuid =
            new HashMap<>();

    /**
     * Invitee UUID -> challenger UUID of the draft inviting them.
     */
    private final Map<String, String> draftChallengerByInviteeUuid =
            new HashMap<>();

    /**
     * Viewer UUID -> ordered match ports shown in their /view menu.
     */
    private final Map<String, List<Integer>> viewTargetsByViewerUuid =
            new HashMap<>();

    public DuelCommands(
            DuelServerManager duelManager,
            DuelWorker worker,
            RankManager rankManager,
            Runnable restartMatch
    ) {
        this.duelManager = duelManager;
        this.worker = worker;
        this.rankManager = rankManager;
        this.restartMatch = restartMatch;
        this.modeMenuId = Menus.registerMenu(this::handleModeSelection);
        this.selectionMenuId = Menus.registerMenu(this::handleSelection);
        this.challengeMenuId = Menus.registerMenu(this::handleChallengeResponse);
        this.teamCountMenuId = Menus.registerMenu(this::handleTeamCountSelection);
        this.pickMenuId = Menus.registerMenu(this::handlePickSelection);
        this.inviteMenuId = Menus.registerMenu(this::handleInviteResponse);
        this.viewMenuId = Menus.registerMenu(this::handleViewSelection);
    }

    void registerClientCommands(CommandHandler handler) {
        handler.<Player>register(
                "play",
                "Start a match: 1v1, Teams, Random Teams, FFA, Training or Sandbox.",
                (args, player) -> openModeMenu(player)
        );

        handler.<Player>register(
                "p",
                "Alias for /play.",
                (args, player) -> openModeMenu(player)
        );

        handler.<Player>register(
                "view",
                "Spectate an ongoing match, or return to the lobby if spectating.",
                (args, player) -> handleViewCommand(player)
        );

        handler.<Player>register(
                "v",
                "Alias for /view.",
                (args, player) -> handleViewCommand(player)
        );

        handler.<Player>register(
                "restart",
                "Commentator/admin: restart the match you are spectating with a fresh map.",
                (args, player) -> handleRestartCommand(player)
        );
    }

    /**
     * Drops any menu state, outstanding challenge or draft that involves a
     * player who just left, so stale UUIDs never accumulate or resolve to a
     * ghost. A draft is cancelled entirely because its rosters are broken.
     */
    public void handlePlayerLeave(Player player) {
        if (player == null) {
            return;
        }

        String uuid = player.uuid();

        selectionTargetsByChallengerUuid.remove(uuid);
        challengeByOpponentUuid.remove(uuid);
        challengeByOpponentUuid.values().removeIf(
                pending -> pending.challengerUuid().equals(uuid)
        );
        draftChallengerByInviteeUuid.remove(uuid);
        viewTargetsByViewerUuid.remove(uuid);

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

    private void openModeMenu(Player player) {
        if (player == null) {
            return;
        }

        if (!duelManager.isConfigured()) {
            player.sendMessage(
                    "[scarlet]The match server is not set up yet. Ask an admin.[]"
            );
            return;
        }

        Call.menu(
                player.con,
                modeMenuId,
                "[accent]Play",
                "Select a game mode.",
                new String[][]{
                        {"1v1", "Teams"},
                        {"Random Teams", "FFA"},
                        {"Training", "Sandbox"},
                        {"[red]Cancel"}
                }
        );
    }

    private void handleModeSelection(Player player, int option) {
        if (player == null || option < 0 || option >= MODE_MENU_OPTIONS.length) {
            return;
        }

        MatchMode mode = MODE_MENU_OPTIONS[option];

        switch (mode) {
            case ONE_VS_ONE -> openSelectionMenu(player);
            case TEAMS, FFA -> beginDraft(player, mode, 0);
            case RANDOM_TEAMS -> openTeamCountMenu(player);
            case TRAINING, SANDBOX -> startSoloMatch(player, mode);
        }
    }

    // --- Random Teams (team count first, then one FFA-style player pool) ---

    private void openTeamCountMenu(Player player) {
        if (otherOnlinePlayers(player).isEmpty()) {
            player.sendMessage("[scarlet]No other players are online.[]");
            return;
        }

        Call.menu(
                player.con,
                teamCountMenuId,
                "[accent]Random Teams",
                "How many teams should the players be shuffled into?\n"
                        + "Teams are drawn randomly once everyone accepted.",
                new String[][]{
                        {"2", "3"},
                        {"4", "5"},
                        {"6", "7"},
                        {"8"},
                        {"[red]Cancel"}
                }
        );
    }

    private void handleTeamCountSelection(Player player, int option) {
        if (player == null) {
            return;
        }

        int teamCount = MIN_RANDOM_TEAMS + option;

        if (option < 0 || teamCount > MAX_TEAMS) {
            if (teamCount == MAX_TEAMS + 1) {
                player.sendMessage("[lightgray]Match setup cancelled.[]");
            }

            return;
        }

        beginDraft(player, MatchMode.RANDOM_TEAMS, teamCount);
    }

    /**
     * Starts a Training or Sandbox session immediately: the requester is the
     * only rostered player, so there is nobody to pick or invite.
     */
    private void startSoloMatch(Player player, MatchMode mode) {
        List<List<Player>> rosters = new ArrayList<>();
        rosters.add(List.of(player));

        if (!duelManager.requestMatch(mode, rosters)) {
            player.sendMessage(
                    "[scarlet]All match servers are busy right now. Try again shortly.[]"
            );
        }
    }

    // --- 1v1 (unchanged flow: pick one opponent, accept/decline) ---

    private void openSelectionMenu(Player player) {
        List<Player> opponents = otherOnlinePlayers(player);

        if (opponents.isEmpty()) {
            player.sendMessage("[scarlet]No other players are online.[]");
            return;
        }

        List<String> targetUuids = new ArrayList<>();
        List<String[]> rows = new ArrayList<>();
        List<String> currentRow = new ArrayList<>();

        for (Player opponent : opponents) {
            targetUuids.add(opponent.uuid());
            currentRow.add(PlayerNameFormatter.displayName(opponent));

            if (currentRow.size() == SELECTION_MENU_COLUMNS) {
                rows.add(currentRow.toArray(new String[0]));
                currentRow.clear();
            }
        }

        if (!currentRow.isEmpty()) {
            rows.add(currentRow.toArray(new String[0]));
        }

        rows.add(new String[]{"[red]Cancel"});
        selectionTargetsByChallengerUuid.put(player.uuid(), targetUuids);

        Call.menu(
                player.con,
                selectionMenuId,
                "[accent]1v1",
                "Select a player to challenge to a 1v1.",
                rows.toArray(new String[0][])
        );
    }

    private void handleSelection(Player player, int option) {
        if (player == null) {
            return;
        }

        List<String> targetUuids =
                selectionTargetsByChallengerUuid.remove(player.uuid());

        if (
                targetUuids == null
                        || option < 0
                        || option >= targetUuids.size()
        ) {
            return;
        }

        Player opponent = onlinePlayerByUuid(targetUuids.get(option));

        if (opponent == null || opponent == player) {
            player.sendMessage("[scarlet]That player is no longer online.[]");
            return;
        }

        // Refuse instead of overwriting their pending entry: a clobbered
        // invite left the other match setup waiting forever.
        if (isBusyWithMatchmaking(opponent.uuid())) {
            player.sendMessage(
                    "[scarlet]"
                            + PlayerNameFormatter.displayName(opponent)
                            + "[scarlet] is already in another match setup. Try again shortly.[]"
            );
            return;
        }

        int serial = ++pendingSerial;
        String opponentUuid = opponent.uuid();

        challengeByOpponentUuid.put(
                opponentUuid,
                new PendingChallenge(player.uuid(), serial)
        );
        Time.run(
                PENDING_RESPONSE_TIMEOUT_TICKS,
                () -> expireChallenge(opponentUuid, serial)
        );

        player.sendMessage(
                "[accent]Challenge sent to "
                        + PlayerNameFormatter.displayName(opponent)
                        + "[accent].[]"
        );

        Call.menu(
                opponent.con,
                challengeMenuId,
                "[accent]1v1 Challenge",
                PlayerNameFormatter.displayName(player)
                        + "[white] has challenged you to a 1v1.",
                new String[][]{
                        {"[green]Accept"},
                        {"[red]Decline"}
                }
        );
    }

    private void handleChallengeResponse(Player opponent, int option) {
        if (opponent == null) {
            return;
        }

        PendingChallenge pending =
                challengeByOpponentUuid.remove(opponent.uuid());

        if (pending == null) {
            // Only answer an actual click; dismissing a stale menu is silent.
            if (option == ACCEPT_OPTION) {
                opponent.sendMessage(
                        "[lightgray]That challenge is no longer active.[]"
                );
            }

            return;
        }

        String challengerUuid = pending.challengerUuid();

        Player challenger = onlinePlayerByUuid(challengerUuid);

        if (challenger == null || challenger == opponent) {
            opponent.sendMessage(
                    "[scarlet]The challenger is no longer online.[]"
            );
            return;
        }

        if (option != ACCEPT_OPTION) {
            challenger.sendMessage(
                    "[scarlet]"
                            + PlayerNameFormatter.displayName(opponent)
                            + "[scarlet] declined your 1v1.[]"
            );
            return;
        }

        /*
          The manager reserves a worker and redirects both players once it is
          hosting. Spawning happens off the main thread, so this returns right
          away; a false result means no free worker slot is available.
         */
        List<List<Player>> rosters = new ArrayList<>();
        rosters.add(List.of(challenger));
        rosters.add(List.of(opponent));

        if (!duelManager.requestMatch(MatchMode.ONE_VS_ONE, rosters)) {
            challenger.sendMessage(
                    "[scarlet]All match servers are busy right now. Try again shortly.[]"
            );
            opponent.sendMessage(
                    "[scarlet]All match servers are busy right now. Try again shortly.[]"
            );
        }
    }

    // --- Teams / FFA drafts (pick menus only, no typed input) ---

    private void beginDraft(Player challenger, MatchMode mode, int teamCount) {
        if (otherOnlinePlayers(challenger).isEmpty()) {
            challenger.sendMessage("[scarlet]No other players are online.[]");
            return;
        }

        // Starting a fresh draft supersedes any previous one by the same
        // challenger. A draft whose invites are already out is cancelled
        // properly so its invitees learn their menus are dead instead of
        // clicking into a void.
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

        for (Player candidate : otherOnlinePlayers(challenger)) {
            if (!draft.involves(candidate.uuid())) {
                candidates.add(candidate.uuid());
            }
        }

        draft.candidateUuids = candidates;

        List<String[]> rows = new ArrayList<>();
        List<String> currentRow = new ArrayList<>();

        for (String uuid : candidates) {
            Player candidate = onlinePlayerByUuid(uuid);
            currentRow.add(
                    candidate == null
                            ? "?"
                            : PlayerNameFormatter.displayName(candidate)
            );

            if (currentRow.size() == SELECTION_MENU_COLUMNS) {
                rows.add(currentRow.toArray(new String[0]));
                currentRow.clear();
            }
        }

        if (!currentRow.isEmpty()) {
            rows.add(currentRow.toArray(new String[0]));
        }

        // Footer buttons on their own full-width rows: Next team (Teams mode
        // only, hidden once the safety cap is reached), Done, Cancel.
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

    /**
     * True while the Teams pick menu offers the Next team button; hidden at
     * the {@link #MAX_TEAMS} cap, which shifts the Done/Cancel indices up.
     */
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

            if (onlinePlayerByUuid(pickedUuid) == null) {
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
            Player invitee = onlinePlayerByUuid(uuid);

            if (invitee == null) {
                cancelDraft(draft, "a picked player left the server");
                return;
            }

            // Refuse instead of overwriting their pending entry: a clobbered
            // invite left the other match setup waiting forever.
            if (isBusyWithMatchmaking(uuid)) {
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
                PENDING_RESPONSE_TIMEOUT_TICKS,
                () -> expireDraftInvites(draft, serial)
        );

        String summary = rosterSummary(draft);

        for (String uuid : inviteeUuids) {
            Player invitee = onlinePlayerByUuid(uuid);
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
            if (option == ACCEPT_OPTION) {
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

        if (option != ACCEPT_OPTION) {
            cancelDraft(
                    draft,
                    PlayerNameFormatter.displayName(invitee)
                            + "[scarlet] declined"
            );
            return;
        }

        draft.pendingInviteeUuids.remove(invitee.uuid());

        Player challenger = onlinePlayerByUuid(challengerUuid);

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
                Player player = onlinePlayerByUuid(uuid);

                if (player == null) {
                    cancelDraft(draft, "a player left the server");
                    return;
                }

                rosters.add(List.of(player));
            }
        }

        draftsByChallengerUuid.remove(draft.challengerUuid);

        // Random Teams is a hub-only draft flavor; once the rosters are drawn
        // it runs (and is recorded) as a regular Teams match.
        MatchMode wireMode = draft.mode == MatchMode.RANDOM_TEAMS
                ? MatchMode.TEAMS
                : draft.mode;

        if (!duelManager.requestMatch(wireMode, rosters)) {
            for (List<Player> roster : rosters) {
                for (Player player : roster) {
                    player.sendMessage(
                            "[scarlet]All match servers are busy right now. Try again shortly.[]"
                    );
                }
            }
        }
    }

    /**
     * Shuffles the accepted player pool into the requested number of teams.
     * Sizes stay balanced: when the pool does not divide evenly, the first
     * teams get one extra player, so sizes never differ by more than one.
     */
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

    /**
     * Resolves a roster of UUIDs to online players, or null if anyone left.
     */
    private List<Player> resolveRoster(List<String> uuids) {
        List<Player> players = new ArrayList<>();

        for (String uuid : uuids) {
            Player player = onlinePlayerByUuid(uuid);

            if (player == null) {
                return null;
            }

            players.add(player);
        }

        return players;
    }

    /**
     * Cancels a draft and tells everyone who was part of it why.
     */
    private void cancelDraft(MatchDraft draft, String reason) {
        draftsByChallengerUuid.remove(draft.challengerUuid);

        draftChallengerByInviteeUuid.values()
                .removeIf(draft.challengerUuid::equals);

        for (String uuid : draft.allPickedUuids()) {
            Player member = onlinePlayerByUuid(uuid);

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
            Player player = onlinePlayerByUuid(uuid);

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

    // --- spectating ---

    /**
     * On a match worker /view returns a spectator to the lobby (and refuses
     * the participants). On the hub it opens the menu of matches to spectate.
     */
    private void handleViewCommand(Player player) {
        if (player == null) {
            return;
        }

        if (worker.isActive()) {
            if (worker.isParticipant(player.uuid())) {
                player.sendMessage(
                        "[scarlet]You are in this match; you cannot leave it with /v.[]"
                );
                return;
            }

            worker.returnSpectatorToHub(player);
            return;
        }

        openViewMenu(player);
    }

    private void openViewMenu(Player player) {
        if (!duelManager.isConfigured()) {
            player.sendMessage(
                    "[scarlet]The match server is not set up yet. Ask an admin.[]"
            );
            return;
        }

        List<DuelServerManager.ActiveDuel> duels = duelManager.activeDuels();

        if (duels.isEmpty()) {
            player.sendMessage("[scarlet]No matches are in progress.[]");
            return;
        }

        List<Integer> targetPorts = new ArrayList<>();
        List<String[]> rows = new ArrayList<>();

        for (DuelServerManager.ActiveDuel duel : duels) {
            targetPorts.add(duel.port());
            rows.add(new String[]{duel.label()});
        }

        rows.add(new String[]{"[red]Cancel"});
        viewTargetsByViewerUuid.put(player.uuid(), targetPorts);

        Call.menu(
                player.con,
                viewMenuId,
                "[accent]Spectate a match",
                "Select a match to watch. Use /v again to return to the lobby.",
                rows.toArray(new String[0][])
        );
    }

    private void handleViewSelection(Player player, int option) {
        if (player == null) {
            return;
        }

        List<Integer> targetPorts =
                viewTargetsByViewerUuid.remove(player.uuid());

        if (
                targetPorts == null
                        || option < 0
                        || option >= targetPorts.size()
        ) {
            return;
        }

        if (!duelManager.viewDuel(player, targetPorts.get(option))) {
            player.sendMessage(
                    "[scarlet]That match is no longer available.[]"
            );
        }
    }

    /**
     * Restarts the current match with a fresh map. Only on a worker, only for
     * an admin or commentator, and only for a spectator (never a participant).
     */
    private void handleRestartCommand(Player player) {
        if (player == null) {
            return;
        }

        if (!worker.isActive()) {
            player.sendMessage(
                    "[scarlet]/restart can only be used on a match server.[]"
            );
            return;
        }

        if (!rankManager.canRestartMatches(player)) {
            player.sendMessage(
                    "[scarlet]Only commentators and admins can restart a match.[]"
            );
            return;
        }

        if (worker.isParticipant(player.uuid())) {
            player.sendMessage(
                    "[scarlet]You can't restart a match you are playing in.[]"
            );
            return;
        }

        restartMatch.run();
    }

    /**
     * True while this player has a challenge or invite to answer, sent a
     * challenge that awaits an answer, or is part of a draft whose invites
     * are out. Such a player cannot be targeted by new challenges or invites;
     * with the response timeout this resolves within a minute at worst.
     */
    private boolean isBusyWithMatchmaking(String uuid) {
        if (
                challengeByOpponentUuid.containsKey(uuid)
                        || draftChallengerByInviteeUuid.containsKey(uuid)
        ) {
            return true;
        }

        for (PendingChallenge pending : challengeByOpponentUuid.values()) {
            if (pending.challengerUuid().equals(uuid)) {
                return true;
            }
        }

        for (MatchDraft draft : draftsByChallengerUuid.values()) {
            if (draft.inviting && draft.involves(uuid)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Expires an unanswered 1v1 challenge. The serial check makes sure a
     * newer challenge against the same opponent is left alone.
     */
    private void expireChallenge(String opponentUuid, int serial) {
        PendingChallenge pending = challengeByOpponentUuid.get(opponentUuid);

        if (pending == null || pending.serial() != serial) {
            return;
        }

        challengeByOpponentUuid.remove(opponentUuid);

        Player opponent = onlinePlayerByUuid(opponentUuid);
        Player challenger = onlinePlayerByUuid(pending.challengerUuid());

        if (challenger != null) {
            challenger.sendMessage(
                    "[scarlet]"
                            + (opponent == null
                            ? "Your opponent"
                            : PlayerNameFormatter.displayName(opponent))
                            + "[scarlet] did not answer your 1v1 challenge in time.[]"
            );
        }

        if (opponent != null) {
            opponent.sendMessage(
                    "[lightgray]The 1v1 challenge against you expired.[]"
            );
        }
    }

    /**
     * Expires a draft whose invites were not all answered in time, so its
     * members are never stuck waiting forever.
     */
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

    private List<Player> otherOnlinePlayers(Player self) {
        List<Player> players = new ArrayList<>();

        Groups.player.each(player -> {
            if (player != null && player != self) {
                players.add(player);
            }
        });

        players.sort(
                Comparator.comparing(
                        Player::plainName,
                        String.CASE_INSENSITIVE_ORDER
                )
        );

        return players;
    }

    private Player onlinePlayerByUuid(String uuid) {
        return Groups.player.find(
                player -> player != null && player.uuid().equals(uuid)
        );
    }

    /**
     * A Teams/FFA match being assembled by one challenger: first the pick
     * phase (menus adding players to the rosters), then the invite phase
     * (waiting for every picked player to accept).
     */
    private static final class MatchDraft {
        final MatchMode mode;
        final String challengerUuid;

        /**
         * Random Teams only: how many teams the accepted pool is shuffled
         * into. 0 for every other mode.
         */
        final int teamCount;

        /**
         * The rosters being built. Teams mode grows this list one team at a
         * time via the Next team button (the challenger starts on Team 1, up
         * to {@link #MAX_TEAMS} teams); FFA keeps everyone in the single
         * first list. Picks always go into the last team.
         */
        final List<List<String>> teams = new ArrayList<>();

        boolean inviting = false;

        /**
         * Serial of the invite round sent for this draft, so the expiry task
         * only cancels the round it was armed for.
         */
        int inviteSerial = 0;

        /**
         * Candidates of the most recently shown pick menu, so the selected
         * option index resolves against exactly what the challenger saw.
         */
        List<String> candidateUuids = new ArrayList<>();
        final Set<String> pendingInviteeUuids = new HashSet<>();

        MatchDraft(MatchMode mode, String challengerUuid, int teamCount) {
            this.mode = mode;
            this.challengerUuid = challengerUuid;
            this.teamCount = teamCount;

            List<String> firstTeam = new ArrayList<>();
            firstTeam.add(challengerUuid);
            teams.add(firstTeam);
        }

        List<String> currentRoster() {
            return teams.get(teams.size() - 1);
        }

        List<String> allPickedUuids() {
            List<String> all = new ArrayList<>();

            for (List<String> team : teams) {
                all.addAll(team);
            }

            return all;
        }

        boolean involves(String uuid) {
            for (List<String> team : teams) {
                if (team.contains(uuid)) {
                    return true;
                }
            }

            return false;
        }
    }

    /**
     * An outstanding 1v1 challenge: who sent it, and the serial its expiry
     * task was armed with.
     */
    private record PendingChallenge(String challengerUuid, int serial) {
    }
}
