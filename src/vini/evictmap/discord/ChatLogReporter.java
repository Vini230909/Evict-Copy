package vini.evictmap.discord;

import arc.Core;
import vini.evictmap.core.io.Secrets;
import vini.evictmap.core.util.PluginLog;
import vini.evictmap.gen.EvictSettings;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.Consumer;

/**
 * Mirrors the servers' chat into Discord: one bot-posted channel for the hub
 * and one per worker port, so every port channel reads as start - chat - end,
 * match for match.
 *
 * <p>Sending is a bot ({@link DiscordBotChannel}), not a webhook: Discord
 * caps webhook messages at 30 per minute per channel, a bot at 5 per 5
 * seconds - and the hub channel needs the sustained rate. One token plus a
 * channel id per feed also replaces eleven hand-made webhooks. The token
 * comes from the {@link Secrets} file, never from a command - see there.
 * The pace is deliberately steady - one message per second per channel, no
 * burst - which is exactly the bot's sustained limit, so the mirror never
 * runs into 429s and a line is at most a second late.
 *
 * <p>Each line is normally its own Discord message. Nothing is thrown away
 * to keep up: past the pace the queue falls behind and catches up one send
 * later, merging backlogged lines into multi-line messages so the catch-up
 * takes one message rather than many. (Discord visually groups consecutive
 * messages anyway.) Only a Discord outage long enough to fill the hard queue
 * cap loses lines, and that loss is counted and marked in the channel.
 *
 * <p>Lifecycle embeds (match start/end, round end) ride the same per-channel
 * queue as the lines, which is what keeps them in order with the chat around
 * them. They are never merged.
 *
 * <p>Hub only, like every Discord sender here. Lines can be enqueued from any
 * thread (the worker chat tail runs on a background poll); sending happens on
 * the main-thread {@link #update()} tick.
 */
public final class ChatLogReporter {

    /**
     * Gap between two messages on one channel: the bot's sustained limit of
     * 5 per 5 seconds, ridden evenly rather than in bursts.
     */
    private static final long PACE_MILLIS = 1_000L;

    /**
     * Backlog size up to which lines are still sent one per message. Beyond
     * it the channel is behind, and older lines are merged to catch up.
     */
    private static final int SINGLE_LINE_BACKLOG = 3;

    /** Character budget of one merged message (Discord caps content at 2000). */
    private static final int MAX_MERGED_CONTENT = 1_900;

    /**
     * Hard per-channel queue cap. Reached only when Discord is unreachable
     * for a long time on a busy server; the oldest chat line is the cheapest
     * thing to lose, and the loss is announced in the channel afterwards.
     */
    private static final int MAX_QUEUE = 2_000;

    /** Total time budget for the best-effort flush on shutdown. */
    private static final long SHUTDOWN_FLUSH_BUDGET_MILLIS = 3_000L;

    /** The hub's key in a setup result; every other key is a port number. */
    private static final String HUB_KEY = "hub";

    /** The Discord category the setup creates its channels in. */
    private static final String CATEGORY_NAME = "Evict Logs";

    /**
     * One queued message: a mirrored line ({@code content}) that may be merged
     * with its neighbours, or a finished payload ({@code payload}) - a
     * lifecycle embed - that is sent exactly as it is.
     */
    private static final class Entry {
        final String content;
        final String payload;

        static Entry line(String content) {
            return new Entry(content, null);
        }

        static Entry embed(String payload) {
            return new Entry(null, payload);
        }

        private Entry(String content, String payload) {
            this.content = content;
            this.payload = payload;
        }

        boolean isLine() {
            return content != null;
        }
    }

    /** One Discord channel: its sender, its queue, its bookkeeping. */
    private static final class Channel {
        final DiscordBotChannel sender;
        final ArrayDeque<Entry> pending = new ArrayDeque<>();
        long lastSendMillis;
        int dropped;

        Channel(String channelId, String token, HttpClient client) {
            this.sender = new DiscordBotChannel(channelId, token, client);
        }
    }

    private final EvictSettings settings;

    /** One shared HTTP client; eleven single-thread clients would be silly. */
    private final HttpClient client = DiscordWebhook.newClient();

