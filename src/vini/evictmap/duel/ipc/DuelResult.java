package vini.evictmap.duel.ipc;

import vini.evictmap.core.io.PropertiesFile;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * The worker&rarr;hub match result contract ({@code result.properties}), as one
 * typed value instead of scattered {@code getProperty}/{@code setProperty} calls
 * on both sides of the process boundary.
 *
 * <p>Wire keys: {@code mode}, {@code winner.uuid} / {@code winner.uuids},
 * {@code loser.uuid} / {@code loser.uuids}, {@code reason}. The single
 * {@code *.uuid} keys carry the first roster member for readers that only need a
 * representative; the {@code *.uuids} keys carry the whole comma-separated list.
 */
public final class DuelResult {

    private final String modeId;
    private final List<String> winnerUuids;
    private final List<String> loserUuids;
    private final String reason;

    public DuelResult(
            String modeId,
            List<String> winnerUuids,
            List<String> loserUuids,
            String reason
    ) {
        this.modeId = modeId == null ? "" : modeId;
        this.winnerUuids = List.copyOf(winnerUuids);
        this.loserUuids = List.copyOf(loserUuids);
        this.reason = reason == null ? "" : reason;
    }

    /** The raw mode id, or {@code fallback} when it was absent/blank. */
    public String modeId(String fallback) {
        return modeId.isBlank() ? fallback : modeId;
    }

    public List<String> winnerUuids() {
        return winnerUuids;
    }

    public List<String> loserUuids() {
        return loserUuids;
    }

    /** The representative winner uuid ({@code winner.uuid}), or {@code ""}. */
    public String firstWinner() {
        return winnerUuids.isEmpty() ? "" : winnerUuids.get(0);
    }

    public String firstLoser() {
        return loserUuids.isEmpty() ? "" : loserUuids.get(0);
    }

    public String reason() {
        return reason.isBlank() ? "?" : reason;
    }

    public void write(File file) {
        Properties properties = new Properties();
        properties.setProperty("mode", modeId);
        properties.setProperty("winner.uuid", firstWinner());
        properties.setProperty("loser.uuid", firstLoser());
        properties.setProperty("winner.uuids", String.join(",", winnerUuids));
        properties.setProperty("loser.uuids", String.join(",", loserUuids));
        properties.setProperty("reason", reason);
        PropertiesFile.save(file, properties, "Evict duel result");
    }

    public static DuelResult read(File file) {
        Properties properties = PropertiesFile.load(file);
        return new DuelResult(
                PropertiesFile.getString(properties, "mode", "").trim(),
                readList(properties, "winner.uuids", "winner.uuid"),
                readList(properties, "loser.uuids", "loser.uuid"),
                PropertiesFile.getString(properties, "reason", "").trim()
        );
    }

    /** Prefers the comma list; falls back to the single uuid; drops blanks. */
    private static List<String> readList(Properties p, String listKey, String singleKey) {
        List<String> result = new ArrayList<>();
        String list = PropertiesFile.getString(p, listKey, "").trim();

        if (!list.isEmpty()) {
            for (String token : list.split(",")) {
                String trimmed = token.trim();
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
            return result;
        }

        String single = PropertiesFile.getString(p, singleKey, "").trim();
        if (!single.isEmpty()) {
            result.add(single);
        }
        return result;
    }
}
