// Checks that moderation metadata in /info reaches current admins only, including delayed queries and player pickers.
package Extinction;

import Extinction.data.PlayerDataManager;
import arc.Core;
import arc.Settings;
import arc.mock.MockApplication;
import mindustry.Vars;
import mindustry.core.NetServer;
import mindustry.gen.Groups;
import mindustry.gen.MenuCallPacket;
import mindustry.gen.Player;
import mindustry.net.Net;
import mindustry.net.NetConnection;
import mindustry.ui.Menus;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

public final class PlayerModerationInfoTest {
    static final String TARGET_UUID = "AAAAAAAAAAA=";
    static final String SECRET_REASON = "PRIVATE WARNING REASON MUST NEVER APPEAR IN INFO";
    static final BlockingQueue<Runnable> delivered = new LinkedBlockingQueue<>();
    static int checks;
    static void require(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }

    public static void main(String[] args) throws Exception {
        Core.settings = new Settings();
        Core.app = new MockApplication() {
            @Override public void post(Runnable runnable) { delivered.add(runnable); }
        };
        Vars.net = new Net(null) {
            @Override public boolean server() { return true; }
            @Override public void send(Object packet, boolean reliable) { }
        };
        Groups.init();
        Vars.netServer = new NetServer();
        TestPlayer admin = new TestPlayer("admin", "Admin");
        admin.admin = true;
        TestPlayer regular = new TestPlayer("regular", "Regular");
        TestPlayer target = new TestPlayer(TARGET_UUID, "Target");
        TestPlayer other = new TestPlayer("AQEBAQEBAQE=", "Target Other");
        PlayerDataManager data = new PlayerDataManager();
        data.start();
        for (TestPlayer player : List.of(target, other)) {
            data.handlePlayerJoin(player);
            data.handlePlayerLeave(player);
        }
        PlayerDataManager.PlayerInfo[] stored = {null};
        data.findPlayerInfoByUuid(TARGET_UUID, info -> stored[0] = info);
        callback().run();
        require(stored[0] != null, "fixture stored player exists");
        Mutes mutes = new Mutes(true, data, null, Path.of("config"));
        mutes.install();
        mutes.mute(TARGET_UUID, "Console");
        WarningList list = new WarningList(true, Path.of("config"));
        WarningList.Entry first = entry("Mod One", "2026-10-18T22:15:30Z", false);
        WarningList.Entry second = entry("Mod [label]", "2026-10-19T08:05:07Z", true);
        require(list.add(first) && list.add(second), "pending and accepted warning fixture saved");
        Warnings warnings = new Warnings(true, data, null, list, System::nanoTime);
        PlayerStats stats = new PlayerStats(data, mutes, warnings);

        stats.show(regular, TARGET_UUID);
        callback().run();
        publicOnly(regular, stored[0], "normal viewer direct lookup");
        stats.show(admin, TARGET_UUID);
        callback().run();
        adminInfo(admin, stored[0], true, "admin direct lookup");

        admin.messages.clear();
        stats.show(admin, TARGET_UUID);
        Runnable pending = callback();
        admin.admin = false;
        pending.run();
        publicOnly(admin, stored[0], "admin revoked while direct query pending");
        admin.admin = true;

        for (TestPlayer viewer : List.of(regular, admin)) {
            viewer.messages.clear();
            Dialogs.forget(viewer);
            stats.show(viewer, "Target");
            callback().run();
            choose(viewer, "Target");
            callback().run();
            if (viewer.admin) adminInfo(viewer, stored[0], true, "ambiguous name picker admin");
            else publicOnly(viewer, stored[0], "ambiguous name picker normal viewer");
        }

        admin.messages.clear();
        Dialogs.forget(admin);
        stats.show(admin, "");
        choose(admin, PlayerNames.displayName(target));
        pending = callback();
        admin.admin = false;
        pending.run();
        publicOnly(admin, stored[0], "admin revoked while online picker lookup pending");
        admin.admin = true;

        admin.messages.clear();
        stats.show(admin, TARGET_UUID);
        pending = callback();
        mutes.unmute(TARGET_UUID, "Console");
        pending.run();
        adminInfo(admin, stored[0], false, "mute state reflects delivery time");
        Method plainLine = PlayerStats.class.getDeclaredMethod("plainLine", PlayerDataManager.PlayerInfo.class);
        plainLine.setAccessible(true);
        String console = (String) plainLine.invoke(null, stored[0]);
        require(!console.contains("Muted:") && !console.contains("Warnings:") && !console.contains(SECRET_REASON),
                "console playerinfo remains unchanged");
        System.out.println("Player moderation info checks passed: " + checks);
    }

