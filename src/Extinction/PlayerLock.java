// The lock: who is locked, and what a locked account can and cannot do - on the hub and every match server.
package Extinction;

import Extinction.core.util.PluginLog;
import Extinction.moderation.vpn.VpnVerdict;

import arc.func.Cons;
import arc.struct.Seq;
import arc.util.CommandHandler;
import arc.util.Strings;
import mindustry.Vars;
import mindustry.content.StatusEffects;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Iconc;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.net.Administration.ActionType;
import mindustry.type.StatusEffect;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

// The hub decides and writes LockList.HUB_FILE; a worker only follows it. Why the slow flight is vanilla statuses: GAMEPLAY.md.
public final class PlayerLock {

    // What a locked player may still type. Everything else is the reminder.
    static final Set<String> ALLOWED_COMMANDS = Set.of("s", "spectate", "sync");

    // The game's own padlock glyph in red, in front of a locked player's name; synced to every client like any name.
    public static final String NAME_PREFIX = "[scarlet]" + Iconc.lock + "[] ";

    // A worker re-reads the hub's list this often.
    private static final long WORKER_SYNC_MILLIS = 5_000L;

    // The status effects are refreshed this often, and last a bit longer.
    private static final int STATUS_REFRESH_TICKS = 60;
    private static final float STATUS_DURATION_TICKS = 5f * 60f;

    // One lock event for the ban log: verdict and joins are set for a lock or a locked account's join, the actor for a free.
    public record Event(Kind kind, String name, String uuid, String ip, VpnVerdict verdict, int joins, String actor) {

        public enum Kind {
            LOCKED,
            JOINED,
            FREED
        }

        public static Event locked(String name, String uuid, String ip, VpnVerdict verdict, int joins) {
            return new Event(Kind.LOCKED, name, uuid, ip, verdict, joins, "");
        }

        public static Event joined(String name, String uuid, String ip, VpnVerdict verdict, int joins) {
            return new Event(Kind.JOINED, name, uuid, ip, verdict, joins, "");
        }

        public static Event freed(String name, String uuid, String ip, String actor) {
            return new Event(Kind.FREED, name, uuid, ip, null, 0, actor);
        }
    }

    // What freeing an account came to, as a line for whoever asked.
    public record FreeResult(boolean freed, String line) {
    }

    // True on the hub, which owns the list; false on a worker.
    private final boolean hub;
    private final Supplier<String> appealUrl;

    // Hub: gives a freed player their team and hex, if they are online.
    private final Consumer<Player> onFreed;

    // Hub: the ban log line.
    private final Consumer<Event> log;

    private final Map<String, LockList.Entry> locked = new LinkedHashMap<>();
    private final Map<String, LockList.Verified> verified = new LinkedHashMap<>();

    private boolean installed;
    private long lastSyncMillis;
    private long lastModifiedMillis = Long.MIN_VALUE;
    private int ticks;

    // Resolved lazily: content is not something to touch in a field initialiser.
    private StatusEffect[] slowing;

    public PlayerLock(boolean hub, Supplier<String> appealUrl, Consumer<Player> onFreed, Consumer<Event> log) {
        this.hub = hub;
        this.appealUrl = appealUrl;
        this.onFreed = onFreed;
        this.log = log;
    }

    // Both roles: loads the list and arms the chat, action and command gates.
    public void install() {
        if (installed) {
            return;
        }

        if (Vars.netServer == null) {
            PluginLog.err("Player lock could not arm - no net server yet.");
            return;
        }

        installed = true;

        if (hub) {
            adopt(LockList.read(LockList.HUB_FILE));
        } else {
            sync(true);
        }

        Vars.netServer.admins.addChatFilter((player, message) -> {
            if (player != null && isLocked(player.uuid())) {
                remind(player);
                return null;
            }

            return message;
        });

        Vars.netServer.admins.addActionFilter(action ->
                action == null
                        || action.player == null
                        || action.type == ActionType.respawn
                        || !isLocked(action.player.uuid())
        );

        Vars.netServer.clientCommands =
                new CommandGate(Vars.netServer.clientCommands, this);

        PluginLog.info(
                "Player lock armed (@): @ locked account(s), @ verified.",
                hub ? "hub" : "match server, following the hub's list",
                locked.size(),
                verified.size()
        );
    }

