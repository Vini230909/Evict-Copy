// Ban evasion: a banned address or account that tries to join again is written up, and a banned account's new address is banned.
package Extinction;

import Extinction.core.util.PluginLog;

import arc.util.Strings;
import mindustry.Vars;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.net.Administration;
import mindustry.net.Administration.PlayerInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

// Hub only. A banned address is refused before the game knows the account, so the line is all there is. A banned account
// from a new address gets that address banned too - its own address, the one-step rule - unless it is a VPN, shared by many.
public final class Evasion {

    // One line per address, or per account and address, this often: a banned player retrying must not flood the channel.
    private static final long WRITE_INTERVAL_MILLIS = 10L * 60L * 1000L;

    // The banned accounts named for a banned address; more than this become the first few.
    private static final int MAX_NAMES = 3;

    // What became of a refused comeback.
    public enum Outcome {

        // A banned address itself: refused, nothing more to do.
        KNOWN_ADDRESS,

        // A banned account from a new address: that address is banned now too.
        ADDRESS_BANNED,

        // A banned account through a VPN, proxy or hosting address: shared by many, so left open.
        VPN,

        // A banned account from an address that could not be looked up: left open.
        UNCHECKED
    }

    // One refused comeback for the ban log: who (blank for a banned address), from where, what the address is, and why.
    public record Entry(String name, String uuid, String ip, VpnVerdict verdict, Outcome outcome, List<String> bannedNames) {
    }

    private final Bans bans;
    private final VpnScan scan;
    private final BanScreen screen;

    // The ban log.
    private final Consumer<Entry> log;

    // Address, or account and address -> when it was last written up. Main thread only.
    private final Map<String, Long> lastWritten = new HashMap<>();

    public Evasion(Bans bans, VpnScan scan, BanScreen screen, Consumer<Entry> log) {
        this.bans = bans;
        this.scan = scan;
        this.screen = screen;
        this.log = log;
    }

    // A refused comeback from BanScreen, on the main thread; the connection is already kicked.
    public void refused(BanScreen.Refusal refusal) {
        if (refusal == null || refusal.ip() == null || refusal.ip().isBlank() || Vars.netServer == null) {
            return;
        }

        String ip = refusal.ip();

        if (refusal.uuid().isBlank()) {
            if (due(ip)) {
                write(new Entry("", "", ip, null, Outcome.KNOWN_ADDRESS, bannedNamesAt(ip)));
            }

            return;
        }

        String uuid = refusal.uuid();
        String name = refusal.name();

        if (!due(uuid + "@" + ip)) {
            return;
        }

        scan.check(ip, verdict -> {
            // Unbanned while the lookup ran: nothing to evade any more.
            if (!Vars.netServer.admins.isIDBanned(uuid)) {
                return;
            }

            Outcome outcome = verdict == null
                    ? Outcome.UNCHECKED
                    : verdict.flagged() ? Outcome.VPN : Outcome.ADDRESS_BANNED;

            if (outcome == Outcome.ADDRESS_BANNED) {
                banAddress(uuid, ip);
            }

            write(new Entry(name, uuid, ip, verdict, outcome, List.of()));
        });
    }

    // Recorded on the account, so its unban lifts it again; added quietly like the cascade's, never via banPlayerIP.
    private void banAddress(String uuid, String ip) {
        Administration admins = Vars.netServer.admins;
        PlayerInfo info = admins.getInfoOptional(uuid);

        if (info != null) {
            info.ips.addUnique(ip);
        }

        if (!admins.bannedIPs.contains(ip, false)) {
            admins.bannedIPs.add(ip);
        }

        admins.save();
        bans.publishList();

        // Whoever is still playing from it goes too, like any address the cascade bans.
        List<Player> connected = new ArrayList<>();

        Groups.player.each(player -> {
            if (player != null && player.con != null && ip.equals(player.con.address)) {
                connected.add(player);
            }
        });

        for (Player player : connected) {
            screen.kick(player.con);
        }
    }

    private void write(Entry entry) {
        PluginLog.info(
                "Ban evasion: @ from @ - @.",
                entry.uuid().isBlank() ? "a banned address" : Strings.stripColors(entry.name()) + " (" + entry.uuid() + ")",
                entry.ip(),
                switch (entry.outcome()) {
                    case KNOWN_ADDRESS -> "refused";
                    case ADDRESS_BANNED -> "refused, and the new address is banned too";
                    case VPN -> "refused; the address is a VPN or proxy and stays open";
                    case UNCHECKED -> "refused; the address could not be looked up and stays open";
                }
        );

        if (log != null) {
            log.accept(entry);
        }
    }

    // True when this key was not written up within the interval; notes it as written now.
    private boolean due(String key) {
        long now = System.currentTimeMillis();
        Long last = lastWritten.get(key);

        if (last != null && now - last < WRITE_INTERVAL_MILLIS) {
            return false;
        }

        lastWritten.values().removeIf(time -> now - time >= WRITE_INTERVAL_MILLIS);
        lastWritten.put(key, now);
        return true;
    }

    // The banned accounts that have used this address - whose comeback it most likely is.
    private static List<String> bannedNamesAt(String ip) {
        List<String> names = new ArrayList<>();

        for (PlayerInfo info : Vars.netServer.admins.playerInfo.values()) {
            if (names.size() >= MAX_NAMES) {
                break;
            }

            if (
                    info != null
                            && info.banned
                            && info.lastName != null
                            && !info.lastName.isBlank()
                            && (ip.equals(info.lastIP) || (info.ips != null && info.ips.contains(ip, false)))
            ) {
                names.add(info.lastName);
            }
        }

        return names;
    }
}
