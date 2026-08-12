package vini.evictmap.discord;

import arc.util.serialization.Jval;
import vini.evictmap.core.util.PluginLog;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The one inbound connection the plugin has to Discord: a minimal gateway
 * client that exists so slash commands can arrive.
 *
 * <p>Everything else the plugin sends to Discord is plain REST - a webhook or
 * a bot POST - because sending needs nothing more. Receiving does. Discord
 * delivers an interaction either to a public HTTPS endpoint of your own (which
 * would mean a certificate, a reverse proxy and an open port in front of a game
 * server) or over this WebSocket, which the server opens outbound like any
 * other connection. Hence the gateway.
 *
 * <p>Deliberately not a Discord library: this needs four opcodes. It identifies
 * with {@code intents: 0} - interactions are delivered regardless of intents,
 * so the bot never receives, and never has to be trusted with, the contents of
 * the Discord server it is in.
 *
 * <p>Threading: the listener callbacks arrive on the HTTP client's threads and
 * do nothing but hand the frame to {@code scheduler}. Every piece of state
 * below, and every frame sent, belongs to that single scheduler thread, so
 * there are no locks and no ordering surprises. A connection carries a
 * {@code serial}; a callback from a connection that is no longer the current
 * one is dropped, which is what stops a dying socket from tearing down its own
 * replacement.
 */
final class DiscordGateway {

    /** Used for the first connect; a resume uses the URL Discord hands back. */
    private static final String DEFAULT_URL = "wss://gateway.discord.gg";

    private static final String QUERY = "/?v=10&encoding=json";