    // Every tick. A worker follows the hub's file; both keep the slow flight up.
    public void update() {
        if (!installed) {
            return;
        }

        if (!hub) {
            sync(false);
        }

        if (++ticks >= STATUS_REFRESH_TICKS) {
            ticks = 0;

            Groups.player.each(player -> {
                if (player == null) {
                    return;
                }

                if (isLocked(player.uuid())) {
                    applySlow(player);

                    // A worker may learn of the lock only after the join.
                    if (!player.name.startsWith(NAME_PREFIX)) {
                        applyPrefix(player);
                    }
                } else if (player.name.startsWith(NAME_PREFIX)) {
                    // Freed on the hub while on a match server: the worker
                    // hears through the list and takes the padlock off.
                    restoreName(player);
                    clearSlow(player);
                }
            });
        }
    }

    public boolean isLocked(String uuid) {
        return uuid != null && locked.containsKey(uuid);
    }

    public boolean isVerified(String uuid) {
        return uuid != null && verified.containsKey(uuid);
    }

    public int lockedCount() {
        return locked.size();
    }

    public int verifiedCount() {
        return verified.size();
    }

    // Every locked account, newest first.
    public List<LockList.Entry> lockedEntries() {
        List<LockList.Entry> entries = new ArrayList<>(locked.values());
        entries.sort(Comparator.comparingLong(LockList.Entry::sinceMillis).reversed());
        return entries;
    }

    // The locked accounts whose UUID is the query or whose plain name contains it, newest first.
    public List<LockList.Entry> matching(String query) {
        List<LockList.Entry> matches = new ArrayList<>();
        String needle = query.toLowerCase(Locale.ROOT);

        for (LockList.Entry entry : lockedEntries()) {
            if (
                    entry.uuid().equals(query)
                            || Strings.stripColors(entry.name()).toLowerCase(Locale.ROOT).contains(needle)
            ) {
                matches.add(entry);
            }
        }

        return matches;
    }

    // Hub: locks an account. The player, if online, is told; the caller has already parked them on Fallen.
    public void lock(String uuid, String name, String ip, VpnVerdict verdict, int joins) {
        if (!hub || uuid == null || uuid.isBlank()) {
            return;
        }

        String plain = stripPrefix(name);

        locked.put(uuid, new LockList.Entry(
                uuid,
                plain,
                ip == null ? "" : ip,
                System.currentTimeMillis(),
                verdict == null ? "" : verdict.flags()
        ));
        save();

        PluginLog.info(
                "Locked @ (@) from @: new account through @. Free with 'free', /free or Discord /free.",
                Strings.stripColors(plain),
                uuid,
                ip,
                verdict == null ? "a VPN" : verdict.flags()
        );

        if (log != null) {
            log.accept(Event.locked(plain, uuid, ip, verdict, joins));
        }

        Player online = find(uuid);

        if (online != null) {
            announce(online);
        }
    }

    // Hub: a locked account joined again and stays locked - written up every time, with what its address looks like now.
    public void noteJoin(String uuid, String name, String ip, VpnVerdict verdict, int joins) {
        if (!hub || uuid == null || uuid.isBlank()) {
            return;
        }

        String plain = stripPrefix(name);

        PluginLog.info(
                "Locked account joined: @ (@) from @: @. Still locked - free with 'free', /free or Discord /free.",
                Strings.stripColors(plain),
                uuid,
                ip,
                verdict == null ? "no verdict" : verdict.flags()
        );

        if (log != null) {
            log.accept(Event.joined(plain, uuid, ip, verdict, joins));
        }
    }

