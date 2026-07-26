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
 * connection. So a ban is not applied to the one identifier an admin happened
 * to type: it is expanded across the links Mindustry already records in its
 * admin store, where every {@code PlayerInfo} carries every IP that account has
 * ever connected from.
 *
 * <p>The expansion is deliberately two steps deep and then stops:
 *
 * <ul>
 *   <li>seeded with a UUID: that account's IPs, then every account that has
 *       used one of those IPs;</li>
 *   <li>seeded with an IP: every account that has used it, then all of those
 *       accounts' IPs.</li>
 * </ul>
 *
 * <p>Running it to exhaustion instead was considered and rejected: one shared
 * mobile-carrier address anywhere in the chain links two strangers, and from
 * there the closure grows through their addresses into accounts that have
 * nothing to do with each other. Two steps catches the alt accounts of one
 * person - which is the point - without the chain ever escaping into the rest
 * of the player base.
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
     * Expands a UUID ban: the account's own addresses, then every account
     * reachable through them.
     */
    static Result fromUuid(String uuid) {
        Set<String> uuids = new LinkedHashSet<>();
        Set<String> ips = new LinkedHashSet<>();

        if (uuid == null || uuid.isBlank()) {
            return empty();
        }

        uuids.add(uuid);
        collectIps(info(uuid), ips);
        addAccountsUsing(ips, uuids);

        return build(labelFor(uuid), uuids, ips);
    }

    /**
     * Expands an IP ban: every account seen at that address, then all of their
     * other addresses.
     */
    static Result fromIp(String ip) {
        Set<String> uuids = new LinkedHashSet<>();
        Set<String> ips = new LinkedHashSet<>();

        if (!usableIp(ip)) {
            return empty();
        }

        ips.add(ip);
        addAccountsUsing(ips, uuids);

        for (String accountUuid : List.copyOf(uuids)) {
            collectIps(info(accountUuid), ips);
        }

        return build(ip, uuids, ips);
    }

    /**
     * Every account that has ever connected from one of these addresses.
     *
     * <p>Used to reconcile the report with what actually happened: Mindustry's
     * own {@code banPlayerIP} marks every account that has used the address, so
     * banning the second-step addresses can catch accounts the two-step
     * expansion never listed. The log has to say who was really hit, not who
     * the algorithm planned to hit.
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
