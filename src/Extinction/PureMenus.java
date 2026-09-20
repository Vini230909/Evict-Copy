// The Pure /play flow: Training, 2 Team PvP, 4 Team PvP or 1v1 PvP, then a scrollable map picker.
package Extinction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

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
        for (MapSelection selection : new ArrayList<>(selectionByUuid.values())) {
            if (selection.uuids().contains(player.uuid())) {
                selectionByUuid.remove(selection.uuids().get(0));
                selection.cancelled().run();
            }
        }
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

        MatchMode mode = MODE_MENU_OPTIONS[option];
        switch (mode) {
            case PURE_TRAINING -> openMapMenu(player, mode, List.of(player.uuid()),
                    map -> matchmaking.startSoloMatch(player, mode, map), () -> {});
            case PURE_1V1 -> matchmaking.challenges.openSelectionMenu(player, mode);
            default -> roster.begin(player, mode);
        }
    }

    // One map per row in the client's scrollable menu; only maps with the mode's team count.
    void openMapMenu(Player player, MatchMode mode, List<String> uuids,
                     Consumer<String> selected, Runnable cancelled) {
        Set<String> vetoes = matchmaking.mapVetoes.combined(uuids);
        List<PureMaps.PureMap> maps = MapVetoes.ordered(mode, vetoes);
        boolean available = maps.stream().anyMatch(map -> !vetoes.contains(map.name()));

        List<String> shown = new ArrayList<>();
        List<String[]> rows = new ArrayList<>();

        for (PureMaps.PureMap map : maps) {
            shown.add(map.name());
            rows.add(new String[]{MapVetoes.label(map, vetoes.contains(map.name()))});
        }

        rows.add(new String[]{available ? "[red]Cancel" : "[lightgray]Close"});
        selectionByUuid.remove(player.uuid());
        if (available) {
            selectionByUuid.put(player.uuid(), new MapSelection(mode, shown, List.copyOf(uuids),
                    vetoes, selected, cancelled));
        } else {
            cancelled.run();
        }

        Call.menu(
                player.con,
                mapMenuId,
                "[accent]" + mode.label(),
                available ? "Pick the map. Scarlet maps are vetoed by a player and cannot be selected."
                        : "[scarlet]No map is available " + (maps.isEmpty() ? "for this mode" : "after player vetoes")
                                + ". Match cancelled.[]",
                rows.toArray(new String[0][])
        );
    }

    private void handleMapSelection(Player player, int option) {
        if (player == null) {
            return;
        }

        MapSelection selection = selectionByUuid.remove(player.uuid());

        if (selection == null) {
            return;
        }

        int mapCount = selection.shown().size();

        if (option >= 0 && option < mapCount) {
            String map = selection.shown().get(option);

            if (selection.uuids().stream().anyMatch(uuid -> Matchmaking.onlinePlayerByUuid(uuid) == null)) {
                selection.cancelled().run();
                player.sendMessage("[scarlet]A picked player left. Match setup cancelled.[]");
                return;
            }

            if (selection.vetoes().contains(map) || matchmaking.mapVetoes.blocks(selection.uuids(), map)) {
                player.sendMessage("[scarlet]That map is vetoed. Pick an available map.[]");
                openMapMenu(player, selection.mode(), selection.uuids(), selection.selected(), selection.cancelled());
                return;
            }

            if (!PureMaps.exists(map)) {
                player.sendMessage("[scarlet]That map is no longer available.[]");
                selection.cancelled().run();
                return;
            }

            selection.selected().accept(map);
            return;
        }

        selection.cancelled().run();
        player.sendMessage("[lightgray]Match setup cancelled.[]");
    }

    // One open map picker: the mode and the map names shown in row order.
    private record MapSelection(MatchMode mode, List<String> shown, List<String> uuids,
                                Set<String> vetoes, Consumer<String> selected, Runnable cancelled) {
    }
}