    /**
     * The hub channel and the per-port channels. Lines arrive from the main
     * thread and from the worker-chat poll thread, while the console rewires
     * channels on the main thread - hence volatile + a concurrent map; the
     * queues themselves synchronize on their deque.
     */
    private volatile Channel hub;
    private final Map<Integer, Channel> byPort = new ConcurrentSkipListMap<>();

    /**
     * The bot token as last read from the secrets file. Cached so a send does
     * not touch the disk; {@code evictchatlog reload} re-reads it.
     */
    private volatile String token = "";

    private boolean started;

    public ChatLogReporter(EvictSettings settings) {
        this.settings = settings;
    }

    /** Hub-only startup: adopt the stored wiring and arrange the last flush. */
    public void start() {
        if (started) {
            return;
        }

        started = true;

        // Also creates the commented template when the file is missing, so a
        // fresh server shows an admin where the token goes.
        Secrets.reload();
        token = Secrets.get(Secrets.DISCORD_CHAT_BOT_TOKEN);

        if (!settings.chatLogHubChannel().isBlank()) {
            hub = new Channel(settings.chatLogHubChannel(), token, client);
        }

        for (Map.Entry<Integer, String> entry
                : settings.chatLogPortChannels().entrySet()) {
            byPort.put(
                    entry.getKey(),
                    new Channel(entry.getValue(), token, client)
            );
        }

        int channels = (hub == null ? 0 : 1) + byPort.size();

        if (channels > 0) {
            PluginLog.info(
                    "Discord chat log is on (@ channel(s)@).",
                    channels,
                    token.isBlank()
                            ? ", but " + Secrets.DISCORD_CHAT_BOT_TOKEN
                            + " is not set in " + Secrets.path()
                            : ""
            );
        }

        // Best effort only: a restart should not swallow the lines already
        // queued. Registered whether or not anything is wired up yet - a
        // channel may be added later, and the flush does nothing when idle.
        Runtime.getRuntime().addShutdownHook(
                new Thread(this::flushOnShutdown, "evict-discord-chatflush")
        );
    }

    /** Called every frame from the hub's update trigger; paced internally. */
    public void update() {
        if (!started) {
            return;
        }

        long now = System.currentTimeMillis();

        if (hub != null) {
            pump(hub, now);
        }

        for (Channel channel : byPort.values()) {
            pump(channel, now);
        }
    }

    /** One mirrored line for the hub channel. Any thread. */
    public void hubLine(String line) {
        enqueue(hub, Entry.line(line));
    }

    /** One mirrored line for a port channel. Any thread. */
    public void portLine(int port, String line) {
        enqueue(byPort.get(port), Entry.line(line));
    }

    /**
     * The match-start embed on a port channel. Takes the plain roster names
     * as captured at spawn and sanitises them here.
     */
    public void matchStarted(
            int port,
            String modeLabel,
            List<List<String>> teamNames
    ) {
        List<List<String>> escaped = new ArrayList<>();

        for (List<String> roster : teamNames) {
            List<String> names = new ArrayList<>();

            for (String name : roster) {
                names.add(DiscordFormat.playerName(name));
            }

            escaped.add(names);
        }

        enqueue(
                byPort.get(port),
                Entry.embed(ChatLogMessage.matchStart(modeLabel, escaped))
        );
    }

    /**
     * The match-end embed on a port channel. The freeform strings (winners,
     * losers) arrive already sanitised - the caller built them from names it
     * pushed through {@link DiscordFormat}.
     */
    public void matchEnded(
            int port,
            String modeLabel,
            String winners,
            String losers,
            long durationSeconds,
            String howItEnded,
            boolean decided
    ) {
        enqueue(
                byPort.get(port),
                Entry.embed(ChatLogMessage.matchEnd(
                        modeLabel,
                        winners,
                        losers,
                        durationSeconds,
                        howItEnded,
                        decided
                ))
        );
    }

