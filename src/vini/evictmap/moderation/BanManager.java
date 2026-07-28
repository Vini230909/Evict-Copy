package vini.evictmap.moderation;

import arc.Events;
import mindustry.Vars;
import mindustry.game.EventType.PlayerBanEvent;
import mindustry.game.EventType.PlayerIpBanEvent;
import mindustry.game.EventType.PlayerIpUnbanEvent;
import mindustry.game.EventType.PlayerUnbanEvent;
import mindustry.gen.Groups;
import mindustry.net.Administration;
import mindustry.net.Administration.PlayerInfo;
import mindustry.net.Packets.KickReason;
import vini.evictmap.core.util.PluginLog;
import vini.evictmap.gen.EvictSettings;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Makes a ban stick.
 *
 * <p>Vanilla bans are narrower than they look. {@code ban id} bans the account
 * and leaves the address free; {@code ban name} short-circuits and never gets
 * to the address at all; {@code ban ip} bans the accounts seen there but not
 * the other addresses those accounts use. Only the in-game hammer bans both,
 * and only the address the player is connected from right now. Every one of
 * them is a five-minute inconvenience to somebody with a second install.
 *
 * <p>This manager hooks the events Mindustry fires for all of those paths, so
 * admins keep using the commands and the button they already know, and widens
 * each ban through {@link BanCascade} - one step: the banned account's own
 * addresses, never onward to other accounts. Everything the cascade turns up
 * is banned, kicked and written to the file the duel workers read.
 *
 * <p>The widening adds addresses to the banned-IP list directly instead of
 * calling {@code banPlayerIP}: vanilla's method also flips every account that
 * ever used the address, and on a shared address (CGNAT, VPN, a university)
 * that permanently bans strangers. A plugin-added address blocks connections
 * from it, nothing more; only an admin's own explicit {@code ban ip} keeps
 * vanilla's account-flipping semantics.
 *
 * <p>Hub only. A worker applies what the hub decided (see {@link BanSync}) and
 * must never run a cascade of its own: its admin store is a throwaway copy, and
 * two processes deciding who is banned is one too many.
 */
public final class BanManager {

    private final EvictSettings settings;

    /** Where a finished action goes to be written up. */
    private final Consumer<BanReport> reportSink;

    /** The one-off import of pre-existing bans, handed over in one piece. */
    private final Consumer<List<BanReport>> importSink;

    /**
     * True while the cascade is applying its own bans. Every
     * {@code banPlayerID} fires the event this class listens to, so without
     * this the first ban would start a cascade inside a cascade inside a
     * cascade.
     */
    private boolean applying;

    /**
     * The word-filter hit being seeded right now, if any. {@code banPlayerID}
     * fires its event on this thread before it returns, so the handler picks
     * the details up from here instead of the ban paths all having to carry a
     * cause they do not have.
     */
    private String wordFilterUuid;
    private WordFilterHit wordFilterHit;

    private boolean installed;
    private boolean startupDone;

    public BanManager(
            EvictSettings settings,
            Consumer<BanReport> reportSink,
            Consumer<List<BanReport>> importSink
    ) {
        this.settings = settings;
        this.reportSink = reportSink;
        this.importSink = importSink;
    }

    /** Hub-only: start widening bans. Safe to call once. */
    public void install() {
        if (installed) {
            return;
        }

        installed = true;

        Events.on(PlayerBanEvent.class, event -> handleBan(
                BanCascade.fromUuid(event.uuid)
        ));

        Events.on(PlayerIpBanEvent.class, event -> handleBan(
                BanCascade.fromIp(event.ip)
        ));

        // Unbans are left exactly as Mindustry applies them - no cascade. An
        // admin lifting one ban means that one ban, and unwinding a whole
        // cluster from a single command is the kind of surprise that is only
        // noticed weeks later. They are logged, nothing more.
        Events.on(PlayerUnbanEvent.class, event -> handleUnban(event.uuid, null));
        Events.on(PlayerIpUnbanEvent.class, event -> handleUnban(null, event.ip));
    }

    /**
     * Runs the startup work the first time the server is far enough along to
     * have an admin store. Called from the hub's update trigger rather than
     * from {@code init()}, where {@code netServer} may not exist yet.
     */
    public void update() {
        if (startupDone || !installed || Vars.netServer == null) {
            return;
        }

        startupDone = true;

        importExistingBans();
        publishList();
    }

