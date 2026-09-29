// The Discord ban log: one new message per ban, unban, VPN hit or lock, queued and posted to a staff-only webhook.
package Extinction;

import Extinction.core.util.PluginLog;
import Extinction.discord.DiscordFormat;
import Extinction.discord.DiscordJson;
import Extinction.discord.DiscordWebhook;
import Extinction.moderation.ban.BanOrigin;
import Extinction.moderation.ban.BanReport;
import Extinction.moderation.ban.WordFilterHit;
import Extinction.moderation.lock.LockEvent;
import Extinction.moderation.vpn.VpnScanHit;
import Extinction.moderation.vpn.VpnVerdict;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

// Hub only: every worker would report the same ban. Never edits: a log is a sequence.
public final class BanLog {

    // Discord rate-limits a webhook at a handful of requests a second, so the queue drips.
    private static final long PACE_MILLIS = 1_500L;

    // Reached only while Discord is unreachable for long; the oldest entry is dropped first.
    private static final int MAX_QUEUE = 250;

    // A first import larger than this is posted as summaries of IMPORT_SUMMARY_CHUNK instead.
    private static final int MAX_INDIVIDUAL_IMPORTS = 25;
    private static final int IMPORT_SUMMARY_CHUNK = 20;

    private static final long COLOR_BAN = 0xED4245L;
    private static final long COLOR_WORD_FILTER = 0xE67E22L;
    private static final long COLOR_UNBAN = 0x57F287L;
    private static final long COLOR_IMPORT = 0x99AAB5L;

    // Discord's hard limit on one embed field value.
    private static final int MAX_FIELD_VALUE = 1024;

    // Field names may not be empty; a zero-width space renders as a bare line.
    private static final String BLANK_FIELD_NAME = "​";

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
    public void log(BanReport report) {
        if (report == null || report.isEmpty() || !webhook.isConfigured()) {
            return;
        }

        enqueue(payload(report, epochSeconds()));
    }

    // One VPN scan hit as a line; same queue as the bans, so it never crowds one out of order.
    public void logVpnHit(VpnScanHit hit) {
        if (hit == null || !webhook.isConfigured()) {
            return;
        }

        enqueue(vpnScanLine(hit));
    }

    // A lock or a free as one line.
    public void logLock(LockEvent event) {
        if (event == null || !webhook.isConfigured()) {
            return;
        }

        enqueue(lockLine(event));
    }

    // The console test's verdict, so the test proves the channel too.
    public void logVpnTest(VpnVerdict verdict) {
        if (verdict == null || !webhook.isConfigured()) {
            return;
        }

        enqueue(vpnScanTestLine(verdict));
    }

    // True while a webhook is set - whether or not it is currently healthy.
    public boolean isConfigured() {
        return webhook.isConfigured();
    }

