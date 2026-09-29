// The Discord ban log: one plain-text message per ban, unban, VPN check or free, queued and posted to a staff-only webhook.
package Extinction;

import Extinction.core.util.MessageIdFilter;
import Extinction.core.util.PluginLog;
import Extinction.discord.DiscordFormat;
import Extinction.discord.DiscordJson;
import Extinction.discord.DiscordWebhook;
import Extinction.moderation.vpn.VpnScanHit;
import Extinction.moderation.vpn.VpnVerdict;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

// Hub only: every worker would report the same ban. Never edits: a log is a sequence. Layout as on Fish (see GAMEPLAY.md, Bans).
public final class BanLog {

    // Discord rate-limits a webhook at a handful of requests a second, so the queue drips.
    private static final long PACE_MILLIS = 1_500L;

    // Reached only while Discord is unreachable for long; the oldest entry is dropped first.
    private static final int MAX_QUEUE = 250;

    // A first import larger than this is posted as summaries of IMPORT_SUMMARY_CHUNK instead.
    private static final int MAX_INDIVIDUAL_IMPORTS = 25;
    private static final int IMPORT_SUMMARY_CHUNK = 20;

    // Accounts or addresses shown on one line before the rest become "+ N more".
    private static final int MAX_LISTED = 10;

    // A name keeps its colour tags here, so it gets more room than the chat mirror's.
    private static final int MAX_NAME_LENGTH = 80;

    // Discord's hard limit on one message.
    private static final int MAX_MESSAGE = 2000;

    // Actors that are not a person's name read as the start of a sentence: "The console banned ...".
    private static final Set<String> PHRASE_ACTORS = Set.of("the console", Bans.Origin.WORD_FILTER_ACTOR, Bans.Origin.UNKNOWN_ADMIN);

    // Players choose their names, so nothing in an entry may ping (a player called @everyone).
    private static final String NO_MENTIONS = "{\"parse\":[]}";

    // A log never edits a message, so there is no id to remember.
    private final DiscordWebhook webhook = new DiscordWebhook(messageId -> {
    });

    private final Deque<String> pending = new ArrayDeque<>();

    private long lastSendMillis;
    private boolean started;
    private int dropped;

    // Hub-only startup: adopt the stored webhook.
    public void start() {
        if (started) {
            return;
        }

        started = true;
        webhook.configure(Config.discordBanLogWebhookUrl, "");

        if (webhook.isConfigured()) {
            PluginLog.info("Discord ban log is on.");
        }
    }

    // Called every frame from the hub's update trigger; paced internally.
    public void update() {
        if (!started || pending.isEmpty()) {
            return;
        }

        if (!webhook.isConfigured() || webhook.isBroken()) {
            pending.clear();
            return;
        }

        long now = System.currentTimeMillis();

        if (now - lastSendMillis < PACE_MILLIS || !webhook.canSend()) {
            return;
        }

        lastSendMillis = now;
        webhook.post(pending.poll());
    }

    // Queues one action. Cheap and safe to call when no webhook is set.
    public void log(Bans.Report report) {
        if (report == null || report.isEmpty() || !webhook.isConfigured()) {
            return;
        }

        enqueue(banMessage(report));
    }

    // One VPN scan hit; same queue as the bans, so it never crowds one out of order.
    public void logVpnHit(VpnScanHit hit) {
        if (hit == null || !webhook.isConfigured()) {
            return;
        }

        enqueue(vpnCheck(hit.name(), hit.timesJoined(), hit.verdict(), hit.uuid(), hit.ip()));
    }

    // A lock, a locked account's join or a free.
    public void logLock(PlayerLock.Event event) {
        if (event == null || !webhook.isConfigured()) {
            return;
        }

        enqueue(lockMessage(event));
    }

    // The console test's verdict, so the test proves the channel too.
    public void logVpnTest(VpnVerdict verdict) {
        if (verdict == null || !webhook.isConfigured()) {
            return;
        }

        enqueue("VPN check (test): " + code(verdict.ip()) + "\n**Triggered:** " + triggered(verdict));
    }

    // True while a webhook is set - whether or not it is currently healthy.
    public boolean isConfigured() {
        return webhook.isConfigured();
    }

