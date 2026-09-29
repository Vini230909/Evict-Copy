// The hub's ban system: every ban widened to the account's own addresses, kicked, announced, logged and shared with the workers.
package Extinction;

import Extinction.core.text.Text;
import Extinction.core.util.PluginLog;

import arc.Events;
import arc.util.Strings;
import mindustry.Vars;
import mindustry.game.EventType.PlayerBanEvent;
import mindustry.game.EventType.PlayerIpBanEvent;
import mindustry.game.EventType.PlayerIpUnbanEvent;
import mindustry.game.EventType.PlayerUnbanEvent;
import mindustry.gen.Groups;
import mindustry.net.Administration;
import mindustry.net.Administration.PlayerInfo;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

// Hooks the vanilla ban events, so the commands and the hammer admins know keep working. Why one step only: GAMEPLAY.md, Bans.
// Hub only: a worker applies what the hub decided (WorkerBans) and forwards its own bans here.
public final class Bans {

    // One account to ban, with why and where it was decided; the word filter's hit only when the filter decided it.
    public record Request(String uuid, Origin origin, WordFilter.Hit wordFilterHit) {

        // A person's ban: an admin's /ban, hammer or console command.
        public static Request admin(String uuid, Origin origin) {
            return new Request(uuid, origin, null);
        }

        // The word filter's ban, with what it saw.
        public static Request wordFilter(String uuid, Origin origin, WordFilter.Hit hit) {
            return new Request(uuid, origin, hit);
        }

        public boolean isEmpty() {
            return uuid == null || uuid.isBlank();
        }
    }

    // Who decided a ban, where, when - in the console's own format, so the log line can be found again - and why.
    public record Origin(String actor, String server, String consoleTime, String reason) {

        // The word filter is not a person, but it is an actor like any other.
        public static final String WORD_FILTER_ACTOR = "the word filter";

        // A ban or unban typed into a server console carries no name: the console is its actor.
        public static final String CONSOLE = "the console";

        // Shown when nobody signed a ban at all.
        public static final String UNKNOWN_ADMIN = "an admin";

        public static final String HUB = "the hub";

        private static final DateTimeFormatter CONSOLE_TIME =
                DateTimeFormatter.ofPattern("MM-dd-yyyy HH:mm:ss");

        // Stamped the moment the ban is decided, on the server deciding it; no reason (the console, the word filter).
        public static Origin now(String actor, String server) {
            return now(actor, server, "");
        }

        public static Origin now(String actor, String server, String reason) {
            return new Origin(
                    actor == null || actor.isBlank() ? UNKNOWN_ADMIN : actor,
                    server,
                    CONSOLE_TIME.format(LocalDateTime.now()),
                    reason == null ? "" : reason.trim()
            );
        }

        // Rebuilds what a match server published, tagged with its own log.
        public static Origin fromWorker(String actor, int port, String consoleTime, String reason) {
            return new Origin(
                    actor == null || actor.isBlank() ? UNKNOWN_ADMIN : actor,
                    matchServer(port),
                    consoleTime == null || consoleTime.isBlank()
                            ? CONSOLE_TIME.format(LocalDateTime.now())
                            : consoleTime,
                    reason == null ? "" : reason.trim()
            );
        }

        // The label for one match server's console log.
        public static String matchServer(int port) {
            return "match server on port " + port;
        }

        // True when this ban was decided somewhere other than the hub.
        public boolean fromMatchServer() {
            return !HUB.equals(server);
        }
    }

    // What one moderation action came to. Plain data: the ban log ships it to Discord from another thread.
    public record Report(
            Kind kind,
            String seedLabel,
            List<String> names,
            List<String> uuids,
            List<String> ips,
            Origin origin,
            WordFilter.Hit wordFilterHit
    ) {

        // An action with no story to tell: an admin's ban, straight on the hub.
        public Report(
                Kind kind,
                String seedLabel,
                List<String> names,
                List<String> uuids,
                List<String> ips
        ) {
            this(kind, seedLabel, names, uuids, ips, null, null);
        }

        public enum Kind {

            // An admin banned somebody; the cascade ran.
            BAN,

            // The word filter banned somebody on its own.
            WORD_FILTER,

            // An admin lifted a ban. Left exactly as Mindustry applies it.
            UNBAN,

            // A ban that predates the cascade, pulled through it once.
            IMPORT
        }

        public boolean isEmpty() {
            return uuids.isEmpty() && ips.isEmpty();
        }
    }

