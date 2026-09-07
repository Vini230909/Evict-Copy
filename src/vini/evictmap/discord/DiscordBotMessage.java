package vini.evictmap.discord;

import arc.util.serialization.Jval;
import vini.evictmap.core.util.PluginLog;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Keeps one bot-posted message in a Discord channel up to date: the first send
 * creates it ({@code POST /channels/&lt;id&gt;/messages}, whose reply carries
 * the new message's id), every send after that edits it in place ({@code PATCH
 * .../messages/&lt;id&gt;}). An edit notifies nobody and does not bump the
 * channel, so a table that rewrites itself every few seconds is invisible to
 * everyone who is not looking at it.
 *
 * <p>Why not the two senders that already exist: {@link DiscordWebhook} does
 * exactly this but over a webhook, and the performance channel is created by
 * the bot ({@link DiscordGuildSetup}) rather than by an admin making a webhook
 * by hand - there is no webhook to send through. {@link DiscordBotChannel}
 * speaks as the same bot but only ever posts new messages, which is what a
 * chat mirror wants and the opposite of what a live table wants.
 *
 * <p>Same failure handling as both: one request in flight at a time, 429
 * honoured through {@code retry_after}, a 404 while editing means someone
 * deleted the message so the next send posts a fresh one, and a hard rejection
 * (bad token, no access) marks the sender broken instead of retrying forever.
 * Swapping the token clears that, so a rotated credential heals without a
 * restart.
 */
final class DiscordBotMessage {

    private static final String API_BASE =
            "https://discord.com/api/v10/channels/";

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    /** How long to stay quiet after an unexplained rate limit. */
    private static final long DEFAULT_BACKOFF_MILLIS = 30_000L;

    /** Log a repeating network failure on the first one, then rarely. */
    private static final int FAILURE_LOG_INTERVAL = 40;

    private final HttpClient client;

    /** Persists a newly created message id so restarts reuse the same message. */
    private final Consumer<String> messageIdSink;

    /** Only ever one request in flight, so a slow Discord cannot pile up sends. */
    private final AtomicBoolean sending = new AtomicBoolean(false);

    private volatile String channelId = "";
    private volatile String token = "";
    private volatile String messageId = "";
    private volatile boolean broken = false;
    private volatile String lastError = "";
    private volatile long lastSuccessMillis = 0L;
    private volatile long backoffUntilMillis = 0L;
    private volatile long lastRateLimitMillis = 0L;
    private volatile int rateLimits = 0;
    private volatile int consecutiveFailures = 0;

    DiscordBotMessage(HttpClient client, Consumer<String> messageIdSink) {
        this.client = client;
        this.messageIdSink = messageIdSink;
    }

    /** Points the sender at a channel and the message it already owns there. */
    void configure(String newChannelId, String storedMessageId) {
        channelId = newChannelId == null ? "" : newChannelId.trim();
        messageId = storedMessageId == null ? "" : storedMessageId.trim();
        broken = false;
        lastError = "";
        consecutiveFailures = 0;
        backoffUntilMillis = 0L;
    }

    /** Swaps the bot token and gives a broken sender another chance. */
    void setToken(String newToken) {
        token = newToken == null ? "" : newToken.trim();
        broken = false;
        lastError = "";
        consecutiveFailures = 0;
        backoffUntilMillis = 0L;
    }

    boolean isConfigured() {
        return !channelId.isEmpty() && !token.isEmpty();
    }

    boolean hasChannel() {
        return !channelId.isEmpty();
    }

    boolean isBroken() {
        return broken;
    }

    String lastError() {
        return lastError;
    }

    long lastSuccessMillis() {
        return lastSuccessMillis;
    }

    String messageId() {
        return messageId;
    }

    /**
     * When Discord last asked us to slow down. The reporter reads this to widen
     * its own interval rather than keep knocking at the same rate - a table
     * that refreshes a little slower is better than one whose sends are being
     * rejected.
     */
    long lastRateLimitMillis() {
        return lastRateLimitMillis;
    }

    /**
     * True when a send would actually go out right now. The reporter shares one
     * request budget between a dozen of these, so it asks first rather than
     * spending a request on a sender that would drop it.
     */
    boolean canSend() {
        return isConfigured()
                && !broken
                && System.currentTimeMillis() >= backoffUntilMillis
                && !sending.get();
    }