    // The one-off import of old bans: one entry each while few, summaries when many.
    public void logImport(List<BanReport> reports) {
        if (reports == null || reports.isEmpty() || !webhook.isConfigured()) {
            return;
        }

        if (reports.size() <= MAX_INDIVIDUAL_IMPORTS) {
            for (BanReport report : reports) {
                log(report);
            }

            return;
        }

        List<List<BanReport>> chunks = chunk(reports);

        for (int index = 0; index < chunks.size(); index++) {
            enqueue(importSummary(chunks.get(index), index + 1, chunks.size(), epochSeconds()));
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

    // 'banlog test': a sample entry so an admin can check the channel is wired up.
    public boolean publishTest() {
        if (!webhook.isConfigured()) {
            return false;
        }

        enqueue(payload(
                new BanReport(
                        BanReport.Kind.IMPORT,
                        "ban log test",
                        List.of("nobody"),
                        List.of("(test entry, nothing was banned)"),
                        List.of()
                ),
                epochSeconds()
        ));

        return true;
    }

    private void enqueue(String payload) {
        while (pending.size() >= MAX_QUEUE) {
            pending.poll();
            dropped++;
        }

        pending.add(payload);
    }

    // One action as one embed: its story, then every name, account and address it covered.
    private static String payload(BanReport report, long timestampSeconds) {
        DiscordJson.Arr fields = new DiscordJson.Arr();

        addStoryFields(fields, report);

        fields.add(field("Names (" + report.names().size() + ")", nameList(report.names()), false));
        fields.add(field("UUIDs (" + report.uuids().size() + ")", codeList(report.uuids()), false));
        fields.add(field("IPs (" + report.ips().size() + ")", codeList(report.ips()), false));
        fields.add(field(BLANK_FIELD_NAME, "Logged " + DiscordFormat.relativeTimestamp(timestampSeconds), false));

        DiscordJson.Obj embed = new DiscordJson.Obj()
                .str("title", title(report))
                .num("color", color(report.kind()))
                .raw("fields", fields.toString());

        return embedMessage(embed);
    }

    // A line, not an embed: the scan decides nothing, and the line says so itself.
    private static String vpnScanLine(VpnScanHit hit) {
        return lineMessage("🔍 **VPN scan** · **" + DiscordFormat.playerName(hit.name())
                + "** (`" + hit.uuid() + "`) from `" + hit.ip() + "` — **"
                + hit.verdict().flags() + "** · "
                + DiscordFormat.escapeMarkdown(hit.verdict().network())
                + " · account: " + account(hit)
                + " · log only, nothing was done");
    }

    // The console test's line, marked as a test.
    private static String vpnScanTestLine(VpnVerdict verdict) {
        return lineMessage("🔍 **VPN scan test** · `" + verdict.ip() + "` — **"
                + verdict.flags() + "** · "
                + DiscordFormat.escapeMarkdown(verdict.network())
                + (verdict.flagged()
                ? " · a join from here is written here"
                : " · a join from here writes nothing")
                + " · log only, nothing was done");
    }

    // The lock line carries the UUID in a code span: it is what the admin pastes into Discord's /free.
    private static String lockLine(LockEvent event) {
        if (event.kind() == LockEvent.Kind.LOCKED) {
            return lineMessage("🔒 **Locked** · **" + DiscordFormat.playerName(event.name())
                    + "** (`" + event.uuid() + "`) from `" + event.ip() + "` — **"
                    + (event.verdict() == null ? "VPN" : event.verdict().flags()) + "**"
                    + (event.verdict() == null ? "" : " · " + DiscordFormat.escapeMarkdown(event.verdict().network()))
                    + " · first join · can watch and /s only · free with `/free " + event.uuid() + "`");
        }

        return lineMessage("🔓 **Freed** · **" + DiscordFormat.playerName(event.name())
                + "** (`" + event.uuid() + "`) · by " + DiscordFormat.escapeMarkdown(event.actor())
                + " · verified from now on");
    }

    // "first seen 3 months ago, played 12 h 3 min, 41 joins".
    private static String account(VpnScanHit hit) {
        if (hit.unknownAccount()) {
            return "not stored yet, " + hit.joins();
        }

        return "first seen " + DiscordFormat.relativeTimestamp(hit.firstSeenMillis() / 1000L)
                + ", played " + hit.playtime()
                + ", " + hit.joins();
    }

    // A batch of imported old bans collapsed into one embed, so a long history does not flood the channel.
    private static String importSummary(List<BanReport> reports, int part, int parts, long timestampSeconds) {
        StringBuilder lines = new StringBuilder();

        for (BanReport report : reports) {
            lines.append("• ")
                    .append(DiscordFormat.playerName(report.seedLabel()))
                    .append(" — ")
                    .append(report.uuids().size())
                    .append(report.uuids().size() == 1 ? " account, " : " accounts, ")
                    .append(report.ips().size())
                    .append(report.ips().size() == 1 ? " IP" : " IPs")
                    .append('\n');
        }

        DiscordJson.Arr fields = new DiscordJson.Arr();
        fields.add(field(
                "Bans (" + reports.size() + ")",
                DiscordFormat.truncate(lines.toString().trim(), MAX_FIELD_VALUE),
                false
        ));
        fields.add(field(BLANK_FIELD_NAME, "Logged " + DiscordFormat.relativeTimestamp(timestampSeconds), false));

        String suffix = parts > 1 ? " (" + part + "/" + parts + ")" : "";

        DiscordJson.Obj embed = new DiscordJson.Obj()
                .str("title", "📥 Existing bans imported" + suffix)
                .str(
                        "description",
                        "Bans that predate the ban cascade, now widened to the "
                                + "accounts and addresses linked to them."
                )
                .num("color", COLOR_IMPORT)
                .raw("fields", fields.toString());

        return embedMessage(embed);
    }

    // Why the ban happened: the word filter's evidence, then who decided it, where, and the console time.
    private static void addStoryFields(DiscordJson.Arr fields, BanReport report) {
        WordFilterHit hit = report.wordFilterHit();

        if (hit != null) {
            fields.add(field("Word", codeSpan(hit.word()), true));
            fields.add(field("Found in", hit.source().label(), true));

            fields.add(field(
                    hit.source() == WordFilterHit.Source.CHAT ? "Message" : "Name",
                    DiscordFormat.playerText(hit.text()),
                    false
            ));
        }

        BanOrigin origin = report.origin();

        if (origin == null) {
            return;
        }

        fields.add(field("Banned by", DiscordFormat.playerName(origin.actor()), true));
        fields.add(field("Server", origin.server(), true));
        fields.add(field("Console time", codeSpan(origin.consoleTime()), false));
    }

    // A backslash renders literally inside a code span, so a backtick is replaced rather than escaped.
    private static String codeSpan(String value) {
        String cleaned = value == null ? "" : value.replace('`', '\'').trim();

        return cleaned.isEmpty() ? "—" : "`" + cleaned + "`";
    }

    private static String title(BanReport report) {
        String subject = DiscordFormat.playerName(report.seedLabel());

        return switch (report.kind()) {
            case BAN -> "🔨 Ban — " + subject;
            case WORD_FILTER -> "🤖 Word filter ban — " + subject;
            case UNBAN -> "♻️ Unban — " + subject;
            case IMPORT -> "📥 Imported ban — " + subject;
        };
    }

    private static long color(BanReport.Kind kind) {
        return switch (kind) {
            case BAN -> COLOR_BAN;
            case WORD_FILTER -> COLOR_WORD_FILTER;
            case UNBAN -> COLOR_UNBAN;
            case IMPORT -> COLOR_IMPORT;
        };
    }

    // Names are escaped, not code-spanned: a name may hold a backtick that would end the span.
    private static String nameList(List<String> names) {
        if (names.isEmpty()) {
            return "—";
        }

        StringBuilder text = new StringBuilder();
        int listed = 0;

        for (String name : names) {
            String line = DiscordFormat.playerName(name) + "\n";

            if (text.length() + line.length() > MAX_FIELD_VALUE - 24) {
                break;
            }

            text.append(line);
            listed++;
        }

        return withRemainder(text, listed, names.size());
    }

    // UUIDs and addresses are server- or network-issued, so neither can carry a backtick.
    private static String codeList(List<String> values) {
        if (values.isEmpty()) {
            return "—";
        }

        StringBuilder text = new StringBuilder();
        int listed = 0;

        for (String value : values) {
            String line = "`" + value + "`\n";

            if (text.length() + line.length() > MAX_FIELD_VALUE - 24) {
                break;
            }

            text.append(line);
            listed++;
        }

        return withRemainder(text, listed, values.size());
    }

    private static String withRemainder(StringBuilder text, int listed, int total) {
        if (listed < total) {
            text.append("+ ").append(total - listed).append(" more");
        }

        return text.toString().trim();
    }

    private static DiscordJson.Obj field(String name, String value, boolean inline) {
        return new DiscordJson.Obj()
                .str("name", name)
                .str("value", value)
                .raw("inline", Boolean.toString(inline));
    }

    private static String embedMessage(DiscordJson.Obj embed) {
        return new DiscordJson.Obj()
                .raw("allowed_mentions", NO_MENTIONS)
                .raw("embeds", new DiscordJson.Arr().add(embed).toString())
                .toString();
    }

    private static String lineMessage(String content) {
        return new DiscordJson.Obj()
                .raw("allowed_mentions", NO_MENTIONS)
                .str("content", content)
                .toString();
    }

    private static List<List<BanReport>> chunk(List<BanReport> reports) {
        List<List<BanReport>> chunks = new ArrayList<>();

        for (int start = 0; start < reports.size(); start += IMPORT_SUMMARY_CHUNK) {
            chunks.add(reports.subList(start, Math.min(start + IMPORT_SUMMARY_CHUNK, reports.size())));
        }

        return chunks;
    }

    private static long epochSeconds() {
        return System.currentTimeMillis() / 1000L;
    }

    private static boolean isWebhookUrl(String url) {
        return url.startsWith("https://")
                && url.contains("/api/webhooks/")
                && !url.endsWith("/api/webhooks/");
    }
}
