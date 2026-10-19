// Required moderation warnings, queued per account until read for three seconds and acknowledged.
package Extinction;

import Extinction.core.util.PluginLog;
import Extinction.data.PlayerDataManager;

import arc.Core;
import arc.func.Cons;
import arc.struct.Seq;
import arc.util.CommandHandler;
import mindustry.Vars;
import mindustry.gen.Groups;
import mindustry.gen.MenuChooseCallPacket;
import mindustry.gen.Player;
import mindustry.gen.TextInputResultCallPacket;
import mindustry.ui.Menus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

public final class Warnings {
    public record Summary(long timeMillis, String actor, String server, boolean accepted) { }
    private static final String CONSOLE_REASON = "A moderator didn't like your action.";
    private static final long READING_NANOS = 3_000_000_000L;
    private static final long SYNC_NANOS = 1_000_000_000L;
    private static final Set<String> MENU_COMMANDS = Set.of("ban", "free", "mute", "unmute", "warn", "play", "p",
            "spectate", "s", "history", "h", "info", "maps");
    private record Target(String uuid, String name) { }
    private static final class Reading {
        final WarningList.Entry entry;
        long deadline;
        int remaining = -1;
        Reading(WarningList.Entry entry, long now) { this.entry = entry; deadline = now + READING_NANOS; }
    }

    private final boolean hub;
    private final PlayerDataManager data;
    private final BanLog banLog;
    private final WarningList list;
    private final LongSupplier clock;
    private final Map<String, List<Target>> pickers = new HashMap<>();
    private final Map<String, Target> reasons = new HashMap<>();
    private final Map<Player, Reading> reading = new HashMap<>();
    private final Map<String, List<WarningList.Entry>> pending = new HashMap<>();
    private final int pickerId = Menus.registerMenu(this::handlePicker);
    private final int reasonId = Menus.registerTextInput(this::handleReason);
    private final int warningId = Menus.registerMenu(this::handleAcceptance);
    private boolean installed;
    private long lastSync = Long.MIN_VALUE;

    public Warnings(boolean hub, PlayerDataManager data, BanLog banLog) {
        this(hub, data, banLog, new WarningList(hub), System::nanoTime);
    }

    Warnings(boolean hub, PlayerDataManager data, BanLog banLog, WarningList list, LongSupplier clock) {
        this.hub = hub;
        this.data = data;
        this.banLog = banLog;
        this.list = list;
        this.clock = clock;
    }

    public void install() {
        if (installed || Vars.netServer == null || Vars.net == null) return;
        installed = true;
        refresh();
        Dialogs.warnings(this::blocked);
        Vars.net.handleServer(MenuChooseCallPacket.class, (connection, packet) -> {
            if (!connection.kicked && Dialogs.menuResult(connection.player, packet.menuId)) packet.handleServer(connection);
        });
        Vars.net.handleServer(TextInputResultCallPacket.class, (connection, packet) -> {
            if (!connection.kicked && Dialogs.textResult(connection.player, packet.textInputId)) packet.handleServer(connection);
        });
        Vars.netServer.clientCommands = new CommandGate(Vars.netServer.clientCommands, this);
    }

    public void update() {
        if (!installed) return;
        long now = clock.getAsLong();
        if (lastSync == Long.MIN_VALUE || now - lastSync >= SYNC_NANOS) {
            lastSync = now;
            refresh();
        }
        reading.keySet().removeIf(player -> !player.isAdded() || player.con == null || player.con.kicked);
        Groups.player.each(player -> {
            if (player.con == null || player.con.kicked) return;
            Reading shown = reading.get(player);
            WarningList.Entry next = next(player.uuid());
            if (shown != null) for (WarningList.Entry entry : pending.getOrDefault(player.uuid(), List.of())) {
                if (entry.id().equals(shown.entry.id())) { next = shown.entry; break; }
            }
            if (next == null) {
                if (shown != null) { reading.remove(player); Dialogs.finishWarning(player, warningId); }
                Dialogs.flush(player);
                return;
            }
            if (!Dialogs.canWarn(player)) return;
            if (shown == null || !shown.entry.id().equals(next.id())) {
                shown = new Reading(next, now);
                reading.put(player, shown);
            }
            show(player, shown, now, false);
        });
    }

    public boolean blocked(Player player) {
        return player != null && next(player.uuid()) != null;
    }

    public boolean busy(Player player) {
        return player != null && (blocked(player) || pickers.containsKey(player.uuid()) || reasons.containsKey(player.uuid()));
    }

    // Audit metadata for moderation views; warning reasons stay in the ban log and required acknowledgement dialog.
    public List<Summary> history(String uuid) {
        if (uuid == null || uuid.isBlank()) return List.of();
        refresh();
        return list.entries().values().stream().filter(entry -> entry.uuid().equals(uuid))
                .sorted(Comparator.comparingLong(WarningList.Entry::time).thenComparing(WarningList.Entry::id).reversed())
                .map(entry -> new Summary(entry.time(), entry.actor(), entry.server(), entry.accepted())).toList();
    }

    public void handlePlayerJoin(Player player) {
        Dialogs.forget(player);
        reading.remove(player);
        refresh();
    }