    /**
     * The round-end embed on the hub channel. Strings arrive sanitised, as in
     * {@link #matchEnded}.
     */
    public void roundEnded(
            String winnerLabel,
            String memberLines,
            long durationSeconds,
            String howItWasWon,
            boolean fallenWon
    ) {
        enqueue(hub, Entry.embed(ChatLogMessage.roundEnd(
                winnerLabel,
                memberLines,
                durationSeconds,
                howItWasWon,
                fallenWon
        )));
    }

    /** True when at least one channel is wired up. */
    public boolean isConfigured() {
        return hub != null || !byPort.isEmpty();
    }

    /**
     * Re-reads the secrets file and hands the token to every channel, current
     * and future. Also un-breaks channels a bad token had disabled, so
     * replacing the value and reloading heals them without a restart.
     *
     * @return true when the file now holds a token
     */
    public boolean reloadToken() {
        Secrets.reload();
        token = Secrets.get(Secrets.DISCORD_CHAT_BOT_TOKEN);

        if (hub != null) {
            hub.sender.setToken(token);
        }

        for (Channel channel : byPort.values()) {
            channel.sender.setToken(token);
        }

        return !token.isBlank();
    }

    /** Points the hub feed at a channel id. */
    public boolean configureHub(String channelId) {
        String trimmed = channelId == null ? "" : channelId.trim();

        if (!isChannelId(trimmed)) {
            return false;
        }

        settings.setChatLogHubChannel(trimmed);
        hub = new Channel(trimmed, token, client);
        return true;
    }

    /** Points one port's feed at a channel id. */
    public boolean configurePort(int port, String channelId) {
        String trimmed = channelId == null ? "" : channelId.trim();

        if (!isChannelId(trimmed)) {
            return false;
        }

        settings.setChatLogPortChannel(port, trimmed);
        byPort.put(port, new Channel(trimmed, token, client));
        return true;
    }

    /** True when the token file held a token at the last read. */
    public boolean hasToken() {
        return !token.isBlank();
    }

    /** Stops mirroring the hub channel and forgets its queue. */
    public void disableHub() {
        settings.setChatLogHubChannel("");
        hub = null;
    }

    /** Stops mirroring one port and forgets its queue. */
    public void disablePort(int port) {
        settings.setChatLogPortChannel(port, "");
        byPort.remove(port);
    }

    /** Stops mirroring everywhere: every queue and the token are dropped. */
    public void disableAll() {
        settings.clearChatLog();
        hub = null;
        byPort.clear();
    }

    /**
     * The wiring checklist for the console: the token, the hub and every port
     * of the current pool, plus any configured port that has fallen outside
     * the pool (a changed base port leaves those behind).
     */
    public List<String> statusLines(int basePort, int maxWorkers) {
        List<String> lines = new ArrayList<>();

        lines.add(
                "bot token: " + (hasToken()
                        ? "loaded from " + Secrets.path()
                        : "NOT SET - set " + Secrets.DISCORD_CHAT_BOT_TOKEN
                        + " in " + Secrets.path() + ", then 'evictchatlog reload'")
        );
        lines.add("hub: " + describe(hub));

        for (int offset = 0; offset < maxWorkers; offset++) {
            int port = basePort + offset;
            lines.add("port " + port + ": " + describe(byPort.get(port)));
        }

        for (Map.Entry<Integer, Channel> entry : byPort.entrySet()) {
            int port = entry.getKey();

            if (port < basePort || port >= basePort + maxWorkers) {
                lines.add(
                        "port " + port + ": " + describe(entry.getValue())
                                + " - outside the current port range, never used"
                );
            }
        }

        return lines;
    }

    /**
     * Posts a test line into every configured channel, so a mis-pasted
     * channel id shows up as the one channel that stays quiet.
     *
     * @return how many channels were tested
     */
    public int publishTest() {
        int tested = 0;

        if (hub != null) {
            hubLine("✅ Chat log test — this channel mirrors the hub.");
            tested++;
        }

        for (Map.Entry<Integer, Channel> entry : byPort.entrySet()) {
            portLine(
                    entry.getKey(),
                    "✅ Chat log test — this channel mirrors the match server on port "
                            + entry.getKey() + "."
            );
            tested++;
        }

        return tested;
    }