    static WarningList.Entry entry(String actor, String time, boolean accepted) {
        return new WarningList.Entry(UUID.randomUUID().toString(), TARGET_UUID, "Target", actor, "port-6568",
                SECRET_REASON, Instant.parse(time).toEpochMilli(), accepted);
    }

    static Runnable callback() throws Exception {
        Runnable runnable = delivered.poll(5, TimeUnit.SECONDS);
        require(runnable != null, "database callback delivered");
        return runnable;
    }

    static String original(PlayerDataManager.PlayerInfo info, boolean admin) throws Exception {
        Method format = PlayerStats.class.getDeclaredMethod("format", PlayerDataManager.PlayerInfo.class, boolean.class);
        format.setAccessible(true);
        return (String) format.invoke(null, info, admin);
    }

    static void publicOnly(TestPlayer viewer, PlayerDataManager.PlayerInfo info, String context) throws Exception {
        require(viewer.messages.size() == 1 && viewer.messages.get(0).equals(original(info, false)), context + ": original public text only");
        String text = String.join("\n", viewer.messages);
        require(!text.contains("Muted:") && !text.contains("Warnings:") && !text.contains("UUID:")
                && !text.contains(SECRET_REASON) && !text.contains("Mod One"), context + ": no moderation metadata or UUID");
        viewer.messages.clear();
    }

    static void adminInfo(TestPlayer viewer, PlayerDataManager.PlayerInfo info, boolean muted, String context) throws Exception {
        require(viewer.messages.size() >= 2 && viewer.messages.get(0).equals(original(info, true)), context + ": original admin stats remain first");
        String text = String.join("\n", viewer.messages);
        String visible = arc.util.Strings.stripColors(text);
        require(visible.contains("Muted: " + (muted ? "yes" : "no")), context + ": current mute state visible");
        require(visible.contains("Warnings: 2"), context + ": all warnings counted");
        require(text.contains("2026-10-18 22:15:30 UTC") && text.contains("Mod One"), context + ": pending warning date and issuer");
        require(text.contains("2026-10-19 08:05:07 UTC") && text.contains("Mod [[label]"), context + ": accepted warning date and escaped issuer");
        require(text.contains("accepted") && text.contains("pending") && text.contains("port-6568"), context + ": both warning states and origin");
        require(!text.contains(SECRET_REASON), context + ": warning reasons excluded");
        viewer.messages.clear();
    }

    static void choose(TestPlayer viewer, String label) {
        MenuCallPacket menu = viewer.menu;
        require(menu != null, "player info picker opened");
        int option = 0;
        for (String[] row : menu.options) for (String cell : row) {
            if (cell.equals(label)) {
                require(Dialogs.menuResult(viewer, menu.menuId), "picker callback belongs to current dialog");
                Menus.menuChoose(viewer, menu.menuId, option);
                viewer.menu = null;
                return;
            }
            option++;
        }
        throw new AssertionError("Target missing from picker: " + label);
    }

    static final class TestPlayer extends Player {
        final List<String> messages = new ArrayList<>();
        MenuCallPacket menu;
        TestPlayer(String uuid, String label) {
            name = label;
            con = new NetConnection("127.0.0.1") {
                @Override public void send(Object packet, boolean reliable) { if (packet instanceof MenuCallPacket shown) menu = shown; }
                @Override public void close() { }
            };
            con.uuid = uuid;
            con.player = this;
            add();
        }
        @Override public void sendMessage(String text) { messages.add(text); }
    }
}