    public void handlePlayerLeave(Player player) {
        if (player == null) return;
        pickers.remove(player.uuid());
        reasons.remove(player.uuid());
        reading.remove(player);
        Dialogs.forget(player);
    }

    public void handleWarn(String[] args, Player admin) {
        if (!authorized(admin)) return;
        if (busy(admin) || Dialogs.busy(admin)) { admin.sendMessage("[scarlet]Finish your current dialog first.[]"); return; }
        String query = String.join(" ", args).trim();
        if (query.isEmpty()) {
            List<Target> targets = new ArrayList<>();
            Groups.player.each(player -> {
                if (!player.uuid().equals(admin.uuid())) targets.add(new Target(player.uuid(), PlayerNames.displayName(player)));
            });
            targets.sort(Comparator.comparing(Target::name, String.CASE_INSENSITIVE_ORDER));
            picker(admin, targets, "Select a player to warn.");
            return;
        }
        targets(query, matches -> {
            if (!authorized(admin) || busy(admin) || Dialogs.busy(admin)) return;
            List<Target> targets = matches.stream().limit(20).toList();
            if (targets.size() == 1) askReason(admin, targets.get(0), "");
            else picker(admin, targets, matches.size() > 20 ? "Showing 20 matches; refine the name for the rest." : "Select a player to warn.");
        });
    }

    public void consoleWarn(String query) {
        if (query == null || query.isBlank()) { PluginLog.info("Usage: warn <name/uuid>"); return; }
        targets(query.trim(), matches -> {
            if (matches.isEmpty()) { PluginLog.info("No player matches '@'.", query); return; }
            if (matches.size() != 1) {
                PluginLog.info("Several players match '@'; use their UUID:", query);
                matches.forEach(info -> PluginLog.info("@ - @", info.name(), info.uuid()));
                return;
            }
            issue(matches.get(0), "Console", CONSOLE_REASON, null);
        });
    }

    private void targets(String query, Consumer<List<Target>> callback) {
        Consumer<List<PlayerDataManager.PlayerInfo>> stored = matches -> {
            if (!matches.isEmpty()) {
                callback.accept(matches.stream().map(info -> new Target(info.uuid(), info.lastName())).toList());
                return;
            }
            String folded = query.toLowerCase(Locale.ROOT);
            List<Target> online = new ArrayList<>();
            Groups.player.each(player -> {
                String name = PlayerLock.stripPrefix(Mutes.stripPrefix(player.name));
                if (player.uuid().equals(query) || arc.util.Strings.stripColors(name).toLowerCase(Locale.ROOT).contains(folded)) {
                    online.add(new Target(player.uuid(), name));
                }
            });
            callback.accept(online);
        };
        if (data == null) stored.accept(List.of());
        else data.searchPlayerInfo(query, stored);
    }

    private void picker(Player admin, List<Target> targets, String text) {
        if (targets.isEmpty()) { admin.sendMessage("[scarlet]No matching players.[]"); return; }
        pickers.put(admin.uuid(), targets);
        List<String[]> rows = new ArrayList<>();
        for (int index = 0; index < targets.size(); index += 2) {
            rows.add(index + 1 < targets.size() ? new String[]{targets.get(index).name, targets.get(index + 1).name}
                    : new String[]{targets.get(index).name});
        }
        rows.add(new String[]{"Cancel"});
        Dialogs.menu(admin.con, pickerId, "Warn a player", text, rows.toArray(new String[0][]));
    }

    private void handlePicker(Player admin, int option) {
        if (admin == null) return;
        List<Target> targets = pickers.remove(admin.uuid());
        if (!authorized(admin) || targets == null || option < 0 || option >= targets.size()) return;
        askReason(admin, targets.get(option), "");
    }

    private void askReason(Player admin, Target target, String error) {
        if (!authorized(admin)) return;
        if (target.uuid.equals(admin.uuid())) { admin.sendMessage("[scarlet]You cannot warn yourself.[]"); return; }
        reasons.put(admin.uuid(), target);
        Dialogs.textInput(admin.con, reasonId, "Warn " + target.name,
                error + "Why? The player must read and accept this warning. The reason goes into the ban log.",
                120, "", false, false);
    }

    private void handleReason(Player admin, String text) {
        if (admin == null) return;
        Target target = reasons.remove(admin.uuid());
        if (target == null || !authorized(admin)) return;
        if (text == null) { admin.sendMessage("[lightgray]Warning cancelled.[]"); return; }
        String reason = text.replaceAll("\\s+", " ").trim();
        if (reason.isEmpty()) { askReason(admin, target, "[scarlet]A warning needs a reason.[]\n\n"); return; }
        if (reason.length() > 120) reason = reason.substring(0, 120);
        issue(target, PlayerLock.stripPrefix(admin.plainName()), reason, admin);
    }

