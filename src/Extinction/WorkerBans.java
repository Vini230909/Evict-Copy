// A match server's side of bans: it applies the hub's ban list here and hands every ban made here to the hub.
package Extinction;

import Extinction.core.util.PluginLog;

import arc.Events;
import arc.util.Time;
import mindustry.Vars;
import mindustry.game.EventType.PlayerBanEvent;
import mindustry.game.EventType.PlayerIpBanEvent;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.net.Administration;
import mindustry.net.Administration.PlayerInfo;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

// A worker's admin store is a throwaway copy of the hub's: a ban made only here is lifted by the next sync and never
// reaches the hub, so it is forwarded - the local ban makes the player leave now, the hub's makes it stick.
public final class WorkerBans {

    // How often the hub's list is checked.
    private static final long POLL_INTERVAL_MILLIS = 5_000L;

    private final File file = BanList.WORKER_VIEW_FILE;

    // Where a forwarded ban goes: the worker's status file, for the hub.
    private final Consumer<Bans.Request> requestSink;

    // What the kicked player reads - the ban plus how to appeal it.
    private final BanScreen screen;

    private long lastPollMillis;
    private long lastModifiedMillis = Long.MIN_VALUE;
    private boolean everApplied;

    // True while the hub's list is applied: it fires the ban events watched here, and must not be sent back.
    private boolean applying;

    // Who is banning, for the moment a ban is seeded: Mindustry's event carries the target but not the admin.
    private Bans.Request pending;

    private boolean installed;

    public WorkerBans(Consumer<Bans.Request> requestSink, BanScreen screen) {
        this.requestSink = requestSink;
        this.screen = screen;
    }

    // Worker-only: start forwarding. Safe to call once.
    public void install() {
        if (installed) {
            return;
        }

        installed = true;

        Events.on(PlayerBanEvent.class, event -> forward(event.uuid));

        // Only 'ban ip' typed into a worker console makes one of these - a mistake worth naming: the next sync lifts it.
        Events.on(PlayerIpBanEvent.class, event -> {
            if (!applying) {
                PluginLog.warn(
                        "Address @ was banned on this match server. Address bans "
                                + "belong on the hub - this one will be lifted by "
                                + "the next ban-list sync.",
                        event.ip
                );
            }
        });
    }

    // Called from the worker's update trigger. Cheap: it only stats the file unless it actually changed.
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

        applying = true;

        try {
            apply(BanList.read(file));
        } finally {
            applying = false;
        }
    }

    // Bans an account on this match server and forwards it to the hub.
    public void ban(Bans.Request request) {
        if (request == null || request.isEmpty() || Vars.netServer == null) {
            return;
        }

        pending = request;

        try {
            // Already banned locally (a synced hub ban, say): no event fires, so forward it here instead of losing it.
            if (!Vars.netServer.admins.banPlayerID(request.uuid())) {
                send(request);
            }
        } finally {
            pending = null;
        }

        kick(request.uuid());
    }

    // Any ban made on this worker; one not seeded through ban() was typed into its console (the hammer asks /ban's reason).
    private void forward(String uuid) {
        if (applying || uuid == null || uuid.isBlank()) {
            return;
        }

        Bans.Request request = pending != null && uuid.equals(pending.uuid())
                ? pending
                : Bans.Request.admin(uuid, Bans.Origin.now(
                        Bans.Origin.CONSOLE,
                        "this match server"
                ));

        send(request);
        kick(uuid);
    }

    private void send(Bans.Request request) {
        PluginLog.info(
                "Ban on @ made here; forwarding it to the hub, which applies it.",
                request.uuid()
        );

        requestSink.accept(request);
    }

    // Banning does not kick by itself on every path; make sure they are gone.
    private void kick(String uuid) {
        Player banned = Groups.player.find(
                player -> player != null && uuid.equals(player.uuid())
        );

        if (banned != null) {
            screen.kick(banned.con);
        }
    }

    // Brings this server's bans in line with the hub's: adds the new, drops the lifted, removes whoever is now banned.
    private void apply(BanList.Snapshot snapshot) {
        Administration admins = Vars.netServer.admins;

        // Lifted bans go first: dropping a stale address also clears accounts an older build flipped through it.
        int removed = dropLiftedBans(admins, snapshot);
        int added = 0;

        for (String uuid : snapshot.uuids()) {
            if (!admins.isIDBanned(uuid)) {
                admins.banPlayerID(uuid);
                added++;
            }
        }

        // Addresses go onto the list directly, never through banPlayerIP, which would brand every local account behind
        // a shared address - the hub's own rule in Bans.apply. A quietly added address still refuses the connection.
        boolean addressesAdded = false;

        for (String ip : snapshot.ips()) {
            if (!admins.bannedIPs.contains(ip, false)) {
                admins.bannedIPs.add(ip);
                addressesAdded = true;
                added++;
            }
        }

        if (addressesAdded) {
            admins.save();
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

    // Lifts bans the hub no longer lists. An account stays banned only when the hub lists it by UUID, never by address.
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

            if (snapshot.uuids().contains(info.id)) {
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

    // Removes connected players the hub has banned, mid-match included.
    private int kickBanned(BanList.Snapshot snapshot) {
        if (snapshot.isEmpty()) {
            return 0;
        }

        Set<Player> hit = new LinkedHashSet<>();

        Groups.player.each(player -> {
            if (player == null || player.con == null) {
                return;
            }

            if (snapshot.uuids().contains(player.uuid())
                    || snapshot.ips().contains(player.con.address)) {
                hit.add(player);
            }
        });

        for (Player player : hit) {
            screen.kick(player.con);
        }

        return hit.size();
    }
}