    // The one-off import of old bans: one entry each while few, summaries when many.
    public void logImport(List<Bans.Report> reports) {
        if (reports == null || reports.isEmpty() || !webhook.isConfigured()) {
            return;
        }

        if (reports.size() <= MAX_INDIVIDUAL_IMPORTS) {
            for (Bans.Report report : reports) {
                log(report);
            }

            return;
        }

        List<List<Bans.Report>> chunks = chunk(reports);

        for (int index = 0; index < chunks.size(); index++) {
            enqueue(importSummary(chunks.get(index), index + 1, chunks.size()));
        }
    }

    // 'banlog <url>': a blank URL turns it off; anything that is not a webhook URL is refused.
    public boolean configure(String webhookUrl) {
        String trimmed = webhookUrl == null ? "" : webhookUrl.trim();

        if (!isWebhookUrl(trimmed)) {
            return false;
        }

        Config.discordBanLogWebhookUrl = trimmed;
        Config.save();
        webhook.configure(trimmed, "");
        return true;
    }

    // 'banlog off': stops logging and forgets the queue.
    public void disable() {
        pending.clear();
        Config.discordBanLogWebhookUrl = "";
        Config.save();
        webhook.configure("", "");
    }

    // 'banlog': one line describing the current wiring.
    public String statusLine() {
        if (!webhook.isConfigured()) {
            return "not set (use 'banlog <webhook-url>')";
        }

        StringBuilder status = new StringBuilder();
        status.append(webhook.isBroken() ? "BROKEN" : "on");
        status.append(", queued=").append(pending.size());

        if (dropped > 0) {
            status.append(", dropped=").append(dropped);
        }

        if (webhook.lastSuccessMillis() > 0L) {
            status.append(", last success ")
                    .append((System.currentTimeMillis() - webhook.lastSuccessMillis()) / 1000L)
                    .append("s ago");
        }

        if (!webhook.lastError().isEmpty()) {
            status.append(", last error: ").append(webhook.lastError());
        }

        return status.toString();
    }

    // 'banlog test': a sample entry in the real layout, so an admin can check the channel is wired up.
    public boolean publishTest() {
        if (!webhook.isConfigured()) {
            return false;
        }

        enqueue(banMessage(new Bans.Report(
                Bans.Report.Kind.BAN,
                "nobody (ban log test, nothing was banned)",
                List.of(),
                List.of(),
                List.of(),
                Bans.Origin.now("the console", Bans.Origin.HUB),
                null
        )));

        return true;
    }

    private void enqueue(String content) {
        while (pending.size() >= MAX_QUEUE) {
            pending.poll();
            dropped++;
        }

        pending.add(new DiscordJson.Obj()
                .raw("allowed_mentions", NO_MENTIONS)
                .str("content", DiscordFormat.truncate(content, MAX_MESSAGE))
                .toString());
    }

    // "<Admin> banned <name> with the **reason:** <reason>", then the server, the accounts and the addresses.
    private static String banMessage(Bans.Report report) {
        Bans.Origin origin = report.origin();
        String actor = actor(origin == null ? Bans.Origin.UNKNOWN_ADMIN : origin.actor());
        String subject = coloredName(subject(report));

        String headline = switch (report.kind()) {
            case BAN, WORD_FILTER -> actor + " banned " + subject + " with the **reason:** " + reason(report);
            case UNBAN -> actor + " unbanned " + subject;
            case IMPORT -> "An old ban was imported: " + subject;
        };

        return headline
                + "\n**Server:** " + server(origin)
                + "\n**uuid:** " + codes(report.uuids())
                + "\n**ip:** " + codes(report.ips());
    }

    // "VPN check: <name> joined first time" for a lock, "... joined 3 times" for a locked account back; a free says who.
    private static String lockMessage(PlayerLock.Event event) {
        if (event.kind() == PlayerLock.Event.Kind.FREED) {
            return "VPN check: " + coloredName(event.name()) + " was freed by " + DiscordFormat.escapeMarkdown(event.actor())
                    + "\n**uuid:** " + code(event.uuid())
                    + "\n**ip:** " + code(event.ip());
        }

        return vpnCheck(event.name(), event.joins(), event.verdict(), event.uuid(), event.ip());
    }

    private static String vpnCheck(String name, int joins, VpnVerdict verdict, String uuid, String ip) {
        return "VPN check: " + coloredName(name) + " joined " + (joins <= 1 ? "first time" : joins + " times")
                + "\n**Triggered:** " + triggered(verdict)
                + "\n**uuid:** " + code(uuid)
                + "\n**ip:** " + code(ip);
    }

