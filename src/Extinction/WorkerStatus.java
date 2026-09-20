// What this worker publishes for the hub (status.properties) and what it reads of its siblings.
package Extinction;

import Extinction.moderation.ban.BanRequest;
import Extinction.moderation.ban.WordFilterHit;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Supplier;

import arc.util.Log;
import mindustry.gen.Groups;

public final class WorkerStatus {

    private static final File STATUS_FILE = new File("status.properties");
    private static final String RESULT_FILE_NAME = "result.properties";

    // Worker folders sit next to each other as duel-<port>; this worker's cwd is its own folder.
    private static final String SIBLING_DIR_PREFIX = "duel-";

    // A sibling whose status file is older than this is dead and drops out of the hop menu.
    private static final long SIBLING_STATUS_FRESH_MILLIS = 10_000L;

    private final Referee referee;

    // Accounts banned here, cumulative and republished every write: banning is idempotent on the hub.
    private final Set<String> banRequestUuids = new LinkedHashSet<>();

    // Who banned each account and, for the word filter, what it saw - for the hub's ban log.
    private final Map<String, BanRequest> banRequestDetails = new LinkedHashMap<>();

    // Per-UUID playtime on this worker; the hub credits it, the worker never writes the database.
    private Supplier<Map<String, Long>> playtimeSource = Map::of;

    public WorkerStatus(Referee referee) {
        this.referee = referee;
    }

    public void setPlaytimeSource(Supplier<Map<String, Long>> source) {
        this.playtimeSource = source == null ? Map::of : source;
    }

    // Published at once, not on the next scheduled write: the hub should have the ban before
    // the account reconnects there.
    public void requestBan(BanRequest request) {
        if (request == null
                || request.isEmpty()
                || !banRequestUuids.add(request.uuid())) {
            return;
        }

        banRequestDetails.put(request.uuid(), request);
        write();
    }

    // Rewritten every second; the hub polls it once a second. Best-effort.
    public void write() {
        Properties properties = new Properties();
        properties.setProperty("state", referee.stateName());
        properties.setProperty(
                "elapsedSeconds",
                Long.toString(referee.matchElapsedSeconds())
        );

        StringBuilder players = new StringBuilder();

        Groups.player.each(player -> {
            if (player != null) {
                if (!players.isEmpty()) {
                    players.append(",");
                }
                players.append(player.plainName()).append("|").append(player.uuid());
            }
        });

        properties.setProperty("players", players.toString());
        properties.setProperty("out", String.join(",", referee.outUuids()));
        // The live roster (offline members included) and the owner let the hub bounce a
        // disconnected participant back here, even a sandbox guest who joined after launch.
        properties.setProperty("participants", String.join(",", referee.participantUuids()));
        properties.setProperty("owner", referee.sandbox.ownerUuid());
        properties.setProperty("playtime", packPlaytime());
        // The hub owns bans: it applies these properly and needs the story with them.
        properties.setProperty("banrequests", String.join(",", banRequestUuids));

        for (Map.Entry<String, BanRequest> entry : banRequestDetails.entrySet()) {
            String prefix = "banrequest." + entry.getKey() + ".";
            BanRequest request = entry.getValue();

            properties.setProperty(prefix + "actor", request.origin().actor());
            properties.setProperty(prefix + "time", request.origin().consoleTime());

            WordFilterHit hit = request.wordFilterHit();

            if (hit != null) {
                properties.setProperty(prefix + "source", hit.source().key());
                properties.setProperty(prefix + "word", hit.word());
                properties.setProperty(prefix + "text", hit.text());
            }
        }

        try (FileOutputStream output = new FileOutputStream(STATUS_FILE)) {
            properties.store(output, "Evict duel status");
        } catch (Exception ignored) {
            // Status is best-effort; a missed write just shows stale data.
        }
    }

    // uuid:millis pairs, comma separated; empty when nobody has played.
    private String packPlaytime() {
        StringBuilder packed = new StringBuilder();

        for (Map.Entry<String, Long> entry : playtimeSource.get().entrySet()) {
            if (entry.getKey().isBlank() || entry.getValue() == null) {
                continue;
            }

            if (!packed.isEmpty()) {
                packed.append(",");
            }

            packed.append(entry.getKey()).append(":").append(entry.getValue());
        }

        return packed.toString();
    }

    // The matches running on sibling workers, by port: fresh status, not finished, no result yet.
    public static List<Referee.SiblingMatch> listSiblings() {
        List<Referee.SiblingMatch> matches = new ArrayList<>();

        try {
            File ownDir = new File(".").getCanonicalFile();
            File[] siblings = ownDir.getParentFile() == null
                    ? null
                    : ownDir.getParentFile().listFiles(File::isDirectory);

            if (siblings == null) {
                return matches;
            }

            for (File dir : siblings) {
                int port = parseSiblingPort(dir.getName());

                if (port < 0 || dir.getCanonicalFile().equals(ownDir)) {
                    continue;
                }

                if (new File(dir, RESULT_FILE_NAME).exists()) {
                    continue;
                }

                File statusFile = new File(dir, STATUS_FILE.getName());

                if (
                        !statusFile.exists()
                                || System.currentTimeMillis()
                                - statusFile.lastModified()
                                > SIBLING_STATUS_FRESH_MILLIS
                ) {
                    continue;
                }

                Properties status = new Properties();

                try (FileInputStream input = new FileInputStream(statusFile)) {
                    status.load(input);
                }

                if ("finished".equals(status.getProperty("state", ""))) {
                    continue;
                }

                matches.add(new Referee.SiblingMatch(port, siblingLabel(dir, port)));
            }
        } catch (Exception exception) {
            Log.err(
                    "[EvictMapGenerator] Duel worker could not scan sibling matches.",
                    exception
            );
        }

        matches.sort(Comparator.comparingInt(Referee.SiblingMatch::port));
        return matches;
    }

    // The port a sibling folder name encodes, or -1 for any other folder.
    private static int parseSiblingPort(String dirName) {
        if (!dirName.startsWith(SIBLING_DIR_PREFIX)) {
            return -1;
        }

        try {
            return Integer.parseInt(
                    dirName.substring(SIBLING_DIR_PREFIX.length())
            );
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    // The label the sibling's handshake carries, or a port fallback for an older hub's file.
    private static String siblingLabel(File dir, int port) {
        String label = MatchHandshake.read(new File(dir, MatchHandshake.FILE_NAME)).label;
        return label.isEmpty() ? "Match on port " + port : label;
    }
}
