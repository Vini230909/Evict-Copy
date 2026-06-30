package vini.evictmap;

import arc.util.CommandHandler;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.ui.Menus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * /play (alias /p) 1v1 duel challenges.
 * A challenger picks an online opponent from a menu. The opponent receives an
 * accept/decline menu. On accept both players are redirected to the configured
 * dedicated 1v1 server instance with {@link Call#connect}.
 * This hub server only sends the two players to the duel instance. Map/mode
 * selection and the match itself live on that separate instance, because a
 * single Mindustry server process can only host one game at a time.
 * The duel target (ip/port) is a persistent server setting, so nothing is
 * hard-coded and the worker instance can be pointed at whenever it is ready.
 */
final class DuelCommands {

    private static final int SELECTION_MENU_COLUMNS = 2;
    private static final int ACCEPT_OPTION = 0;

    private final DuelServerManager duelManager;
    private final DuelWorker worker;
    private final RankManager rankManager;
    private final Runnable restartMatch;

    private final int selectionMenuId;
    private final int challengeMenuId;
    private final int viewMenuId;

    /**
     * Challenger UUID -> ordered opponent UUIDs shown in their menu.
     */
    private final Map<String, List<String>> selectionTargetsByChallengerUuid =
            new HashMap<>();

    /**
     * Opponent UUID -> challenger UUID for an outstanding challenge.
     */
    private final Map<String, String> challengerByOpponentUuid =
            new HashMap<>();

    /**
     * Viewer UUID -> ordered duel ports shown in their /view menu.
     */
    private final Map<String, List<Integer>> viewTargetsByViewerUuid =
            new HashMap<>();

    DuelCommands(
            DuelServerManager duelManager,
            DuelWorker worker,
            RankManager rankManager,
            Runnable restartMatch
    ) {
        this.duelManager = duelManager;
        this.worker = worker;
        this.rankManager = rankManager;
        this.restartMatch = restartMatch;
        this.selectionMenuId = Menus.registerMenu(this::handleSelection);
        this.challengeMenuId = Menus.registerMenu(this::handleChallengeResponse);
        this.viewMenuId = Menus.registerMenu(this::handleViewSelection);
    }

    void registerClientCommands(CommandHandler handler) {
        handler.<Player>register(
                "play",
                "Challenge an online player to a 1v1 on the duel server.",
                (args, player) -> openSelectionMenu(player)
        );

        handler.<Player>register(
                "p",
                "Alias for /play.",
                (args, player) -> openSelectionMenu(player)
        );

        handler.<Player>register(
                "view",
                "Spectate an ongoing 1v1, or return to the lobby if spectating.",
                (args, player) -> handleViewCommand(player)
        );

        handler.<Player>register(
                "v",
                "Alias for /view.",
                (args, player) -> handleViewCommand(player)
        );

        handler.<Player>register(
                "restart",
                "Commentator/admin: restart the 1v1 you are spectating with a fresh map.",
                (args, player) -> handleRestartCommand(player)
        );
    }

    /**
     * Drops any selection menu or outstanding challenge that involves a player
     * who just left, so stale UUIDs never accumulate or resolve to a ghost.
     */
    void handlePlayerLeave(Player player) {
        if (player == null) {
            return;
        }

        String uuid = player.uuid();

        selectionTargetsByChallengerUuid.remove(uuid);
        challengerByOpponentUuid.remove(uuid);
        challengerByOpponentUuid.values().removeIf(uuid::equals);
        viewTargetsByViewerUuid.remove(uuid);
    }

    private void openSelectionMenu(Player player) {
        if (player == null) {
            return;
        }

        if (!duelManager.isConfigured()) {
            player.sendMessage(
                    "[scarlet]The 1v1 server is not set up yet. Ask an admin.[]"
            );
            return;
        }

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

        challengerByOpponentUuid.put(opponent.uuid(), player.uuid());

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

        String challengerUuid =
                challengerByOpponentUuid.remove(opponent.uuid());

        if (challengerUuid == null) {
            return;
        }

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
        if (!duelManager.requestDuel(challenger, opponent)) {
            challenger.sendMessage(
                    "[scarlet]All 1v1 servers are busy right now. Try again shortly.[]"
            );
            opponent.sendMessage(
                    "[scarlet]All 1v1 servers are busy right now. Try again shortly.[]"
            );
        }
    }

    /**
     * On a duel worker /view returns a spectator to the lobby (and refuses the
     * two duelists). On the hub it opens the menu of matches to spectate.
     */
    private void handleViewCommand(Player player) {
        if (player == null) {
            return;
        }

        if (worker.isActive()) {
            if (worker.isParticipant(player.uuid())) {
                player.sendMessage(
                        "[scarlet]You are in this 1v1; you cannot leave it with /v.[]"
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
                    "[scarlet]The 1v1 server is not set up yet. Ask an admin.[]"
            );
            return;
        }

        List<DuelServerManager.ActiveDuel> duels = duelManager.activeDuels();

        if (duels.isEmpty()) {
            player.sendMessage("[scarlet]No 1v1 matches are in progress.[]");
            return;
        }

        List<Integer> targetPorts = new ArrayList<>();
        List<String[]> rows = new ArrayList<>();

        for (DuelServerManager.ActiveDuel duel : duels) {
            targetPorts.add(duel.port());
            rows.add(new String[]{
                    duel.player1Display() + " [white]vs[] " + duel.player2Display()
            });
        }

        rows.add(new String[]{"[red]Cancel"});
        viewTargetsByViewerUuid.put(player.uuid(), targetPorts);

        Call.menu(
                player.con,
                viewMenuId,
                "[accent]Spectate a 1v1",
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
     * Restarts the current duel with a fresh map. Only on a worker, only for an
     * admin or commentator, and only for a spectator (never one of the players).
     */
    private void handleRestartCommand(Player player) {
        if (player == null) {
            return;
        }

        if (!worker.isActive()) {
            player.sendMessage(
                    "[scarlet]/restart can only be used on a 1v1 server.[]"
            );
            return;
        }

        if (!rankManager.canRestartMatches(player)) {
            player.sendMessage(
                    "[scarlet]Only commentators and admins can restart a 1v1.[]"
            );
            return;
        }

        if (worker.isParticipant(player.uuid())) {
            player.sendMessage(
                    "[scarlet]You can't restart a 1v1 you are playing in.[]"
            );
            return;
        }

        restartMatch.run();
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
}