    // What each source said, then where the address is: "vpnapi: vpn / ip-api: proxy **|** AS9009 M247 Europe SRL (RO)".
    private static String triggered(VpnVerdict verdict) {
        if (verdict == null) {
            return "no verdict **|** unknown";
        }

        return DiscordFormat.escapeMarkdown(verdict.flags()) + " **|** " + DiscordFormat.escapeMarkdown(verdict.network());
    }

    // No reason is asked for yet except by the word filter, whose reason is the word and where it stood.
    private static String reason(Bans.Report report) {
        WordFilter.Hit hit = report.wordFilterHit();

        if (hit == null) {
            return "none given";
        }

        String word = "banned word \"" + DiscordFormat.escapeMarkdown(hit.word()) + "\"";

        return hit.source() == WordFilter.Hit.Source.CHAT
                ? word + " in the chat message: " + DiscordFormat.playerText(hit.text())
                : word + " in the player name";
    }

    // What was acted on; an address ban is named after the first account it hit, when there is one.
    private static String subject(Bans.Report report) {
        String seed = report.seedLabel();

        if (report.ips().contains(seed) && !report.names().isEmpty()) {
            return report.names().get(0);
        }

        return seed;
    }

    // An admin's name as it is; "the console" or "an admin" start the sentence with a capital.
    private static String actor(String actor) {
        String name = actor == null || actor.isBlank() ? Bans.Origin.UNKNOWN_ADMIN : actor;

        if (PHRASE_ACTORS.contains(name)) {
            name = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        }

        return DiscordFormat.escapeMarkdown(name);
    }

    // "hub" or "port-6568"; a ban with no origin is a vanilla one, and those happen on the hub.
    private static String server(Bans.Origin origin) {
        // Bans.Origin.matchServer writes "match server on port <n>".
        String matchServer = "match server on port ";

        if (origin == null || origin.server() == null || Bans.Origin.HUB.equals(origin.server())) {
            return "hub";
        }

        return origin.server().startsWith(matchServer)
                ? "port-" + origin.server().substring(matchServer.length())
                : DiscordFormat.escapeMarkdown(origin.server());
    }

    // The name with its colour tags, as players see it typed: markdown escaped, no masked link, one line.
    private static String coloredName(String raw) {
        String name = raw == null ? "" : raw.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
        name = MessageIdFilter.strip(name);

        if (name.isEmpty()) {
            return "(unnamed)";
        }

        name = DiscordFormat.truncate(name, MAX_NAME_LENGTH);
        return DiscordFormat.escapeMarkdown(name).replace("](", "]\\(");
    }

    // UUIDs and addresses are server- or network-issued, so neither can carry a backtick.
    private static String code(String value) {
        return value == null || value.isBlank() ? "—" : "`" + value + "`";
    }

    private static String codes(List<String> values) {
        if (values.isEmpty()) {
            return "—";
        }

        int listed = Math.min(values.size(), MAX_LISTED);
        List<String> shown = new ArrayList<>(listed);

        for (int index = 0; index < listed; index++) {
            shown.add(code(values.get(index)));
        }

        String text = String.join(", ", shown);
        return values.size() > listed ? text + " + " + (values.size() - listed) + " more" : text;
    }

    // A batch of imported old bans in one message, so a long history does not flood the channel.
    private static String importSummary(List<Bans.Report> reports, int part, int parts) {
        StringBuilder text = new StringBuilder("Old bans imported")
                .append(parts > 1 ? " (" + part + "/" + parts + ")" : "")
                .append(": ").append(reports.size());

        for (Bans.Report report : reports) {
            text.append("\n").append(coloredName(subject(report)))
                    .append(" — ").append(report.uuids().size())
                    .append(report.uuids().size() == 1 ? " account, " : " accounts, ")
                    .append(report.ips().size())
                    .append(report.ips().size() == 1 ? " IP" : " IPs");
        }

        return text.toString();
    }

    private static List<List<Bans.Report>> chunk(List<Bans.Report> reports) {
        List<List<Bans.Report>> chunks = new ArrayList<>();

        for (int start = 0; start < reports.size(); start += IMPORT_SUMMARY_CHUNK) {
            chunks.add(reports.subList(start, Math.min(start + IMPORT_SUMMARY_CHUNK, reports.size())));
        }

        return chunks;
    }

    private static boolean isWebhookUrl(String url) {
        return url.startsWith("https://")
                && url.contains("/api/webhooks/")
                && !url.endsWith("/api/webhooks/");
    }
}
