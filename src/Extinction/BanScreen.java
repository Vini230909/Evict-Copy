// What a banned player reads: the ban plus the Discord invite to appeal it, at the ban and at every later join attempt.
package Extinction;

import Extinction.core.util.PluginLog;

import arc.Events;
import mindustry.Vars;
import mindustry.game.EventType.ConnectPacketEvent;
import mindustry.game.EventType.ConnectionEvent;
import mindustry.net.Administration;
import mindustry.net.NetConnection;

import java.util.function.Consumer;

// Vanilla kicks with a fixed enum that has no room for an invite; a kick with a string shows it verbatim.
// Both roles: a banned player can aim their client straight at a worker's port.
public final class BanScreen {

    private static final String BANNED = "You are banned from this server.";

    private static final String WORD_FILTER =
            "You were banned for using a word that is not allowed on this server.";

    // One refused comeback: a banned address (name and uuid blank - the game does not know them yet) or account.
    public record Refusal(String name, String uuid, String ip) {
    }

    // Hub: where a refused comeback goes (Evasion); null on a worker, which only refuses.
    private final Consumer<Refusal> refused;

    private boolean installed;

    public BanScreen(Consumer<Refusal> refused) {
        this.refused = refused;
    }

    // The plain ban screen.
    public String message() {
        return withAppeal(BANNED);
    }

    // The word filter's screen: same appeal, its own reason.
    public String wordFilterMessage() {
        return withAppeal(WORD_FILTER);
    }

    // Kicks the connection with the ban screen, unless it is already gone.
    public void kick(NetConnection con) {
        if (con != null && !con.kicked) {
            con.kick(message());
        }
    }

    // ConnectionEvent fires before vanilla's address check and ConnectPacketEvent before its account check,
    // and a kicked connection ignores the next kick - so this kicks first, with the appeal text. Safe to call once.
    public void install() {
        if (installed) {
            return;
        }

        installed = true;

        // TCP connect: the address is all that is known yet. Vanilla's own check follows in the same handler.
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
                report(new Refusal("", "", con.address));
            }
        });

        // Connect packet: now the account is known too; the steam prefix is already folded into the uuid.
        Events.on(ConnectPacketEvent.class, event -> {
            NetConnection con = event.connection;

            if (con == null || con.kicked || event.packet == null) {
                return;
            }

            String uuid = event.packet.uuid;
            Administration admins = admins();

            if (admins != null && uuid != null && admins.isIDBanned(uuid)) {
                kick(con);
                report(new Refusal(event.packet.name == null ? "" : event.packet.name, uuid, con.address));
            }
        });

        PluginLog.info(
                "Ban screen armed: @",
                hasAppeal() ? "appeals go to " + Config.banAppealUrl.trim() : "no appeal link set"
        );
    }

    // Hands a refusal on without letting a failure there reach vanilla's connect handler.
    private void report(Refusal refusal) {
        if (refused == null) {
            return;
        }

        try {
            refused.accept(refusal);
        } catch (Exception exception) {
            PluginLog.err("Ban evasion could not be handled: @", exception.toString());
        }
    }

    // True while an appeal link is configured.
    public boolean hasAppeal() {
        String url = Config.banAppealUrl;
        return url != null && !url.isBlank();
    }

    private String withAppeal(String reason) {
        if (!hasAppeal()) {
            return reason;
        }

        return reason
                + "\n\nTo appeal, join our Discord:\n"
                + displayUrl(Config.banAppealUrl.trim());
    }

    // The short form of a Discord invite, for a screen where it is typed off by hand; anything else as is.
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