    // Hub: an admin looked and decided - the account is freed and verified, online or not.
    public FreeResult free(String uuid, String actor) {
        return free(uuid, actor, true);
    }

    // Hub: verify false is the automatic free (back from a clean address); the account is a known one after it, not verified.
    public FreeResult free(String uuid, String actor, boolean verify) {
        if (!hub) {
            return new FreeResult(false, "Locks are freed on the hub - use /free there, or Discord's /free.");
        }

        if (uuid == null || uuid.isBlank()) {
            return new FreeResult(false, "Give the account's UUID.");
        }

        LockList.Entry entry = locked.remove(uuid);

        if (entry == null) {
            return new FreeResult(false, uuid + " is not locked.");
        }

        if (verify) {
            verified.put(uuid, new LockList.Verified(uuid, entry.name(), System.currentTimeMillis()));
        }

        save();

        String plainName = Strings.stripColors(entry.name());
        String who = actor == null || actor.isBlank() ? "an admin" : actor;

        PluginLog.info("Freed @ (@) - by @.", plainName, uuid, who);

        if (log != null) {
            log.accept(Event.freed(entry.name(), uuid, entry.ip(), who));
        }

        Player online = find(uuid);

        if (online != null) {
            clearSlow(online);
            restoreName(online);
            online.sendMessage(
                    "[green]You have been freed by " + who
                            + ". Welcome - you can build and chat now.[]"
            );

            if (onFreed != null) {
                onFreed.accept(online);
            }
        }

        return new FreeResult(true, plainName + " (" + uuid + ") was freed.");
    }

    // The name without the lock's padlock, whichever way round it arrived.
    public static String stripPrefix(String name) {
        if (name == null) {
            return "";
        }

        String stripped = name;

        while (stripped.startsWith(NAME_PREFIX)) {
            stripped = stripped.substring(NAME_PREFIX.length());
        }

        return stripped;
    }

    private static void applyPrefix(Player player) {
        String plain = stripPrefix(player.name);
        player.name = NAME_PREFIX + plain;
    }

    private static void restoreName(Player player) {
        player.name = stripPrefix(player.name);
    }

    // A locked player arrived (either role): the reminder as a chat line and a dialog, and the slow flight at once.
    public void handlePlayerJoin(Player player) {
        if (player == null || !isLocked(player.uuid())) {
            return;
        }

        announce(player);
    }

    // The reminder, as one chat line. Sent on every refused message or command.
    public void remind(Player player) {
        if (player == null) {
            return;
        }

        player.sendMessage(chatNotice());
    }

    private void announce(Player player) {
        applyPrefix(player);
        player.sendMessage(chatNotice());
        Call.infoMessage(player.con, dialogNotice());
        applySlow(player);
    }

    private String chatNotice() {
        return "[scarlet]Your account is locked.[] [lightgray]It is new and joined through a VPN or proxy. "
                + "You can watch and use /s, but not build or chat until an admin frees it.[] "
                + howToGetFreed();
    }

    private String dialogNotice() {
        return "[scarlet]Your account is locked.[]\n\n"
                + "[lightgray]It is new and joined through a VPN or proxy - which is how banned players "
                + "come back, so a new account on one is checked by a person first.\n\n"
                + "Until an admin frees it you can look around, read the chat and use [white]/s[lightgray] "
                + "to watch matches, but not build, chat or use other commands.[]\n\n"
                + howToGetFreed();
    }

    private String howToGetFreed() {
        String url = appealUrl == null ? "" : appealUrl.get();

        if (url == null || url.isBlank()) {
            return "[accent]To get freed, ask an admin on the server.[]";
        }

        return "[accent]To get freed, join our Discord [white]" + BanScreen.displayUrl(url)
                + "[accent] and ask an admin.[]";
    }

    private void applySlow(Player player) {
        Unit unit = player.unit();

        if (unit == null || player.dead()) {
            return;
        }

        for (StatusEffect effect : slowing()) {
            unit.apply(effect, STATUS_DURATION_TICKS);
        }
    }

