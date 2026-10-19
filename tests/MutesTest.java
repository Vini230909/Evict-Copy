// Exercises durable mute requests, chat routes, command allowances, reconnects and combined moderation prefixes.
package Extinction;

import arc.Core;
import arc.Settings;
import mindustry.Vars;
import mindustry.core.NetServer;
import mindustry.gen.Groups;
import mindustry.gen.MenuCallPacket;
import mindustry.gen.Player;
import mindustry.net.Net;
import mindustry.net.NetConnection;
import mindustry.ui.Menus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class MutesTest {
    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Core.settings = new Settings();
        Vars.net = new Net(null) {
            @Override public boolean server() { return true; }
            @Override public void send(Object packet, boolean reliable) { }
        };
        Groups.init();
        Vars.netServer = new NetServer();
        Path config = Path.of("config");
        Mutes hub = new Mutes(true, null, null, config);
        hub.install();
        TestPlayer target = new TestPlayer("AAAAAAAAAAA=", "[pink]Target");
        int[] chats = {0}, others = {0};
        for (String command : List.of("t", "a", "msg", "w", "whisper")) {
            Vars.netServer.clientCommands.<Player>register(command, "<message...>", "chat", (a,p) -> chats[0]++);
        }
        for (String command : List.of("play", "info", "r", "sync")) {
            Vars.netServer.clientCommands.<Player>register(command, "normal", (a,p) -> others[0]++);
        }
        require(hub.mute(target.uuid(), "Admin").contains("was muted"), "mute succeeds");
        require(target.name.startsWith(Mutes.NAME_PREFIX), "mute prefix appears immediately");
        require(Vars.netServer.admins.filterMessage(target, "global") == null, "global chat rejected");
        for (String command : List.of("t", "a", "msg", "w", "whisper")) {
            Vars.netServer.clientCommands.handleMessage("/" + command + " hello", target);
        }
        require(chats[0] == 0, "team, admin and private chat cannot execute");
        for (String command : List.of("play", "info", "r", "sync")) Vars.netServer.clientCommands.handleMessage("/" + command, target);
        require(others[0] == 4, "normal commands and restart alias remain available");
        require(Mutes.blockedCommand("/msg\tTarget hi", "/"), "whitespace cannot bypass private chat gate");
        require(!Mutes.blockedCommand("/mute Target", "/") && !Mutes.blockedCommand("hello", "/"), "non-chat commands unaffected");
        target.name = PlayerLock.NAME_PREFIX + target.name;
        hub.handlePlayerJoin(target);
        require(target.name.equals(PlayerLock.NAME_PREFIX + Mutes.NAME_PREFIX + "[pink]Target"), "lock stays first");
        require(hub.unmute(target.uuid(), "Admin").contains("was unmuted"), "unmute succeeds");
        require(target.name.equals(PlayerLock.NAME_PREFIX + "[pink]Target"), "unmute preserves VPN lock prefix");
        require(Mutes.stripPrefix(Mutes.NAME_PREFIX + PlayerLock.NAME_PREFIX + Mutes.NAME_PREFIX + "Name")
                .equals(PlayerLock.NAME_PREFIX + "Name"), "mixed duplicate prefixes normalize safely");
        TestPlayer admin = new TestPlayer("AQEBAQEBAQE=", "Admin");
        admin.admin = true;
        hub.mute(target.uuid(), "Admin");
        hub.handleUnmute(new String[0], admin);
        MenuCallPacket firstPicker = admin.menu;
        hub.handleMute(new String[0], admin);
        require(admin.menu == firstPicker, "repeated moderation command leaves active picker and targets intact");
        Menus.menuChoose(admin, firstPicker.menuId, 0);
        require(!hub.isMuted(target.uuid()), "original unmute picker still performs its original action");
        hub.mute(target.uuid(), "Admin");
        Mutes restartedHub = new Mutes(true, null, null, config);
        restartedHub.install();
        require(restartedHub.isMuted(target.uuid()), "mute survives hub restart");
        TestPlayer rejoined = new TestPlayer(target.uuid(), "New name");
        restartedHub.handlePlayerJoin(rejoined);
        require(rejoined.name.startsWith(Mutes.NAME_PREFIX) && !rejoined.messages.isEmpty(), "reconnect reapplies mute and reminder");

        MuteFiles files = new MuteFiles(config);
        require(files.read().get(target.uuid()).equals("[pink]Target"), "UUID with padding and coloured name round trip");
        Mutes worker = new Mutes(false, null, null, config);
        worker.install();
        worker.unmute(target.uuid(), "Worker admin");
        require(!worker.isMuted(target.uuid()), "worker unmute takes effect locally");
        require(files.read().containsKey(target.uuid()), "worker never writes the durable state");
        require(files.requests().size() == 1 && files.requests().get(0).server().startsWith("port-"), "request identifies worker port");
        Mutes nextHub = new Mutes(true, null, null, config);
        nextHub.install();
        require(!nextHub.isMuted(target.uuid()) && files.requests().isEmpty(), "hub restart consumes durable worker request");
        require(files.read().isEmpty(), "hub persisted worker unmute");

        worker.mute(target.uuid(), "Worker admin");
        worker.unmute(target.uuid(), "Worker admin");
        require(files.requests().size() == 2, "rapid opposite worker actions are both retained");
        Mutes finalHub = new Mutes(true, null, null, config);
        finalHub.install();
        require(!finalHub.isMuted(target.uuid()) && files.requests().isEmpty(), "worker action order preserved");

        Path failedConfig = Path.of("blocked-config");
        Files.writeString(failedConfig, "not a directory");
        Mutes failed = new Mutes(true, null, null, failedConfig);
        require(failed.mute(target.uuid(), "Admin").contains("Could not save") && !failed.isMuted(target.uuid()), "failed save does not claim success");
        require(hub.mute(null, "Admin").contains("required"), "blank UUID handled safely");
        System.out.println("Mute checks passed.");
    }

    static final class TestPlayer extends Player {
        final List<String> messages = new ArrayList<>();
        MenuCallPacket menu;
        TestPlayer(String uuid, String nickname) {
            name = nickname;
            con = new NetConnection("127.0.0.1") {
                @Override public void send(Object packet, boolean reliable) { if (packet instanceof MenuCallPacket sent) menu = sent; }
                @Override public void close() { }
            };
            con.player = this;
            con.uuid = uuid;
            add();
        }
        @Override public void sendMessage(String message) { messages.add(message); }
    }
}
