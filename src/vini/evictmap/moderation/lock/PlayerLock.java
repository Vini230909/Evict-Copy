package vini.evictmap.moderation.lock;

import vini.evictmap.moderation.ban.BanScreen;
import vini.evictmap.moderation.vpn.VpnVerdict;

import mindustry.Vars;
import mindustry.content.StatusEffects;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Iconc;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.net.Administration.ActionType;
import mindustry.type.StatusEffect;
import vini.evictmap.core.util.PluginLog;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The lock: what a locked account can and cannot do, and who is locked.
 *
 * <p>A locked player is on the Fallen team and can look around and read the
 * chat, use {@code /s} to watch a match - and nothing else. Every chat line
 * and every other command comes back as the reminder of how to get freed;
 * every build plan, block, unit or turret interaction is refused by an
 * action filter; the unit flies at a tenth of its speed. The lock stays with
 * the account across joins and restarts until an admin frees it, and a freed
 * account is verified: never locked again.
 *
 * <p>Enforcement runs on the hub and on every match server alike, off the
 * same list - the hub writes {@code config/evict-locks.txt}, a worker reads
 * it back every few seconds (the ban list's arrangement), so a locked player
 * who {@code /s}-hops into a match cannot talk there either. Deciding who is
 * locked is the hub's alone ({@link LockGate}); a worker never locks or frees.
 *
 * <p>The slow flight is four vanilla status effects stacked - {@code slow}
 * (0.4), {@code freezing} (0.6), {@code tarred} (0.6) and {@code sapped}
 * (0.7), 0.1 together - plus {@code disarmed}. Not a status effect of the
 * plugin's own, because clients install nothing and a status they do not
 * have would break their unit sync; the vanilla ones travel in it. They show
 * as icons on the unit, which is the honest side effect of the choice.
 */
public final class PlayerLock {

    /** What a locked player may still type. Everything else is the reminder. */
    static final Set<String> ALLOWED_COMMANDS = Set.of("s", "spectate", "sync");

    /**
     * Put in front of a locked player's name while they are locked - the
     * padlock from the game's own icon font, in red - so everyone in the
     * player list and over the unit sees who is locked. The name is synced
     * to every client like any name change. Taken off again when they are
     * freed, and stripped wherever the plain name is wanted.
     */
    public static final String NAME_PREFIX = "[scarlet]" + Iconc.lock + "[] ";

    /** A worker re-reads the hub's list this often. */
    private static final long WORKER_SYNC_MILLIS = 5_000L;

    /** The status effects are refreshed this often, and last a bit longer. */
    private static final int STATUS_REFRESH_TICKS = 60;
    private static final float STATUS_DURATION_TICKS = 5f * 60f;

    private final boolean hub;
    private final Supplier<String> appealUrl;

    /** Hub: gives a freed player their team and hex, if they are online. */
    private final Consumer<Player> onFreed;

    /** Hub: the ban log line. */
    private final Consumer<LockEvent> log;

    private final Map<String, LockList.Entry> locked = new LinkedHashMap<>();
    private final Map<String, LockList.Verified> verified = new LinkedHashMap<>();

    private boolean installed;
    private long lastSyncMillis;
    private long lastModifiedMillis = Long.MIN_VALUE;
    private int ticks;

    /** Resolved lazily: content is not something to touch in a field initialiser. */
    private StatusEffect[] slowing;

    /**
     * @param hub      true on the hub, which owns the list; false on a worker
     * @param onFreed  hub: what to do with a freed player who is online
     * @param log      hub: where lock and free events are written up
     */
    public PlayerLock(
            boolean hub,
            Supplier<String> appealUrl,
            Consumer<Player> onFreed,
            Consumer<LockEvent> log
    ) {
        this.hub = hub;
        this.appealUrl = appealUrl;
        this.onFreed = onFreed;
        this.log = log;
    }

    /** Both roles: loads the list and arms the chat, action and command gates. */
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
                new LockCommandGate(Vars.netServer.clientCommands, this);

        PluginLog.info(
                "Player lock armed (@): @ locked account(s), @ verified.",
                hub ? "hub" : "match server, following the hub's list",
                locked.size(),
                verified.size()
        );
    }

    /** Every tick. A worker follows the hub's file; both keep the slow flight up. */
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

    /** Every locked account, newest first. */
    public List<LockList.Entry> lockedEntries() {
        List<LockList.Entry> entries = new ArrayList<>(locked.values());
        entries.sort(Comparator.comparingLong(LockList.Entry::sinceMillis).reversed());
        return entries;
    }

    /**
     * Hub: locks an account. The player, if online, is told; the caller has
     * already parked them on the Fallen team.
     */
    public void lock(String uuid, String name, String ip, VpnVerdict verdict) {
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
                "Locked @ (@) from @: new account through @. Free with 'evictfree', /free or Discord /free.",
                arc.util.Strings.stripColors(plain),
                uuid,
                ip,
                verdict == null ? "a VPN" : verdict.flags()
        );

        if (log != null) {
            log.accept(LockEvent.locked(plain, uuid, ip, verdict));
        }

        Player online = find(uuid);

        if (online != null) {
            announce(online);
        }
    }

    /** What freeing an account came to, as a line for whoever asked. */
    public record FreeResult(boolean freed, String line) {
    }

    /**
     * Hub: frees an account and marks it verified - an admin looked and
     * decided. Works on an account that is online (they get their team at
     * once) and on one that has left.
     */
    public FreeResult free(String uuid, String actor) {
        return free(uuid, actor, true);
    }

    /**
     * Hub: frees an account. {@code verify} false is the automatic free - a
     * locked account came back from a clean address - which lifts the lock
     * but does not vouch for the account: it is scanned like any other from
     * then on.
     */
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

        String plainName = arc.util.Strings.stripColors(entry.name());
        String who = actor == null || actor.isBlank() ? "an admin" : actor;

        PluginLog.info("Freed @ (@) - by @.", plainName, uuid, who);

        if (log != null) {
            log.accept(LockEvent.freed(entry.name(), uuid, who));
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

    /** The name without the lock's padlock, whichever way round it arrived. */
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

    /**
     * A locked player arrived (either role): the reminder as a chat line and
     * as a dialog, and the slow flight from the first second.
     */
    public void handlePlayerJoin(Player player) {
        if (player == null || !isLocked(player.uuid())) {
            return;
        }

        announce(player);
    }

    /** The reminder, as one chat line. Sent on every refused message or command. */
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

    /** Worker: follows the hub's file by its modification time. */
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
}
