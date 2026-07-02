package vini.evictmap;

import arc.Core;
import arc.util.Log;
import mindustry.game.Team;
import mindustry.gen.Groups;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Persistent player profile and statistics storage.
 * Database writes run on a single background thread. Game-world state is only
 * read on the server thread before small immutable write jobs are queued.
 */
final class PlayerDataManager {

    private static final File DATABASE_FILE =
            new File("config/evict-players.db");
    private static final int DEFAULT_ELO = 1000;

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
    private final Set<String> ffaParticipantsThisRound = new HashSet<>();

    void start() {
        enqueue(this::createSchema);

        Runtime.getRuntime().addShutdownHook(
                new Thread(this::shutdown, "EvictPlayerDataShutdown")
        );
    }

    void beginFfaRound() {
        synchronized (this) {
            long now = System.currentTimeMillis();
            flushActiveSessions(now);
            ffaParticipantsThisRound.clear();

            for (ActiveSession session : activeSessionsByUuid.values()) {
                session.startedAtMillis = now;
                session.ffaParticipant = false;
            }
        }
    }

    void handlePlayerJoin(Player player) {
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

    void handlePlayerLeave(Player player) {
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

    void recordConnectedFfaParticipants(TeamManager teamManager) {
        Groups.player.each(player -> {
            if (
                    teamManager.isPersonalRoundPlayer(player)
            ) {
                recordFfaParticipation(player);
            }
        });
    }

    void recordFfaWinner(TeamManager teamManager, Team winner) {
        if (
                winner == null
                        || winner == TeamManager.FALLEN_TEAM
                        || winner == Team.derelict
        ) {
            return;
        }

        List<String> winnerUuids = teamManager.playerUuidsForTeam(winner);

        synchronized (this) {
            for (String uuid : winnerUuids) {
                if (ffaParticipantsThisRound.contains(uuid)) {
                    enqueue(() -> incrementFfaWins(uuid));
                }
            }
        }
    }

    /**
     * Records a finished 1v1 duel: a win for the winner, a loss for the loser,
     * a played match for both, and one row in the match history. ELO counters
     * are intentionally left untouched. Called on the hub once a worker reports
     * its result; no-op if either UUID is missing (e.g. the worker could not
     * identify a winner).
     */
    void recordRankedResult(
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
     * Records a finished FFA as an unranked history entry: one row listing
     * every participant, a win only in the sense of /history rendering. No
     * ranked counters or ELO are touched. Called on the hub once a worker
     * reports an FFA result.
     */
    void recordFfaMatch(
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

        enqueue(() -> insertFfaMatch(
                playedAtMillis,
                winnerUuid,
                safeWinnerName,
                uuidsPacked,
                namesPacked
        ));
    }

    /**
     * Records a finished Teams match as an unranked history entry: the two
     * rosters and who won. No ranked counters or ELO are touched. Called on
     * the hub once a worker reports a Teams result.
     */
    void recordTeamsMatch(
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

        enqueue(() -> insertTeamsMatch(
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
    void findDuelHistory(
            String uuid,
            Consumer<List<DuelMatch>> callback
    ) {
        enqueue(() -> deliver(callback, loadDuelHistory(uuid)));
    }

    void searchPlayerInfo(
            String query,
            Consumer<List<PlayerInfo>> callback
    ) {
        enqueue(() -> deliver(callback, searchPlayerInfo(query)));
    }

    private void recordFfaParticipation(Player player) {
        String uuid = player.uuid();
        String name = player.plainName();
        long now = System.currentTimeMillis();
        boolean newlyParticipating;

        synchronized (this) {
            ActiveSession session =
                    activeSessionsByUuid.computeIfAbsent(
                            uuid,
                            ignored -> new ActiveSession(name, now)
                    );

            session.lastName = name;
            session.ffaParticipant = true;
            newlyParticipating = ffaParticipantsThisRound.add(uuid);
        }

        enqueue(() -> upsertPlayer(uuid, name, now));

        if (newlyParticipating) {
            enqueue(() -> incrementFfaPlayed(uuid));
        }
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
        long ffaPlayedMillis = session.ffaParticipant ? playedMillis : 0L;
        String name = session.lastName;

        session.startedAtMillis = finishedAtMillis;

        enqueue(
                () -> addPlaytime(
                        uuid,
                        name,
                        finishedAtMillis,
                        playedMillis,
                        ffaPlayedMillis
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
                    "CREATE TABLE IF NOT EXISTS players ("
                            + "uuid TEXT PRIMARY KEY,"
                            + "last_name TEXT NOT NULL,"
                            + "first_seen_ms INTEGER NOT NULL,"
                            + "last_seen_ms INTEGER NOT NULL,"
                            + "total_playtime_ms INTEGER NOT NULL DEFAULT 0,"
                            + "ffa_playtime_ms INTEGER NOT NULL DEFAULT 0,"
                            + "ffa_played INTEGER NOT NULL DEFAULT 0,"
                            + "ffa_won INTEGER NOT NULL DEFAULT 0,"
                            + "ranked_playtime_ms INTEGER NOT NULL DEFAULT 0,"
                            + "ranked_wins INTEGER NOT NULL DEFAULT 0,"
                            + "ranked_losses INTEGER NOT NULL DEFAULT 0,"
                            + "ranked_matches_played INTEGER NOT NULL DEFAULT 0,"
                            + "elo INTEGER NOT NULL DEFAULT " + DEFAULT_ELO + ","
                            + "peak_elo INTEGER NOT NULL DEFAULT " + DEFAULT_ELO
                            + ")"
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
            // players being online. The elo columns default to 0 until the elo
            // feature lands. FFA rows additionally carry every participant
            // (uuids comma-joined, names newline-joined) and no loser columns.
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
                    statement,
                    "mode TEXT NOT NULL DEFAULT '1v1'"
            );
            addColumnIfMissing(
                    statement,
                    "participant_uuids TEXT NOT NULL DEFAULT ''"
            );
            addColumnIfMissing(
                    statement,
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
    }

    /**
     * Adds a column to duel_matches, ignoring the "duplicate column name"
     * error on databases that already have it. SQLite has no
     * ADD COLUMN IF NOT EXISTS.
     */
    private static void addColumnIfMissing(
            Statement statement,
            String columnDefinition
    ) {
        try {
            statement.executeUpdate(
                    "ALTER TABLE duel_matches ADD COLUMN " + columnDefinition
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
            long totalMillis,
            long ffaMillis
    ) throws SQLException {
        upsertPlayer(uuid, name, seenAtMillis);

        try (
                Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE players SET "
                                + "last_name = ?, "
                                + "last_seen_ms = ?, "
                                + "total_playtime_ms = total_playtime_ms + ?, "
                                + "ffa_playtime_ms = ffa_playtime_ms + ? "
                                + "WHERE uuid = ?"
                )
        ) {
            statement.setString(1, name);
            statement.setLong(2, seenAtMillis);
            statement.setLong(3, totalMillis);
            statement.setLong(4, ffaMillis);
            statement.setString(5, uuid);
            statement.executeUpdate();
        }
    }

    private void incrementFfaPlayed(String uuid) throws SQLException {
        try (
                Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE players SET ffa_played = ffa_played + 1 "
                                + "WHERE uuid = ?"
                )
        ) {
            statement.setString(1, uuid);
            statement.executeUpdate();
        }
    }

    private void incrementFfaWins(String uuid) throws SQLException {
        try (
                Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE players SET ffa_won = ffa_won + 1 "
                                + "WHERE uuid = ?"
                )
        ) {
            statement.setString(1, uuid);
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
            updateRankedOutcome(connection, winnerUuid, true);
            updateRankedOutcome(connection, loserUuid, false);
            insertDuelMatch(
                    connection,
                    playedAtMillis,
                    winnerUuid,
                    winnerName,
                    loserUuid,
                    loserName
            );
        }
    }

    private void insertDuelMatch(
            Connection connection,
            long playedAtMillis,
            String winnerUuid,
            String winnerName,
            String loserUuid,
            String loserName
    ) throws SQLException {
        try (
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO duel_matches "
                                + "(played_at_ms, winner_uuid, winner_name, "
                                + "loser_uuid, loser_name) "
                                + "VALUES (?, ?, ?, ?, ?)"
                )
        ) {
            statement.setLong(1, playedAtMillis);
            statement.setString(2, winnerUuid);
            statement.setString(3, winnerName);
            statement.setString(4, loserUuid);
            statement.setString(5, loserName);
            statement.executeUpdate();
        }
    }

    private void insertFfaMatch(
            long playedAtMillis,
            String winnerUuid,
            String winnerName,
            String participantUuidsPacked,
            String participantNamesPacked
    ) throws SQLException {
        try (
                Connection connection = connect();
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
     * Teams rows pack the whole winning/losing rosters into the winner/loser
     * uuid columns (comma-joined) so /history can tell which side the picked
     * player was on; the name columns hold the ready-made team labels.
     */
    private void insertTeamsMatch(
            long playedAtMillis,
            String winnerUuidsPacked,
            String winnerTeamLabel,
            String loserUuidsPacked,
            String loserTeamLabel
    ) throws SQLException {
        try (
                Connection connection = connect();
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
                                + "participant_names FROM duel_matches "
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
                            rows.getString("participant_names")
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
        try (
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE players SET "
                                + (won ? "ranked_wins = ranked_wins + 1, "
                                : "ranked_losses = ranked_losses + 1, ")
                                + "ranked_matches_played = ranked_matches_played + 1 "
                                + "WHERE uuid = ?"
                )
        ) {
            statement.setString(1, uuid);
            statement.executeUpdate();
        }
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
        long liveFfaMillis;

        synchronized (this) {
            ActiveSession session = activeSessionsByUuid.get(uuid);

            if (session == null) {
                liveTotalMillis = 0L;
                liveFfaMillis = 0L;
            } else {
                liveTotalMillis =
                        Math.max(0L, now - session.startedAtMillis);
                liveFfaMillis =
                        session.ffaParticipant ? liveTotalMillis : 0L;
            }
        }

        return new PlayerInfo(
                uuid,
                result.getString("last_name"),
                playerNames(connection, uuid),
                result.getLong("first_seen_ms"),
                result.getLong("last_seen_ms"),
                result.getLong("total_playtime_ms") + liveTotalMillis,
                result.getLong("ffa_playtime_ms") + liveFfaMillis,
                result.getInt("ffa_played"),
                result.getInt("ffa_won"),
                result.getLong("ranked_playtime_ms"),
                result.getInt("ranked_wins"),
                result.getInt("ranked_losses"),
                result.getInt("ranked_matches_played"),
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

    void useHistoryDatabase(File file) {
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
        boolean ffaParticipant;

        ActiveSession(String lastName, long startedAtMillis) {
            this.lastName = lastName;
            this.startedAtMillis = startedAtMillis;
        }
    }

    record PlayerInfo(
            String uuid,
            String lastName,
            List<String> knownNames,
            long firstSeenMillis,
            long lastSeenMillis,
            long totalPlaytimeMillis,
            long ffaPlaytimeMillis,
            int ffaPlayed,
            int ffaWon,
            long rankedPlaytimeMillis,
            int rankedWins,
            int rankedLosses,
            int rankedMatchesPlayed,
            int elo,
            int peakElo
    ) {
    }

    /**
     * One /history entry. mode is "1v1" or "ffa"; FFA rows carry every
     * participant's display name (newline-joined) and no loser columns.
     */
    record DuelMatch(
            long playedAtMillis,
            String winnerUuid,
            String winnerName,
            String loserUuid,
            String loserName,
            String mode,
            String participantNamesPacked
    ) {
    }
}
