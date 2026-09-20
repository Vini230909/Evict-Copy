// The Pure maps: every .msav in config/maps except the Extinction map, with its number of core teams.
package Extinction;

import Extinction.core.util.PluginLog;

import java.util.ArrayList;
import java.util.List;

import arc.graphics.Pixmap;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.io.MapIO;
import mindustry.maps.Map;

public final class PureMaps {

    // One playable map: its name (as /play shows it and the worker hosts it) and its core teams.
    public record PureMap(String name, int teams) {
    }

    private static final List<PureMap> MAPS = new ArrayList<>();

    private PureMaps() {
    }

    // Hub startup. Also pins the hub's next round to the Extinction map: the server's map shuffle
    // would otherwise rotate into a Pure map and generate the next round at that map's size.
    public static void load() {
        MAPS.clear();
        Map extinction = null;

        for (Map map : Vars.maps.customMaps()) {
            if (isExtinctionMap(map)) {
                extinction = map;
                continue;
            }

            int teams = countCoreTeams(map);

            if (teams > 0) {
                MAPS.add(new PureMap(map.plainName(), teams));
            }
        }

        if (extinction != null) {
            Map pinned = extinction;
            Vars.maps.setMapProvider((mode, previous) -> pinned);
        } else {
            PluginLog.warn("Pure maps: the Extinction map '@' is not in config/maps; the next round follows the server's map shuffle.", Config.duelWorkerMap);
        }

        PluginLog.info("Pure maps: @ playable (@).", MAPS.size(), summary());
    }

    // The map the hub plays and every Extinction worker hosts: never a Pure map.
    private static boolean isExtinctionMap(Map map) {
        return map.plainName().replace('_', ' ')
                .equalsIgnoreCase(Config.duelWorkerMap.replace('_', ' '));
    }

    // How many teams own at least one core (a team usually has several); derelict does not count.
    // Reading the tiles is what fills Map.teams; the server never does that on its own.
    private static int countCoreTeams(Map map) {
        try {
            Pixmap preview = MapIO.generatePreview(map);
            preview.dispose();
        } catch (Throwable error) {
            PluginLog.err("Pure maps: could not read '@' (@); it is left out.", map.plainName(), error.getMessage());
            return 0;
        }

        int[] teams = {0};
        map.teams.each(id -> {
            if (id != Team.derelict.id) {
                teams[0]++;
            }
        });

        return teams[0];
    }

    private static String summary() {
        StringBuilder text = new StringBuilder();

        for (PureMap map : MAPS) {
            if (!text.isEmpty()) {
                text.append(", ");
            }

            text.append(map.name()).append(": ").append(map.teams()).append(" teams");
        }

        return text.isEmpty() ? "none" : text.toString();
    }

    // The maps a mode may be played on: any for Training, else exactly the mode's team count.
    public static List<PureMap> forMode(MatchMode mode) {
        List<PureMap> maps = new ArrayList<>();

        for (PureMap map : MAPS) {
            if (mode.mapTeams() == 0 || map.teams() == mode.mapTeams()) {
                maps.add(map);
            }
        }

        return maps;
    }

    public static boolean exists(String name) {
        for (PureMap map : MAPS) {
            if (map.name().equals(name)) {
                return true;
            }
        }

        return false;
    }
}