    private void enqueue(Channel channel, Entry entry) {
        if (channel == null || entry == null) {
            return;
        }

        synchronized (channel.pending) {
            while (channel.pending.size() >= MAX_QUEUE) {
                channel.pending.poll();
                channel.dropped++;
            }

            channel.pending.add(entry);
        }
    }

    /**
     * Sends one message on one channel if its pace allows: an embed alone, a
     * single line while the backlog is small, or a merged block of backlogged
     * lines to catch up after a burst.
     */
    private void pump(Channel channel, long now) {
        if (channel.sender.isBroken()) {
            synchronized (channel.pending) {
                channel.pending.clear();
            }
            return;
        }

        if (
                now - channel.lastSendMillis < PACE_MILLIS
                        || !channel.sender.canSend()
        ) {
            return;
        }

        String payload = nextPayload(channel);

        if (payload == null) {
            return;
        }

        channel.lastSendMillis = now;
        channel.sender.post(payload);
    }

    /** Takes the next message off the queue; null when there is nothing. */
    private String nextPayload(Channel channel) {
        synchronized (channel.pending) {
            Entry first = channel.pending.peek();

            if (first == null) {
                return null;
            }

            if (!first.isLine()) {
                channel.pending.poll();
                return first.payload;
            }

            if (
                    channel.pending.size() <= SINGLE_LINE_BACKLOG
                            && channel.dropped == 0
            ) {
                channel.pending.poll();
                return ChatLogMessage.lines(first.content);
            }

            // Behind: merge consecutive lines into one message. An embed in
            // the queue ends the merge so the order around it survives.
            StringBuilder content = new StringBuilder();

            if (channel.dropped > 0) {
                content.append("⚠ ")
                        .append(channel.dropped)
                        .append(" line(s) were dropped while Discord was unreachable.");
                channel.dropped = 0;
            }

            while (true) {
                Entry next = channel.pending.peek();

                if (
                        next == null
                                || !next.isLine()
                                || content.length() + next.content.length() + 1
                                > MAX_MERGED_CONTENT
                ) {
                    break;
                }

                channel.pending.poll();

                if (!content.isEmpty()) {
                    content.append('\n');
                }

                content.append(next.content);
            }

            // Unreachable with the bounded lines the capture produces, but an
            // empty content would 400 forever without ever consuming the
            // entry: force the head line through, truncated.
            if (content.isEmpty()) {
                channel.pending.poll();
                content.append(DiscordFormat.truncate(
                        first.content,
                        MAX_MERGED_CONTENT
                ));
            }

            return ChatLogMessage.lines(content.toString());
        }
    }

    /**
     * The last breath on shutdown: merged messages per channel with whatever
     * is still queued, sent blocking because the JVM is on its way out.
     * Bounded by a global budget so a slow Discord cannot stall the restart;
     * whatever does not fit is lost with the process.
     */
    private void flushOnShutdown() {
        long deadline = System.currentTimeMillis() + SHUTDOWN_FLUSH_BUDGET_MILLIS;

        List<Channel> channels = new ArrayList<>();

        if (hub != null) {
            channels.add(hub);
        }

        channels.addAll(byPort.values());

        for (Channel channel : channels) {
            while (true) {
                long remaining = deadline - System.currentTimeMillis();

                if (remaining <= 0L) {
                    return;
                }

                String payload = nextPayload(channel);

                if (payload == null) {
                    break;
                }

                channel.sender.publishBlocking(
                        payload,
                        Duration.ofMillis(remaining)
                );
            }
        }
    }

    private String describe(Channel channel) {
        if (channel == null) {
            return "NOT SET";
        }

        StringBuilder status = new StringBuilder();
        status.append(channel.sender.isBroken() ? "BROKEN" : "on");

        synchronized (channel.pending) {
            if (!channel.pending.isEmpty()) {
                status.append(", queued=").append(channel.pending.size());
            }

            if (channel.dropped > 0) {
                status.append(", dropped=").append(channel.dropped);
            }
        }

        if (channel.sender.lastSuccessMillis() > 0L) {
            status.append(", last success ")
                    .append((System.currentTimeMillis()
                            - channel.sender.lastSuccessMillis()) / 1000L)
                    .append("s ago");
        }

        if (!channel.sender.lastError().isEmpty()) {
            status.append(", last error: ").append(channel.sender.lastError());
        }

        return status.toString();
    }