    /**
     * Bans an account the word filter caught, remembering what it caught.
     *
     * <p>Goes through {@code banPlayerID} like every other ban, so the account
     * is widened, kicked, written to the workers' list and logged - the entry
     * is simply marked as the filter's rather than an admin's, and carries the
     * word, the text and the console time to find it by.
     */
    public void banForWordFilter(String uuid, WordFilterHit hit) {
        if (uuid == null || uuid.isBlank() || Vars.netServer == null) {
            return;
        }

        wordFilterUuid = uuid;
        wordFilterHit = hit;

        try {
            Vars.netServer.admins.banPlayerID(uuid);
        } finally {
            wordFilterUuid = null;
            wordFilterHit = null;
        }
    }

    private void handleBan(BanCascade.Result result) {
        if (applying || result.isEmpty()) {
            return;
        }

        WordFilterHit hit = wordFilterUuid != null
                && result.uuids().contains(wordFilterUuid)
                ? wordFilterHit
                : null;

        BanReport report = apply(
                result,
                hit == null ? BanReport.Kind.BAN : BanReport.Kind.WORD_FILTER,
                hit
        );

        PluginLog.info(
                "@ on @ covers @ account(s) and @ address(es).",
                hit == null ? "Ban" : "Word filter ban",
                report.seedLabel(),
                report.uuids().size(),
                report.ips().size()
        );

        reportSink.accept(report);
    }

    /**
     * Reports what an unban actually freed.
     *
     * <p>Mindustry's unban is wider than the identifier it is given, in both
     * directions: lifting an account also drops every address that account has
     * used, and lifting an address also lifts every account seen there. The
     * event fires after all of that has happened, so the report is read back
     * off the live state rather than assumed - an admin has to be able to see
     * what a single command let back in.
     */
    private void handleUnban(String uuid, String ip) {
        if (applying) {
            return;
        }

        Administration admins = Vars.netServer.admins;
        Set<String> uuids = new LinkedHashSet<>();
        Set<String> ips = new LinkedHashSet<>();
        String label;

        if (uuid != null && !uuid.isBlank()) {
            uuids.add(uuid);
            label = nameOf(uuid);

            // The addresses vanilla just removed from the ban list along with
            // the account.
            for (String freed : BanCascade.ipsOf(uuid)) {
                if (!admins.bannedIPs.contains(freed, false)) {
                    ips.add(freed);
                }
            }
        } else if (BanCascade.usableIp(ip)) {
            ips.add(ip);
            label = ip;

            // Every account that was banned through this address and is not
            // banned any more.
            for (String freed : BanCascade.accountsUsing(ips)) {
                if (!admins.isIDBanned(freed)) {
                    uuids.add(freed);
                }
            }
        } else {
            return;
        }

        PluginLog.info(
                "Unban on @ freed @ account(s) and @ address(es).",
                label,
                uuids.size(),
                ips.size()
        );

        publishList();

        reportSink.accept(new BanReport(
                BanReport.Kind.UNBAN,
                label,
                BanCascade.namesOf(uuids),
                List.copyOf(uuids),
                List.copyOf(ips)
        ));
    }

    /**
     * Applies a cascade and reports what it hit.
     *
     * <p>Accounts are banned through {@code banPlayerID} as usual. Addresses
     * are added to the banned-IP list directly, without {@code banPlayerIP}:
     * that method would flip every account that ever used the address, and on
     * a shared one that bans strangers for good. A quietly added address still
     * blocks connections and is still lifted by a normal unban (vanilla's
     * {@code unbanPlayerID} drops the account's addresses from the same list).
     */
    private BanReport apply(
            BanCascade.Result result,
            BanReport.Kind kind,
            WordFilterHit hit
    ) {
        Administration admins = Vars.netServer.admins;

        applying = true;

        try {
            for (String uuid : result.uuids()) {
                admins.banPlayerID(uuid);
            }

            boolean addressesAdded = false;

            for (String ip : result.ips()) {
                if (!admins.bannedIPs.contains(ip, false)) {
                    admins.bannedIPs.add(ip);
                    addressesAdded = true;
                }
            }

            if (addressesAdded) {
                admins.save();
            }
        } finally {
            applying = false;
        }

        Set<String> uuids = new LinkedHashSet<>(result.uuids());

        kickBanned(uuids, result.ips());
        publishList();

        return new BanReport(
                kind,
                result.seedLabel(),
                BanCascade.namesOf(uuids),
                List.copyOf(uuids),
                List.copyOf(result.ips()),
                hit
        );
    }

