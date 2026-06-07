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
 *
 * Database writes run on a single background thread. Game-world state is only
 * read on the server thread before small immutable write jobs are queued.
 */
final class PlayerDataManager {

    private static final File DATABASE_FILE =
        new File("config/evict-players.db");
    private static final int DEFAULT_ELO = 1000;

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
                player != null
                    && teamManager.isPersonalRoundPlayer(player)
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

    void findPlayerInfoByUuid(
        String uuid,
        Consumer<PlayerInfo> callback
    ) {
        enqueue(() -> deliver(callback, loadPlayerInfoByUuid(uuid)));
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
        }

        Log.info(
            "[EvictMapGenerator] Player data storage ready: @",
            DATABASE_FILE.getPath()
        );
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

    private PlayerInfo loadPlayerInfoByUuid(String uuid) throws SQLException {
        try (
            Connection connection = connect();
            PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM players WHERE uuid = ?"
            )
        ) {
            statement.setString(1, uuid);

            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? playerInfo(connection, result) : null;
            }
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

        return new PlayerInfo(
            uuid,
            result.getString("last_name"),
            playerNames(connection, uuid),
            result.getLong("first_seen_ms"),
            result.getLong("last_seen_ms"),
            result.getLong("total_playtime_ms"),
            result.getLong("ffa_playtime_ms"),
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

    private Connection connect() throws SQLException {
        ensureSqliteDriver();

        return DriverManager.getConnection(
            "jdbc:sqlite:" + DATABASE_FILE.getPath()
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
}
