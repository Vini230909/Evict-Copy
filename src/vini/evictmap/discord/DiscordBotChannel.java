package vini.evictmap.discord;

import arc.util.serialization.Jval;
import vini.evictmap.core.util.PluginLog;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Posts messages into one Discord channel as a bot ({@code POST
 * /channels/&lt;id&gt;/messages} with an {@code Authorization: Bot} header).
 * Pure REST - a bot may send without ever touching the gateway; it merely
 * shows as offline, which a mirror does not care about.
 *
 * <p>Why a bot and not a webhook here: Discord caps webhook messages at 30
 * per minute per channel, a bot at 5 per 5 seconds - twice the sustained
 * rate, which the busy hub channel needs. One token also replaces eleven
 * hand-created webhooks.
 *
 * <p>Same shape as {@link DiscordWebhook}: one instance per channel, one
 * request in flight, 429 honoured via {@code retry_after}, a hard rejection
 * (bad token, missing access, deleted channel) marks the channel broken
 * instead of retrying forever. The token is shared across channels and can be
 * swapped at runtime; swapping it also un-breaks the channel, so a fixed
 * token heals an earlier 401 without a restart.
 */
final class DiscordBotChannel {

    private static final String API_BASE =
            "https://discord.com/api/v10/channels/";

    /** Per-request timeout; the connect timeout lives on the shared client. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    /** How long to stay quiet after an unexplained rate limit. */
    private static final long DEFAULT_BACKOFF_MILLIS = 60_000L;

    /** Log a repeating network failure on the first one, then rarely. */
    private static final int FAILURE_LOG_INTERVAL = 20;

    private final HttpClient client;

    /** Only ever one request in flight, so a slow Discord cannot pile up. */
    private final AtomicBoolean sending = new AtomicBoolean(false);

    private volatile String channelId = "";
    private volatile String token = "";
    private volatile boolean broken = false;
    private volatile String lastError = "";
    private volatile long lastSuccessMillis = 0L;
    private volatile long backoffUntilMillis = 0L;
    private volatile int consecutiveFailures = 0;

    DiscordBotChannel(String channelId, String token, HttpClient client) {
        this.client = client;
        this.channelId = channelId == null ? "" : channelId.trim();
        this.token = token == null ? "" : token.trim();
    }

    /** Swaps the bot token and gives a broken channel another chance. */
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

    boolean isBroken() {
        return broken;
    }

    String lastError() {
        return lastError;
    }

    long lastSuccessMillis() {
        return lastSuccessMillis;
    }

    /** True when a send would actually go out right now. */
    boolean canSend() {
        return isConfigured()
                && !broken
                && System.currentTimeMillis() >= backoffUntilMillis
                && !sending.get();
    }

    /** Fires a message payload at the channel on a background thread. */
    void post(String body) {
        if (!canSend() || !sending.compareAndSet(false, true)) {
            return;
        }

        client.sendAsync(
                        buildRequest(body, REQUEST_TIMEOUT),
                        HttpResponse.BodyHandlers.ofString()
                )
                .whenComplete((response, error) -> {
                    try {
                        if (error != null) {
                            recordFailure(rootMessage(error));
                        } else {
                            handleResponse(response.statusCode(), response.body());
                        }
                    } finally {
                        sending.set(false);
                    }
                });
    }

    /**
     * Sends one message and waits for it - the shutdown flush; the JVM is on
     * its way out, so an async send would simply be abandoned.
     */
    void publishBlocking(String body, Duration timeout) {
        if (!isConfigured() || broken) {
            return;
        }

        try {
            HttpResponse<String> response = client.send(
                    buildRequest(body, timeout),
                    HttpResponse.BodyHandlers.ofString()
            );

            handleResponse(response.statusCode(), response.body());
        } catch (Exception exception) {
            PluginLog.info(
                    "Discord chat-mirror flush could not send: @",
                    rootMessage(exception)
            );
        }
    }

    private HttpRequest buildRequest(String body, Duration timeout) {
        return HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + channelId + "/messages"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bot " + token)
                .header("User-Agent", "DiscordBot (EvictMapGenerator chat mirror)")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private void handleResponse(int status, String body) {
        if (status >= 200 && status < 300) {
            lastSuccessMillis = System.currentTimeMillis();
            consecutiveFailures = 0;
            lastError = "";
            return;
        }

        switch (status) {
            case 401 -> breakChannel(
                    "Discord rejected the bot token (HTTP 401). Set a new one "
                            + "with 'evictchatlog token <bot-token>'."
            );
            case 403 -> breakChannel(
                    "The bot may not post in this channel (HTTP 403). Invite "
                            + "it to the server and grant View Channel + Send "
                            + "Messages there."
            );
            case 404 -> breakChannel(
                    "The channel no longer exists (HTTP 404). Rewire it with "
                            + "'evictchatlog ... <channel-id>'."
            );
            case 429 -> applyRateLimit(body);
            default -> recordFailure("HTTP " + status);
        }
    }

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

        backoffUntilMillis = System.currentTimeMillis() + waitMillis;
        recordFailure("rate limited for " + (waitMillis / 1000L) + "s");
    }

    private void breakChannel(String reason) {
        broken = true;
        lastError = reason;
        PluginLog.err("Discord chat mirror channel disabled: @", reason);
    }

    private void recordFailure(String reason) {
        lastError = reason;
        consecutiveFailures++;

        if (consecutiveFailures == 1
                || consecutiveFailures % FAILURE_LOG_INTERVAL == 0) {
            PluginLog.info(
                    "Discord chat mirror send failed (@; attempt @).",
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
