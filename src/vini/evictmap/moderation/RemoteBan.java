package vini.evictmap.moderation;

import arc.util.Strings;
import mindustry.Vars;
import mindustry.net.Administration;
import mindustry.net.Administration.PlayerInfo;
import vini.evictmap.core.util.PluginLog;

import java.util.function.Consumer;

/**
 * Bans and unbans asked for from outside the game - currently the Discord
 * {@code /ban} and {@code /unban} commands - and says in one line what happened.
 *
 * <p>Nothing is decided here. A UUID goes through the same {@link BanRequest}
 * an admin's in-game {@code /ban} produces; an address goes through the same
 * {@code banPlayerIP} an admin's console {@code ban ip} calls. Both therefore
 * fire the events {@link BanManager} hooks, and are widened, kicked, written to
 * the file the match servers read, announced in chat and posted to the ban log
 * without this class arranging any of it. All it does is work out which of the
 * two the argument is, and phrase the answer.
 *
 * <p>An address is told apart from a UUID by looking for a dot or a colon: a
 * Mindustry UUID is base64 and can hold neither.
 *
 * <p>Main thread only - it touches the admin store and fires Mindustry events.
 */
public final class RemoteBan {

    /** Mindustry's placeholder for an account it has never seen a name for. */
    private static final String UNKNOWN_NAME = "<unknown>";

    /** A name is player-chosen; keep it short enough to stay one line. */
    private static final int MAX_NAME_LENGTH = 40;

    private final Consumer<BanRequest> seedBan;

    public RemoteBan(Consumer<BanRequest> seedBan) {
        this.seedBan = seedBan;
    }

    /**
     * Bans one account or one address.
     *
     * @param target a player UUID or an IP address
     * @param actor  who asked, as it should read in the ban log
     */
    public String ban(String target, String actor) {
        String cleaned = target == null ? "" : target.trim();

        if (cleaned.isEmpty()) {
            return "Give a player UUID or an IP address.";
        }

        if (Vars.netServer == null) {
            return "The server is not ready yet; try again in a moment.";
        }

        Administration admins = Vars.netServer.admins;

        PluginLog.info("Ban asked for by @: @", actor, cleaned);

        if (isAddress(cleaned)) {
            if (admins.isIPBanned(cleaned)) {
                return cleaned + " is already banned.";
            }

            // Vanilla's own semantics, exactly as an admin typing 'ban ip'
            // gets them: the address, plus the accounts seen at it.
            admins.banPlayerIP(cleaned);

            return cleaned + " was banned from the server.";
        }

        // Read before the ban: banPlayerID creates an empty record for an
        // account the server has never seen, and the label would lose its name.
        String label = label(admins, cleaned);

        if (admins.isIDBanned(cleaned)) {
            return label + " is already banned.";
        }

        seedBan.accept(BanRequest.admin(cleaned, BanOrigin.now(actor, BanOrigin.HUB)));

        if (!admins.isIDBanned(cleaned)) {
            return "Could not ban " + label + "; check the server console.";
        }

        return label + " was banned from the server.";
    }

    /**
     * Lifts a ban on one account or one address.
     *
     * <p>Not widened, and deliberately: an unban means the one ban it names.
     * Mindustry is already broader than the identifier it is given (lifting an
     * account drops its addresses too), and stacking a cascade on top of that
     * lets a single command quietly let a whole cluster back in.
     */
    public String unban(String target, String actor) {
        String cleaned = target == null ? "" : target.trim();

        if (cleaned.isEmpty()) {
            return "Give a player UUID or an IP address.";
        }

        if (Vars.netServer == null) {
            return "The server is not ready yet; try again in a moment.";
        }

        Administration admins = Vars.netServer.admins;

        PluginLog.info("Unban asked for by @: @", actor, cleaned);

        if (isAddress(cleaned)) {
            return admins.unbanPlayerIP(cleaned)
                    ? cleaned + " was unbanned from the server."
                    : cleaned + " is not banned.";
        }

        String label = label(admins, cleaned);

        return admins.unbanPlayerID(cleaned)
                ? label + " was unbanned from the server."
                : label + " is not banned.";
    }

    /**
     * {@code name (uuid)}, or the bare UUID when the server has never seen the
     * account - the UUID is the part that identifies it, the name is there so a
     * human recognises who was hit.
     */
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

    /** A Mindustry UUID is base64: it never carries a dot or a colon. */
    private static boolean isAddress(String value) {
        return value.indexOf('.') >= 0 || value.indexOf(':') >= 0;
    }
}
