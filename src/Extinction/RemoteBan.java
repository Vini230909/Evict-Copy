// Bans and unbans asked for from outside the game - Discord's /ban and /unban - and one line saying what happened.
package Extinction;

import Extinction.core.util.PluginLog;

import arc.util.Strings;
import mindustry.Vars;
import mindustry.net.Administration;
import mindustry.net.Administration.PlayerInfo;

// Nothing is decided here: a UUID takes /ban's route, an address console 'ban ip''s, so Bans widens and logs both.
// Hub and main thread only - it touches the admin store and fires Mindustry events.
public final class RemoteBan {

    // Mindustry's placeholder for an account it has never seen a name for.
    private static final String UNKNOWN_NAME = "<unknown>";

    // A name is player-chosen; keep it short enough to stay one line.
    private static final int MAX_NAME_LENGTH = 40;

    private final Bans bans;

    public RemoteBan(Bans bans) {
        this.bans = bans;
    }

    // Bans one account or one address; the actor is who asked and the reason why, as the ban log shows them.
    public String ban(String target, String actor, String reason) {
        String cleaned = target == null ? "" : target.trim();

        if (cleaned.isEmpty()) {
            return "Give a player UUID or an IP address.";
        }

        // Discord makes the field required; an old registration of /ban may still lack it.
        if (reason == null || reason.isBlank()) {
            return "A ban needs a reason - fill in the reason field.";
        }

        if (Vars.netServer == null) {
            return "The server is not ready yet; try again in a moment.";
        }

        Administration admins = Vars.netServer.admins;
        Bans.Origin origin = Bans.Origin.now(actor, Bans.Origin.HUB, reason);

        PluginLog.info("Ban asked for by @: @. Reason: @", actor, cleaned, origin.reason());

        if (isAddress(cleaned)) {
            if (admins.isIPBanned(cleaned)) {
                return cleaned + " is already banned.";
            }

            // Vanilla's own semantics, exactly as an admin typing 'ban ip' gets them: the address and its accounts.
            bans.banAddress(cleaned, origin);

            return cleaned + " was banned from the server.";
        }

        // Read before the ban: banPlayerID creates an empty record for an unseen account, and the label would lose its name.
        String label = label(admins, cleaned);

        if (admins.isIDBanned(cleaned)) {
            return label + " is already banned.";
        }

        bans.ban(Bans.Request.admin(cleaned, origin));

        if (!admins.isIDBanned(cleaned)) {
            return "Could not ban " + label + "; check the server console.";
        }

        return label + " was banned from the server.";
    }

    // Lifts one ban, not widened: an unban means the one ban it names, and vanilla is already broader than that.
    public String unban(String target, String actor) {
        String cleaned = target == null ? "" : target.trim();

        if (cleaned.isEmpty()) {
            return "Give a player UUID or an IP address.";
        }

        if (Vars.netServer == null) {
            return "The server is not ready yet; try again in a moment.";
        }

        Administration admins = Vars.netServer.admins;
        Bans.Origin origin = Bans.Origin.now(actor, Bans.Origin.HUB);

        PluginLog.info("Unban asked for by @: @", actor, cleaned);

        if (isAddress(cleaned)) {
            return bans.unban(cleaned, true, origin)
                    ? cleaned + " was unbanned from the server."
                    : cleaned + " is not banned.";
        }

        String label = label(admins, cleaned);

        return bans.unban(cleaned, false, origin)
                ? label + " was unbanned from the server."
                : label + " is not banned.";
    }

    // "name (uuid)", or the bare UUID for an account the server has never seen.
    private static String label(Administration admins, String uuid) {
        PlayerInfo info = admins.playerInfo.get(uuid);
        String name = info == null ? null : info.lastName;

        if (name == null || name.isBlank() || UNKNOWN_NAME.equals(name)) {
            return uuid;
        }

        String cleaned = Strings.stripColors(name)
                .replaceAll("\\s+", " ")
                .trim();

        if (cleaned.isEmpty()) {
            return uuid;
        }

        if (cleaned.length() > MAX_NAME_LENGTH) {
            cleaned = cleaned.substring(0, MAX_NAME_LENGTH) + "…";
        }

        return cleaned + " (" + uuid + ")";
    }

    // A Mindustry UUID is base64: it never carries a dot or a colon.
    private static boolean isAddress(String value) {
        return value.indexOf('.') >= 0 || value.indexOf(':') >= 0;
    }
}
