package vini.evictmap;

import arc.util.CommandHandler;
import mindustry.gen.Call;
import mindustry.gen.Player;
import mindustry.ui.Menus;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * /history (alias /h): a paginated menu of the player's own 1v1 matches.
 *
 * Every match is one entry: win/lose from the viewer's perspective, the
 * opponent's name, and an elo line. ELO is not computed yet, so the line shows
 * a zero delta for now. Because this server only runs ranked Evict 1v1s, the
 * map/mode line and the player/map/time filters from the reference design are
 * intentionally left out.
 */
final class HistoryCommands {

    private static final int ENTRIES_PER_PAGE = 10;

    // Pagination button option indices (row-major across the button grid).
    private static final int OPTION_FIRST = 0;
    private static final int OPTION_PREVIOUS = 1;
    private static final int OPTION_PAGE = 2;
    private static final int OPTION_NEXT = 3;
    private static final int OPTION_LAST = 4;
    private static final int OPTION_CLOSE = 5;

    private final PlayerDataManager playerDataManager;
    private final int historyMenuId;

    /** Viewer UUID -> their open history view (cached list + current page). */
    private final Map<String, HistoryView> viewsByViewerUuid = new HashMap<>();

    HistoryCommands(PlayerDataManager playerDataManager) {
        this.playerDataManager = playerDataManager;
        this.historyMenuId = Menus.registerMenu(this::handleMenu);
    }

    void registerClientCommands(CommandHandler handler) {
        handler.<Player>register(
            "history",
            "Show your 1v1 match history.",
            (args, player) -> openHistory(player)
        );

        handler.<Player>register(
            "h",
            "Alias for /history.",
            (args, player) -> openHistory(player)
        );
    }

    void handlePlayerLeave(Player player) {
        if (player != null) {
            viewsByViewerUuid.remove(player.uuid());
        }
    }

    private void openHistory(Player player) {
        if (player == null) {
            return;
        }

        String uuid = player.uuid();

        playerDataManager.findDuelHistory(uuid, matches -> {
            // Always open the menu, even when empty, instead of a chat line.
            HistoryView view = new HistoryView(matches);
            viewsByViewerUuid.put(uuid, view);
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
            message.append("[gray]You have no 1v1 matches yet.[]");
        }

        for (int index = start; index < end; index++) {
            if (index > start) {
                message.append("\n\n");
            }

            message.append(formatMatch(player.uuid(), matches.get(index)));
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
            "[orange]History",
            message.toString(),
            buttons
        );
    }

    private String formatMatch(
        String viewerUuid,
        PlayerDataManager.DuelMatch match
    ) {
        boolean won = viewerUuid.equals(match.winnerUuid());
        String you = won ? match.winnerName() : match.loserName();
        String opponent = won ? match.loserName() : match.winnerName();
        String result = won ? "[green]win[]" : "[scarlet]lose[]";
        String eloDelta = won ? "[green]+0[]" : "[scarlet]-0[]";

        return result + "\n" + you + " [white]vs[] " + opponent
            + "\n[gray]elo: []" + eloDelta;
    }

    private static int pageCount(int matchCount) {
        return Math.max(1, (matchCount + ENTRIES_PER_PAGE - 1) / ENTRIES_PER_PAGE);
    }

    private static final class HistoryView {
        final List<PlayerDataManager.DuelMatch> matches;
        int page;

        HistoryView(List<PlayerDataManager.DuelMatch> matches) {
            this.matches = matches;
        }
    }
}
