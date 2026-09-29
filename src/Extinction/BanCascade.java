// Works out what one ban covers: the account plus its own recorded addresses, or an address plus the accounts seen there.
package Extinction;

import arc.struct.Seq;
import mindustry.Vars;
import mindustry.net.Administration.PlayerInfo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// One step and never further: a deeper cascade through shared addresses banned strangers (see GAMEPLAY.md, Bans).
// Pure computation over the admin store: it changes nothing; Bans applies the result.
final class BanCascade {

    // Mindustry's placeholder address for an account never seen connecting - shared by all of them, never banned.
    private static final String UNKNOWN_IP = "<unknown>";

    private BanCascade() {
    }

    // Everything one ban covers: the accounts, their addresses, their names.
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

    // A UUID ban: the account plus its own recorded addresses; never on through them to other accounts.
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

    // An IP ban: the address plus every account seen there - the set vanilla's banPlayerIP flips anyway.
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

    // Every account that has ever connected from one of these addresses; the unban report uses it.
    static Set<String> accountsUsing(Set<String> ips) {
        Set<String> uuids = new LinkedHashSet<>();
        addAccountsUsing(ips, uuids);
        return uuids;
    }

    // Every name ever used by these accounts.
    static List<String> namesOf(Set<String> uuids) {
        return collectNames(uuids);
    }

    // Every address this account has ever connected from.
    static Set<String> ipsOf(String uuid) {
        Set<String> ips = new LinkedHashSet<>();
        collectIps(info(uuid), ips);
        return ips;
    }

    // Adds every account that has ever connected from one of these addresses.
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

    // Every name every account in the cascade has ever used, newest last.
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

    // The account's last known name for the headline; the UUID for an account the server has never seen.
    private static String labelFor(String uuid) {
        PlayerInfo info = info(uuid);

        return info == null || info.lastName == null || info.lastName.isBlank()
                ? uuid
                : info.lastName;
    }

    // Reads an account without creating one: getInfo would store an empty record for a typo'd UUID forever.
    private static PlayerInfo info(String uuid) {
        if (Vars.netServer == null || uuid == null || uuid.isBlank()) {
            return null;
        }

        return Vars.netServer.admins.playerInfo.get(uuid);
    }

    // True for an address worth banning; filters blanks and the placeholder.
    static boolean usableIp(String ip) {
        return ip != null && !ip.isBlank() && !UNKNOWN_IP.equals(ip);
    }
}
