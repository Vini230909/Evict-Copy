// Per-account Pure map vetoes, persisted outside the player database, and the /maps menu.
package Extinction;

import Extinction.core.io.PropertiesFile;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import mindustry.gen.Call;
import mindustry.gen.Player;
import mindustry.ui.Menus;

public final class MapVetoes {
    private static final File FILE = new File("config/evict-map-vetoes.properties");
    private final Map<String, List<PureMaps.PureMap>> shownByUuid = new HashMap<>();
    private final int menuId = Menus.registerMenu(this::handleSelection);
    private Properties accounts;

    // Loaded only when used on the hub; creating matchmaking on a worker never reads this file.
    private Set<String> vetoes(String uuid) {
        if (accounts == null) {
            accounts = PropertiesFile.load(FILE);
        }
        Set<String> names = new LinkedHashSet<>();
        String saved = accounts.getProperty(uuid, "");
        if (!saved.isEmpty()) {
            names.addAll(List.of(saved.split(",")));
        }
        return names;
    }

    Set<String> combined(List<String> uuids) {
        Set<String> names = new LinkedHashSet<>();
        for (String uuid : uuids) {
            names.addAll(vetoes(uuid));
        }
        return names;
    }

    boolean blocks(List<String> uuids, String map) {
        return combined(uuids).contains(map);
    }

    static List<PureMaps.PureMap> ordered(MatchMode mode, Set<String> vetoes) {
        List<PureMaps.PureMap> maps = PureMaps.forMode(mode);
        maps.sort(Comparator.comparing((PureMaps.PureMap map) -> vetoes.contains(map.name()))
                .thenComparing(PureMaps.PureMap::name, String.CASE_INSENSITIVE_ORDER));
        return maps;
    }

    static String label(PureMaps.PureMap map, boolean vetoed) {
        return (vetoed ? "[scarlet]" : "") + map.name().replace("[", "[[")
                + " (" + map.teams() + " teams)[]";
    }

    public void openMenu(Player player) {
        if ("true".equals(System.getProperty("evict.duelWorker"))) {
            player.sendMessage("[scarlet]/maps is only available on the hub, not on a match server.[]");
            return;
        }
        Set<String> vetoes = vetoes(player.uuid());
        List<PureMaps.PureMap> maps = ordered(MatchMode.PURE_TRAINING, vetoes);
        List<String[]> rows = new ArrayList<>();
        for (PureMaps.PureMap map : maps) {
            rows.add(new String[]{label(map, vetoes.contains(map.name()))});
        }
        rows.add(new String[]{"[lightgray]Close"});
        shownByUuid.put(player.uuid(), maps);
        Call.menu(player.con, menuId, "[accent]Your Pure maps",
                maps.isEmpty() ? "No Pure maps are available in config/maps."
                        : "Tap a map to toggle it. Scarlet maps are vetoed in every Pure match you join.",
                rows.toArray(new String[0][]));
    }

    private void handleSelection(Player player, int option) {
        if (player == null) {
            return;
        }
        List<PureMaps.PureMap> maps = shownByUuid.remove(player.uuid());
        if (maps == null || option < 0 || option >= maps.size()) {
            return;
        }
        Set<String> names = vetoes(player.uuid());
        String name = maps.get(option).name();
        if (!names.remove(name)) {
            names.add(name);
        }
        Properties updated = new Properties();
        updated.putAll(accounts);
        if (names.isEmpty()) {
            updated.remove(player.uuid());
        } else {
            updated.setProperty(player.uuid(), String.join(",", names));
        }
        if (PropertiesFile.save(FILE, updated, "Pure map vetoes: UUID -> comma-separated map names")) {
            accounts = updated;
        } else {
            player.sendMessage("[scarlet]Could not save your map vetoes. Please try again.[]");
        }
        openMenu(player);
    }

    void handlePlayerLeave(Player player) {
        shownByUuid.remove(player.uuid());
    }
}