    /**
     * Throws out everyone the ban just caught. Mindustry only kicks the account
     * the admin typed; the alts it pulled in would otherwise keep playing until
     * they next reconnected.
     */
    private void kickBanned(Set<String> uuids, Set<String> ips) {
        List<mindustry.gen.Player> hit = new ArrayList<>();

        Groups.player.each(player -> {
            if (player == null || player.con == null) {
                return;
            }

            if (uuids.contains(player.uuid()) || ips.contains(player.con.address)) {
                hit.add(player);
            }
        });

        for (mindustry.gen.Player player : hit) {
            player.con.kick(KickReason.banned);
        }
    }

    /**
     * Rewrites the shared ban file from the live admin store, so the duel
     * workers see the same bans the hub does. Rebuilt in full rather than
     * appended to, which also picks up bans made before this feature existed
     * and unbans made by hand.
     */
    private void publishList() {
        if (Vars.netServer == null) {
            return;
        }

        Administration admins = Vars.netServer.admins;
        Set<String> uuids = new LinkedHashSet<>();
        Set<String> ips = new LinkedHashSet<>();

        for (PlayerInfo info : admins.getBanned()) {
            if (info != null && info.id != null && !info.id.isBlank()) {
                uuids.add(info.id);
            }
        }

        for (String ip : admins.bannedIPs) {
            if (BanCascade.usableIp(ip)) {
                ips.add(ip);
            }
        }

        BanList.write(BanList.HUB_FILE, uuids, ips);
    }

    /**
     * Pulls the bans that predate this feature through the cascade, once ever.
     * Without it every account banned in the old days keeps its untouched
     * addresses, and the people already evading those bans stay invisible.
     *
     * <p>Seeds already covered by an earlier cascade in the same run are
     * skipped, so one cluster of six aliases produces one entry rather than
     * six.
     */
    private void importExistingBans() {
        if (settings.banBackfillDone()) {
            return;
        }

        runImport();
    }

    /**
     * Runs the import again on demand, whether or not it has run before.
     *
     * <p>The automatic one fires on the first start after the upgrade, which is
     * necessarily before anyone could have configured the log webhook - so the
     * bans get widened, but nothing is written up. This is how an admin gets
     * the write-up: every ban currently on the server, pulled through the
     * cascade and posted, one entry per cluster.
     *
     * @return how many entries were reported
     */
    public int importNow() {
        if (Vars.netServer == null) {
            return 0;
        }

        return runImport();
    }

    private int runImport() {
        Administration admins = Vars.netServer.admins;

        // Snapshotted first: applying the cascade adds to both of these.
        List<String> seedUuids = new ArrayList<>();
        List<String> seedIps = new ArrayList<>();

        for (PlayerInfo info : admins.getBanned()) {
            if (info != null && info.id != null && !info.id.isBlank()) {
                seedUuids.add(info.id);
            }
        }

        for (String ip : admins.bannedIPs) {
            if (BanCascade.usableIp(ip)) {
                seedIps.add(ip);
            }
        }

        settings.markBanBackfillDone();

        if (seedUuids.isEmpty() && seedIps.isEmpty()) {
            return 0;
        }

        List<BanReport> reports = new ArrayList<>();
        Set<String> covered = new LinkedHashSet<>();

        for (String uuid : seedUuids) {
            if (covered.contains(uuid)) {
                continue;
            }

            collectImport(BanCascade.fromUuid(uuid), reports, covered);
        }

        for (String ip : seedIps) {
            if (covered.contains(ip)) {
                continue;
            }

            collectImport(BanCascade.fromIp(ip), reports, covered);
        }

        PluginLog.info(
                "Imported @ existing ban(s) as @ entries; the cascade now covers @ accounts and addresses.",
                seedUuids.size() + seedIps.size(),
                reports.size(),
                covered.size()
        );

        importSink.accept(reports);
        return reports.size();
    }

    private void collectImport(
            BanCascade.Result result,
            List<BanReport> reports,
            Set<String> covered
    ) {
        if (result.isEmpty()) {
            return;
        }

        BanReport report = apply(result, BanReport.Kind.IMPORT, null);

        covered.addAll(report.uuids());
        covered.addAll(report.ips());
        reports.add(report);
    }

    private static String nameOf(String uuid) {
        PlayerInfo info = Vars.netServer == null
                ? null
                : Vars.netServer.admins.playerInfo.get(uuid);

        return info == null || info.lastName == null || info.lastName.isBlank()
                ? uuid
                : info.lastName;
    }
}
