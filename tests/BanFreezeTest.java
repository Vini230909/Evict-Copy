// Exercises reason prompts, independent holds, reconnections, timeout, filters and command mirroring.
package Extinction;

import arc.Core;
import arc.Settings;
import mindustry.Vars;
import mindustry.core.NetServer;
import mindustry.gen.*;
import mindustry.net.*;
import mindustry.ui.Menus;
import java.lang.reflect.Field;
import java.util.*;

public final class BanFreezeTest {
    static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        Core.settings = new Settings();
        Vars.net = new Net(null) {
            @Override public boolean server() { return true; }
            @Override public void send(Object packet, boolean reliable) { }
        };
        Groups.init();
        Vars.netServer = new NetServer();
        Vars.content = new mindustry.core.ContentLoader();
        Vars.state = new mindustry.core.GameState();
        mindustry.content.StatusEffects.load();
        TestPlayer admin = new TestPlayer("Admin"), other = new TestPlayer("Other"), target = new TestPlayer("Target");
        admin.admin = other.admin = true;
        List<Bans.Request> bans = new ArrayList<>();
        BanMenu menu = new BanMenu(null, true, bans::add);
        menu.freeze.install();
        List<String> mirrored = new ArrayList<>();
        ChatLogCapture mirror = new ChatLogCapture(mirrored::add);
        mirror.suppress(menu.freeze::frozen);
        mirror.installChatFilter();
        int[] ran = {0};
        Vars.netServer.clientCommands.<Player>register("probe", "test", (a,p) -> ran[0]++);
        UnitEntity unit = UnitEntity.create();
        unit.type = new mindustry.type.UnitType("freeze-test-unit");
        unit.health = 100;
        target.testUnit = unit;
        unit.vel.set(5, 6);
        unit.isShooting = target.shooting = true;
        open(menu, admin, target);
        require(unit.hasEffect(mindustry.content.StatusEffects.unmoving) && unit.hasEffect(mindustry.content.StatusEffects.disarmed),
                "real vanilla statuses applied");
        require(unit.vel.isZero() && !unit.isShooting && !target.shooting, "movement inertia and shooting stopped");
        require(menu.freeze.frozen(target), "opening prompt freezes target");
        menu.freeze.update();
        require(target.messages.size() == 1, "notice appears only once");
        Menus.textInputResult(admin, admin.input.textInputId, "  ");
        require(menu.freeze.frozen(target) && admin.input.message.contains("A ban needs a reason"), "empty reason keeps hold");
        require(Vars.netServer.admins.filterMessage(target, "hello") == null && mirrored.isEmpty(), "chat never reaches mirror");
        mirror.handleRawMessage(target, "/probe");
        Vars.netServer.clientCommands.handleMessage("/probe", target);
        require(mirrored.isEmpty() && ran[0] == 0, "command neither runs nor mirrors");
        for (Administration.ActionType type : Administration.ActionType.values()) {
            require(Vars.netServer.admins.allowAction(target, type, a -> {}) == (type == Administration.ActionType.respawn),
                    "action gated: " + type);
        }
        open(menu, other, target);
        Menus.textInputResult(admin, admin.input.textInputId, null);
        require(menu.freeze.frozen(target), "second admin still holds");
        target.remove();
        menu.handlePlayerLeave(target);
        TestPlayer rejoined = new TestPlayer("Target");
        menu.freeze.update();
        require(menu.freeze.frozen(rejoined) && rejoined.messages.size() == 1, "rejoin held with notice");
        menu.handlePlayerLeave(other);
        require(!menu.freeze.frozen(rejoined), "admin departure releases last hold");
        require(unit.getDuration(mindustry.content.StatusEffects.unmoving) == 0f, "old unit released on disconnect");
        long[] now = {0};
        BanFreeze timed = new BanFreeze(() -> now[0]);
        timed.begin("a", "Target");
        now[0] = 60_000;
        timed.begin("b", "Target");
        now[0] = 120_000;
        timed.update();
        require(timed.frozen(rejoined), "second hold outlives first timeout");
        now[0] = 180_000;
        timed.update();
        require(!timed.frozen(rejoined), "last timeout releases");
        open(menu, admin, rejoined);
        Field holds = BanFreeze.class.getDeclaredField("holds");
        holds.setAccessible(true);
        ((Map<?,?>) holds.get(menu.freeze)).clear();
        menu.freeze.update();
        Menus.textInputResult(admin, admin.input.textInputId, "late reason");
        require(bans.size() == 1 && bans.get(0).uuid().equals("Target"), "late answer still bans");
        Vars.netServer.clientCommands.handleMessage("/probe", rejoined);
        require(ran[0] == 1, "commands resume");
        System.out.println("Ban freeze checks passed.");
    }
    static void open(BanMenu menu, TestPlayer admin, TestPlayer target) {
        menu.handleBan(new String[0], admin);
        int index = 0;
        for (String[] row : admin.menu.options) for (String cell : row) {
            if (cell.equals(PlayerNames.displayName(target))) { Menus.menuChoose(admin, admin.menu.menuId, index); return; }
            index++;
        }
        throw new AssertionError("target missing");
    }
    static final class TestPlayer extends Player {
        Unit testUnit;
        @Override public Unit unit() { return testUnit == null ? super.unit() : testUnit; }
        @Override public boolean dead() { return testUnit == null || testUnit.dead; }
        List<String> messages = new ArrayList<>();
        MenuCallPacket menu;
        TextInputCallPacket2 input;
        TestPlayer(String id) {
            name = id;
            con = new NetConnection("127.0.0.1") {
                @Override public void send(Object packet, boolean reliable) {
                    if (packet instanceof MenuCallPacket m) menu = m;
                    if (packet instanceof TextInputCallPacket2 t) input = t;
                }
                @Override public void close() {}
            };
            con.uuid = id;
            add();
        }
        @Override public void sendMessage(String message) { messages.add(message); }
    }
}
