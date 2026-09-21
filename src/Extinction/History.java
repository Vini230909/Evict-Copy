// /history: pick a player, then page through their Ranked, 1v1, Teams and FFA matches.
package Extinction;

import Extinction.data.PlayerDataManager;

import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.ui.Menus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class History {

    private static final int ENTRIES_PER_PAGE = 10;
    private static final int PICKER_MENU_COLUMNS = 2;

    // FFA has no participant cap; past this many names an entry folds the rest into "+N more".
    private static final int MAX_PARTICIPANT_NAMES = 4;

    // Pagination button option indices (row-major across the button grid).
    private static final int OPTION_FIRST = 0;
    private static final int OPTION_PREVIOUS = 1;
    private static final int OPTION_PAGE = 2;
    private static final int OPTION_NEXT = 3;
    private static final int OPTION_LAST = 4;
    private static final int OPTION_CLOSE = 5;

    // One viewer's open history: whose it is, the cached list and the page they are on.
    private static final class View {
        final String subjectUuid;
        final String subjectName;
        final List<PlayerDataManager.DuelMatch> matches;
        int page;

        View(String subjectUuid, String subjectName, List<PlayerDataManager.DuelMatch> matches) {
            this.subjectUuid = subjectUuid;
            this.subjectName = subjectName;
            this.matches = matches;
        }
    }

    private final PlayerDataManager playerData;
    private final int pickerMenuId;
    private final int historyMenuId;

    // Viewer UUID -> ordered player UUIDs shown in their picker.
    private final Map<String, List<String>> pickerTargetsByViewerUuid = new HashMap<>();

    // Viewer UUID -> their open history view.
    private final Map<String, View> viewsByViewerUuid = new HashMap<>();

    public History(PlayerDataManager playerData) {
        this.playerData = playerData;
        this.pickerMenuId = Menus.registerMenu(this::handlePicker);
        this.historyMenuId = Menus.registerMenu(this::handleMenu);
    }

    public void handlePlayerLeave(Player player) {
        if (player != null) {
            pickerTargetsByViewerUuid.remove(player.uuid());
            viewsByViewerUuid.remove(player.uuid());
        }
    }

    // /history: a picker of the online players, two per row, Cancel at the bottom.
    public void openPicker(Player player) {
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
            currentRow.add(PlayerNames.displayName(target));

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

        List<String> targetUuids = pickerTargetsByViewerUuid.remove(player.uuid());

        if (targetUuids == null || option < 0 || option >= targetUuids.size()) {
            return;
        }

        String subjectUuid = targetUuids.get(option);
        Player subject = Groups.player.find(online -> online != null && online.uuid().equals(subjectUuid));
        String subjectName = subject != null ? PlayerNames.displayName(subject) : subjectUuid;

        playerData.findDuelHistory(subjectUuid, matches -> {
            View view = new View(subjectUuid, subjectName, matches);
            viewsByViewerUuid.put(player.uuid(), view);
            showPage(player, view);
        });
    }

    private void handleMenu(Player player, int option) {
        if (player == null) {
            return;
        }

        View view = viewsByViewerUuid.get(player.uuid());

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

    private void showPage(Player player, View view) {
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

    // Ranked shows the ELO swing; casual 1v1, Teams and FFA are unranked, so just win/lose.
    private String formatMatch(String subjectUuid, PlayerDataManager.DuelMatch match) {
        if (MatchMode.FFA.id().equals(match.mode())) {
            String participants = PlayerNames.joinShortened(
                    List.of(match.participantNamesPacked().split("\n")),
                    " [white]vs[] ",
                    MAX_PARTICIPANT_NAMES
            );

            return "[lightgray]FFA[]\n" + participants + "\n"
                    + winLose(subjectUuid.equals(match.winnerUuid()));
        }

        // Teams packs whole rosters in the uuid columns (winners first), so membership decides win/lose.
        if (MatchMode.TEAMS.id().equals(match.mode())) {
            return "[lightgray]Teams[]\n"
                    + match.winnerName() + " [white]vs[] " + match.loserName()
                    + "\n"
                    + winLose(packedContainsUuid(match.winnerUuid(), subjectUuid));
        }

        boolean won = subjectUuid.equals(match.winnerUuid());
        String subject = won ? match.winnerName() : match.loserName();
        String opponent = won ? match.loserName() : match.winnerName();

        if (MatchMode.RANKED.id().equals(match.mode())) {
            int eloDelta = won
                    ? match.winnerEloAfter() - match.winnerEloBefore()
                    : match.loserEloAfter() - match.loserEloBefore();

            return "[lightgray]1v1[]\n"
                    + winLose(won) + "\n" + subject + " [white]vs[] " + opponent
                    + "\n[gray]elo: []" + formatEloDelta(eloDelta);
        }

        return "[lightgray]Unranked[]\n"
                + winLose(won) + "\n" + subject + " [white]vs[] " + opponent;
    }

    private static String winLose(boolean won) {
        return won ? "[green]win[]" : "[scarlet]lose[]";
    }

    // Signed, coloured swing; gray zero for old 1v1 rows recorded before ELO was tracked.
    private static String formatEloDelta(int delta) {
        if (delta > 0) {
            return "[green]+" + delta + "[]";
        }

        if (delta < 0) {
            return "[scarlet]" + delta + "[]";
        }

        return "[gray]0[]";
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

    private static List<Player> onlinePlayers() {
        List<Player> players = new ArrayList<>();

        Groups.player.each(player -> {
            if (player != null) {
                players.add(player);
            }
        });

        players.sort(Comparator.comparing(Player::plainName, String.CASE_INSENSITIVE_ORDER));
        return players;
    }

    private static int pageCount(int matchCount) {
        return Math.max(1, (matchCount + ENTRIES_PER_PAGE - 1) / ENTRIES_PER_PAGE);
    }
}
