package vini.evictmap.moderation;

import arc.struct.Seq;
import mindustry.Vars;
import mindustry.net.Administration.PlayerInfo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Works out everything that belongs to one ban.
 *
 * <p>A banned player comes back under a new name, and often enough under a new
 * account entirely - Mindustry UUIDs are handed out by the client, so a fresh
 * install is a fresh identity. What does not change nearly as often is the
 * connection. So a UUID ban is not applied to the one identifier an admin
 * happened to type: it also covers every address Mindustry's admin store has
 * recorded for that account.
 *
 * <p>That is the whole expansion - one step, never through an address to other
 * accounts:
 *
 * <ul>
 *   <li>seeded with a UUID: that account plus its own recorded IPs;</li>
 *   <li>seeded with an IP: that address plus the accounts seen there (which is
 *       exactly what vanilla's {@code banPlayerIP} flips anyway), and nothing
 *       beyond them.</li>
 * </ul>
 *
 * <p>Anything deeper was tried and reverted: a single shared address - CGNAT,
 * a VPN endpoint, a university network - links dozens of strangers, and one
 * more hop through their dynamic IPs turns one ban into hundreds of banned
 * addresses that mostly belong to innocent players. The one step catches the
 * offender's own connections - which is the point - without the chain ever
 * escaping into the rest of the player base.
 *
 * <p>Pure computation over an in-memory snapshot: it changes nothing and is
 * safe to run for a preview. {@link BanManager} applies the result.
 */
final class BanCascade {

    /**
     * Placeholder Mindustry stores for an account it has never seen connect,
     * and which must never be treated as an address: it is shared by every such
     * account, so banning it would ban all of them.
     */
    private static final String UNKNOWN_IP = "<unknown>";

    private BanCascade() {
    }

    /** Everything one ban covers: the accounts, their addresses, their names. */
    record Result(
            String seedLabel,
            Set<String> uuids,
            Set<String> ips,
            List<String> names
    ) {

        boolean isEmpty() {
            return uuids.isEmpty() && ips.isEmpty();
        }
    }

    /**
     * Expands a UUID ban: the account plus its own recorded addresses. Never
     * follows those addresses to other accounts.
     */
    static Result fromUuid(String uuid) {
        Set<String> uuids = new LinkedHashSet<>();
        Set<String> ips = new LinkedHashSet<>();

        if (uuid == null || uuid.isBlank()) {
            return empty();
        }

        uuids.add(uuid);
        collectIps(info(uuid), ips);

        return build(labelFor(uuid), uuids, ips);
    }

    /**
     * Expands an IP ban: the address plus every account seen there - the same
     * set vanilla's {@code banPlayerIP} flips on its own, so this mostly makes
     * the report honest. Those accounts' other addresses are deliberately left
     * alone.
     */
    static Result fromIp(String ip) {
        Set<String> uuids = new LinkedHashSet<>();
        Set<String> ips = new LinkedHashSet<>();

        if (!usableIp(ip)) {
            return empty();
        }

        ips.add(ip);
        addAccountsUsing(ips, uuids);

        return build(ip, uuids, ips);
    }

    /**
     * Every account that has ever connected from one of these addresses. Used
     * by the unban report to see who a lifted address ban let back in.
     */
    static Set<String> accountsUsing(Set<String> ips) {
        Set<String> uuids = new LinkedHashSet<>();
        addAccountsUsing(ips, uuids);
        return uuids;
    }

    /** Every name ever used by these accounts. */
    static List<String> namesOf(Set<String> uuids) {
        return collectNames(uuids);
    }

    /** Every address this account has ever connected from. */
    static Set<String> ipsOf(String uuid) {
        Set<String> ips = new LinkedHashSet<>();
        collectIps(info(uuid), ips);
        return ips;
    }

    /** Adds every account that has ever connected from one of {@code ips}. */
    private static void addAccountsUsing(Set<String> ips, Set<String> uuids) {
        if (ips.isEmpty() || Vars.netServer == null) {
            return;
        }

        for (PlayerInfo info : Vars.netServer.admins.playerInfo.values()) {
            if (info == null || info.id == null) {
                continue;
            }

            if (usesAnyOf(info, ips)) {
                uuids.add(info.id);
            }
        }
    }

    private static boolean usesAnyOf(PlayerInfo info, Set<String> ips) {
        if (ips.contains(info.lastIP)) {
            return true;
        }

        Seq<String> known = info.ips;

        if (known == null) {
            return false;
        }

        for (String ip : known) {
            if (ips.contains(ip)) {
                return true;
            }
        }

        return false;
    }

    private static void collectIps(PlayerInfo info, Set<String> ips) {
        if (info == null) {
            return;
        }

        if (usableIp(info.lastIP)) {
            ips.add(info.lastIP);
        }

        if (info.ips == null) {
            return;
        }

        for (String ip : info.ips) {
            if (usableIp(ip)) {
                ips.add(ip);
            }
        }
    }

    /** Every name every account in the cascade has ever used, newest last. */
    private static List<String> collectNames(Set<String> uuids) {
        Set<String> names = new LinkedHashSet<>();

        for (String uuid : uuids) {
            PlayerInfo info = info(uuid);

            if (info == null) {
                continue;
            }

            if (info.names != null) {
                for (String name : info.names) {
                    if (name != null && !name.isBlank()) {
                        names.add(name);
                    }
                }
            }

            if (info.lastName != null && !info.lastName.isBlank()) {
                names.add(info.lastName);
            }
        }

        return new ArrayList<>(names);
    }

    private static Result build(String seedLabel, Set<String> uuids, Set<String> ips) {
        return new Result(seedLabel, uuids, ips, collectNames(uuids));
    }

    private static Result empty() {
        return new Result("", Set.of(), Set.of(), List.of());
    }

    /**
     * The account's last known name, for the log headline. Falls back to the
     * UUID for an account the server has never actually seen - a ban typed from
     * a UUID somebody was handed elsewhere.
     */
    private static String labelFor(String uuid) {
        PlayerInfo info = info(uuid);

        return info == null || info.lastName == null || info.lastName.isBlank()
                ? uuid
                : info.lastName;
    }

    /**
     * Reads an account without creating one. {@code getInfo} would happily
     * invent an empty record for a typo'd UUID and store it forever.
     */
    private static PlayerInfo info(String uuid) {
        if (Vars.netServer == null || uuid == null || uuid.isBlank()) {
            return null;
        }

        return Vars.netServer.admins.playerInfo.get(uuid);
    }

    /** True for an address worth banning; filters blanks and the placeholder. */
    static boolean usableIp(String ip) {
        return ip != null && !ip.isBlank() && !UNKNOWN_IP.equals(ip);
    }
}