    private void clearSlow(Player player) {
        Unit unit = player.unit();

        if (unit == null || player.dead()) {
            return;
        }

        for (StatusEffect effect : slowing()) {
            unit.unapply(effect);
        }
    }

    // slow 0.4 x freezing 0.6 x tarred 0.6 x sapped 0.7 = a tenth of the speed, plus disarmed.
    private StatusEffect[] slowing() {
        if (slowing == null) {
            slowing = new StatusEffect[]{
                    StatusEffects.slow,
                    StatusEffects.freezing,
                    StatusEffects.tarred,
                    StatusEffects.sapped,
                    StatusEffects.disarmed
            };
        }

        return slowing;
    }

    private void save() {
        LockList.write(LockList.HUB_FILE, locked.values(), verified.values());
    }

    private void adopt(LockList.Snapshot snapshot) {
        locked.clear();
        locked.putAll(snapshot.locked());
        verified.clear();
        verified.putAll(snapshot.verified());
    }

    // Worker: follows the hub's file by its modification time.
    private void sync(boolean force) {
        long now = System.currentTimeMillis();

        if (!force && now - lastSyncMillis < WORKER_SYNC_MILLIS) {
            return;
        }

        lastSyncMillis = now;
        File file = LockList.WORKER_VIEW_FILE;
        long modified = file.exists() ? file.lastModified() : 0L;

        if (!force && modified == lastModifiedMillis) {
            return;
        }

        lastModifiedMillis = modified;
        adopt(LockList.read(file));
    }

    private static Player find(String uuid) {
        return Groups.player.find(player -> player != null && uuid.equals(player.uuid()));
    }

    // Stands in front of netServer.clientCommands, the one place every command passes, whoever registered it.
    // Everything is delegated; only handleMessage looks at who is asking first.
    private static final class CommandGate extends CommandHandler {

        private final CommandHandler delegate;
        private final PlayerLock lock;

        CommandGate(CommandHandler delegate, PlayerLock lock) {
            super(delegate.getPrefix());
            this.delegate = delegate;
            this.lock = lock;
        }

        // A refused command answers valid, so the text is neither treated as chat nor followed by "unknown command".
        @Override
        public CommandResponse handleMessage(String message, Object params) {
            if (
                    params instanceof Player player
                            && message != null
                            && message.startsWith(delegate.getPrefix())
                            && lock.isLocked(player.uuid())
                            && !ALLOWED_COMMANDS.contains(commandName(message))
            ) {
                lock.remind(player);
                return new CommandResponse(ResponseType.valid, null, message);
            }

            return delegate.handleMessage(message, params);
        }

        @Override
        public CommandResponse handleMessage(String message) {
            return delegate.handleMessage(message);
        }

        @Override
        public void setPrefix(String prefix) {
            super.setPrefix(prefix);
            delegate.setPrefix(prefix);
        }

        @Override
        public String getPrefix() {
            return delegate.getPrefix();
        }

        @Override
        public void removeCommand(String text) {
            delegate.removeCommand(text);
        }

        @Override
        public <T> Command register(String text, String description, CommandRunner<T> runner) {
            return delegate.register(text, description, runner);
        }

        @Override
        public <T> Command register(String text, String params, String description, CommandRunner<T> runner) {
            return delegate.register(text, params, description, runner);
        }

        @Override
        public Command register(String text, String description, Cons<String[]> runner) {
            return delegate.register(text, description, runner);
        }

        @Override
        public Command register(String text, String params, String description, Cons<String[]> runner) {
            return delegate.register(text, params, description, runner);
        }

        @Override
        public Seq<Command> getCommandList() {
            return delegate.getCommandList();
        }

        // "/play foo" -> "play", lower-case.
        private String commandName(String message) {
            String rest = message.substring(delegate.getPrefix().length()).trim();
            int space = rest.indexOf(' ');
            return (space < 0 ? rest : rest.substring(0, space)).toLowerCase();
        }
    }
}
