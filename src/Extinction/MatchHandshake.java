// The hub -> worker handshake file (duel.properties): hub address, mode, label, rosters, names.
package Extinction;

import Extinction.core.io.PropertiesFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import arc.Core;

public final class MatchHandshake {

    public static final String FILE_NAME = "duel.properties";

    public final String hubIp;
    public final int hubPort;
    public final MatchMode mode;
    public final String label;

    // One inner list per match team; a uuid counts once, in the first team naming it.
    public final List<List<String>> rosterTeams;
    public final Set<String> participantUuids;

    // uuid -> display name, so the worker's waiting HUD can name a player not yet arrived.
    public final Map<String, String> names;

    private MatchHandshake(
            String hubIp,
            int hubPort,
            MatchMode mode,
            String label,
            List<List<String>> rosterTeams,
            Set<String> participantUuids,
            Map<String, String> names
    ) {
        this.hubIp = hubIp;
        this.hubPort = hubPort;
        this.mode = mode;
        this.label = label;
        this.rosterTeams = rosterTeams;
        this.participantUuids = participantUuids;
        this.names = names;
    }

    // Hub side, at spawn.
    public static void write(
            File workerDir,
            MatchSlot slot,
            List<List<String>> rosterUuids
    ) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("mode", slot.mode.id());
        // The label is repeated so sibling workers can list this match in their /s hop menu.
        properties.setProperty("label", slot.label);
        properties.setProperty(
                "team.count",
                Integer.toString(rosterUuids.size())
        );

        for (int index = 0; index < rosterUuids.size(); index++) {
            properties.setProperty(
                    "team." + (index + 1),
                    String.join(",", rosterUuids.get(index))
            );
        }

        for (MatchSlot.Participant participant : slot.participants) {
            properties.setProperty(
                    "name." + participant.uuid(),
                    participant.display()
            );
        }

        properties.setProperty("hub.ip", Config.duelServerIp);
        properties.setProperty("hub.port", Integer.toString(hubPort()));

        try (FileOutputStream output =
                     new FileOutputStream(new File(workerDir, FILE_NAME))) {
            properties.store(output, "Evict duel handshake");
        }
    }

    // The hub's own host port, so a worker can send players back. Same IP as the workers.
    private static int hubPort() {
        if (Core.settings == null) {
            return 6567;
        }

        return Core.settings.getInt("port", 6567);
    }

    // Worker side. A missing or unreadable file reads as empty (no participants).
    public static MatchHandshake read(File file) {
        Properties properties = PropertiesFile.load(file);

        String hubIp = properties.getProperty("hub.ip", "").trim();
        int hubPort = PropertiesFile.getInt(properties, "hub.port", 6567);
        MatchMode mode = MatchMode.fromId(properties.getProperty("mode", "1v1").trim());
        String label = properties.getProperty("label", "").trim();

        List<List<String>> rosterTeams = new ArrayList<>();
        Set<String> participantUuids = new LinkedHashSet<>();
        int teamCount = PropertiesFile.getInt(properties, "team.count", 0);

        for (int index = 1; index <= teamCount; index++) {
            List<String> roster = new ArrayList<>();

            for (String uuid : properties.getProperty("team." + index, "").split(",")) {
                String trimmed = uuid.trim();

                if (!trimmed.isEmpty() && participantUuids.add(trimmed)) {
                    roster.add(trimmed);
                }
            }

            if (!roster.isEmpty()) {
                rosterTeams.add(roster);
            }
        }

        // Handshake written by an older hub: two 1v1 duelists.
        if (rosterTeams.isEmpty()) {
            for (String key : new String[]{"player1.uuid", "player2.uuid"}) {
                String uuid = properties.getProperty(key, "").trim();

                if (!uuid.isEmpty() && participantUuids.add(uuid)) {
                    List<String> roster = new ArrayList<>();
                    roster.add(uuid);
                    rosterTeams.add(roster);
                }
            }
        }

        Map<String, String> names = new LinkedHashMap<>();

        for (String uuid : participantUuids) {
            String name = properties.getProperty("name." + uuid, "").trim();

            if (!name.isEmpty()) {
                names.put(uuid, name);
            }
        }

        return new MatchHandshake(hubIp, hubPort, mode, label, rosterTeams, participantUuids, names);
    }
}
