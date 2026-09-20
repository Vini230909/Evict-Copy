// The Pure /play flow: Training, 2 Team PvP, 4 Team PvP or 1v1 PvP, then a scrollable map picker.
package Extinction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import mindustry.gen.Call;
import mindustry.gen.Player;
import mindustry.ui.Menus;

public final class PureMenus {

    // Option indexes of the Pure mode menu (see openModeMenu's rows).
    private static final MatchMode[] MODE_MENU_OPTIONS = {
            MatchMode.PURE_TRAINING,
            MatchMode.PURE_2TEAM,
            MatchMode.PURE_4TEAM,
            MatchMode.PURE_1V1
    };

    private final Matchmaking matchmaking;
    private final PureRoster roster;

    private final int modeMenuId;
    private final int mapMenuId;

    // Player UUID -> the maps shown, so the clicked row resolves to the map shown.
    private final Map<String, MapSelection> selectionByUuid = new HashMap<>();

    public PureMenus(Matchmaking matchmaking) {
        this.matchmaking = matchmaking;
        this.roster = new PureRoster(matchmaking);
        this.modeMenuId = Menus.registerMenu(this::handleModeSelection);
        this.mapMenuId = Menus.registerMenu(this::handleMapSelection);
    }

    void handlePlayerLeave(Player player) {
        selectionByUuid.remove(player.uuid());
        roster.handlePlayerLeave(player);
    }

    void openModeMenu(Player player) {
        Call.menu(
                player.con,
                modeMenuId,
                "[accent]Pure",
                "Plain Mindustry PvP on a real map. Nothing is recorded.",
                new String[][]{
                        {"Training", "2 Team PvP"},
                        {"4 Team PvP", "1v1 PvP"},
                        {"[red]Cancel"}
                }
        );
    }

    private void handleModeSelection(Player player, int option) {
        if (player == null || option < 0 || option >= MODE_MENU_OPTIONS.length) {
            return;
        }

        openMapMenu(player, MODE_MENU_OPTIONS[option]);
    }

    // One map per row in the client's scrollable menu; only maps with the mode's team count.
    private void openMapMenu(Player player, MatchMode mode) {
        List<PureMaps.PureMap> maps = PureMaps.forMode(mode);

        if (maps.isEmpty()) {
            player.sendMessage(
                    mode.mapTeams() == 0
                            ? "[scarlet]No Pure map is in config/maps.[]"
                            : "[scarlet]No Pure map with " + mode.mapTeams() + " teams is in config/maps.[]"
            );
            return;
        }

        List<String> shown = new ArrayList<>();
        List<String[]> rows = new ArrayList<>();

        for (PureMaps.PureMap map : maps) {
            shown.add(map.name());
            rows.add(new String[]{map.name() + " [lightgray](" + map.teams() + " teams)[]"});
        }

        rows.add(new String[]{"[red]Cancel"});
        selectionByUuid.put(player.uuid(), new MapSelection(mode, shown));

        Call.menu(
                player.con,
                mapMenuId,
                "[accent]" + mode.label(),
                "Pick the map.",
                rows.toArray(new String[0][])
        );
    }

    private void handleMapSelection(Player player, int option) {
        if (player == null) {
            return;
        }

        MapSelection selection = selectionByUuid.remove(player.uuid());

        if (selection == null || option < 0) {
            return;
        }

        int mapCount = selection.shown().size();

        if (option < mapCount) {
            String map = selection.shown().get(option);

            if (!PureMaps.exists(map)) {
                player.sendMessage("[scarlet]That map is no longer available.[]");
                return;
            }

            start(player, selection.mode(), map);
            return;
        }

        if (option == mapCount) {
            player.sendMessage("[lightgray]Match setup cancelled.[]");
        }
    }

    // The map is chosen: Training starts alone, 1v1 picks an opponent, the team modes open the grid.
    private void start(Player player, MatchMode mode, String map) {
        switch (mode) {
            case PURE_TRAINING -> matchmaking.startSoloMatch(player, mode, map);
            case PURE_1V1 -> matchmaking.challenges.openSelectionMenu(player, mode, map);
            default -> roster.begin(player, mode, map);
        }
    }

    // One open map picker: the mode and the map names shown in row order.
    private record MapSelection(MatchMode mode, List<String> shown) {
    }
}
