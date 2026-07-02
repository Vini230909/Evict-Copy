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
 * /history (alias /h): first pick a player, then page through that player's
 * 1v1, Teams and FFA matches.
 * A 1v1 entry is win/lose from the picked player's perspective, both names,
 * and an elo line (ELO is not computed yet, so the line shows a zero delta).
 * Teams and FFA entries list everyone with just win/lose below - they are
 * unranked. Training and Sandbox sessions leave no history.
 */
final class HistoryCommands {

    private static final int ENTRIES_PER_PAGE = 10;
    private static final int PICKER_MENU_COLUMNS = 2;

    // Pagination button option indices (row-major across the button grid).
    private static final int OPTION_FIRST = 0;
    private static final int OPTION_PREVIOUS = 1;
    private static final int OPTION_PAGE = 2;
    private static final int OPTION_NEXT = 3;
    private static final int OPTION_LAST = 4;
    private static final int OPTION_CLOSE = 5;

    private final PlayerDataManager playerDataManager;
    private final int pickerMenuId;
    private final int historyMenuId;

    /**
     * Viewer UUID -> ordered player UUIDs shown in their picker.
     */
    private final Map<String, List<String>> pickerTargetsByViewerUuid =
            new HashMap<>();

    /**
     * Viewer UUID -> their open history view (subject + cached list + page).
     */
    private final Map<String, HistoryView> viewsByViewerUuid = new HashMap<>();

    HistoryCommands(PlayerDataManager playerDataManager) {
        this.playerDataManager = playerDataManager;
        this.pickerMenuId = Menus.registerMenu(this::handlePicker);
        this.historyMenuId = Menus.registerMenu(this::handleMenu);
    }

    void registerClientCommands(CommandHandler handler) {
        handler.<Player>register(
                "history",
                "Pick a player and view their 1v1, Teams and FFA match history.",
                (args, player) -> openPicker(player)
        );

        handler.<Player>register(
                "h",
                "Alias for /history.",
                (args, player) -> openPicker(player)
        );
    }

    void handlePlayerLeave(Player player) {
        if (player != null) {
            pickerTargetsByViewerUuid.remove(player.uuid());
            viewsByViewerUuid.remove(player.uuid());
        }
    }

    private void openPicker(Player player) {
        if (player == null) {
            return;
        }

        List<Player> players = onlinePlayers();

        if (players.isEmpty()) {
            player.sendMessage("[scarlet]No players are online.[]");
            return;
        }

        List<String> targetUuids = new ArrayList<>();
        List<String[]> rows = new ArrayList<>();
        List<String> currentRow = new ArrayList<>();

        for (Player target : players) {
            targetUuids.add(target.uuid());
            currentRow.add(PlayerNameFormatter.displayName(target));

            if (currentRow.size() == PICKER_MENU_COLUMNS) {
                rows.add(currentRow.toArray(new String[0]));
                currentRow.clear();
            }
        }

        if (!currentRow.isEmpty()) {
            rows.add(currentRow.toArray(new String[0]));
        }

        rows.add(new String[]{"[red]Cancel"});
        pickerTargetsByViewerUuid.put(player.uuid(), targetUuids);

        Call.menu(
                player.con,
                pickerMenuId,
                "[orange]History",
                "Select a player to view their match history.",
                rows.toArray(new String[0][])
        );
    }

    private void handlePicker(Player player, int option) {
        if (player == null) {
            return;
        }

        List<String> targetUuids =
                pickerTargetsByViewerUuid.remove(player.uuid());

        if (
                targetUuids == null
                        || option < 0
                        || option >= targetUuids.size()
        ) {
            return;
        }

        String subjectUuid = targetUuids.get(option);
        Player subject = Groups.player.find(
                online -> online != null && online.uuid().equals(subjectUuid)
        );
        String subjectName = subject != null
                ? PlayerNameFormatter.displayName(subject)
                : subjectUuid;

        playerDataManager.findDuelHistory(subjectUuid, matches -> {
            HistoryView view = new HistoryView(subjectUuid, subjectName, matches);
            viewsByViewerUuid.put(player.uuid(), view);
            showPage(player, view);
        });
    }

