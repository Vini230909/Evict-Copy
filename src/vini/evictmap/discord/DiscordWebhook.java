package vini.evictmap.discord;

import arc.util.serialization.Jval;
import vini.evictmap.core.util.PluginLog;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Sends the status message to a Discord webhook, and owns the small amount of
 * state that keeps it to a single message: which message id was created, and
 * when the endpoint has told us to back off.
 *
 * <p>The first send creates the message ({@code POST ...?wait=true}, which
 * makes Discord return the created message so its id can be stored); every send
 * after that edits it in place ({@code PATCH .../messages/<id>}). An edit
 * notifies nobody and does not bump the channel, so refreshing twice a minute
 * is invisible to members.
 *
 * <p>Arc's {@code Http} cannot do this: it has no PATCH method, and neither
 * does {@code HttpURLConnection} underneath it. Hence the JDK's own
 * {@link HttpClient}, which is no extra dependency and gives real async sends.
 */
final class DiscordWebhook {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    /** How long to stay quiet after an unexplained rate limit. */
    private static final long DEFAULT_BACKOFF_MILLIS = 60_000L;

    /** Log a repeating network failure on the first one, then rarely. */
    private static final int FAILURE_LOG_INTERVAL = 20;

    private final HttpClient client;

    /** Persists a newly created message id so restarts reuse the same message. */
    private final Consumer<String> messageIdSink;

    /** Only ever one request in flight, so a slow Discord cannot pile up sends. */
    private final AtomicBoolean sending = new AtomicBoolean(false);

    private volatile String url = "";
    private volatile String messageId = "";
    private volatile boolean broken = false;
    private volatile String lastError = "";
    private volatile long lastSuccessMillis = 0L;
    private volatile long backoffUntilMillis = 0L;
    private volatile int consecutiveFailures = 0;

    DiscordWebhook(Consumer<String> messageIdSink) {
        this.messageIdSink = messageIdSink;

        ThreadFactory threads = runnable -> {
            Thread thread = new Thread(runnable, "evict-discord");
            thread.setDaemon(true);
            return thread;
        };

        this.client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .executor(Executors.newFixedThreadPool(1, threads))
                .build();
    }

    void configure(String webhookUrl, String storedMessageId) {
        this.url = webhookUrl == null ? "" : webhookUrl.trim();
        this.messageId = storedMessageId == null ? "" : storedMessageId.trim();
        this.broken = false;
        this.lastError = "";
        this.consecutiveFailures = 0;
        this.backoffUntilMillis = 0L;
    }

    boolean isConfigured() {
        return !url.isEmpty();
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
     * True when a send would actually go out right now. Callers that queue
     * their messages (the ban log) check this before handing one over, so a
     * message is held rather than dropped while Discord is busy or backing off.
     */
    boolean canSend() {
        return isConfigured()
                && !broken
                && System.currentTimeMillis() >= backoffUntilMillis
                && !sending.get();
    }

    /**
     * Fires a payload at Discord on a background thread, editing the tracked
     * message. Returns quietly when a send is already running, when the webhook
     * has been rejected, or while a rate-limit backoff is in effect.
     */
    void publish(String body) {
        send(body, true);
    }

    /**
     * Posts a payload as a new message and leaves the tracked message alone.
     * This is what a log wants: a record per event, in order, never overwriting
     * the one before it.
     */
    void post(String body) {
        send(body, false);
    }

    private void send(String body, boolean edit) {
        if (!isConfigured() || broken) {
            return;
        }

        if (System.currentTimeMillis() < backoffUntilMillis) {
            return;
        }

        if (!sending.compareAndSet(false, true)) {
            return;
        }

        client.sendAsync(
                        buildRequest(body, REQUEST_TIMEOUT, edit),
                        HttpResponse.BodyHandlers.ofString()
                )
                .whenComplete((response, error) -> {
                    try {
                        if (error != null) {
                            recordFailure(rootMessage(error));
                        } else {
                            handleResponse(response.statusCode(), response.body(), edit);
                        }
                    } finally {
                        sending.set(false);
                    }
                });
    }

    /**
     * Sends one snapshot and waits for it, for the farewell "Offline" message:
     * the JVM is on its way out, so an async send would simply be abandoned.
     * Failure here is not worth reporting loudly - the server is stopping
     * either way.
     */
    void publishBlocking(String body, Duration timeout) {
        if (!isConfigured() || broken) {
            return;
        }

        try {
            HttpResponse<String> response = client.send(
                    buildRequest(body, timeout, true),
                    HttpResponse.BodyHandlers.ofString()
            );

            handleResponse(response.statusCode(), response.body(), true);
        } catch (Exception exception) {
            PluginLog.info(
                    "Discord offline notice could not be sent: @",
                    rootMessage(exception)
            );
        }
    }

    /**
     * Builds the create-or-edit request. With no stored message id this is a
     * create; {@code wait=true} makes Discord respond with the created message
     * so {@link #handleResponse} can capture and persist its id.
     */
    private HttpRequest buildRequest(String body, Duration timeout, boolean edit) {
        boolean editing = edit && !messageId.isEmpty();
        String target = editing
                ? url + "/messages/" + messageId
                : url + "?wait=true";

        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(target))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("User-Agent", "EvictMapGenerator (status reporter)");

        return editing
                ? request.method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build()
                : request.POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private void handleResponse(int status, String body, boolean edit) {
        if (status >= 200 && status < 300) {
            if (edit) {
                captureMessageId(body);
            }

            lastSuccessMillis = System.currentTimeMillis();
            consecutiveFailures = 0;
            lastError = "";
            return;
        }

        switch (status) {
            case 401, 403 -> breakWebhook(
                    "Discord rejected the webhook (HTTP " + status
                            + "). Set a new one with 'evictdiscord <url>'."
            );
            case 404 -> handleNotFound(edit);
            case 429 -> applyRateLimit(body);
            default -> recordFailure("HTTP " + status);
        }
    }

    /**
     * A 404 while editing means the message is gone - someone deleted it. Drop
     * the stored id so the next cycle posts a fresh one. A 404 while creating
     * means the webhook itself is gone, and retrying that forever would be
     * pointless noise.
     */
    private void handleNotFound(boolean edit) {
        if (!edit || messageId.isEmpty()) {
            breakWebhook(
                    "The Discord webhook no longer exists (HTTP 404). "
                            + "Create a new one and set it with 'evictdiscord <url>'."
            );
            return;
        }

        PluginLog.info(
                "Discord status message was deleted; posting a new one."
        );
        setMessageId("");
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

    /** Reads the {@code id} of a freshly created message and persists it. */
    private void captureMessageId(String body) {
        if (!messageId.isEmpty() || body == null || body.isBlank()) {
            return;
        }

        try {
            String created = Jval.read(body).getString("id", "");

            if (!created.isBlank()) {
                setMessageId(created);
                PluginLog.info("Discord status message created (id @).", created);
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

    private void breakWebhook(String reason) {
        broken = true;
        lastError = reason;
        PluginLog.err("Discord status disabled: @", reason);
    }

    /**
     * Records a transient failure. Logged on the first one and then only
     * occasionally: a Discord outage or a dead network link must not fill the
     * server log with one line every 30 seconds.
     */
    private void recordFailure(String reason) {
        lastError = reason;
        consecutiveFailures++;

        if (consecutiveFailures == 1
                || consecutiveFailures % FAILURE_LOG_INTERVAL == 0) {
            PluginLog.info(
                    "Discord status update failed (@; attempt @).",
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
