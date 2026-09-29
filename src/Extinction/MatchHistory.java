// Match history schema, reads and the one-time stats recount; the hub supplies its single writer queue.
package Extinction;

import arc.Core;
import arc.util.Log;
import java.sql.*;
import java.util.*;
import java.util.function.Consumer;

public final class MatchHistory {
    private static final int DEFAULT_ELO = Elo.STARTING_ELO;
    public interface Job { void run() throws Exception; }
    public interface ConnectionSource { Connection open() throws SQLException; }
    private final Consumer<Job> queue;
    private final ConnectionSource reader;
    private final ConnectionSource writer;

    public MatchHistory(Consumer<Job> queue, ConnectionSource reader, ConnectionSource writer) {
        this.queue = queue;
        this.reader = reader;
        this.writer = writer;
    }

    public static void createSchema(Statement statement) throws SQLException {
        // History rows retain their original mode, roster names and ratings.
        statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS duel_matches ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "played_at_ms INTEGER NOT NULL,"
                        + "winner_uuid TEXT NOT NULL,"
                        + "winner_name TEXT NOT NULL,"
                        + "loser_uuid TEXT NOT NULL,"
                        + "loser_name TEXT NOT NULL,"
                        + "winner_elo_before INTEGER NOT NULL DEFAULT 0,"
                        + "winner_elo_after INTEGER NOT NULL DEFAULT 0,"
                        + "loser_elo_before INTEGER NOT NULL DEFAULT 0,"
                        + "loser_elo_after INTEGER NOT NULL DEFAULT 0,"
                        + "mode TEXT NOT NULL DEFAULT '1v1',"
                        + "participant_uuids TEXT NOT NULL DEFAULT '',"
                        + "participant_names TEXT NOT NULL DEFAULT ''"
                        + ")"
        );

        // Databases created before the FFA history feature miss the new
        // columns; ALTER fails harmlessly where they already exist.
        addColumnIfMissing(
                statement, "duel_matches",
                "mode TEXT NOT NULL DEFAULT '1v1'"
        );
        addColumnIfMissing(
                statement, "duel_matches",
                "participant_uuids TEXT NOT NULL DEFAULT ''"
        );
        addColumnIfMissing(
                statement, "duel_matches",
                "participant_names TEXT NOT NULL DEFAULT ''"
        );