    /**
     * No intents at all. Interactions are pushed to the bot whatever its
     * intents are, so this connection never asks to see messages, members or
     * anything else in the Discord server.
     */
    private static final int INTENTS = 0;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);

    private static final long MIN_RECONNECT_MILLIS = 2_000L;
    private static final long MAX_RECONNECT_MILLIS = 60_000L;

    /** Log a repeating reconnect failure on the first one, then rarely. */
    private static final int FAILURE_LOG_INTERVAL = 10;

    private final HttpClient client;

    /** Handed the {@code d} object of every INTERACTION_CREATE dispatch. */
    private final Consumer<Jval> interactions;

    private final ScheduledExecutorService scheduler;

    /** Assembled here because Discord may split one frame across callbacks. */
    private final StringBuilder frame = new StringBuilder();

    private volatile String token = "";
    private volatile boolean running;
    private volatile boolean connected;
    private volatile String lastError = "";

    // Scheduler thread only.
    private WebSocket socket;
    private ScheduledFuture<?> heartbeat;
    private CompletableFuture<Void> sendChain =
            CompletableFuture.completedFuture(null);
    private String sessionId = "";
    private String resumeUrl = "";
    private long sequence = -1L;
    private boolean acknowledged = true;
    private boolean down = true;
    private boolean resuming;
    private int serial;
    private int failures;

    DiscordGateway(HttpClient client, Consumer<Jval> interactions) {
        this.client = client;
        this.interactions = interactions;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "evict-discord-gateway");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Connects, or reconnects with a new token. Safe to call repeatedly. */
    void connect(String botToken) {
        String cleaned = botToken == null ? "" : botToken.trim();

        if (cleaned.isEmpty()) {
            disconnect();
            return;
        }

        token = cleaned;
        running = true;

        scheduler.execute(() -> {
            teardown();
            sessionId = "";
            resumeUrl = "";
            sequence = -1L;
            failures = 0;
            open(false);
        });
    }

    /** Drops the connection and stops reconnecting. */
    void disconnect() {
        running = false;
        scheduler.execute(this::teardown);
    }

    boolean isConnected() {
        return connected;
    }

    boolean isRunning() {
        return running;
    }

    String lastError() {
        return lastError;
    }

    private void teardown() {
        // Invalidates every callback still in flight for the old connection.
        serial++;
        down = true;
        connected = false;

        cancelHeartbeat();

        WebSocket old = socket;
        socket = null;

        if (old != null) {
            old.abort();
        }
    }

    private void open(boolean resume) {
        if (!running || token.isEmpty()) {
            return;
        }

        serial++;
        down = false;
        resuming = resume && !sessionId.isEmpty();

        int connection = serial;
        String base = resuming && !resumeUrl.isEmpty() ? resumeUrl : DEFAULT_URL;

        try {
            client.newWebSocketBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .buildAsync(URI.create(base + QUERY), new Frames(connection))
                    .whenComplete((webSocket, error) -> {
                        if (error != null) {
                            scheduler.execute(() -> failed(
                                    connection,
                                    "could not reach the gateway ("
                                            + rootMessage(error) + ")"
                            ));
                        }
                    });
        } catch (Exception exception) {
            failed(connection, "could not open a connection (" + exception + ")");
        }
    }

    /**
     * Gives up on one connection and schedules the next, backing off so a
     * server that is down, or a token that has been revoked, is not hammered.
     */
    private void failed(int connection, String reason) {
        if (connection != serial || down) {
            return;
        }

        down = true;
        connected = false;
        lastError = reason;

        cancelHeartbeat();

        WebSocket old = socket;
        socket = null;

        if (old != null) {
            old.abort();
        }

        if (!running) {
            return;
        }

        failures++;

        long delay = Math.min(
                MAX_RECONNECT_MILLIS,
                MIN_RECONNECT_MILLIS << Math.min(5, failures - 1)
        );

        if (failures == 1 || failures % FAILURE_LOG_INTERVAL == 0) {
            PluginLog.info(
                    "Discord command bot: @. Reconnecting in @s (attempt @).",
                    reason,
                    delay / 1000L,
                    failures
            );
        }

        scheduler.schedule(
                () -> open(true),
                delay,
                TimeUnit.MILLISECONDS
        );
    }

    private void receive(int connection, String json) {
        if (connection != serial) {
            return;
        }

        Jval payload;

        try {
            payload = Jval.read(json);
        } catch (Exception exception) {
            return;
        }

        if (payload == null || !payload.isObject()) {
            return;
        }

        Jval seq = payload.get("s");

        if (seq != null && seq.isNumber()) {
            sequence = seq.asLong();
        }

        Jval data = payload.get("d");

        switch (payload.getInt("op", -1)) {
            case 10 -> hello(data);
            case 11 -> acknowledged = true;
            case 1 -> sendHeartbeat();
            case 7 -> failed(connection, "Discord asked for a reconnect");
            case 9 -> {
                // An invalidated session cannot be trusted to resume; start
                // over rather than guess at Discord's "resumable" flag.
                sessionId = "";
                failed(connection, "Discord invalidated the session");
            }
            case 0 -> dispatch(payload.getString("t", ""), data);
            default -> {
                // Every other opcode is Discord's business, not ours.
            }
        }
    }

    private void hello(Jval data) {
        long interval = data == null
                ? 45_000L
                : data.getLong("heartbeat_interval", 45_000L);

        acknowledged = true;
        startHeartbeat(interval);

        send(resuming && !sessionId.isEmpty() ? resume() : identify());
    }

    private void dispatch(String type, Jval data) {
        switch (type) {
            case "READY" -> {
                connected = true;
                failures = 0;
                lastError = "";
                sessionId = data == null ? "" : data.getString("session_id", "");
                resumeUrl = data == null
                        ? ""
                        : data.getString("resume_gateway_url", "");

                PluginLog.info("Discord command bot connected.");
            }
            case "RESUMED" -> {
                connected = true;
                failures = 0;
                lastError = "";
            }
            case "INTERACTION_CREATE" -> {
                if (data != null) {
                    interactions.accept(data);
                }
            }
            default -> {
                // Nothing else is subscribed to; with no intents, nothing else
                // arrives either.
            }
        }
    }

    private String identify() {
        return new DiscordJson.Obj()
                .num("op", 2)
                .raw("d", new DiscordJson.Obj()
                        .str("token", token)
                        .num("intents", INTENTS)
                        .raw("properties", new DiscordJson.Obj()
                                .str("os", "linux")
                                .str("browser", "EvictMapGenerator")
                                .str("device", "EvictMapGenerator")
                                .toString())
                        .toString())
                .toString();
    }

    private String resume() {
        return new DiscordJson.Obj()
                .num("op", 6)
                .raw("d", new DiscordJson.Obj()
                        .str("token", token)
                        .str("session_id", sessionId)
                        .num("seq", sequence)
                        .toString())
                .toString();
    }

    private void startHeartbeat(long interval) {
        cancelHeartbeat();

        // Discord asks for a random first delay so every bot on a shard does
        // not beat in lockstep.
        long first = (long) (interval * Math.random());

        heartbeat = scheduler.scheduleAtFixedRate(
                this::beat,
                first,
                interval,
                TimeUnit.MILLISECONDS
        );
    }

    private void cancelHeartbeat() {
        if (heartbeat != null) {
            heartbeat.cancel(false);
            heartbeat = null;
        }
    }

    /**
     * A beat that was never acknowledged means the connection is a zombie: it
     * still looks open, but nothing is getting through. Dropping it is the only
     * way to find out.
     */
    private void beat() {
        if (!acknowledged) {
            failed(serial, "the gateway stopped answering heartbeats");
            return;
        }

        acknowledged = false;
        sendHeartbeat();
    }

    private void sendHeartbeat() {
        send("{\"op\":1,\"d\":"
                + (sequence < 0L ? "null" : Long.toString(sequence))
                + "}");
    }

    /**
     * Frames are chained rather than sent outright: a WebSocket may not be
     * handed a second message before the first has gone out, and a heartbeat
     * can fall due while an identify is still being written.
     */
    private void send(String json) {
        WebSocket target = socket;

        if (target == null) {
            return;
        }

        sendChain = sendChain
                .handle((ignored, error) -> (Void) null)
                .thenCompose(ignored -> target.sendText(json, true))
                .handle((ignored, error) -> {
                    if (error != null) {
                        lastError = rootMessage(error);
                    }

                    return (Void) null;
                });
    }

    /** Listener for one connection; every callback is tagged with its serial. */
    private final class Frames implements WebSocket.Listener {

        private final int connection;

        Frames(int connection) {
            this.connection = connection;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            scheduler.execute(() -> {
                if (connection == serial) {
                    socket = webSocket;
                }
            });

            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(
                WebSocket webSocket,
                CharSequence data,
                boolean last
        ) {
            frame.append(data);

            if (last) {
                String json = frame.toString();
                frame.setLength(0);
                scheduler.execute(() -> receive(connection, json));
            }

            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(
                WebSocket webSocket,
                int status,
                String reason
        ) {
            scheduler.execute(() -> failed(
                    connection,
                    "the gateway closed the connection (" + explain(status, reason) + ")"
            ));

            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            scheduler.execute(() -> failed(
                    connection,
                    "the connection failed (" + rootMessage(error) + ")"
            ));
        }
    }

    /**
     * Names the close codes an admin can actually do something about. The rest
     * are printed as-is; Discord documents them, and guessing at wording for
     * codes that never occur in practice helps nobody.
     */
    private static String explain(int status, String reason) {
        String detail = reason == null || reason.isBlank() ? "" : " " + reason;

        return switch (status) {
            case 4004 -> "HTTP 4004 - the bot token was rejected; check "
                    + "DISCORD_CHAT_BOT_TOKEN and run 'evictdiscordcmd reload'";
            case 4013, 4014 -> "code " + status + " - the bot asked for "
                    + "privileged data it is not approved for";
            default -> "code " + status + detail;
        };
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
