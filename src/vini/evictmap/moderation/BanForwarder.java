package vini.evictmap.moderation;

import arc.Events;
import mindustry.Vars;
import mindustry.game.EventType.PlayerBanEvent;
import mindustry.game.EventType.PlayerIpBanEvent;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.net.Packets.KickReason;
import vini.evictmap.core.util.PluginLog;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Worker-side counterpart of {@link BanManager}: hands a ban made on a match
 * server to the hub, which owns every ban.
 *
 * <p>A worker's admin store is a throwaway copy of the hub's list, kept in step
 * by {@link BanSync}. A ban made only there therefore evaporates - the next sync
 * reads it as a leftover and lifts it - and the hub never hears about it, so the
 * account is not widened to its addresses, not written to the shared list, not
 * announced and not logged to Discord. The player simply reconnects to the hub.
 *
 * <p>So every ban made here is forwarded: the local ban stands (the player is
 * gone from the match at once), and the account travels to the hub in the
 * worker's status file, where it goes through the normal path as if an admin
 * had typed it on the hub.
 *
 * <p>Bans {@link BanSync} itself applies are ignored - those <em>came</em> from
 * the hub, and sending them back would be an echo.
 */
public final class BanForwarder {

    /** Where a forwarded ban goes: the worker's status file, for the hub. */
    private final Consumer<BanRequest> requestSink;

    /** True while {@link BanSync} is applying the hub's list. */
    private final BooleanSupplier syncing;

    /**
     * Who is banning, set for the moment a ban is seeded. Mindustry's event
     * carries the target but not the admin, so the paths that know name
     * themselves here; anything else is forwarded as "an admin".
     */
    private BanRequest pending;

    private boolean installed;

    public BanForwarder(Consumer<BanRequest> requestSink, BooleanSupplier syncing) {
        this.requestSink = requestSink;
        this.syncing = syncing;
    }

    /** Worker-only: start forwarding. Safe to call once. */
    public void install() {
        if (installed) {
            return;
        }

        installed = true;

        Events.on(PlayerBanEvent.class, event -> forward(event.uuid));

        // Nothing on a worker produces one of these except an admin typing
        // 'ban ip' into a worker console, which is a mistake worth naming: the
        // hub's list is what the workers apply, so an address banned here is
        // lifted again on the next sync.
        Events.on(PlayerIpBanEvent.class, event -> {
            if (!syncing.getAsBoolean()) {
                PluginLog.warn(
                        "Address @ was banned on this match server. Address bans "
                                + "belong on the hub - this one will be lifted by "
                                + "the next ban-list sync.",
                        event.ip
                );
            }
        });
    }

    /**
     * Bans an account on this match server and forwards it to the hub.
     *
     * <p>The local ban is what makes the player leave now; the forwarded one is
     * what makes it stick.
     */
    public void ban(BanRequest request) {
        if (request == null || request.isEmpty() || Vars.netServer == null) {
            return;
        }

        pending = request;

        try {
            // Already banned locally (a synced hub ban, say): no event fires,
            // so forward it here instead of losing it.
            if (!Vars.netServer.admins.banPlayerID(request.uuid())) {
                send(request);
            }
        } finally {
            pending = null;
        }

        kick(request.uuid());
    }

    /**
     * A ban made on this worker by any other path - the in-game hammer above
     * all, which goes straight to the admin store.
     */
    private void forward(String uuid) {
        if (syncing.getAsBoolean() || uuid == null || uuid.isBlank()) {
            return;
        }

        BanRequest request = pending != null && uuid.equals(pending.uuid())
                ? pending
                : BanRequest.admin(uuid, BanOrigin.now(
                        BanOrigin.UNKNOWN_ADMIN,
                        "this match server"
                ));

        send(request);
        kick(uuid);
    }

    private void send(BanRequest request) {
        PluginLog.info(
                "Ban on @ made here; forwarding it to the hub, which applies it.",
                request.uuid()
        );

        requestSink.accept(request);
    }

    /** Banning does not kick by itself on every path; make sure they are gone. */
    private void kick(String uuid) {
        Player banned = Groups.player.find(
                player -> player != null && uuid.equals(player.uuid())
        );

        if (banned != null && banned.con != null && !banned.con.kicked) {
            banned.con.kick(KickReason.banned);
        }
    }
}
