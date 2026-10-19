// Account mutes, admin pickers, chat gates and names on the hub and every match worker.
package Extinction;

import Extinction.core.util.PluginLog;
import Extinction.data.PlayerDataManager;

import arc.func.Cons;
import arc.struct.Seq;
import arc.util.CommandHandler;
import arc.util.Strings;
import mindustry.Vars;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.ui.Menus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class Mutes {
    public static final String NAME_PREFIX = "[scarlet][[MUTED][] ";
    private static final String NOTICE = "[scarlet]You are muted. You may use commands, but cannot send chat or private messages.[]";
    private static final Set<String> CHAT_COMMANDS = Set.of(
            "t", "team", "a", "msg", "message", "m", "pm", "dm", "w", "whisper", "tell", "reply", "say", "chat", "global", "me");
    private record Target(String uuid, String name) { }
    private record Picker(boolean muted, List<Target> targets) { }
    private final boolean hub;
    private final PlayerDataManager data;
    private final BanLog banLog;
    private final MuteFiles files;
    private final Map<String, String> muted = new LinkedHashMap<>();
    private final Map<String, MuteFiles.Request> pending = new LinkedHashMap<>();
    private final Map<String, Picker> pickers = new HashMap<>();
    private final int pickerId;
    private boolean installed;
    private long lastSync;

    public Mutes(boolean hub, PlayerDataManager data, BanLog banLog) {
        this(hub, data, banLog, Path.of(hub ? "config" : "../../config"));
    }

    Mutes(boolean hub, PlayerDataManager data, BanLog banLog, Path config) {
        this.hub = hub;
        this.data = data;
        this.banLog = banLog;
        files = new MuteFiles(config);
        pickerId = Menus.registerMenu(this::picked);
    }

    public void install() {
        if (installed || Vars.netServer == null) return;
        installed = true;
        sync();
        Vars.netServer.admins.addChatFilter((player, message) -> {
            if (player == null || !isMuted(player.uuid())) return message;
            player.sendMessage(NOTICE);
            return null;
        });
        Vars.netServer.clientCommands = new CommandGate(Vars.netServer.clientCommands, this);
    }

    public boolean isMuted(String uuid) {
        return uuid != null && muted.containsKey(uuid);
    }

    public void update() {
        if (!installed) return;
        long now = System.currentTimeMillis();
        if (now - lastSync < 1_000L) return;
        lastSync = now;
        sync();
        Groups.player.each(this::refreshName);
    }

    public void handlePlayerJoin(Player player) {
        if (player == null) return;
        if (!hub) sync();
        refreshName(player);
        if (isMuted(player.uuid())) player.sendMessage(NOTICE);
    }

    public void handlePlayerLeave(Player player) {
        if (player != null) pickers.remove(player.uuid());
    }

    public void handleMute(String[] args, Player player) {
        handle(args, player, true);
    }

    public void handleUnmute(String[] args, Player player) {
        handle(args, player, false);
    }

    public void consoleMute(String query) {
        console(query, true);
    }

    public void consoleUnmute(String query) {
        console(query, false);
    }

    public String mute(String uuid, String actor) {
        return uuid == null || !uuid(uuid) ? "A valid player UUID is required." : change(uuid, nameOf(uuid), actor, true);
    }

    public String unmute(String uuid, String actor) {
        return uuid == null || !uuid(uuid) ? "A valid player UUID is required." : change(uuid, nameOf(uuid), actor, false);
    }

    private String change(String uuid, String name, String actor, boolean value) {
        if (uuid == null || uuid.isBlank()) return "A player UUID is required.";
        actor = actor == null || actor.isBlank() ? "An admin" : actor;
        if (isMuted(uuid) == value) return plain(name) + " is " + (value ? "already muted." : "not muted.");
        String source = hub ? "hub" : "port-" + mindustry.net.Administration.Config.port.num();
        if (hub) {
            if (!apply(uuid, name, actor, source, value)) return "Could not save mute state. Please try again.";
        } else {
            MuteFiles.Request request = files.request(uuid, name, actor, source, value);
            if (request == null) return "Could not send mute request to the hub. Please try again.";
            pending.put(uuid, request);
            set(uuid, name, value);
            announce(uuid, value);
        }
        return plain(name) + " (" + uuid + ") was " + (value ? "muted." : "unmuted.");
    }

    private boolean apply(String uuid, String name, String actor, String server, boolean value) {
        if (isMuted(uuid) == value) return true;
        Map<String, String> next = new LinkedHashMap<>(muted);
        if (value) next.put(uuid, name); else next.remove(uuid);
        if (!files.save(next)) return false;
        set(uuid, name, value);
        announce(uuid, value);
        String action = value ? "muted" : "unmuted";
        PluginLog.info("@ @ @ (@) on @.", actor, action, plain(name), uuid, server);
        if (banLog != null) banLog.logModeration(action, name, uuid, actor, server, "");
        return true;
    }

    private void set(String uuid, String name, boolean value) {
        if (value) muted.put(uuid, name); else muted.remove(uuid);
    }

    private void announce(String uuid, boolean value) {
        Groups.player.each(player -> {
            if (!uuid.equals(player.uuid())) return;
            refreshName(player);
            player.sendMessage(value ? NOTICE : "[green]You are no longer muted.[]");
        });
    }

    private void sync() {
        if (hub) {
            if (!installed || muted.isEmpty()) {
                Map<String, String> saved = files.read();
                if (saved != null) { muted.clear(); muted.putAll(saved); }
            }
            for (MuteFiles.Request request : files.requests()) {
                if (apply(request.uuid(), request.name(), request.actor(), request.server(), request.muted())) {
                    files.acknowledge(request);
                }
            }
        } else {
            pending.values().removeIf(request -> !Files.exists(request.file()));
            Map<String, String> saved = files.read();
            if (saved == null) return;
            Map<String, String> before = new HashMap<>(muted);
            muted.clear();
            muted.putAll(saved);
            pending.values().forEach(request -> set(request.uuid(), request.name(), request.muted()));
            Groups.player.each(player -> {
                if (before.containsKey(player.uuid()) != isMuted(player.uuid())) announce(player.uuid(), isMuted(player.uuid()));
            });
        }
    }

    private void handle(String[] args, Player admin, boolean value) {
        if (admin == null) return;
        if (!admin.admin) { admin.sendMessage("[scarlet]Only admins can mute or unmute players.[]"); return; }
        if (pickers.containsKey(admin.uuid())) { admin.sendMessage("[scarlet]Finish your current mute menu first.[]"); return; }
        String query = String.join(" ", args).trim();
        resolve(query, targets -> {
            if (!admin.admin || !admin.isAdded() || admin.con == null || admin.con.kicked) return;
            if (pickers.containsKey(admin.uuid())) return;
            targets = targets.stream().filter(target -> isMuted(target.uuid()) != value).toList();
            if (targets.isEmpty()) { admin.sendMessage("[lightgray]No " + (value ? "unmuted" : "muted") + " player matches.[]"); return; }
            if (!query.isBlank() && targets.size() == 1) {
                Target target = targets.get(0);
                admin.sendMessage(change(target.uuid(), target.name(), admin.plainName(), value));
            } else showPicker(admin, targets, value);
        });
    }

    private void console(String query, boolean value) {
        if (query == null || query.isBlank()) { PluginLog.info("Usage: @ <name/uuid>", value ? "mute" : "unmute"); return; }
        resolve(query.trim(), targets -> {
            if (targets.isEmpty()) PluginLog.info("No player matches '@'.", query);
            else if (targets.size() > 1) {
                PluginLog.info("Several players match '@'; use their UUID:", query);
                targets.forEach(target -> PluginLog.info("@ (@)", plain(target.name()), target.uuid()));
            } else {
                Target target = targets.get(0);
                PluginLog.info(change(target.uuid(), target.name(), "Console", value));
            }
        });
    }

    private void resolve(String query, Consumer<List<Target>> result) {
        List<Target> online = new ArrayList<>();
        Groups.player.each(player -> online.add(new Target(player.uuid(), plain(player.name))));
        if (query.isBlank()) {
            Map<String, Target> all = new LinkedHashMap<>();
            muted.forEach((uuid, name) -> all.put(uuid, new Target(uuid, name)));
            online.forEach(target -> all.put(target.uuid(), target));
            result.accept(new ArrayList<>(all.values()));
            return;
        }
        if (online.stream().anyMatch(target -> target.uuid().equals(query)) || muted.containsKey(query) || uuid(query)) {
            result.accept(List.of(new Target(query, nameOf(query))));
            return;
        }
        if (data != null) data.searchPlayerInfo(query, matches -> {
            if (!matches.isEmpty()) result.accept(matches.stream().map(info -> new Target(info.uuid(), info.lastName())).toList());
            else result.accept(matchLocal(query, online));
        });
        else result.accept(matchLocal(query, online));
    }

    private List<Target> matchLocal(String query, List<Target> online) {
        Map<String, Target> all = new LinkedHashMap<>();
        muted.forEach((uuid, name) -> all.put(uuid, new Target(uuid, name)));
        online.forEach(target -> all.put(target.uuid(), target));
        String needle = Strings.stripColors(query).toLowerCase(Locale.ROOT);
        return all.values().stream().filter(target -> Strings.stripColors(target.name()).toLowerCase(Locale.ROOT).contains(needle)).toList();
    }

    private static boolean uuid(String value) {
        try { return java.util.Base64.getDecoder().decode(value).length == 8; }
        catch (IllegalArgumentException ignored) { return false; }
    }

    private String nameOf(String uuid) {
        Player player = Groups.player.find(online -> uuid.equals(online.uuid()));
        if (player != null) return plain(player.name);
        if (muted.containsKey(uuid)) return muted.get(uuid);
        var info = Vars.netServer == null ? null : Vars.netServer.admins.playerInfo.get(uuid);
        return info == null || info.lastName == null ? uuid : plain(info.lastName);
    }

    private void showPicker(Player admin, List<Target> targets, boolean value) {
        List<Target> shown = targets.stream().sorted(Comparator.comparing(Target::name, String.CASE_INSENSITIVE_ORDER)).limit(20).toList();
        pickers.put(admin.uuid(), new Picker(value, shown));
        List<String[]> rows = new ArrayList<>();
        for (Target target : shown) rows.add(new String[]{target.name()});
        rows.add(new String[]{"Cancel"});
        Dialogs.menu(admin.con, pickerId, value ? "Mute a player" : "Unmute a player",
                targets.size() > shown.size() ? "Showing 20 players. Type a name to narrow the list." : "Select a player.", rows.toArray(String[][]::new));
    }

    private void picked(Player admin, int option) {
        if (admin == null) return;
        Picker picker = pickers.remove(admin.uuid());
        if (!admin.admin || picker == null || option < 0 || option >= picker.targets().size()) return;
        Target target = picker.targets().get(option);
        admin.sendMessage(change(target.uuid(), target.name(), admin.plainName(), picker.muted()));
    }

    public static String stripPrefix(String name) {
        if (name == null) return "";
        String locks = "";
        String rest = name;
        while (rest.startsWith(NAME_PREFIX) || rest.startsWith(PlayerLock.NAME_PREFIX)) {
            if (rest.startsWith(NAME_PREFIX)) rest = rest.substring(NAME_PREFIX.length());
            else { locks += PlayerLock.NAME_PREFIX; rest = rest.substring(PlayerLock.NAME_PREFIX.length()); }
        }
        return locks + rest;
    }

    private static String plain(String name) {
        return PlayerLock.stripPrefix(stripPrefix(name));
    }

    private void refreshName(Player player) {
        String name = stripPrefix(player.name);
        if (isMuted(player.uuid())) {
            if (name.startsWith(PlayerLock.NAME_PREFIX)) {
                name = PlayerLock.NAME_PREFIX + NAME_PREFIX + name.substring(PlayerLock.NAME_PREFIX.length());
            } else name = NAME_PREFIX + name;
        }
        player.name = name;
    }

    static boolean blockedCommand(String message, String prefix) {
        if (message == null || !message.startsWith(prefix)) return false;
        String command = message.substring(prefix.length());
        int end = 0;
        while (end < command.length() && !Character.isWhitespace(command.charAt(end))) end++;
        return CHAT_COMMANDS.contains(command.substring(0, end).toLowerCase(Locale.ROOT));
    }

    private static final class CommandGate extends CommandHandler {
        private final CommandHandler delegate;
        private final Mutes mutes;

        CommandGate(CommandHandler delegate, Mutes mutes) {
            super(delegate.getPrefix());
            this.delegate = delegate;
            this.mutes = mutes;
        }

        @Override public CommandResponse handleMessage(String message, Object params) {
            if (params instanceof Player player && mutes.isMuted(player.uuid()) && blockedCommand(message, delegate.getPrefix())) {
                player.sendMessage(NOTICE);
                return new CommandResponse(ResponseType.valid, null, message);
            }
            return delegate.handleMessage(message, params);
        }

        @Override public CommandResponse handleMessage(String message) { return delegate.handleMessage(message); }
        @Override public void setPrefix(String prefix) { super.setPrefix(prefix); delegate.setPrefix(prefix); }
        @Override public String getPrefix() { return delegate.getPrefix(); }
        @Override public void removeCommand(String text) { delegate.removeCommand(text); }
        @Override public <T> Command register(String text, String description, CommandRunner<T> runner) { return delegate.register(text, description, runner); }
        @Override public <T> Command register(String text, String params, String description, CommandRunner<T> runner) { return delegate.register(text, params, description, runner); }
        @Override public Command register(String text, String description, Cons<String[]> runner) { return delegate.register(text, description, runner); }
        @Override public Command register(String text, String params, String description, Cons<String[]> runner) { return delegate.register(text, params, description, runner); }
        @Override public Seq<Command> getCommandList() { return delegate.getCommandList(); }
    }
}
