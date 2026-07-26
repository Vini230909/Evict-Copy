package vini.evictmap.moderation;

import arc.util.Time;
import mindustry.Vars;
import mindustry.gen.Groups;
import mindustry.net.Administration;
import mindustry.net.Administration.PlayerInfo;
import mindustry.net.Packets.KickReason;
import vini.evictmap.core.util.PluginLog;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Worker-side half of the ban system: takes the hub's ban list and applies it
 * to this match server.
 *
 * <p>A duel worker is a separate Mindustry process with its own {@code config/}
 * and therefore its own, empty, ban list. Until now that meant a banned player
 * who knew a worker's port could join a match on it, which is exactly the sort
 * of hole somebody being evasive finds. The hub writes every ban to
 * {@link BanList#HUB_FILE}; this reads it back and hands it to the worker's own
 * admin store, so a banned connection is refused by Mindustry itself with the
 * normal kick screen rather than by a bespoke check that has to remember every
 * way in.
 *
 * <p>The file is re-read on a timer instead of once at startup: a worker can
 * outlive several bans, and somebody banned mid-match has to leave the match.
 */
public final class BanSync {

    /** How often the hub's list is checked. */
    private static final long POLL_INTERVAL_MILLIS = 5_000L;

    private final File file;

    private long lastPollMillis;
    private long lastModifiedMillis = Long.MIN_VALUE;
    private boolean everApplied;

    public BanSync(File file) {
        this.file = file;
    }

    /** Worker default: the hub's list, two directories up. */
    public BanSync() {
        this(BanList.WORKER_VIEW_FILE);
    }

    /**
     * Called from the worker's update trigger. Cheap: it only stats the file
     * unless it actually changed.
     */
    public void update() {
        if (Vars.netServer == null) {
            return;
        }

        if (everApplied
                && Time.timeSinceMillis(lastPollMillis) < POLL_INTERVAL_MILLIS) {
            return;
        }

        lastPollMillis = Time.millis();

        long modified = file.exists() ? file.lastModified() : 0L;

        if (everApplied && modified == lastModifiedMillis) {
            return;
        }

        lastModifiedMillis = modified;
        everApplied = true;

        apply(BanList.read(file));
    }

    /**
     * Brings this server's bans in line with the hub's: adds what is new, drops
     * what the hub has lifted, and removes anyone now banned who is currently
     * connected.
     */
    private void apply(BanList.Snapshot snapshot) {
        Administration admins = Vars.netServer.admins;

        // Lifted bans go first. Applying an IP ban flips every local account
        // that has used the address, and those accounts are not in the hub's
        // list by name - running the cleanup afterwards would read them as
        // stale and unban them again, address and all, once every poll.
        int removed = dropLiftedBans(admins, snapshot);
        int added = 0;

        for (String uuid : snapshot.uuids()) {
            if (!admins.isIDBanned(uuid)) {
                admins.banPlayerID(uuid);
                added++;
            }
        }

        for (String ip : snapshot.ips()) {
            if (!admins.bannedIPs.contains(ip, false)) {
                admins.banPlayerIP(ip);
                added++;
            }
        }

        int kicked = kickBanned(snapshot);

        if (added > 0 || removed > 0 || kicked > 0) {
            PluginLog.info(
                    "Ban list synced from the hub: @ added, @ lifted, @ kicked.",
                    added,
                    removed,
                    kicked
            );
        }
    }

    /**
     * Lifts bans the hub no longer lists. The worker's store is a copy of the
     * hub's decisions, so anything in it that the hub has dropped is stale.
     */
    private int dropLiftedBans(
            Administration admins,
            BanList.Snapshot snapshot
    ) {
        int removed = 0;

        List<String> staleUuids = new ArrayList<>();

        for (PlayerInfo info : admins.getBanned()) {
            if (info == null || info.id == null) {
                continue;
            }

            // Not listed by UUID, but banned because of an address the hub
            // bans: that is a live ban, not a leftover. Unbanning it would
            // also strip the address out of the ban list.
            if (snapshot.uuids().contains(info.id) || usesBannedIp(info, snapshot)) {
                continue;
            }

            staleUuids.add(info.id);
        }

        for (String uuid : staleUuids) {
            admins.unbanPlayerID(uuid);
            removed++;
        }

        List<String> staleIps = new ArrayList<>();

        for (String ip : admins.bannedIPs) {
            if (!snapshot.ips().contains(ip)) {
                staleIps.add(ip);
            }
        }

        for (String ip : staleIps) {
            admins.unbanPlayerIP(ip);
            removed++;
        }

        return removed;
    }

    /** True when this account has ever connected from an address the hub bans. */
    private static boolean usesBannedIp(PlayerInfo info, BanList.Snapshot snapshot) {
        if (info.lastIP != null && snapshot.ips().contains(info.lastIP)) {
            return true;
        }

        if (info.ips == null) {
            return false;
        }

        for (String ip : info.ips) {
            if (snapshot.ips().contains(ip)) {
                return true;
            }
        }

        return false;
    }

    /** Removes connected players the hub has banned, mid-match included. */
    private int kickBanned(BanList.Snapshot snapshot) {
        if (snapshot.isEmpty()) {
            return 0;
        }

        Set<mindustry.gen.Player> hit = new LinkedHashSet<>();

        Groups.player.each(player -> {
            if (player == null || player.con == null) {
                return;
            }

            if (snapshot.uuids().contains(player.uuid())
                    || snapshot.ips().contains(player.con.address)) {
                hit.add(player);
            }
        });

        for (mindustry.gen.Player player : hit) {
            player.con.kick(KickReason.banned);
        }

        return hit.size();
    }
}