    private void handleMenu(Player player, int option) {
        if (player == null) {
            return;
        }

        HistoryView view = viewsByViewerUuid.get(player.uuid());

        if (view == null) {
            return;
        }

        int pages = pageCount(view.matches.size());

        switch (option) {
            case OPTION_FIRST -> view.page = 0;
            case OPTION_PREVIOUS -> view.page = Math.max(0, view.page - 1);
            case OPTION_NEXT -> view.page = Math.min(pages - 1, view.page + 1);
            case OPTION_LAST -> view.page = pages - 1;
            case OPTION_PAGE -> {
                // Re-show the current page.
            }
            case OPTION_CLOSE -> {
                viewsByViewerUuid.remove(player.uuid());
                return;
            }
            default -> {
                return;
            }
        }

        showPage(player, view);
    }

    private void showPage(Player player, HistoryView view) {
        List<PlayerDataManager.DuelMatch> matches = view.matches;
        int pages = pageCount(matches.size());
        view.page = Math.max(0, Math.min(view.page, pages - 1));

        int start = view.page * ENTRIES_PER_PAGE;
        int end = Math.min(start + ENTRIES_PER_PAGE, matches.size());

        StringBuilder message = new StringBuilder();

        if (matches.isEmpty()) {
            message.append("[gray]No matches yet.[]");
        }

        for (int index = start; index < end; index++) {
            if (index > start) {
                message.append("\n\n");
            }

            message.append(formatMatch(view.subjectUuid, matches.get(index)));
        }

        String[][] buttons = {
                {
                        "[gray]<<[]",
                        "[gray]<[]",
                        "[lightgray]" + (view.page + 1) + "[gray]/[lightgray]" + pages,
                        "[gray]>[]",
                        "[gray]>>[]"
                },
                {"Close"}
        };

        Call.menu(
                player.con,
                historyMenuId,
                "[orange]History: []" + view.subjectName,
                message.toString(),
                buttons
        );
    }

    private String formatMatch(
            String subjectUuid,
            PlayerDataManager.DuelMatch match
    ) {
        // An FFA entry lists every participant with just win/lose below it -
        // FFAs are unranked, so there is no elo line.
        if (MatchMode.FFA.id().equals(match.mode())) {
            String participants = String.join(
                    " [white]vs[] ",
                    match.participantNamesPacked().split("\n")
            );

            return "[lightgray]FFA[]\n" + participants + "\n"
                    + winLose(subjectUuid.equals(match.winnerUuid()));
        }

        // A Teams entry shows both rosters (winners listed first); the uuid
        // columns pack the whole roster, so membership decides win/lose.
        if (MatchMode.TEAMS.id().equals(match.mode())) {
            return "[lightgray]Teams[]\n"
                    + match.winnerName() + " [white]vs[] " + match.loserName()
                    + "\n"
                    + winLose(packedContainsUuid(match.winnerUuid(), subjectUuid));
        }

        boolean won = subjectUuid.equals(match.winnerUuid());
        String subject = won ? match.winnerName() : match.loserName();
        String opponent = won ? match.loserName() : match.winnerName();
        String eloDelta = won ? "[green]+0[]" : "[scarlet]-0[]";

        return "[lightgray]1v1[]\n"
                + winLose(won) + "\n" + subject + " [white]vs[] " + opponent
                + "\n[gray]elo: []" + eloDelta;
    }

    private static String winLose(boolean won) {
        return won ? "[green]win[]" : "[scarlet]lose[]";
    }

    private static boolean packedContainsUuid(String packed, String uuid) {
        if (packed == null || packed.isBlank()) {
            return false;
        }

        for (String entry : packed.split(",")) {
            if (entry.trim().equals(uuid)) {
                return true;
            }
        }

        return false;
    }

    private List<Player> onlinePlayers() {
        List<Player> players = new ArrayList<>();

        Groups.player.each(player -> {
            if (player != null) {
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

    private static int pageCount(int matchCount) {
        return Math.max(1, (matchCount + ENTRIES_PER_PAGE - 1) / ENTRIES_PER_PAGE);
    }

    private static final class HistoryView {
        final String subjectUuid;
        final String subjectName;
        final List<PlayerDataManager.DuelMatch> matches;
        int page;

        HistoryView(
                String subjectUuid,
                String subjectName,
                List<PlayerDataManager.DuelMatch> matches
        ) {
            this.subjectUuid = subjectUuid;
            this.subjectName = subjectName;
            this.matches = matches;
        }
    }
}
