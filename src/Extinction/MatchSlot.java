// One slot of the match worker pool: its port, process, mode and roster, snapshotted at spawn.
package Extinction;


import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class MatchSlot {

    // One rostered player, snapshotted at spawn so names render after they disconnect.
    public record Participant(String uuid, String plainName, String display, int teamIndex) {
    }

    // How many names a /s or /h label spells out before folding the rest into "+N more".
    public static final int MAX_LABEL_NAMES = 4;

    public final int port;
    public final long spawnedAtMillis = System.currentTimeMillis();
    public volatile Process process;
    public MatchMode mode = MatchMode.ONE_VS_ONE;
    public String label = "?";
    public final List<Participant> participants = new ArrayList<>();
    public List<String> adminUuids = new ArrayList<>();
    public List<String> bannedBlocks = new ArrayList<>();

    // Whether the chat mirror's end embed went out, so the release-time fallback fires once.
    public boolean endReported;

    public MatchSlot(int port) {
        this.port = port;
    }

    public boolean alive() {
        return process != null && process.isAlive();
    }

    public Participant participant(String uuid) {
        for (Participant participant : participants) {
            if (participant.uuid().equals(uuid)) {
                return participant;
            }
        }

        return null;
    }

    // The coloured display name captured at spawn, or the uuid when unknown.
    public String displayNameFor(String uuid) {
        Participant participant = participant(uuid);
        return participant == null ? uuid : participant.display();
    }

    // The participants' plain names grouped into their teams, in team order.
    public List<List<String>> rosterNames() {
        Map<Integer, List<String>> byTeam = new TreeMap<>();

        for (Participant participant : participants) {
            byTeam.computeIfAbsent(
                    participant.teamIndex(),
                    index -> new ArrayList<>()
            ).add(participant.plainName());
        }

        return new ArrayList<>(byTeam.values());
    }

    // "name (uuid), ..." for the console, or "(none)".
    public String summary() {
        StringBuilder summary = new StringBuilder();

        for (Participant participant : participants) {
            if (!summary.isEmpty()) {
                summary.append(", ");
            }

            summary.append(participant.plainName())
                    .append(" (")
                    .append(participant.uuid())
                    .append(")");
        }

        return summary.isEmpty() ? "(none)" : summary.toString();
    }
}