    // Where a finished action goes to be written up.
    private final Consumer<Report> reportSink;

    // The one-off import of pre-existing bans, handed over in one piece.
    private final Consumer<List<Report>> importSink;

    // Mirrors the chat announcement into the Discord chat log: it was visible in the hub's chat.
    private final Consumer<String> announcementEcho;

    // What the kicked player reads - the ban plus how to appeal it.
    private final BanScreen screen;

    // True while the cascade applies its own bans; each fires the event this listens to.
    private boolean applying;

    // The request being seeded right now. banPlayerID fires its event before it returns, so the handler finds it here.
    private Request pending;

    // The same for an address ban or an unban asked for from Discord, which take vanilla's own route.
    private Origin pendingOrigin;

    private boolean installed;
    private boolean startupDone;

    public Bans(
            Consumer<Report> reportSink,
            Consumer<List<Report>> importSink,
            Consumer<String> announcementEcho,
            BanScreen screen
    ) {
        this.reportSink = reportSink;
        this.importSink = importSink;
        this.announcementEcho = announcementEcho;
        this.screen = screen;
    }

    // Hub-only: start widening bans. Safe to call once.
    public void install() {
        if (installed) {
            return;
        }

        installed = true;

        Events.on(PlayerBanEvent.class, event -> handleBan(
                BanCascade.fromUuid(event.uuid),
                false
        ));

        Events.on(PlayerIpBanEvent.class, event -> handleBan(
                BanCascade.fromIp(event.ip),
                true
        ));

        // Unbans are left exactly as Mindustry applies them - no cascade, logged only.
        Events.on(PlayerUnbanEvent.class, event -> handleUnban(event.uuid, null));
        Events.on(PlayerIpUnbanEvent.class, event -> handleUnban(null, event.ip));
    }

    // Startup work, once the admin store exists; called from the hub's update trigger, not init().
    public void update() {
        if (startupDone || !installed || Vars.netServer == null) {
            return;
        }

        startupDone = true;

        importExistingBans();
        publishList();
    }

    // Bans an account and remembers why; the way in for the word filter, /ban and a match server's forwarded ban.
    public void ban(Request request) {
        if (request == null || request.isEmpty() || Vars.netServer == null) {
            return;
        }

        pending = request;

        try {
            Vars.netServer.admins.banPlayerID(request.uuid());
        } finally {
            pending = null;
        }
    }

    // An address ban with its story - Discord's /ban with an IP - through vanilla's banPlayerIP, as console 'ban ip' does it.
    public void banAddress(String ip, Origin origin) {
        if (Vars.netServer == null) {
            return;
        }

        pendingOrigin = origin;

        try {
            Vars.netServer.admins.banPlayerIP(ip);
        } finally {
            pendingOrigin = null;
        }
    }

    // Lifts one ban with its story - Discord's /unban - exactly as vanilla does; true when there was one to lift.
    public boolean unban(String target, boolean address, Origin origin) {
        if (Vars.netServer == null) {
            return false;
        }

        pendingOrigin = origin;

        try {
            return address
                    ? Vars.netServer.admins.unbanPlayerIP(target)
                    : Vars.netServer.admins.unbanPlayerID(target);
        } finally {
            pendingOrigin = null;
        }
    }

