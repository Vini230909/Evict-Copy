package vini.evictmap.moderation.ban;

import arc.Events;
import mindustry.Vars;
import mindustry.game.EventType.ConnectPacketEvent;
import mindustry.game.EventType.ConnectionEvent;
import mindustry.net.Administration;
import mindustry.net.NetConnection;
import vini.evictmap.core.util.PluginLog;

import java.util.function.Supplier;

/**
 * What a banned player reads on their screen, and how to appeal it.
 *
 * <p>Mindustry kicks a banned connection with {@code KickReason.banned}, a fixed
 * enum the client translates itself into "You are banned on this server." -
 * there is no room in it for a Discord invite. Kicking with a <em>string</em>
 * instead shows that string verbatim, so every ban kick the plugin makes goes
 * through here and carries the appeal line when one is configured.
 *
 * <p>Two screens, and the second is the one that matters: the kick at the
 * moment of the ban (which the plugin already makes itself, on every path), and
 * the refusal on every later join attempt, which vanilla makes inside its
 * connect handlers before any plugin code runs. Except that it does not quite:
 * {@link ConnectionEvent} fires before the address check and
 * {@link ConnectPacketEvent} before the account check, and
 * {@link NetConnection#kick} is a no-op once the connection is kicked. So this
 * looks the ban up itself on those two events, kicks with the appeal text
 * first, and vanilla's own {@code kick(KickReason.banned)} a few lines later
 * does nothing. Installed on the hub and on every match server - a banned
 * player can aim their client straight at a worker's port.
 *
 * <p>The kick dialog is a plain label, nothing in it can be clicked or copied,
 * so the invite is shown in its short form for typing by hand.
 */
public final class BanScreen {

    private static final String BANNED = "You are banned from this server.";

    private static final String WORD_FILTER =
            "You were banned for using a word that is not allowed on this server.";

    private final Supplier<String> appealUrl;

    private boolean installed;

    /** @param appealUrl the configured invite; blank means no appeal line. */
    public BanScreen(Supplier<String> appealUrl) {
        this.appealUrl = appealUrl;
    }

    /** The plain ban screen. */
    public String message() {
        return withAppeal(BANNED);
    }

    /** The word filter's screen: same appeal, its own reason. */
    public String wordFilterMessage() {
        return withAppeal(WORD_FILTER);
    }

    /** Kicks the connection with the ban screen, unless it is already gone. */
    public void kick(NetConnection con) {
        if (con != null && !con.kicked) {
            con.kick(message());
        }
    }

    /**
     * Refuses banned join attempts with the appeal text before vanilla refuses
     * them with its own. Safe to call once.
     */
    public void install() {
        if (installed) {
            return;
        }

        installed = true;

        // TCP connect: the address is all that is known yet. Vanilla's own
        // check follows in the same handler.
        Events.on(ConnectionEvent.class, event -> {
            NetConnection con = event.connection;

            if (con == null || con.kicked || con.address == null) {
                return;
            }

            Administration admins = admins();

            if (admins != null
                    && (admins.isIPBanned(con.address)
                    || admins.isSubnetBanned(con.address))) {
                kick(con);
            }
        });

        // Connect packet: now the account is known too. The steam prefix is
        // already folded into the uuid by the time the event fires.
        Events.on(ConnectPacketEvent.class, event -> {
            NetConnection con = event.connection;

            if (con == null || con.kicked || event.packet == null) {
                return;
            }

            String uuid = event.packet.uuid;
            Administration admins = admins();

            if (admins != null && uuid != null && admins.isIDBanned(uuid)) {
                kick(con);
            }
        });

        PluginLog.info(
                "Ban screen armed: @",
                hasAppeal() ? "appeals go to " + appealUrl.get().trim() : "no appeal link set"
        );
    }

    /** True while an appeal link is configured. */
    public boolean hasAppeal() {
        String url = appealUrl.get();
        return url != null && !url.isBlank();
    }

    private String withAppeal(String reason) {
        if (!hasAppeal()) {
            return reason;
        }

        return reason
                + "\n\nTo appeal, join our Discord:\n"
                + displayUrl(appealUrl.get().trim());
    }

    /**
     * The short form of a Discord invite, for a screen where it has to be
     * typed off by hand. Anything that is not a Discord invite is shown as is.
     */
    public static String displayUrl(String url) {
        String rest = url;

        for (String prefix : new String[]{"https://", "http://"}) {
            if (rest.startsWith(prefix)) {
                rest = rest.substring(prefix.length());
                break;
            }
        }

        for (String host : new String[]{"discord.com/invite/", "www.discord.com/invite/", "discordapp.com/invite/"}) {
            if (rest.startsWith(host)) {
                return "discord.gg/" + rest.substring(host.length());
            }
        }

        return rest.startsWith("discord.gg/") ? rest : url;
    }

    private static Administration admins() {
        return Vars.netServer == null ? null : Vars.netServer.admins;
    }
}