    /**
     * Creates or edits the message on a background thread. Returns whether the
     * request was actually started, so the caller knows whether to count it
     * against its budget and treat the body as sent.
     */
    boolean publish(String body) {
        if (!isConfigured() || broken) {
            return false;
        }

        if (System.currentTimeMillis() < backoffUntilMillis) {
            return false;
        }

        if (!sending.compareAndSet(false, true)) {
            return false;
        }

        boolean editing = !messageId.isEmpty();

        client.sendAsync(
                        buildRequest(body, REQUEST_TIMEOUT, editing),
                        HttpResponse.BodyHandlers.ofString()
                )
                .whenComplete((response, error) -> {
                    try {
                        if (error != null) {
                            recordFailure(rootMessage(error));
                        } else {
                            handleResponse(
                                    response.statusCode(),
                                    response.body(),
                                    editing
                            );
                        }
                    } finally {
                        sending.set(false);
                    }
                });

        return true;
    }

    private HttpRequest buildRequest(String body, Duration timeout, boolean editing) {
        String target = API_BASE + channelId + "/messages"
                + (editing ? "/" + messageId : "");

        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(target))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bot " + token)
                .header("User-Agent", "DiscordBot (EvictMapGenerator perf table)");

        return editing
                ? request.method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build()
                : request.POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private void handleResponse(int status, String body, boolean editing) {
        if (status >= 200 && status < 300) {
            if (!editing) {
                captureMessageId(body);
            }

            lastSuccessMillis = System.currentTimeMillis();
            consecutiveFailures = 0;
            lastError = "";
            return;
        }

        switch (status) {
            case 401 -> breakSender(
                    "Discord rejected the bot token (HTTP 401). Set a new one in "
                            + "the secrets file and run 'evictperf reload'."
            );
            case 403 -> breakSender(
                    "The bot may not post in the performance channel (HTTP 403). "
                            + "Grant it View Channel + Send Messages there."
            );
            case 404 -> handleNotFound(editing);
            case 429 -> applyRateLimit(body);
            default -> recordFailure("HTTP " + status);
        }
    }

    /**
     * Editing a message that is gone means somebody deleted it: drop the id so
     * the next refresh posts a new one. Posting into a channel that is gone is
     * a wiring problem and does not improve by being retried.
     */
    private void handleNotFound(boolean editing) {
        if (!editing) {
            breakSender(
                    "The performance channel no longer exists (HTTP 404). "
                            + "Rewire it with 'evictperf setup'."
            );
            return;
        }

        PluginLog.info("Discord performance message was deleted; posting a new one.");
        setMessageId("");
    }

    /**
     * Honours a rate limit - quietly. A 429 that is waited out is backpressure
     * working, not a failure: the caller widens its pacing and the message goes
     * out a moment later. Logging each one filled the console with lines an
     * admin can do nothing about, so the count is kept for {@code evictperf} to
     * report and the caller decides whether it is worth saying anything.
     */
    private void applyRateLimit(String body) {
        long waitMillis = DEFAULT_BACKOFF_MILLIS;

        try {
            double retryAfterSeconds = Jval.read(body).getDouble("retry_after", 0d);

            if (retryAfterSeconds > 0d) {
                waitMillis = (long) Math.ceil(retryAfterSeconds * 1000d);
            }
        } catch (Exception ignored) {
            // Malformed body: the default backoff is the safe answer.
        }

        lastRateLimitMillis = System.currentTimeMillis();
        backoffUntilMillis = lastRateLimitMillis + waitMillis;
        rateLimits++;
        lastError = "rate limited for " + (waitMillis / 1000L) + "s";
    }

    /** When this sender may try again; the channel's bucket, seen from here. */
    long backoffUntilMillis() {
        return backoffUntilMillis;
    }

    /** How many rate limits this message has been handed, for the checklist. */
    int rateLimits() {
        return rateLimits;
    }

    private void captureMessageId(String body) {
        if (body == null || body.isBlank()) {
            return;
        }

        try {
            String created = Jval.read(body).getString("id", "");

            if (!created.isBlank()) {
                setMessageId(created);
                PluginLog.info("Discord performance message created (id @).", created);
            }
        } catch (Exception exception) {
            PluginLog.err(
                    "Could not read the Discord message id from the response: @",
                    exception.getMessage()
            );
        }
    }

    private void setMessageId(String newId) {
        messageId = newId == null ? "" : newId;
        messageIdSink.accept(messageId);
    }

    private void breakSender(String reason) {
        broken = true;
        lastError = reason;
        PluginLog.err("Discord performance table disabled: @", reason);
    }

    private void recordFailure(String reason) {
        lastError = reason;
        consecutiveFailures++;

        if (consecutiveFailures == 1
                || consecutiveFailures % FAILURE_LOG_INTERVAL == 0) {
            PluginLog.info(
                    "Discord performance update failed (@; attempt @).",
                    reason,
                    consecutiveFailures
            );
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;

        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }

        String message = cause.getMessage();

        return message == null || message.isBlank()
                ? cause.getClass().getSimpleName()
                : message;
    }
}
