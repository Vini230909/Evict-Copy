// The worker -> hub result file (result.properties): mode, winner and loser uuids, reason.
package Extinction;

import Extinction.core.io.PropertiesFile;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public final class MatchResult {

    private final String modeId;
    private final List<String> winnerUuids;
    private final List<String> loserUuids;
    private final String reason;
    private final String outcome;
    private final String bannedUuid;

    public MatchResult(
            String modeId,
            List<String> winnerUuids,
            List<String> loserUuids,
            String reason
    ) {
        this(modeId, winnerUuids, loserUuids, reason, "decided", "");
    }

    private MatchResult(String modeId, List<String> winners, List<String> losers,
                        String reason, String outcome, String bannedUuid) {
        this.outcome = outcome;
        this.bannedUuid = bannedUuid;
        this.modeId = modeId == null ? "" : modeId;
        this.winnerUuids = List.copyOf(winners);
        this.loserUuids = List.copyOf(losers);
        this.reason = reason == null ? "" : reason;
    }

    public static MatchResult noContest(String modeId, String bannedUuid) {
        return new MatchResult(modeId, List.of(), List.of(), "a player was banned", "no-contest", bannedUuid);
    }

    public boolean noContest() { return "no-contest".equals(outcome); }
    public String bannedUuid() { return bannedUuid; }

    // The raw mode id, or the fallback when it was absent/blank.
    public String modeId(String fallback) {
        return modeId.isBlank() ? fallback : modeId;
    }

    public List<String> winnerUuids() {
        return winnerUuids;
    }

    public List<String> loserUuids() {
        return loserUuids;
    }

    // The representative winner (winner.uuid), or "".
    public String firstWinner() {
        return winnerUuids.isEmpty() ? "" : winnerUuids.get(0);
    }

    public String firstLoser() {
        return loserUuids.isEmpty() ? "" : loserUuids.get(0);
    }

    public String reason() {
        return reason.isBlank() ? "?" : reason;
    }

    // winner.uuid / loser.uuid carry the first roster member; *.uuids the whole comma list.
    public void write(File file) {
        Properties properties = new Properties();
        properties.setProperty("mode", modeId);
        properties.setProperty("winner.uuid", firstWinner());
        properties.setProperty("loser.uuid", firstLoser());
        properties.setProperty("winner.uuids", String.join(",", winnerUuids));
        properties.setProperty("loser.uuids", String.join(",", loserUuids));
        properties.setProperty("reason", reason);
        properties.setProperty("outcome", outcome);
        properties.setProperty("banned.uuid", bannedUuid);
        PropertiesFile.save(file, properties, "Evict duel result");
    }

    public static MatchResult read(File file) {
        Properties properties = PropertiesFile.load(file);
        return new MatchResult(
                PropertiesFile.getString(properties, "mode", "").trim(),
                readList(properties, "winner.uuids", "winner.uuid"),
                readList(properties, "loser.uuids", "loser.uuid"),
                PropertiesFile.getString(properties, "reason", "").trim(),
                PropertiesFile.getString(properties, "outcome", "decided").trim(),
                PropertiesFile.getString(properties, "banned.uuid", "").trim()
        );
    }

    // Prefers the comma list; falls back to the single uuid; drops blanks.
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