        statement.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_duel_matches_winner "
                        + "ON duel_matches(winner_uuid)"
        );

        statement.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_duel_matches_loser "
                        + "ON duel_matches(loser_uuid)"
        );
    }

    private static final int STATS_REPAIR_VERSION = 1;

    public void repairStatsIfNeeded() throws SQLException {
        try (
                Connection connection = writer.open();
                Statement statement = connection.createStatement()
        ) {
            int version = 0;

            try (ResultSet rows = statement.executeQuery("PRAGMA user_version")) {
                if (rows.next()) {
                    version = rows.getInt(1);
                }
            }

            if (version >= STATS_REPAIR_VERSION) {
                return;
            }

            recountStatsFromHistory(connection);
            statement.executeUpdate(
                    "PRAGMA user_version = " + STATS_REPAIR_VERSION
            );

            Log.info(
                    "[EvictMapGenerator] One-time stats repair done: normal/ranked "
                            + "counters recounted and ELO replayed from match history."
            );
        }
    }

    private void recountStatsFromHistory(Connection connection)
            throws SQLException {
        record MatchRow(
                long id,
                String mode,
                String winnerUuids,
                String loserUuids,
                String participantUuids
        ) {
        }

        List<MatchRow> matchRows = new ArrayList<>();

        try (
                PreparedStatement select = connection.prepareStatement(
                        "SELECT id, mode, winner_uuid, loser_uuid, "
                                + "participant_uuids FROM duel_matches "
                                + "ORDER BY played_at_ms, id"
                );
                ResultSet rows = select.executeQuery()
        ) {
            while (rows.next()) {
                matchRows.add(new MatchRow(
                        rows.getLong("id"),
                        rows.getString("mode"),
                        rows.getString("winner_uuid"),
                        rows.getString("loser_uuid"),
                        rows.getString("participant_uuids")
                ));
            }
        }

        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);

        try {
            Map<String, StatTally> tallies = new HashMap<>();

            try (
                    PreparedStatement updateRankedRow = connection.prepareStatement(
                            "UPDATE duel_matches SET "
                                    + "winner_elo_before = ?, winner_elo_after = ?, "
                                    + "loser_elo_before = ?, loser_elo_after = ? "
                                    + "WHERE id = ?"
                    )
            ) {
                for (MatchRow row : matchRows) {
                    switch (row.mode()) {
                        case "1v1", "teams" -> {
                            for (String uuid : splitUuids(row.winnerUuids())) {
                                tally(tallies, uuid).normalWins++;
                            }
                            for (String uuid : splitUuids(row.loserUuids())) {
                                tally(tallies, uuid).normalLosses++;
                            }
                        }
                        case "ffa" -> {
                            tally(tallies, row.winnerUuids()).normalWins++;
                            for (String uuid : splitUuids(row.participantUuids())) {
                                if (!uuid.equals(row.winnerUuids())) {
                                    tally(tallies, uuid).normalLosses++;
                                }
                            }
                        }
                        case "ranked" -> {
                            StatTally winner = tally(tallies, row.winnerUuids());
                            StatTally loser = tally(tallies, row.loserUuids());
                            Extinction.Elo.Result result =
                                    Extinction.Elo.apply(winner.elo, loser.elo);

                            winner.rankedWins++;
                            loser.rankedLosses++;
                            winner.elo = result.winnerAfter();
                            loser.elo = result.loserAfter();
                            winner.peakElo =
                                    Math.max(winner.peakElo, winner.elo);
                            loser.peakElo = Math.max(loser.peakElo, loser.elo);

                            updateRankedRow.setInt(1, result.winnerBefore());
                            updateRankedRow.setInt(2, result.winnerAfter());
                            updateRankedRow.setInt(3, result.loserBefore());
                            updateRankedRow.setInt(4, result.loserAfter());
                            updateRankedRow.setLong(5, row.id());
                            updateRankedRow.executeUpdate();
                        }
                        default -> {
                            // Unknown mode: leave the row alone.
                        }
                    }
                }
            }

            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        "UPDATE players SET "
                                + "normal_wins = 0, normal_losses = 0, "
                                + "normal_matches_played = 0, "
                                + "ranked_wins = 0, ranked_losses = 0, "
                                + "ranked_matches_played = 0, "
                                + "elo = " + DEFAULT_ELO + ", "
                                + "peak_elo = " + DEFAULT_ELO
                );
            }

            try (
                    PreparedStatement updatePlayer = connection.prepareStatement(
                            "UPDATE players SET "
                                    + "normal_wins = ?, normal_losses = ?, "
                                    + "normal_matches_played = ?, "
                                    + "ranked_wins = ?, ranked_losses = ?, "
                                    + "ranked_matches_played = ?, "
                                    + "elo = ?, peak_elo = ? "
                                    + "WHERE uuid = ?"
                    )
            ) {
                for (Map.Entry<String, StatTally> entry : tallies.entrySet()) {
                    StatTally tallied = entry.getValue();

                    updatePlayer.setInt(1, tallied.normalWins);
                    updatePlayer.setInt(2, tallied.normalLosses);
                    updatePlayer.setInt(
                            3, tallied.normalWins + tallied.normalLosses
                    );
                    updatePlayer.setInt(4, tallied.rankedWins);
                    updatePlayer.setInt(5, tallied.rankedLosses);
                    updatePlayer.setInt(
                            6, tallied.rankedWins + tallied.rankedLosses
                    );
                    updatePlayer.setInt(7, tallied.elo);
                    updatePlayer.setInt(8, tallied.peakElo);
                    updatePlayer.setString(9, entry.getKey());
                    updatePlayer.executeUpdate();
                }
            }

            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    // One player's recounted stats while replaying the match history.
    private static final class StatTally {
        int normalWins;
        int normalLosses;
        int rankedWins;
        int rankedLosses;
        // Peak ELO can never sit below the starting rating - a player who only
        // ever lost still peaked at their starting 1000.
        int elo = DEFAULT_ELO;
        int peakElo = DEFAULT_ELO;
    }

    private static StatTally tally(Map<String, StatTally> tallies, String uuid) {
        return tallies.computeIfAbsent(uuid, ignored -> new StatTally());
    }

    public static List<String> splitUuids(String packed) {
        List<String> uuids = new ArrayList<>();

        if (packed == null || packed.isEmpty()) {
            return uuids;
        }

        for (String uuid : packed.split(",")) {
            if (!uuid.isEmpty()) {
                uuids.add(uuid);
            }
        }

        return uuids;
    }

    private static void addColumnIfMissing(
            Statement statement,
            String table,
            String columnDefinition
    ) {
        try {
            statement.executeUpdate(
                    "ALTER TABLE " + table + " ADD COLUMN " + columnDefinition
            );
        } catch (SQLException ignored) {
            // Column already exists.
        }
    }

    public void findDuelHistory(
            String uuid,
            Consumer<List<DuelMatch>> callback
    ) {
        queue.accept(() -> deliver(callback, loadDuelHistory(uuid)));
    }

    private List<DuelMatch> loadDuelHistory(String uuid) throws SQLException {
        List<DuelMatch> result = new ArrayList<>();

        // UUIDs are fixed-length base64 without commas, so instr() matches a whole element.
        try (
                Connection connection = reader.open();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT played_at_ms, winner_uuid, winner_name, "
                                + "loser_uuid, loser_name, mode, "
                                + "participant_names, "
                                + "winner_elo_before, winner_elo_after, "
                                + "loser_elo_before, loser_elo_after "
                                + "FROM duel_matches "
                                + "WHERE winner_uuid = ? OR loser_uuid = ? "
                                + "OR instr(participant_uuids, ?) > 0 "
                                + "ORDER BY played_at_ms DESC"
                )
        ) {
            statement.setString(1, uuid);
            statement.setString(2, uuid);
            statement.setString(3, uuid);

            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new DuelMatch(
                            rows.getLong("played_at_ms"),
                            rows.getString("winner_uuid"),
                            rows.getString("winner_name"),
                            rows.getString("loser_uuid"),
                            rows.getString("loser_name"),
                            rows.getString("mode"),
                            rows.getString("participant_names"),
                            rows.getInt("winner_elo_before"),
                            rows.getInt("winner_elo_after"),
                            rows.getInt("loser_elo_before"),
                            rows.getInt("loser_elo_after")
                    ));
                }
            }
        }

        return result;
    }

    private <T> void deliver(Consumer<T> callback, T value) {
        if (Core.app == null) {
            callback.accept(value);
            return;
        }

        Core.app.post(() -> callback.accept(value));
    }

    public record DuelMatch(
            long playedAtMillis,
            String winnerUuid,
            String winnerName,
            String loserUuid,
            String loserName,
            String mode,
            String participantNamesPacked,
            int winnerEloBefore,
            int winnerEloAfter,
            int loserEloBefore,
            int loserEloAfter
    ) {
    }
}
