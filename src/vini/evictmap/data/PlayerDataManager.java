package vini.evictmap.data;

import arc.Core;
import arc.util.Log;
import mindustry.gen.Player;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Persistent player profile and statistics storage.
 * Database writes run on a single background thread. Game-world state is only
 * read on the server thread before small immutable write jobs are queued.
 */
public final class PlayerDataManager {

    private static final File DATABASE_FILE =
            new File("config/evict-players.db");
    private static final int DEFAULT_ELO = EloCalculator.STARTING_ELO;

    /**
     * Database that /history reads from. Defaults to this server's own DB. A duel
     * worker points this at the hub DB (the worker's own DB has no real matches),
     * so spectators and players can see real history on a duel server.
     */
    private volatile File historyDatabaseFile = DATABASE_FILE;

    private final ExecutorService databaseExecutor =
            Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "EvictPlayerDataWriter");
                thread.setDaemon(true);
                return thread;
            });

    private final Map<String, ActiveSession> activeSessionsByUuid =
            new HashMap<>();

    public void start() {
        enqueue(this::createSchema);

        Runtime.getRuntime().addShutdownHook(
                new Thread(this::shutdown, "EvictPlayerDataShutdown")
        );
    }

    /**
     * Flushes every connected player's session playtime at a round start, so
     * stored totals stay fresh across rounds.
     */
    public void beginRound() {
        synchronized (this) {
            long now = System.currentTimeMillis();
            flushActiveSessions(now);

            for (ActiveSession session : activeSessionsByUuid.values()) {
                session.startedAtMillis = now;
            }
        }
    }

    public void handlePlayerJoin(Player player) {
        if (player == null) {
            return;
        }

        String uuid = player.uuid();
        String name = player.plainName();
        long now = System.currentTimeMillis();

        synchronized (this) {
            activeSessionsByUuid.putIfAbsent(
                    uuid,
                    new ActiveSession(name, now)
            );
        }

        enqueue(() -> upsertPlayer(uuid, name, now));
    }

    public void handlePlayerLeave(Player player) {
        if (player == null) {
            return;
        }

        synchronized (this) {
            ActiveSession session =
                    activeSessionsByUuid.remove(player.uuid());

            if (session != null) {
                persistSession(
                        player.uuid(),
                        session,
                        System.currentTimeMillis()
                );
            }
        }
    }

    /**
     * Records a finished ranked 1v1: a win for the winner, a loss for the loser,
     * a played match for both, the ELO swing on both profiles (see
     * {@link EloCalculator}) and one ranked row in the match history carrying the
     * before/after ratings. Called on the hub once a worker reports its result;
     * no-op if either UUID is missing (e.g. the worker could not identify a
     * winner). Only the Ranked mode reaches this path; casual 1v1 goes through
     * {@link #recordCasualDuelResult}.
     */
    public void recordRankedResult(
            String winnerUuid,
            String winnerName,
            String loserUuid,
            String loserName
    ) {
        if (
                winnerUuid == null
                        || winnerUuid.isEmpty()
                        || loserUuid == null
                        || loserUuid.isEmpty()
        ) {
            return;
        }

        long playedAtMillis = System.currentTimeMillis();

        enqueue(() -> applyRankedResult(
                winnerUuid,
                safeName(winnerName),
                loserUuid,
                safeName(loserName),
                playedAtMillis
        ));
    }

    /**
     * Records a finished casual 1v1 as an unranked history entry: one row with
     * win/lose and no ELO, and no ranked counters or rating change touched. The
     * unranked counterpart to {@link #recordRankedResult}, called on the hub
     * once a worker reports a casual 1v1 result.
     */
    public void recordCasualDuelResult(
            String winnerUuid,
            String winnerName,
            String loserUuid,
            String loserName
    ) {
        if (
                winnerUuid == null
                        || winnerUuid.isEmpty()
                        || loserUuid == null
                        || loserUuid.isEmpty()
        ) {
            return;
        }

        long playedAtMillis = System.currentTimeMillis();

        enqueue(() -> applyCasualResult(
                winnerUuid,
                safeName(winnerName),
                loserUuid,
                safeName(loserName),
                playedAtMillis
        ));
    }

    /**
     * Records a finished /play FFA: one history row listing every participant
     * plus a normal win for the winner and a normal loss for everyone else.
     * Never touches ranked counters or ELO. Called on the hub once a worker
     * reports an FFA result.
     */
    public void recordFfaMatch(
            String winnerUuid,
            String winnerName,
            List<String> participantUuids,
            List<String> participantNames
    ) {
        if (
                winnerUuid == null
                        || winnerUuid.isEmpty()
                        || participantUuids == null
                        || participantUuids.isEmpty()
        ) {
            return;
        }

        long playedAtMillis = System.currentTimeMillis();
        String safeWinnerName = safeName(winnerName);
        String uuidsPacked = String.join(",", participantUuids);
        String namesPacked = String.join("\n", participantNames);

        enqueue(() -> applyFfaResult(
                playedAtMillis,
                winnerUuid,
                safeWinnerName,
                uuidsPacked,
                namesPacked
        ));
    }

    /**
     * Records a finished Teams match: one history row with the two rosters and
     * who won, plus a normal win for every winner and a normal loss for every
     * loser. Never touches ranked counters or ELO. Called on the hub once a
     * worker reports a Teams result.
     */
    public void recordTeamsMatch(
            List<String> winnerUuids,
            String winnerTeamLabel,
            List<String> loserUuids,
            String loserTeamLabel
    ) {
        if (
                winnerUuids == null
                        || winnerUuids.isEmpty()
                        || loserUuids == null
                        || loserUuids.isEmpty()
        ) {
            return;
        }

        long playedAtMillis = System.currentTimeMillis();
        String winnersPacked = String.join(",", winnerUuids);
        String losersPacked = String.join(",", loserUuids);
        String winnerLabel = safeName(winnerTeamLabel);
        String loserLabel = safeName(loserTeamLabel);

        enqueue(() -> applyTeamsResult(
                playedAtMillis,
                winnersPacked,
                winnerLabel,
                losersPacked,
                loserLabel
        ));
    }

    /**
     * A player's 1v1, Teams and FFA matches, most recent first.
     */
    public void findDuelHistory(
            String uuid,
            Consumer<List<DuelMatch>> callback
    ) {
        enqueue(() -> deliver(callback, loadDuelHistory(uuid)));
    }

    public void searchPlayerInfo(
            String query,
            Consumer<List<PlayerInfo>> callback
    ) {
        enqueue(() -> deliver(callback, searchPlayerInfo(query)));
    }

    /**
     * The top ranked players by ELO (only those who have played a ranked match),
     * highest first, for the /leaderboard command. Delivered on the main thread.
     */
    public void topRankedByElo(
            int limit,
            Consumer<List<PlayerInfo>> callback
    ) {
        enqueue(() -> deliver(callback, loadTopRankedByElo(limit)));
    }

    /**
     * The stored player with this exact UUID, or null if none is stored yet.
     * Used by /info once a player has been picked from the online list.
     */
    public void findPlayerInfoByUuid(
            String uuid,
            Consumer<PlayerInfo> callback
    ) {
        enqueue(() -> deliver(callback, loadPlayerInfoByUuid(uuid)));
    }

    /**
     * Overwrites a stored player's current ELO (peak ELO is only ever raised, so
     * a manual correction never lowers the historical peak). Console admin tool.
     * The callback reports whether a stored row was actually changed - false
     * means no player with that exact UUID exists yet.
     */
    public void setElo(String uuid, int newElo, Consumer<Boolean> callback) {
        if (uuid == null || uuid.isBlank()) {
            deliver(callback, false);
            return;
        }

        String trimmedUuid = uuid.trim();
        int clampedElo = Math.max(0, newElo);

        enqueue(() -> {
            try (Connection connection = connect()) {
                deliver(callback, writeElo(connection, trimmedUuid, clampedElo) > 0);
            }
        });
    }

    private void shutdown() {
        synchronized (this) {
            flushActiveSessions(System.currentTimeMillis());
            activeSessionsByUuid.clear();
        }

        databaseExecutor.shutdown();

        try {
            if (!databaseExecutor.awaitTermination(5L, TimeUnit.SECONDS)) {
                databaseExecutor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            databaseExecutor.shutdownNow();
        }
    }

    private void flushActiveSessions(long now) {
        for (Map.Entry<String, ActiveSession> entry :
                activeSessionsByUuid.entrySet()) {
            persistSession(entry.getKey(), entry.getValue(), now);
        }
    }

    private void persistSession(
            String uuid,
            ActiveSession session,
            long finishedAtMillis
    ) {
        long playedMillis =
                Math.max(0L, finishedAtMillis - session.startedAtMillis);
        String name = session.lastName;

        session.startedAtMillis = finishedAtMillis;

        enqueue(
                () -> addPlaytime(
                        uuid,
                        name,
                        finishedAtMillis,
                        playedMillis
                )
        );
    }

    private void enqueue(DatabaseJob job) {
        databaseExecutor.execute(() -> {
            try {
                job.run();
            } catch (Exception exception) {
                Log.err(
                        "[EvictMapGenerator] Player data write failed.",
                        exception
                );
            }
        });
    }

    private <T> void deliver(Consumer<T> callback, T value) {
        if (Core.app == null) {
            callback.accept(value);
            return;
        }

        Core.app.post(() -> callback.accept(value));
    }

    private void createSchema() throws SQLException {
        File parent = DATABASE_FILE.getParentFile();

        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new SQLException(
                    "Could not create data directory: " + parent.getPath()
            );
        }

        try (
                Connection connection = connect();
                Statement statement = connection.createStatement()
        ) {
            statement.executeUpdate(
                    // Old databases may additionally carry legacy
                    // ffa_playtime_ms / ffa_played / ffa_won /
                    // ranked_playtime_ms columns from removed features; they
                    // are simply ignored.
                    "CREATE TABLE IF NOT EXISTS players ("
                            + "uuid TEXT PRIMARY KEY,"
                            + "last_name TEXT NOT NULL,"
                            + "first_seen_ms INTEGER NOT NULL,"
                            + "last_seen_ms INTEGER NOT NULL,"
                            + "total_playtime_ms INTEGER NOT NULL DEFAULT 0,"
                            + "ranked_wins INTEGER NOT NULL DEFAULT 0,"
                            + "ranked_losses INTEGER NOT NULL DEFAULT 0,"
                            + "ranked_matches_played INTEGER NOT NULL DEFAULT 0,"
                            + "normal_wins INTEGER NOT NULL DEFAULT 0,"
                            + "normal_losses INTEGER NOT NULL DEFAULT 0,"
                            + "normal_matches_played INTEGER NOT NULL DEFAULT 0,"
                            + "elo INTEGER NOT NULL DEFAULT " + DEFAULT_ELO + ","
                            + "peak_elo INTEGER NOT NULL DEFAULT " + DEFAULT_ELO
                            + ")"
            );

            // Databases created by older plugin versions predate some of the
            // columns above; CREATE TABLE IF NOT EXISTS never adds them, so
            // ranked writes and /info reads would fail silently on upgraded
            // servers. ALTER fails harmlessly where a column already exists.
            addColumnIfMissing(
                    statement, "players",
                    "ranked_wins INTEGER NOT NULL DEFAULT 0"
            );
            addColumnIfMissing(
                    statement, "players",
                    "ranked_losses INTEGER NOT NULL DEFAULT 0"
            );
            addColumnIfMissing(
                    statement, "players",
                    "ranked_matches_played INTEGER NOT NULL DEFAULT 0"
            );
            addColumnIfMissing(
                    statement, "players",
                    "normal_wins INTEGER NOT NULL DEFAULT 0"
            );
            addColumnIfMissing(
                    statement, "players",
                    "normal_losses INTEGER NOT NULL DEFAULT 0"
            );
            addColumnIfMissing(
                    statement, "players",
                    "normal_matches_played INTEGER NOT NULL DEFAULT 0"
            );
            addColumnIfMissing(
                    statement, "players",
                    "elo INTEGER NOT NULL DEFAULT " + DEFAULT_ELO
            );
            addColumnIfMissing(
                    statement, "players",
                    "peak_elo INTEGER NOT NULL DEFAULT " + DEFAULT_ELO
            );

            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS player_names ("
                            + "uuid TEXT NOT NULL,"
                            + "name TEXT NOT NULL,"
                            + "first_seen_ms INTEGER NOT NULL,"
                            + "last_seen_ms INTEGER NOT NULL,"
                            + "PRIMARY KEY(uuid, name)"
                            + ")"
            );

            // One row per finished 1v1 or FFA. Names are the colored display
            // names at match time so /history can render them without the
            // players being online. The elo before/after columns are filled for
            // ranked 1v1 rows; FFA/Teams rows are unranked and leave them at 0.
            // FFA rows additionally carry every participant (uuids comma-joined,
            // names newline-joined) and no loser columns.
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

        Log.info(
                "[EvictMapGenerator] Player data storage ready: @",
                DATABASE_FILE.getPath()
        );

        repairStatsIfNeeded();
    }

    /**
     * Data revision stored in {@code PRAGMA user_version}. Revision 1 is the
     * one-time stats repair: plugin versions before 1.4 incremented the ranked
     * win/loss counters for every casual 1v1, so upgraded databases carry
     * casual games inside their ranked numbers. The repair recounts both
     * counter sets from the match history and replays every ranked match
     * through {@link EloCalculator} so ratings match the recorded games.
     */
    private static final int STATS_REPAIR_VERSION = 1;

    private void repairStatsIfNeeded() throws SQLException {
        try (
                Connection connection = connect();
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

    /**
     * Rebuilds every player's normal and ranked counters from the duel_matches
     * rows and replays all ranked matches chronologically through
     * {@link EloCalculator}, rewriting each ranked row's before/after ratings
     * and every player's current and peak ELO. Normal counts every competitive
     * duel row - 1v1, Teams and /play FFA (Training/Sandbox never leave rows);
     * ranked counts only ranked rows. Playtime and any legacy columns are
     * untouched. Manual evictelo overrides are replaced by the replayed
     * values.
     */
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
                            EloCalculator.Result result =
                                    EloCalculator.apply(winner.elo, loser.elo);

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

    /** One player's recounted stats while replaying the match history. */
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

    /**
     * Splits a comma-packed UUID list (Teams rosters, FFA participants) into
     * its entries. UUIDs are fixed-length base64 without commas, so a plain
     * split is always element-exact.
     */
    private static List<String> splitUuids(String packed) {
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

    /**
     * Adds a column to a table, ignoring the "duplicate column name" error on
     * databases that already have it. SQLite has no ADD COLUMN IF NOT EXISTS.
     */
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

    private void upsertPlayer(
            String uuid,
            String name,
            long seenAtMillis
    ) throws SQLException {
        try (
                Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO players "
                                + "(uuid, last_name, first_seen_ms, last_seen_ms) "
                                + "VALUES (?, ?, ?, ?) "
                                + "ON CONFLICT(uuid) DO UPDATE SET "
                                + "last_name = excluded.last_name, "
                                + "last_seen_ms = excluded.last_seen_ms"
                )
        ) {
            statement.setString(1, uuid);
            statement.setString(2, name);
            statement.setLong(3, seenAtMillis);
            statement.setLong(4, seenAtMillis);
            statement.executeUpdate();

            upsertPlayerName(connection, uuid, name, seenAtMillis);
        }
    }

    private void addPlaytime(
            String uuid,
            String name,
            long seenAtMillis,
            long totalMillis
    ) throws SQLException {
        upsertPlayer(uuid, name, seenAtMillis);

        try (
                Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE players SET "
                                + "last_name = ?, "
                                + "last_seen_ms = ?, "
                                + "total_playtime_ms = total_playtime_ms + ? "
                                + "WHERE uuid = ?"
                )
        ) {
            statement.setString(1, name);
            statement.setLong(2, seenAtMillis);
            statement.setLong(3, totalMillis);
            statement.setString(4, uuid);
            statement.executeUpdate();
        }
    }

    private void applyRankedResult(
            String winnerUuid,
            String winnerName,
            String loserUuid,
            String loserName,
            long playedAtMillis
    ) throws SQLException {
        try (Connection connection = connect()) {
            // Read both ratings before either is written, so the calculation
            // uses the pre-match ratings for both players.
            EloCalculator.Result elo = EloCalculator.apply(
                    currentElo(connection, winnerUuid),
                    currentElo(connection, loserUuid)
            );

            updateRankedOutcome(connection, winnerUuid, true);
            updateRankedOutcome(connection, loserUuid, false);
            writeElo(connection, winnerUuid, elo.winnerAfter());
            writeElo(connection, loserUuid, elo.loserAfter());
            insertDuelMatch(
                    connection,
                    playedAtMillis,
                    "ranked",
                    winnerUuid,
                    winnerName,
                    loserUuid,
                    loserName,
                    elo.winnerBefore(),
                    elo.winnerAfter(),
                    elo.loserBefore(),
                    elo.loserAfter()
            );
        }
    }

    /**
     * Records a finished casual 1v1: one unranked /history row (win/lose, no
     * ELO) plus the normal win/loss counters - never the ranked counters and
     * no rating change. The casual counterpart to {@link #applyRankedResult}.
     */
    private void applyCasualResult(
            String winnerUuid,
            String winnerName,
            String loserUuid,
            String loserName,
            long playedAtMillis
    ) throws SQLException {
        try (Connection connection = connect()) {
            updateOutcome(connection, winnerUuid, true, "normal");
            updateOutcome(connection, loserUuid, false, "normal");
            insertDuelMatch(
                    connection,
                    playedAtMillis,
                    "1v1",
                    winnerUuid,
                    winnerName,
                    loserUuid,
                    loserName,
                    0,
                    0,
                    0,
                    0
            );
        }
    }

    /**
     * The stored current ELO for a UUID, or {@link EloCalculator#STARTING_ELO}
     * if the player has no row yet (they are treated as an unrated newcomer).
     */
    private int currentElo(Connection connection, String uuid)
            throws SQLException {
        try (
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT elo FROM players WHERE uuid = ?"
                )
        ) {
            statement.setString(1, uuid);

            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    return rows.getInt("elo");
                }
            }
        }

        return EloCalculator.STARTING_ELO;
    }

    /**
     * Sets a player's current ELO and raises their peak ELO to match if the new
     * rating is a new high. Returns the number of rows changed (0 if no such
     * player is stored). Shared by ranked results and the console override.
     */
    private static int writeElo(
            Connection connection,
            String uuid,
            int newElo
    ) throws SQLException {
        try (
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE players SET "
                                + "elo = ?, "
                                + "peak_elo = MAX(peak_elo, ?) "
                                + "WHERE uuid = ?"
                )
        ) {
            statement.setInt(1, newElo);
            statement.setInt(2, newElo);
            statement.setString(3, uuid);
            return statement.executeUpdate();
        }
    }

    /**
     * Inserts one 1v1-shaped history row. mode is the wire id ("ranked" or
     * "1v1", mirroring {@code MatchMode}); the elo columns carry the rating
     * swing for ranked rows and are passed as 0 for unranked casual rows.
     */
    private void insertDuelMatch(
            Connection connection,
            long playedAtMillis,
            String mode,
            String winnerUuid,
            String winnerName,
            String loserUuid,
            String loserName,
            int winnerEloBefore,
            int winnerEloAfter,
            int loserEloBefore,
            int loserEloAfter
    ) throws SQLException {
        try (
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO duel_matches "
                                + "(played_at_ms, winner_uuid, winner_name, "
                                + "loser_uuid, loser_name, mode, "
                                + "winner_elo_before, winner_elo_after, "
                                + "loser_elo_before, loser_elo_after) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                )
        ) {
            statement.setLong(1, playedAtMillis);
            statement.setString(2, winnerUuid);
            statement.setString(3, winnerName);
            statement.setString(4, loserUuid);
            statement.setString(5, loserName);
            statement.setString(6, mode);
            statement.setInt(7, winnerEloBefore);
            statement.setInt(8, winnerEloAfter);
            statement.setInt(9, loserEloBefore);
            statement.setInt(10, loserEloAfter);
            statement.executeUpdate();
        }
    }

    /**
     * Applies a finished /play FFA on the writer thread: normal win for the
     * winner, normal loss for every other participant, one history row.
     */
    private void applyFfaResult(
            long playedAtMillis,
            String winnerUuid,
            String winnerName,
            String participantUuidsPacked,
            String participantNamesPacked
    ) throws SQLException {
        try (Connection connection = connect()) {
            updateOutcome(connection, winnerUuid, true, "normal");

            for (String uuid : splitUuids(participantUuidsPacked)) {
                if (!uuid.equals(winnerUuid)) {
                    updateOutcome(connection, uuid, false, "normal");
                }
            }

            insertFfaMatch(
                    connection,
                    playedAtMillis,
                    winnerUuid,
                    winnerName,
                    participantUuidsPacked,
                    participantNamesPacked
            );
        }
    }

    private void insertFfaMatch(
            Connection connection,
            long playedAtMillis,
            String winnerUuid,
            String winnerName,
            String participantUuidsPacked,
            String participantNamesPacked
    ) throws SQLException {
        try (
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO duel_matches "
                                + "(played_at_ms, winner_uuid, winner_name, "
                                + "loser_uuid, loser_name, mode, "
                                + "participant_uuids, participant_names) "
                                + "VALUES (?, ?, ?, '', '', 'ffa', ?, ?)"
                )
        ) {
            statement.setLong(1, playedAtMillis);
            statement.setString(2, winnerUuid);
            statement.setString(3, winnerName);
            statement.setString(4, participantUuidsPacked);
            statement.setString(5, participantNamesPacked);
            statement.executeUpdate();
        }
    }

    /**
     * Applies a finished Teams match on the writer thread: normal win for
     * every winning-roster player, normal loss for every losing-roster
     * player, one history row.
     */
    private void applyTeamsResult(
            long playedAtMillis,
            String winnerUuidsPacked,
            String winnerTeamLabel,
            String loserUuidsPacked,
            String loserTeamLabel
    ) throws SQLException {
        try (Connection connection = connect()) {
            for (String uuid : splitUuids(winnerUuidsPacked)) {
                updateOutcome(connection, uuid, true, "normal");
            }

            for (String uuid : splitUuids(loserUuidsPacked)) {
                updateOutcome(connection, uuid, false, "normal");
            }

            insertTeamsMatch(
                    connection,
                    playedAtMillis,
                    winnerUuidsPacked,
                    winnerTeamLabel,
                    loserUuidsPacked,
                    loserTeamLabel
            );
        }
    }

    /**
     * Teams rows pack the whole winning/losing rosters into the winner/loser
     * uuid columns (comma-joined) so /history can tell which side the picked
     * player was on; the name columns hold the ready-made team labels.
     */
    private void insertTeamsMatch(
            Connection connection,
            long playedAtMillis,
            String winnerUuidsPacked,
            String winnerTeamLabel,
            String loserUuidsPacked,
            String loserTeamLabel
    ) throws SQLException {
        try (
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO duel_matches "
                                + "(played_at_ms, winner_uuid, winner_name, "
                                + "loser_uuid, loser_name, mode, "
                                + "participant_uuids, participant_names) "
                                + "VALUES (?, ?, ?, ?, ?, 'teams', ?, ?)"
                )
        ) {
            statement.setLong(1, playedAtMillis);
            statement.setString(2, winnerUuidsPacked);
            statement.setString(3, winnerTeamLabel);
            statement.setString(4, loserUuidsPacked);
            statement.setString(5, loserTeamLabel);
            statement.setString(
                    6,
                    winnerUuidsPacked + "," + loserUuidsPacked
            );
            statement.setString(
                    7,
                    winnerTeamLabel + "\n" + loserTeamLabel
            );
            statement.executeUpdate();
        }
    }

    private List<DuelMatch> loadDuelHistory(String uuid) throws SQLException {
        List<DuelMatch> result = new ArrayList<>();

        /*
          UUIDs are fixed-length base64 without commas, so an instr() hit on
          the comma-joined participant list is always an exact element match.
         */
        try (
                Connection connection = connect(historyDatabaseFile);
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

    private static String safeName(String name) {
        return name == null || name.isBlank() ? "?" : name;
    }

    private void updateRankedOutcome(
            Connection connection,
            String uuid,
            boolean won
    ) throws SQLException {
        updateOutcome(connection, uuid, won, "ranked");
    }

    /**
     * Bumps one player's win-or-loss and matches-played counters for one
     * counter set: "ranked" (rated matches only) or "normal" (casual 1v1).
     */
    private void updateOutcome(
            Connection connection,
            String uuid,
            boolean won,
            String counterPrefix
    ) throws SQLException {
        String wins = counterPrefix + "_wins";
        String losses = counterPrefix + "_losses";
        String played = counterPrefix + "_matches_played";

        try (
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE players SET "
                                + (won ? wins + " = " + wins + " + 1, "
                                : losses + " = " + losses + " + 1, ")
                                + played + " = " + played + " + 1 "
                                + "WHERE uuid = ?"
                )
        ) {
            statement.setString(1, uuid);
            statement.executeUpdate();
        }
    }

    private List<PlayerInfo> loadTopRankedByElo(int limit) throws SQLException {
        int clamped = Math.max(1, Math.min(limit, 100));
        List<PlayerInfo> result = new ArrayList<>();

        try (
                Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT * FROM players WHERE ranked_matches_played > 0 "
                                + "ORDER BY elo DESC, ranked_wins DESC LIMIT ?"
                )
        ) {
            statement.setInt(1, clamped);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(playerInfo(connection, rows));
                }
            }
        }

        return result;
    }

    private List<PlayerInfo> searchPlayerInfo(String query)
            throws SQLException {
        List<PlayerInfo> result = new ArrayList<>();
        String trimmedQuery = query == null ? "" : query.trim();

        if (trimmedQuery.isEmpty()) {
            try (
                    Connection connection = connect();
                    PreparedStatement statement = connection.prepareStatement(
                            "SELECT * FROM players ORDER BY last_seen_ms DESC"
                    );
                    ResultSet rows = statement.executeQuery()
            ) {
                while (rows.next()) {
                    result.add(playerInfo(connection, rows));
                }
            }

            return result;
        }

        String likeQuery =
                "%" + trimmedQuery.toLowerCase().replace("\\", "\\\\")
                        .replace("%", "\\%")
                        .replace("_", "\\_") + "%";

        try (Connection connection = connect()) {
            result.addAll(
                    searchRows(
                            connection,
                            "SELECT * FROM players "
                                    + "WHERE lower(last_name) LIKE ? ESCAPE '\\' "
                                    + "ORDER BY last_seen_ms DESC",
                            likeQuery
                    )
            );

            if (!result.isEmpty()) {
                return result;
            }

            result.addAll(
                    searchRows(
                            connection,
                            "SELECT * FROM players "
                                    + "WHERE lower(uuid) LIKE ? ESCAPE '\\' "
                                    + "OR EXISTS ("
                                    + "SELECT 1 FROM player_names "
                                    + "WHERE player_names.uuid = players.uuid "
                                    + "AND lower(player_names.name) LIKE ? ESCAPE '\\'"
                                    + ") "
                                    + "ORDER BY last_seen_ms DESC",
                            likeQuery,
                            likeQuery
                    )
            );
        }

        return result;
    }

    private PlayerInfo loadPlayerInfoByUuid(String uuid) throws SQLException {
        if (uuid == null || uuid.isBlank()) {
            return null;
        }

        try (Connection connection = connect()) {
            List<PlayerInfo> rows = searchRows(
                    connection,
                    "SELECT * FROM players WHERE uuid = ?",
                    uuid
            );

            return rows.isEmpty() ? null : rows.get(0);
        }
    }

    private List<PlayerInfo> searchRows(
            Connection connection,
            String sql,
            String... parameters
    ) throws SQLException {
        List<PlayerInfo> result = new ArrayList<>();

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setString(index + 1, parameters[index]);
            }

            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(playerInfo(connection, rows));
                }
            }
        }

        return result;
    }

    private PlayerInfo playerInfo(
            Connection connection,
            ResultSet result
    ) throws SQLException {
        String uuid = result.getString("uuid");

        /*
          Stored playtime is only flushed at round starts, on leave and on
          shutdown, so an online player's ongoing session has not reached the
          database yet. Add the live unpersisted session time here, otherwise
          /info reports a stale total that never appears to count their current
          play. Offline players have no active session and read straight from
          the database.
         */
        long now = System.currentTimeMillis();
        long liveTotalMillis;

        synchronized (this) {
            ActiveSession session = activeSessionsByUuid.get(uuid);

            liveTotalMillis = session == null
                    ? 0L
                    : Math.max(0L, now - session.startedAtMillis);
        }

        return new PlayerInfo(
                uuid,
                result.getString("last_name"),
                playerNames(connection, uuid),
                result.getLong("first_seen_ms"),
                result.getLong("last_seen_ms"),
                result.getLong("total_playtime_ms") + liveTotalMillis,
                result.getInt("ranked_wins"),
                result.getInt("ranked_losses"),
                result.getInt("ranked_matches_played"),
                result.getInt("normal_wins"),
                result.getInt("normal_losses"),
                result.getInt("normal_matches_played"),
                result.getInt("elo"),
                result.getInt("peak_elo")
        );
    }

    private void upsertPlayerName(
            Connection connection,
            String uuid,
            String name,
            long seenAtMillis
    ) throws SQLException {
        try (
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO player_names "
                                + "(uuid, name, first_seen_ms, last_seen_ms) "
                                + "VALUES (?, ?, ?, ?) "
                                + "ON CONFLICT(uuid, name) DO UPDATE SET "
                                + "last_seen_ms = excluded.last_seen_ms"
                )
        ) {
            statement.setString(1, uuid);
            statement.setString(2, name);
            statement.setLong(3, seenAtMillis);
            statement.setLong(4, seenAtMillis);
            statement.executeUpdate();
        }
    }

    private List<String> playerNames(
            Connection connection,
            String uuid
    ) throws SQLException {
        List<String> names = new ArrayList<>();

        try (
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT name FROM player_names "
                                + "WHERE uuid = ? "
                                + "ORDER BY last_seen_ms DESC"
                )
        ) {
            statement.setString(1, uuid);

            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    names.add(rows.getString("name"));
                }
            }
        }

        return names;
    }

    public void useHistoryDatabase(File file) {
        if (file != null) {
            historyDatabaseFile = file;
        }
    }

    private Connection connect() throws SQLException {
        return connect(DATABASE_FILE);
    }

    private Connection connect(File file) throws SQLException {
        ensureSqliteDriver();

        return DriverManager.getConnection(
                "jdbc:sqlite:" + file.getPath()
        );
    }

    private void ensureSqliteDriver() throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException exception) {
            throw new SQLException("SQLite JDBC driver is missing.", exception);
        }
    }

    private interface DatabaseJob {
        void run() throws Exception;
    }

    private static final class ActiveSession {
        String lastName;
        long startedAtMillis;

        ActiveSession(String lastName, long startedAtMillis) {
            this.lastName = lastName;
            this.startedAtMillis = startedAtMillis;
        }
    }

    public record PlayerInfo(
            String uuid,
            String lastName,
            List<String> knownNames,
            long firstSeenMillis,
            long lastSeenMillis,
            long totalPlaytimeMillis,
            int rankedWins,
            int rankedLosses,
            int rankedMatchesPlayed,
            int normalWins,
            int normalLosses,
            int normalMatchesPlayed,
            int elo,
            int peakElo
    ) {
    }

    /**
     * One /history entry. mode is "ranked", "1v1", "teams" or "ffa"; FFA rows
     * carry every participant's display name (newline-joined) and no loser
     * columns. The elo before/after fields are only meaningful for "ranked"
     * rows; every other mode leaves them at 0.
     */
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
