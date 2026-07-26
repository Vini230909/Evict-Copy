package vini.evictmap.discord;

import vini.evictmap.core.util.PluginLog;
import vini.evictmap.gen.EvictSettings;
import vini.evictmap.moderation.BanReport;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Posts one Discord message per ban, to its own channel.
 *
 * <p>Unlike the status message this never edits: a log is a sequence, and an
 * entry that can be overwritten is not a record of anything. The channel it
 * points at should be one only staff can read - every entry carries the
 * account's addresses.
 *
 * <p>Messages are queued and released on a slow drip rather than fired the
 * moment they happen. One ban can cover a dozen accounts, an import covers the
 * whole back catalogue, and Discord rate-limits a webhook at a handful of
 * requests a second; a queue turns "some of it arrived" into "all of it
 * arrived, slightly later".
 *
 * <p>Hub only, for the same reason the status reporter is: the workers would
 * each report the same ban.
 */
public final class BanLogReporter {

    /** Minimum gap between two messages. */
    private static final long PACE_MILLIS = 1_500L;

    /**
     * Queue ceiling. Reached only if Discord is unreachable for a long time
     * while bans keep coming; the ban itself is applied either way, so the
     * oldest entry is the cheapest thing to lose.
     */
    private static final int MAX_QUEUE = 250;

    /**
     * Imported bans posted individually before the import is collapsed into
     * summaries instead. A first start on a server with a long ban history
     * should not push a hundred messages into the channel.
     */
    private static final int MAX_INDIVIDUAL_IMPORTS = 25;

    private final EvictSettings settings;
    private final DiscordWebhook webhook;

    private final Deque<String> pending = new ArrayDeque<>();

    private long lastSendMillis;
    private boolean started;
    private int dropped;

    public BanLogReporter(EvictSettings settings) {
        this.settings = settings;

        // A log never edits a message, so there is no id to remember.
        this.webhook = new DiscordWebhook(messageId -> {
        });
    }

    /** Hub-only startup: adopt the stored webhook. */
    public void start() {
        if (started) {
            return;
        }

        started = true;
        webhook.configure(settings.discordBanLogWebhookUrl(), "");

        if (webhook.isConfigured()) {
            PluginLog.info("Discord ban log is on.");
        }
    }

    /** Called every frame from the hub's update trigger; paced internally. */
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

    /** Queues one action. Cheap and safe to call when no webhook is set. */
    public void log(BanReport report) {
        if (report == null || report.isEmpty() || !webhook.isConfigured()) {
            return;
        }

        enqueue(BanLogMessage.payload(report, epochSeconds()));
    }

    /**
     * Queues the one-off import of pre-existing bans: individually while there
     * are few enough to read, collapsed into summaries when there are not.
     */
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
            enqueue(BanLogMessage.importSummary(
                    chunks.get(index),
                    index + 1,
                    chunks.size(),
                    epochSeconds()
            ));
        }
    }

    /**
     * Points the log at a new webhook. A blank URL turns it off; anything that
     * is not a webhook URL is rejected.
     */
    public boolean configure(String webhookUrl) {
        String trimmed = webhookUrl == null ? "" : webhookUrl.trim();

        if (!isWebhookUrl(trimmed)) {
            return false;
        }

        settings.setDiscordBanLogWebhook(trimmed);
        webhook.configure(trimmed, "");
        return true;
    }

    /** Stops logging and forgets the queue. */
    public void disable() {
        pending.clear();
        settings.setDiscordBanLogWebhook("");
        webhook.configure("", "");
    }

    /** One line describing the current wiring, for the console command. */
    public String statusLine() {
        if (!webhook.isConfigured()) {
            return "not set (use 'evictbanlog <webhook-url>')";
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

    /** Sends a sample entry so an admin can check the channel is wired up. */
    public boolean publishTest() {
        if (!webhook.isConfigured()) {
            return false;
        }

        enqueue(BanLogMessage.payload(
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

    private static List<List<BanReport>> chunk(List<BanReport> reports) {
        List<List<BanReport>> chunks = new ArrayList<>();

        for (
                int start = 0;
                start < reports.size();
                start += BanLogMessage.IMPORT_SUMMARY_CHUNK
        ) {
            chunks.add(reports.subList(
                    start,
                    Math.min(start + BanLogMessage.IMPORT_SUMMARY_CHUNK, reports.size())
            ));
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