    /**
     * Creates the mirror's channels in a Discord server with the bot and
     * adopts their ids, so neither the channels nor the eleven ids have to be
     * made and copied by hand. Existing channels of the same name are adopted
     * rather than duplicated, so raising {@code maxWorkers} and running it
     * again only adds the missing ports.
     *
     * <p>The Discord calls block, so they run on their own thread; the ids
     * are stored back on the main thread, and the report is delivered there
     * too. Whatever was created before a failure is still stored - a partial
     * run leaves usable channels behind, not orphans.
     */
    public void setupChannels(
            String guildId,
            int basePort,
            int maxWorkers,
            Consumer<List<String>> report
    ) {
        if (!hasToken()) {
            report.accept(List.of(
                    "No bot token is loaded. Set " + Secrets.DISCORD_CHAT_BOT_TOKEN
                            + " in " + Secrets.path() + " and run 'evictchatlog reload'."
            ));
            return;
        }

        if (!isChannelId(guildId)) {
            report.accept(List.of(
                    "That is not a Discord server id. Enable Developer Mode in "
                            + "Discord, right-click the server, Copy Server ID."
            ));
            return;
        }

        List<DiscordGuildSetup.Wanted> wanted = new ArrayList<>();
        wanted.add(new DiscordGuildSetup.Wanted(
                HUB_KEY,
                "hub",
                "Chat mirror of the main server."
        ));

        for (int offset = 0; offset < maxWorkers; offset++) {
            int port = basePort + offset;
            wanted.add(new DiscordGuildSetup.Wanted(
                    Integer.toString(port),
                    "port-" + port,
                    "Chat mirror of the match server on port " + port + "."
            ));
        }

        String currentToken = token;

        Thread worker = new Thread(
                () -> {
                    DiscordGuildSetup.Result result =
                            new DiscordGuildSetup(client, currentToken, guildId)
                                    .run(CATEGORY_NAME, wanted);

                    Core.app.post(() -> report.accept(applySetup(result)));
                },
                "evict-discord-setup"
        );
        worker.setDaemon(true);
        worker.start();
    }

    /** Stores what the setup created and turns it into console lines. */
    private List<String> applySetup(DiscordGuildSetup.Result result) {
        for (Map.Entry<String, String> entry : result.idsByKey().entrySet()) {
            if (HUB_KEY.equals(entry.getKey())) {
                configureHub(entry.getValue());
            } else {
                try {
                    configurePort(
                            Integer.parseInt(entry.getKey()),
                            entry.getValue()
                    );
                } catch (NumberFormatException ignored) {
                    // Only the hub and port keys are ever put in there.
                }
            }
        }

        List<String> lines = new ArrayList<>();

        if (!result.created().isEmpty()) {
            lines.add("Created: " + String.join(", ", result.created()));
        }

        if (!result.reused().isEmpty()) {
            lines.add("Already there, adopted: "
                    + String.join(", ", result.reused()));
        }

        if (result.ok()) {
            lines.add(
                    "Wired up " + result.idsByKey().size()
                            + " channel(s). They are hidden from @everyone - give "
                            + "your staff role access. 'evictchatlog test' checks them."
            );
        } else {
            lines.add("Stopped: " + result.error());

            if (!result.idsByKey().isEmpty()) {
                lines.add(
                        "The " + result.idsByKey().size()
                                + " channel(s) made before that are wired up; "
                                + "fix the problem and run setup again for the rest."
                );
            }
        }

        return lines;
    }

    /** Where the secrets file is expected, for the console's hints. */
    public String tokenPath() {
        return Secrets.path();
    }

    /** The key the token is read from, for the console's hints. */
    public String tokenKey() {
        return Secrets.DISCORD_CHAT_BOT_TOKEN;
    }

    /** A Discord channel id is a snowflake: digits only, 15-22 of them. */
    private static boolean isChannelId(String value) {
        if (value.length() < 15 || value.length() > 22) {
            return false;
        }

        for (int index = 0; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) {
                return false;
            }
        }

        return true;
    }
}
