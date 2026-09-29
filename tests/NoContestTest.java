// Exercises worker ban paths and the referee's decision boundary without launching a game server.
package Extinction;

import arc.Core;
import arc.Events;
import arc.Settings;
import mindustry.Vars;
import mindustry.core.*;
import mindustry.gen.*;
import mindustry.net.*;
import java.io.File;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;

public final class NoContestTest {
    static final List<String> messages = new ArrayList<>();
    static Referee referee;
    static int checks;
    static void require(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        System.setProperty("evict.duelWorker", "true");
        for (MatchMode mode : MatchMode.values()) {
            for (String phase : List.of("waiting", "countdown", "running", "paused")) {
                setup(mode, phase);
                WorkerBans bans = new WorkerBans(referee, new BanScreen(null));
                bans.install();
                TestPlayer target = new TestPlayer("alice", "10.0.0.1");
                bans.ban(Bans.Request.admin("alice", Bans.Origin.now("Admin", "test", "PRIVATE REASON")));
                if (mode.solo()) require(!referee.resolved(), "solo unaffected: " + mode);
                else verify(mode, "Alice");
                stop();
            }
        }
        setup(MatchMode.RANKED, "running");
        new TestPlayer("alice", "10.0.0.1");
        WorkerBans bans = new WorkerBans(referee, new BanScreen(null));
        bans.install();
        Vars.netServer.admins.banPlayerID("alice");
        verify(MatchMode.RANKED, "Alice");
        stop();

        setup(MatchMode.RANKED, "running");
        TestPlayer filtered = new TestPlayer("alice", "10.0.0.1");
        WorkerBans filterBans = new WorkerBans(referee, new BanScreen(null));
        filterBans.install();
        WordFilter filter = new WordFilter(false, filterBans::ban, new BanScreen(null));
        Config.wordFilter = true;
        var punish = WordFilter.class.getDeclaredMethod("punish", Player.class, String.class, WordFilter.Hit.Source.class, String.class);
        punish.setAccessible(true);
        punish.invoke(filter, filtered, "test", WordFilter.Hit.Source.CHAT, "test");
        verify(MatchMode.RANKED, "Alice");
        stop();

        setup(MatchMode.TEAMS, "running");
        new TestPlayer("alice", "10.0.0.1");
        bans = new WorkerBans(referee, new BanScreen(null));
        bans.install();
        Vars.netServer.admins.banPlayerIP("10.0.0.1");
        verify(MatchMode.TEAMS, "Alice");
        stop();

        for (boolean eliminated : List.of(false, true)) {
            setup(MatchMode.FFA, "paused");
            if (eliminated) referee.demoteToSpectator("alice");
            bans = new WorkerBans(referee, new BanScreen(null));
            BanList.write(BanList.WORKER_VIEW_FILE, Set.of("alice"), Set.of());
            bans.update();
            verify(MatchMode.FFA, "Alice");
            require(referee.outUuids().contains("alice") == eliminated, "eliminated roster preserved");
            stop();
        }
        setup(MatchMode.PURE_2TEAM, "paused");
        Vars.netServer.admins.getInfo("alice").lastIP = "10.0.0.1";
        bans = new WorkerBans(referee, new BanScreen(null));
        BanList.write(BanList.WORKER_VIEW_FILE, Set.of(), Set.of("10.0.0.1"));
        bans.update();
        verify(MatchMode.PURE_2TEAM, "Alice");
        stop();

        setup(MatchMode.ONE_VS_ONE, "running");
        new TestPlayer("alice", "10.0.0.1");
        bans = new WorkerBans(referee, new BanScreen(null));
        BanList.write(BanList.WORKER_VIEW_FILE, Set.of(), Set.of("10.0.0.1"));
        bans.update();
        verify(MatchMode.ONE_VS_ONE, "Alice");
        stop();

        setup(MatchMode.RANKED, "countdown");
        referee.bans.check(new BanList.Snapshot(Set.of("viewer"), Set.of("10.0.9.9")));
        require(!referee.resolved(), "spectator bans change nothing");
        MatchResult normal = new MatchResult("ranked", List.of("alice"), List.of("bob"), "victory");
        normal.write(new File("result.properties"));
        set(referee, "resolved", true);
        referee.handleBan("alice");
        require(!MatchResult.read(new File("result.properties")).noContest(), "later ban cannot replace decided result");
        stop();

        setup(MatchMode.RANKED, "running");
        BanList.write(BanList.WORKER_VIEW_FILE, Set.of("alice"), Set.of());
        referee.handleVictory(mindustry.game.Team.sharded);
        verify(MatchMode.RANKED, "Alice");
        stop();
        System.out.println("No contest checks passed: " + checks);
    }
    static void setup(MatchMode mode, String phase) throws Exception {
        Events.clear();
        Core.settings = new Settings();
        Vars.net = new Net(null) {
            @Override public boolean server() { return true; }
            @Override public void send(Object packet, boolean reliable) {
                if (packet instanceof SendMessageCallPacket p) messages.add(p.message);
            }
        };
        Groups.init();
        Vars.state = new GameState();
        Vars.netServer = new NetServer();
        referee = new Referee();
        referee.rosterTeams().add(List.of("alice"));
        referee.rosterTeams().add(List.of("bob"));
        referee.participantUuids().addAll(List.of("alice", "bob"));
        referee.pause.rememberNames(Map.of("alice", "Alice", "bob", "Bob"));
        set(referee, "handshakeLoaded", true);
        set(referee, "mode", mode);
        set(referee.gate, "countdownStarted", phase.equals("countdown"));
        set(referee.gate, "matchStarted", phase.equals("running") || phase.equals("paused"));
        set(referee.pause, "paused", phase.equals("paused"));
        Vars.state.set(phase.equals("running") ? GameState.State.playing : GameState.State.paused);
        Files.deleteIfExists(new File("result.properties").toPath());
        BanList.write(BanList.WORKER_VIEW_FILE, Set.of(), Set.of());
        messages.clear();
    }
    static void verify(MatchMode mode, String name) throws Exception {
        require(referee.resolved() && !referee.pause.active() && !referee.gate.countingDown(), "resolved with pause/countdown cancelled");
        require(Vars.state.isPlaying(), "game released for return");
        MatchResult result = MatchResult.read(new File("result.properties"));
        require(result.noContest() && result.modeId("").equals(mode.id()), "outcome separate from actual mode");
        require(result.winnerUuids().isEmpty() && result.loserUuids().isEmpty(), "no winners or losers");
        require(result.bannedUuid().equals("alice"), "ban subject preserved");
        require(messages.stream().anyMatch(s -> s.contains(name + " was banned. No contest - nobody wins or loses points. Returning to the lobby in 5 seconds...")), "exact announcement");
        require(messages.stream().noneMatch(s -> s.contains("PRIVATE REASON")), "reason never broadcast");
        referee.handleVictory(mindustry.game.Team.sharded);
        require(MatchResult.read(new File("result.properties")).noContest(), "later victory cannot overwrite");
    }
    static void stop() throws Exception { ((ScheduledExecutorService) get(referee, "scheduler")).shutdownNow(); }
    static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    static class TestPlayer extends Player {
        TestPlayer(String account, String ip) {
            name = account.equals("alice") ? "Alice" : account;
            con = new NetConnection(ip) {
                @Override public void send(Object packet, boolean reliable) { }
                @Override public void close() { }
                @Override public void kick(String reason) {
                    kicked = true;
                    if (!referee.matchMode().solo() && !reason.contains("word")) require(referee.resolved(), "resolved before kick");
                    referee.handlePlayerLeave(TestPlayer.this);
                }
            };
            con.uuid = account;
            add();
        }
    }
}