    private void handleBan(BanCascade.Result result, boolean addressSeeded) {
        if (applying || result.isEmpty()) {
            return;
        }

        Request request = pending != null
                && result.uuids().contains(pending.uuid())
                ? pending
                : null;

        WordFilter.Hit hit = request == null ? null : request.wordFilterHit();

        // Nothing seeded it, so it was typed into the console: the hammer, /ban, Discord and the filter all seed theirs.
        Origin origin = request != null
                ? request.origin()
                : pendingOrigin != null ? pendingOrigin : Origin.now(Origin.CONSOLE, Origin.HUB);

        Report report = apply(
                result,
                hit == null ? Report.Kind.BAN : Report.Kind.WORD_FILTER,
                origin,
                hit
        );

        PluginLog.info(
                "@ on @ by @ covers @ account(s) and @ address(es). Reason: @",
                hit == null ? "Ban" : "Word filter ban",
                report.seedLabel(),
                origin.actor(),
                report.uuids().size(),
                report.ips().size(),
                hit != null ? "'" + hit.word() + "' in the " + hit.source().label() : origin.reason().isBlank() ? "none given" : origin.reason()
        );

        announce(report, addressSeeded);
        reportSink.accept(report);
    }

    // Tells the hub's players somebody was banned: names only, never an address. Imports and unbans say nothing.
    private void announce(Report report, boolean addressSeeded) {
        // An address ban is labelled with the address; name the account it hit instead, or say nothing.
        String name = addressSeeded
                ? (report.names().isEmpty() ? null : report.names().get(0))
                : report.seedLabel();

        if (name == null) {
            return;
        }

        String cleanName = Strings.stripColors(name);
        String suffix;

        if (report.kind() == Report.Kind.WORD_FILTER) {
            suffix = " was banned automatically for using a forbidden word.";
        } else if (report.origin() != null && report.origin().fromMatchServer()) {
            suffix = " was banned on a match server.";
        } else {
            suffix = " was banned.";
        }

        Text.of().scarlet(cleanName).white(suffix).sendAll();

        if (announcementEcho != null) {
            announcementEcho.accept(cleanName + suffix);
        }
    }

    // Reports what an unban freed, read back off the live state: vanilla's unban is wider than its argument.
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

            // The addresses vanilla just removed from the ban list along with the account.
            for (String freed : BanCascade.ipsOf(uuid)) {
                if (!admins.bannedIPs.contains(freed, false)) {
                    ips.add(freed);
                }
            }
        } else if (BanCascade.usableIp(ip)) {
            ips.add(ip);
            label = ip;

            // Every account that was banned through this address and is not banned any more.
            for (String freed : BanCascade.accountsUsing(ips)) {
                if (!admins.isIDBanned(freed)) {
                    uuids.add(freed);
                }
            }
        } else {
            return;
        }

        // Discord's /unban names itself; any other unban was typed into the console.
        Origin origin = pendingOrigin != null ? pendingOrigin : Origin.now(Origin.CONSOLE, Origin.HUB);

        PluginLog.info(
                "Unban on @ by @ freed @ account(s) and @ address(es).",
                label,
                origin.actor(),
                uuids.size(),
                ips.size()
        );

        publishList();

        reportSink.accept(new Report(
                Report.Kind.UNBAN,
                label,
                BanCascade.namesOf(uuids),
                List.copyOf(uuids),
                List.copyOf(ips),
                origin,
                null
        ));
    }

    // Applies a cascade: accounts through banPlayerID, addresses onto the list directly so no stranger is flipped.
    private Report apply(
            BanCascade.Result result,
            Report.Kind kind,
            Origin origin,
            WordFilter.Hit hit
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

        return new Report(
                kind,
                result.seedLabel(),
                BanCascade.namesOf(uuids),
                List.copyOf(uuids),
                List.copyOf(result.ips()),
                origin,
                hit
        );
    }

    // Throws out everyone the ban caught - vanilla only kicks the account typed - with the plugin's own screen, first.
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
            screen.kick(player.con);
        }
    }

    // Rewrites the workers' ban file from the live admin store, in full, so hand-made bans and unbans are in it too.
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

    // Pulls the bans that predate the cascade through it, once ever; seeds an earlier one covered are skipped.
    private void importExistingBans() {
        if (Config.banBackfillDone) {
            return;
        }

        runImport();
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

        Config.banBackfillDone = true;
        Config.save();

        if (seedUuids.isEmpty() && seedIps.isEmpty()) {
            return 0;
        }

        List<Report> reports = new ArrayList<>();
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
            List<Report> reports,
            Set<String> covered
    ) {
        if (result.isEmpty()) {
            return;
        }

        Report report = apply(result, Report.Kind.IMPORT, null, null);

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