    private void issue(Target target, String actor, String reason, Player admin) {
        String server = hub ? "hub" : "port-" + Core.settings.getInt("port", 6567);
        WarningList.Entry entry = new WarningList.Entry(UUID.randomUUID().toString(), target.uuid,
                target.name, actor, server, reason, System.currentTimeMillis(), false);
        if (!list.add(entry)) {
            if (admin != null) admin.sendMessage("[scarlet]Could not save the warning. Try again.[]");
            return;
        }
        indexPending();
        if (hub) log(entry);
        else PluginLog.info("@ warned @: @", actor, target.name, reason);
        if (admin != null) admin.sendMessage("[accent]Warned " + target.name + ".[]");
        update();
    }

    private void log(WarningList.Entry entry) {
        PluginLog.info("@ warned @ (@) on @: @", entry.actor(), entry.name(), entry.uuid(), entry.server(), entry.reason());
        if (banLog != null) banLog.logModeration("warned", entry.name(), entry.uuid(), entry.actor(), entry.server(), entry.reason());
    }

    private WarningList.Entry next(String uuid) {
        List<WarningList.Entry> entries = pending.get(uuid);
        return entries == null || entries.isEmpty() ? null : entries.get(0);
    }

    private void refresh() {
        list.refresh(this::log);
        indexPending();
    }

    private void indexPending() {
        pending.clear();
        list.entries().values().stream().filter(entry -> !entry.accepted())
                .sorted(Comparator.comparingLong(WarningList.Entry::time).thenComparing(WarningList.Entry::id))
                .forEach(entry -> pending.computeIfAbsent(entry.uuid(), ignored -> new ArrayList<>()).add(entry));
    }

    private void show(Player player, Reading shown, long now, boolean force) {
        int remaining = (int)Math.max(0L, (shown.deadline - now + 999_999_999L) / 1_000_000_000L);
        if (!force && shown.remaining == remaining) return;
        shown.remaining = remaining;
        String actor = shown.entry.actor().replace("[", "[[");
        String reason = shown.entry.reason().replace("[", "[[");
        Dialogs.showWarning(player, warningId, "[accent]" + actor + " has warned you.[]\n\n"
                + "[white]" + reason + "[]\n\n[lightgray]Read this warning, then acknowledge it.[]",
                remaining > 0 ? "Read first (" + remaining + "s)" : "I understand");
    }

    private void handleAcceptance(Player player, int option) {
        if (player == null) return;
        Reading shown = reading.get(player);
        if (shown == null) return;
        long now = clock.getAsLong();
        if (option == -1) { shown.deadline = now + READING_NANOS; show(player, shown, now, true); return; }
        if (option != 0 || now < shown.deadline) { show(player, shown, now, true); return; }
        if (!list.accept(shown.entry)) { player.sendMessage("[scarlet]Could not save your acknowledgement. Please try again.[]"); return; }
        indexPending();
        reading.remove(player);
        if (next(player.uuid()) == null) Dialogs.finishWarning(player, warningId);
        update();
    }

    private boolean authorized(Player admin) {
        if (admin == null || admin.con == null || admin.con.kicked || !admin.isAdded()) return false;
        if (admin.admin) return true;
        admin.sendMessage("[scarlet]Only admins can warn players.[]");
        return false;
    }

    private static final class CommandGate extends CommandHandler {
        private final CommandHandler delegate;
        private final Warnings warnings;
        CommandGate(CommandHandler delegate, Warnings warnings) {
            super(delegate.getPrefix());
            this.delegate = delegate;
            this.warnings = warnings;
        }
        @Override public CommandResponse handleMessage(String message, Object params) {
            if (params instanceof Player player && message != null && message.startsWith(delegate.getPrefix())
                    && warnings.blocked(player)) {
                player.sendMessage("[scarlet]Read and acknowledge your moderator warning first.[]");
                return new CommandResponse(ResponseType.valid, null, message);
            }
            if (params instanceof Player player && message != null && message.startsWith(delegate.getPrefix())
                    && Dialogs.busy(player) && MENU_COMMANDS.contains(message.substring(delegate.getPrefix().length())
                    .stripLeading().split("\\s+", 2)[0].toLowerCase(Locale.ROOT))) {
                player.sendMessage("[scarlet]Finish your current dialog first.[]");
                return new CommandResponse(ResponseType.valid, null, message);
            }
            return delegate.handleMessage(message, params);
        }
        @Override public CommandResponse handleMessage(String message) { return delegate.handleMessage(message); }
        @Override public void setPrefix(String prefix) { super.setPrefix(prefix); delegate.setPrefix(prefix); }
        @Override public String getPrefix() { return delegate.getPrefix(); }
        @Override public void removeCommand(String text) { delegate.removeCommand(text); }
        @Override public <T> Command register(String text, String description, CommandRunner<T> runner) {
            return delegate.register(text, description, runner);
        }
        @Override public <T> Command register(String text, String params, String description, CommandRunner<T> runner) {
            return delegate.register(text, params, description, runner);
        }
        @Override public Command register(String text, String description, Cons<String[]> runner) {
            return delegate.register(text, description, runner);
        }
        @Override public Command register(String text, String params, String description, Cons<String[]> runner) {
            return delegate.register(text, params, description, runner);
        }
        @Override public Seq<Command> getCommandList() { return delegate.getCommandList(); }
    }
}
