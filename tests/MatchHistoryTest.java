// Checks SQLite history reads, existing result writes and the one-time stats replay after the move.
package Extinction;

import Extinction.data.PlayerDataManager;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

public final class MatchHistoryTest {
    static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("config"));
        try (Connection c = connect(); Statement statement = c.createStatement()) {
            statement.execute("CREATE TABLE duel_matches (id INTEGER PRIMARY KEY AUTOINCREMENT, played_at_ms INTEGER NOT NULL, "
                    + "winner_uuid TEXT NOT NULL, winner_name TEXT NOT NULL, loser_uuid TEXT NOT NULL, loser_name TEXT NOT NULL, "
                    + "winner_elo_before INTEGER NOT NULL DEFAULT 0, winner_elo_after INTEGER NOT NULL DEFAULT 0, "
                    + "loser_elo_before INTEGER NOT NULL DEFAULT 0, loser_elo_after INTEGER NOT NULL DEFAULT 0)");
        }
        PlayerDataManager data = new PlayerDataManager();
        data.start();
        for (String account : List.of("alice", "bob")) data.handlePlayerJoin(new mindustry.gen.Player() {
            @Override public String uuid() { return account; }
            @Override public String plainName() { return account; }
        });
        data.recordRankedResult("alice", "Alice", "bob", "Bob");
        data.recordCasualDuelResult("alice", "Alice", "bob", "Bob");
        data.recordFfaMatch("alice", "Alice", List.of("alice", "bob"), List.of("Alice", "Bob"));
        data.recordTeamsMatch(List.of("alice"), "Alice", List.of("bob"), "Bob");
        List<MatchHistory.DuelMatch> history = history(data, "bob");
        require(history.size() == 4, "all existing modes found in history");
        require(history.stream().map(MatchHistory.DuelMatch::mode).collect(java.util.stream.Collectors.toSet())
                .equals(Set.of("1v1", "ranked", "teams", "ffa")), "wire modes unchanged");
        checkStats(3, 1);
        for (MatchMode mode : MatchMode.values()) {
            if (mode == MatchMode.RANDOM_TEAMS) continue;
            report(data, mode);
        }
        var withNoContest = history(data, "bob");
        require(withNoContest.size() == 8, "four recorded no contests, Pure and solo have no rows");
        require(history(data, "alice").size() == 8, "every roster member has history");
        var format = History.class.getDeclaredMethod("formatMatch", String.class, MatchHistory.DuelMatch.class);
        format.setAccessible(true);
        History view = new History(data);
        for (var match : withNoContest) {
            if (!match.outcome().equals("no-contest")) continue;
            String text = (String) format.invoke(view, "bob", match);
            require(text.contains("no contest (a player was banned)"), "history outcome text");
            require(!text.contains("[green]win") && !text.contains("[scarlet]lose"), "history has no win or loss");
            if (match.mode().equals("ranked")) require(text.contains("elo: [][gray]0"), "Ranked zero elo delta");
        }
        checkStats(3, 1);
        try (Connection c = connect(); Statement s = c.createStatement()) { s.execute("PRAGMA user_version = 0"); }
        data.history.repairStatsIfNeeded();
        checkStats(3, 1);
        require(history(data, "alice").size() == 8, "repair keeps history");
        PlayerDataManager worker = new PlayerDataManager();
        worker.useHubDatabase(new java.io.File("config/evict-players.db"));
        worker.history.recordNoContest(MatchMode.RANKED, List.of("alice", "bob"), List.of("Alice", "Bob"));
        require(history(worker, "alice").size() == 8, "worker cannot write history");
        System.out.println("Match history checks passed.");
    }
    @SuppressWarnings("unchecked")
    static void report(PlayerDataManager data, MatchMode mode) throws Exception {
        MatchSlot slot = new MatchSlot(6700);
        slot.mode = mode;
        slot.participants.add(new MatchSlot.Participant("alice", "Alice", "Alice", 0));
        slot.participants.add(new MatchSlot.Participant("bob", "Bob", "Bob", 1));
        var chat = new Extinction.discord.ChatLogReporter(null);
        Class<?> channelType = Class.forName("Extinction.discord.ChatLogReporter$Channel");
        var constructor = channelType.getDeclaredConstructor(String.class, String.class, java.net.http.HttpClient.class);
        constructor.setAccessible(true);
        Object channel = constructor.newInstance("test", "", null);
        var ports = chat.getClass().getDeclaredField("byPort");
        ports.setAccessible(true);
        ((Map<Integer,Object>) ports.get(chat)).put(slot.port, channel);
        var result = new java.io.File(WorkerFolder.dir(slot.port), "result.properties");
        MatchResult.noContest(mode.id(), "bob").write(result);
        new WorkerReports(data, r -> {}, chat, null, port -> slot).logResult(slot);
        require(!result.exists() && slot.endReported, "report consumed once");
        var pending = channelType.getDeclaredField("pending");
        pending.setAccessible(true);
        var entries = (java.util.Deque<?>) pending.get(channel);
        require(entries.size() == 1, "exactly one end embed and no Elo line");
        Object entry = entries.peek();
        var payload = entry.getClass().getDeclaredField("payload");
        payload.setAccessible(true);
        String json = (String) payload.get(entry);
        require(json.contains("No contest - Bob was banned."), "Discord wording");
        require(!json.contains("Winner") && !json.contains("Loser"), "Discord has no winner or loser");
    }
    static Connection connect() throws Exception { return DriverManager.getConnection("jdbc:sqlite:config/evict-players.db"); }
    static List<MatchHistory.DuelMatch> history(PlayerDataManager data, String uuid) throws Exception {
        CompletableFuture<List<MatchHistory.DuelMatch>> f = new CompletableFuture<>();
        data.history.findDuelHistory(uuid, f::complete);
        return f.get(5, TimeUnit.SECONDS);
    }
    static void checkStats(int normal, int ranked) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT * FROM players")) {
            int count = 0;
            while (r.next()) {
                count++;
                boolean winner = r.getString("uuid").equals("alice");
                require(r.getInt("normal_matches_played") == normal && r.getInt("ranked_matches_played") == ranked,
                        "match counters");
                require(r.getInt(winner ? "normal_wins" : "normal_losses") == normal, "normal outcome count");
                require(r.getInt(winner ? "ranked_wins" : "ranked_losses") == ranked, "ranked outcome count");
                require(r.getInt("elo") == (winner ? Elo.apply(1000, 1000).winnerAfter() : Elo.apply(1000,1000).loserAfter()), "rating replay");
            }
            require(count == 2, "two profiles");
        }
    }
}

