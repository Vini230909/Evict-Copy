// Checks SQLite history reads, existing result writes and the one-time stats replay after the move.
package Extinction;

import Extinction.data.PlayerDataManager;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

public final class MatchHistoryTest {
    static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
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
        try (Connection c = connect(); Statement s = c.createStatement()) { s.execute("PRAGMA user_version = 0"); }
        data.history.repairStatsIfNeeded();
        checkStats(3, 1);
        require(history(data, "alice").size() == 4, "repair keeps history");
        System.out.println("Match history checks passed.");
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

