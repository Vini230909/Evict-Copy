// Exercises required warning reasons, serial dialogs, reading time, reconnects and durable worker acknowledgements.
package Extinction;

import arc.Core;
import arc.Settings;
import mindustry.Vars;
import mindustry.core.NetServer;
import mindustry.gen.FollowUpMenuCallPacket;
import mindustry.gen.Groups;
import mindustry.gen.HideFollowUpMenuCallPacket;
import mindustry.gen.MenuCallPacket;
import mindustry.gen.MenuChooseCallPacket;
import mindustry.gen.Player;
import mindustry.gen.TextInputCallPacket2;
import mindustry.gen.TextInputResultCallPacket;
import mindustry.net.Net;
import mindustry.net.NetConnection;
import mindustry.net.Packet;
import mindustry.ui.Menus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class WarningsTest {
    static void require(boolean ok, String text) { if (!ok) throw new AssertionError(text); }

    public static void main(String[] args) throws Exception {
        Core.settings = new Settings();
        Vars.net = new Net(null) {
            @Override public boolean server() { return true; }
            @Override public void send(Object packet, boolean reliable) { }
        };
        Groups.init();
        Vars.netServer = new NetServer();
        long[] now = {0L};
        WarningList list = new WarningList(true, Path.of("config"));
        Warnings warnings = new Warnings(true, null, null, list, () -> now[0]);
        warnings.install();
        TestPlayer admin = new TestPlayer("Admin"), target = new TestPlayer("Target");
        admin.admin = true;
        int[] commands = {0}, choices = {0};
        Vars.netServer.clientCommands.<Player>register("probe", "test", (a,p) -> commands[0]++);
        int[] menuCommands = {0};
        Vars.netServer.clientCommands.<Player>register("info", "test", (a,p) -> menuCommands[0]++);
        int ordinary = Menus.registerMenu((player, option) -> choices[0]++);
        Dialogs.menu(target.con, ordinary, "Current menu", "Finish this first.", new String[][]{{"OK"}});
        Vars.netServer.clientCommands.handleMessage("/info", target);
        require(menuCommands[0] == 0, "an open dialog prevents repeated menu commands from overwriting its state");
        warn(warnings, admin, target, "Read the rules.");
        require(warnings.blocked(target) && target.warning == null, "warning waits for the current menu");
        require(list.entries().size() == 1, "one completed reason issues exactly one warning");
        choose(target, ordinary, 0);
        warnings.update();
        require(choices[0] == 1 && target.warning != null, "old menu closes before warning opens");
        int warningId = target.warning.menuId;
        require(target.warning.message.contains("Read the rules.") && target.warning.options[0][0].contains("3s"), "reason and reading countdown shown");
        choose(target, warningId, 0);
        require(warnings.blocked(target), "immediate acceptance refused by server");
        now[0] = 2_999_999_999L;
        choose(target, warningId, 0);
        require(warnings.blocked(target), "acceptance before three real seconds refused");
        Vars.netServer.clientCommands.handleMessage("/probe", target);
        require(commands[0] == 0, "commands cannot open another menu while warning pending");
        choose(target, ordinary, 0);
        require(choices[0] == 1, "stale menu callbacks cannot bypass the warning");
        int menusBefore = target.menus;
        Dialogs.menu(target.con, ordinary, "Later menu", "After warning.", new String[][]{{"OK"}});
        require(target.menus == menusBefore, "new ordinary dialog is deferred");
        now[0] = 3_000_000_000L;
        warnings.update();
        require(target.warning.options[0][0].equals("I understand"), "acknowledgement becomes available at three seconds");
        choose(target, warningId, 0);
        require(!warnings.blocked(target) && target.hidden == 1 && target.menus == menusBefore + 1, "acceptance closes warning then opens deferred menu");
        choose(target, ordinary, 0);

        warn(warnings, admin, target, "First queued warning.");
        int before = target.warnings;
        warn(warnings, admin, target, "Second queued warning.");
        require(target.warnings == before && target.warning.message.contains("First queued warning."), "a second warning never overlays the current warning");
        now[0] += 3_000_000_000L;
        choose(target, warningId, 0);
        require(warnings.blocked(target) && target.warning.message.contains("Second queued warning."), "next warning replaces the accepted one");
        choose(target, warningId, 0);
        require(warnings.blocked(target), "each queued warning gets its own reading timer");
        now[0] += 3_000_000_000L;
        choose(target, warningId, -1);
        choose(target, warningId, 0);
        require(warnings.blocked(target), "Escape reopens the warning and restarts reading time");

        warnings.handlePlayerLeave(target);
        target.remove();
        TestPlayer rejoined = new TestPlayer("Target");
        warnings.handlePlayerJoin(rejoined);
        warnings.update();
        require(rejoined.warning != null && warnings.blocked(rejoined), "reconnect cannot discard pending warning");
        choose(rejoined, warningId, 0);
        require(warnings.blocked(rejoined), "reconnect starts a fresh reading timer");
        WarningList restartedList = new WarningList(true, Path.of("config"));
        restartedList.refresh(entry -> { throw new AssertionError("existing warning must not log twice"); });
        require(restartedList.entries().values().stream().filter(entry -> !entry.accepted()).count() == 1,
                "pending and accepted warnings survive hub restart distinctly");
        now[0] += 3_000_000_000L;
        choose(rejoined, warningId, 0);
        require(!warnings.blocked(rejoined), "reconnected player can acknowledge after reading");
        warnings.consoleWarn("Target");
        require(rejoined.warning.message.contains("A moderator didn't like your action."), "console uses the required default reason");
        require(list.entries().values().stream().anyMatch(entry -> entry.actor().equals("Console")
                && entry.reason().equals("A moderator didn't like your action.")), "console reason and actor persist for logging");

        workerFiles();
        warningHistory();
        System.out.println("Warning checks passed.");
    }

    static void warningHistory() throws Exception {
        Path config = Path.of("history-fixtures");
        WarningList hubList = new WarningList(true, config);
        WarningList.Entry old = new WarningList.Entry(UUID.randomUUID().toString(), "history-uuid==", "Target",
                "Console", "hub", "Private old reason.", 1000L, false);
        require(hubList.add(old) && hubList.accept(old), "accepted history fixture persists");
        WarningList workerList = new WarningList(false, config);
        WarningList.Entry recent = new WarningList.Entry(UUID.randomUUID().toString(), "history-uuid==", "Target",
                "Moderator", "port-6572", "Private recent reason.", 2000L, false);
        require(workerList.add(recent), "uncommitted worker history fixture persists");
        Warnings worker = new Warnings(false, null, null, workerList, () -> 0L);
        List<Warnings.Summary> snapshot = worker.history("history-uuid==");
        require(snapshot.equals(List.of(new Warnings.Summary(2000L, "Moderator", "port-6572", false),
                new Warnings.Summary(1000L, "Console", "hub", true))),
                "history is newest first and combines accepted hub data with pending worker overlay");
        require(!snapshot.toString().contains("Private"), "history summary never exposes warning reasons");
        require(List.of(Warnings.Summary.class.getRecordComponents()).stream().map(component -> component.getName()).toList()
                .equals(List.of("timeMillis", "actor", "server", "accepted")), "summary exposes only audit metadata");
        try { snapshot.clear(); throw new AssertionError("history should be immutable"); }
        catch (UnsupportedOperationException expected) { }
        Warnings restartedHub = new Warnings(true, null, null, new WarningList(true, config), () -> 0L);
        require(restartedHub.history("history-uuid==").equals(snapshot), "restarted hub reads persisted history and ingests worker request");
        require(workerList.accept(recent), "worker history acknowledgement persists");
        require(worker.history("history-uuid==").get(0).accepted(), "local worker acceptance overlays pending hub state");
        require(restartedHub.history("history-uuid==").get(0).accepted(), "hub history refresh consumes worker acknowledgement");
        Warnings restartedWorker = new Warnings(false, null, null, new WarningList(false, config), () -> 0L);
        require(restartedWorker.history("history-uuid==").stream().allMatch(Warnings.Summary::accepted),
                "worker restart retains accepted warning history");
        require(!snapshot.get(0).accepted(), "previous snapshots remain unchanged after acceptance");
        require(worker.history("unknown").isEmpty() && worker.history(null).isEmpty(), "unmatched and blank accounts have no history");
    }

    static void warn(Warnings warnings, TestPlayer admin, TestPlayer target, String reason) {
        warnings.handleWarn(new String[0], admin);
        int option = 0, targetOption = -1;
        for (String[] row : admin.menu.options) for (String name : row) {
            if (name.equals(PlayerNames.displayName(target))) targetOption = option;
            option++;
        }
        require(targetOption >= 0, "warning target appears in picker");
        choose(admin, admin.menu.menuId, targetOption);
        if (reason.equals("Read the rules.")) {
            input(admin, admin.input.textInputId, "  ");
            require(admin.input.message.contains("A warning needs a reason"), "blank in-game reason is rejected");
        }
        input(admin, admin.input.textInputId, reason);
    }

    static void workerFiles() throws Exception {
        Path config = Path.of("worker-fixtures");
        WarningList worker = new WarningList(false, config);
        WarningList.Entry entry = new WarningList.Entry(UUID.randomUUID().toString(), "paddedUuid==", "[pink]Name",
                "Moderator", "port-6571", "Stop spamming.", 1234L, false);
        require(worker.add(entry), "worker saves a durable request");
        require(!Files.exists(config.resolve("evict-warnings.properties")), "worker never writes hub warning state");
        require(worker.accept(entry), "worker writes durable acknowledgement before hub ingests request");
        List<WarningList.Entry> logged = new ArrayList<>();
        WarningList hub = new WarningList(true, config);
        hub.refresh(logged::add);
        require(logged.size() == 1 && logged.get(0).server().equals("port-6571"), "worker warning logs once with originating port");
        require(hub.entries().get(entry.id()).accepted(), "hub ingests request before acknowledgement");
        worker.refresh(logged::add);
        require(worker.entries().get(entry.id()).accepted(), "worker follows accepted hub state");
        WarningList restartedHub = new WarningList(true, config);
        restartedHub.refresh(logged::add);
        require(logged.size() == 1 && restartedHub.entries().get(entry.id()).accepted(), "restart neither repeats warning nor logs twice");
        try (var files = Files.list(config.resolve("evict-warning-mailbox"))) {
            require(files.findAny().isEmpty(), "processed durable requests are drained");
        }
        Path blocked = Path.of("unwritable-config");
        Files.writeString(blocked, "not a directory");
        WarningList failed = new WarningList(true, blocked);
        require(!failed.add(entry) && failed.entries().isEmpty(), "failed save never claims to issue a warning");
    }

    static void choose(TestPlayer player, int id, int option) {
        MenuChooseCallPacket packet = new MenuChooseCallPacket();
        packet.menuId = id;
        packet.option = option;
        dispatch(player.con, packet);
    }

    static void input(TestPlayer player, int id, String text) {
        TextInputResultCallPacket packet = new TextInputResultCallPacket();
        packet.textInputId = id;
        packet.text = text;
        dispatch(player.con, packet);
    }

    @SuppressWarnings("unchecked")
    static void dispatch(NetConnection connection, Packet packet) {
        try {
            var field = Net.class.getDeclaredField("serverListeners");
            field.setAccessible(true);
            var listeners = (arc.struct.ObjectMap<Class<?>, arc.func.Cons2<NetConnection, Packet>>)field.get(Vars.net);
            listeners.get(packet.getClass()).get(connection, packet);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    static final class TestPlayer extends Player {
        MenuCallPacket menu;
        TextInputCallPacket2 input;
        FollowUpMenuCallPacket warning;
        int menus, warnings, hidden;
        TestPlayer(String uuid) {
            name = uuid;
            con = new NetConnection("127.0.0.1") {
                @Override public void send(Object packet, boolean reliable) {
                    if (packet instanceof MenuCallPacket value) { menu = value; menus++; }
                    if (packet instanceof TextInputCallPacket2 value) input = value;
                    if (packet instanceof FollowUpMenuCallPacket value) { warning = value; warnings++; }
                    if (packet instanceof HideFollowUpMenuCallPacket) hidden++;
                }
                @Override public void close() { }
            };
            con.uuid = uuid;
            con.player = this;
            add();
        }
        @Override public void sendMessage(String message) { }
    }
}
