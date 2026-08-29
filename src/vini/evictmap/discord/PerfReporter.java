package vini.evictmap.discord;

import arc.Core;
import vini.evictmap.core.io.Secrets;
import vini.evictmap.core.util.PluginLog;
import vini.evictmap.gen.EvictSettings;
import vini.evictmap.metrics.PerfSnapshot;
import vini.evictmap.metrics.ServerPerf;
import vini.evictmap.metrics.TpsHistory;

import java.net.http.HttpClient;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Keeps one Discord message per server up to date in a single channel: the hub
 * and every match-server slot, each with its own report, all rewritten in place.
 *
 * <p>One message each rather than one shared table, because a report has room
 * for the trend line and the breakdown that actually answer "why is it slow" -
 * and one channel rather than one per server, so the whole fleet reads top to
 * bottom in one place.
 *
 * <h2>The request budget</h2>
 * That layout runs straight into Discord's rate limit: message edits are
 * limited per <em>channel</em>, at roughly five requests per five seconds, and
 * eleven messages refreshing every three seconds would be four times over it.
 * So sends are not scheduled per message - they are drawn from one shared
 * budget ({@code evictperf rate}, four requests per five seconds by default)
 * and handed out round-robin to whichever report has something new to say.
 *
 * <p>That makes the refresh rate degrade honestly instead of failing: with the
 * hub alone it refreshes every few seconds, and with ten matches running the
 * same budget is shared eleven ways, so each report updates about every
 * thirteen. A slot with no worker in it costs nothing at all - its one-line
 * offline text only changes when the slot does, so it is skipped until then.
 *
 * <p>Hub only, like every Discord sender here. Worker numbers arrive through
 * the status files the duel manager already polls, so this class never talks to
 * a worker itself - it only renders what it is handed.
 */
public final class PerfReporter {

    /**
     * Floor on how often one message is rewritten, however much budget is
     * free. A single running server could otherwise spend the whole budget
     * redrawing itself four times in five seconds, which nobody can read and
     * which leaves nothing spare for a match that starts a moment later.
     */
    private static final long MIN_MESSAGE_INTERVAL_MILLIS = 3_000L;

    /** How often the reporter looks at the fleet at all; see {@link #update()}. */
    private static final long TICK_INTERVAL_MILLIS = 250L;

    /** The Discord category the setup creates the channel in. */
    private static final String CATEGORY_NAME = "Evict Logs";

    /** The key the setup stores its one channel under. */
    private static final String CHANNEL_KEY = "perf";

    private static final String CHANNEL_NAME = "performance";

    /** The hub's slot key; every other key is a worker port. */
    private static final String HUB_KEY = "hub";

    /** One server's message: its sender, its trend history, what it last said. */
    private static final class Slot {

        final String key;
        final DiscordBotMessage sender;
        final TpsHistory history = new TpsHistory();

        String lastSentBody = "";
        long lastSendMillis;

        Slot(String key, DiscordBotMessage sender) {
            this.key = key;
            this.sender = sender;
        }
    }

    private final EvictSettings settings;
    private final Supplier<PerfSnapshot> hubPerf;
    private final Supplier<List<ServerPerf>> workerPerf;

    private final HttpClient client = DiscordWebhook.newClient();

    /** Slots in the order they appear in the channel: hub first, then ports. */
    private final Map<String, Slot> slots = new LinkedHashMap<>();

    /** When each of the recent requests went out, for the budget window. */
    private final ArrayDeque<Long> recentSends = new ArrayDeque<>();

    /** Where the round-robin resumes, so one busy server cannot hog the budget. */
    private int cursor;

    private long lastTickMillis;

    /** How many times the reports have thrown; see {@link #reportFailure}. */
    private int failures;

    /** Log a repeating failure on the first one, then rarely. */
    private static final int FAILURE_LOG_INTERVAL = 200;

    /** The bot token as last read from the secrets file. */
    private volatile String token = "";

    private boolean started;

    public PerfReporter(
            EvictSettings settings,
            Supplier<PerfSnapshot> hubPerf,
            Supplier<List<ServerPerf>> workerPerf
    ) {
        this.settings = settings;
        this.hubPerf = hubPerf;
        this.workerPerf = workerPerf;
    }

    /** Hub-only startup: adopt the stored wiring. */
    public void start() {
        if (started) {
            return;
        }

        started = true;

        // Also creates the commented template when the file is missing, so a
        // fresh server shows an admin where the token goes.
        Secrets.reload();
        token = Secrets.get(Secrets.DISCORD_CHAT_BOT_TOKEN);

        if (settings.perfChannel().isBlank()) {
            return;
        }

        PluginLog.info(
                "Discord performance reports are on (@ requests per @s@).",
                settings.perfRateRequests(),
                settings.perfRateSeconds(),
                token.isBlank()
                        ? ", but " + Secrets.DISCORD_CHAT_BOT_TOKEN
                        + " is not set in " + Secrets.path()
                        : ""
        );
    }

    /**
     * Called every frame from the hub's update trigger, and gated to a few
     * times a second: taking a snapshot means sorting the tick ring and tallying
     * a minute of profiler samples, which is cheap four times a second and
     * pointless sixty. The gate is far finer than the budget's own spacing, so
     * it costs no freshness.
     */
    public void update() {
        if (!started || settings.perfChannel().isBlank() || token.isBlank()) {
            return;
        }

        long now = System.currentTimeMillis();

        if (now - lastTickMillis < TICK_INTERVAL_MILLIS) {
            return;
        }

        lastTickMillis = now;

        try {
            List<ServerPerf> servers = servers();

            recordHistory(servers, now);
            dispatch(servers, now);
        } catch (Throwable error) {
            // A monitoring feature must never be able to stop the server it is
            // monitoring. This is not hypothetical: a field that exists in the
            // Mindustry jar this compiles against but not in the one the server
            // runs threw a NoSuchFieldError straight onto the game loop and put
            // the hub into a restart loop. Throwable, because that was an Error
            // and a catch on Exception let it through.
            reportFailure(error);
        }
    }

    /**
     * Logs a failure in the reports themselves - loudly the first time, rarely
     * after that. Whatever is broken is broken every tick, so the alternative
     * is a server log with nothing else in it.
     */
    private void reportFailure(Throwable error) {
        failures++;

        if (failures == 1) {
            PluginLog.err(
                    "The performance reports failed and are being skipped; "
                            + "everything else is unaffected.",
                    error
            );
            return;
        }

        if (failures % FAILURE_LOG_INTERVAL == 0) {
            PluginLog.err(
                    "The performance reports are still failing (@ times): @",
                    failures,
                    String.valueOf(error)
            );
        }
    }

    /** The hub's own report, then one per match-server slot. */
    private List<ServerPerf> servers() {
        List<ServerPerf> servers = new ArrayList<>();

        servers.add(new ServerPerf(HUB_KEY, true, hubPerf.get(), 0L));
        servers.addAll(workerPerf.get());

        return servers;
    }

    /**
     * Feeds the trend lines. Kept here rather than in the sampler because the
     * hub is the only process that sees all of them, and a worker could not
     * keep a history anyway - it lives for one match. The history throttles
     * itself, so calling this every frame costs a comparison.
     */
    private void recordHistory(List<ServerPerf> servers, long now) {
        for (ServerPerf server : servers) {
            Slot slot = slot(server.name());

            if (server.hasData()) {
                slot.history.record(server.perf().tps(), now);
            } else {
                // A slot whose worker has gone: the next match on this port is
                // a different server, and stitching its line onto the old one
                // would draw a gap that never happened.
                slot.history.clear();
            }
        }
    }

    /**
     * Spends one request, on the report that has waited longest and has
     * something new to say. Returns without sending when the budget for this
     * window is used up - that is the mechanism that keeps eleven messages
     * inside a limit meant for far fewer.
     */
    private void dispatch(List<ServerPerf> servers, long now) {
        if (!budgetAvailable(now)) {
            return;
        }

        long timestamp = now / 1000L;
        int firstUncreated = firstUncreated(servers);

        for (int offset = 0; offset < servers.size(); offset++) {
            int index = (cursor + offset) % servers.size();
            ServerPerf server = servers.get(index);
            Slot slot = slot(server.name());

            // Messages are created in order, so the channel reads hub first and
            // then ports ascending. Until the one before it exists, this one
            // waits - a report posted out of order stays out of order forever.
            if (slot.sender.messageId().isEmpty() && index != firstUncreated) {
                continue;
            }

            if (!slot.sender.canSend()) {
                continue;
            }

            if (now - slot.lastSendMillis < MIN_MESSAGE_INTERVAL_MILLIS) {
                continue;
            }

            String body = PerfMessage.payload(server, slot.history, timestamp);

            // An offline slot renders the same line every time; there is no
            // reason to spend a request saying it again.
            if (body.equals(slot.lastSentBody)) {
                continue;
            }

            if (!slot.sender.publish(body)) {
                continue;
            }

            slot.lastSentBody = body;
            slot.lastSendMillis = now;
            recentSends.addLast(now);
            cursor = index + 1;
            return;
        }
    }

    /** True while this window still has a request left in it. */
    private boolean budgetAvailable(long now) {
        long windowMillis = settings.perfRateSeconds() * 1000L;

        while (!recentSends.isEmpty() && now - recentSends.peekFirst() >= windowMillis) {
            recentSends.removeFirst();
        }

        return recentSends.size() < settings.perfRateRequests();
    }

    /**
     * The index of the first server whose message does not exist yet, or -1.
     * A slot whose sender is broken is passed over rather than counted: its
     * message can never be created, and letting it hold the front of the queue
     * would keep every report behind it from ever being posted.
     */
    private int firstUncreated(List<ServerPerf> servers) {
        for (int index = 0; index < servers.size(); index++) {
            Slot slot = slot(servers.get(index).name());

            if (slot.sender.messageId().isEmpty() && !slot.sender.isBroken()) {
                return index;
            }
        }

        return -1;
    }

    /** The slot for one server, created on first sight with its stored id. */
    private Slot slot(String key) {
        Slot existing = slots.get(key);

        if (existing != null) {
            return existing;
        }

        DiscordBotMessage sender = new DiscordBotMessage(
                client,
                // The sender's thread cannot write the settings file - the main
                // thread saves it too. Hop back before persisting a new id.
                messageId -> Core.app.post(
                        () -> settings.setPerfMessage(key, messageId)
                )
        );

        sender.setToken(token);
        sender.configure(
                settings.perfChannel(),
                settings.perfMessages().getOrDefault(key, "")
        );

        Slot slot = new Slot(key, sender);
        slots.put(key, slot);

        return slot;
    }

    /** Wires the reports to a channel that already exists. */
    public void configureChannel(String channelId) {
        settings.setPerfChannel(channelId);
        slots.clear();
        recentSends.clear();
        cursor = 0;
    }

    /** Stops the reports and forgets the channel; the secrets file is left alone. */
    public void disable() {
        settings.clearPerf();
        slots.clear();
        recentSends.clear();
    }

    /**
     * Changes the Discord request budget. Everything else follows from it: with
     * more requests each report refreshes sooner, with fewer they all slow down
     * together.
     */
    public void setRate(int requests, int seconds) {
        settings.setPerfRate(requests, seconds);
        recentSends.clear();
    }

    /** Re-reads the bot token after a rotation; true when one is now loaded. */
    public boolean reloadToken() {
        Secrets.reload();
        token = Secrets.get(Secrets.DISCORD_CHAT_BOT_TOKEN);

        for (Slot slot : slots.values()) {
            slot.sender.setToken(token);
        }

        return !token.isBlank();
    }

    public boolean hasToken() {
        return !token.isBlank();
    }

    public boolean isConfigured() {
        return !settings.perfChannel().isBlank();
    }

    /** Forces every report to be redrawn as budget allows. */
    public void refreshAll() {
        for (Slot slot : slots.values()) {
            slot.lastSentBody = "";
            slot.lastSendMillis = 0L;
        }
    }

    /**
     * Has the bot create the performance channel in a Discord server and adopts
     * its id, so no channel id is ever copied by hand. An existing channel of
     * the same name is adopted rather than duplicated, so running it twice is
     * harmless.
     *
     * <p>The Discord calls block, so they run on their own thread; the id is
     * stored and the report delivered back on the main thread.
     */
    public void setupChannel(String guildId, Consumer<List<String>> report) {
        if (!hasToken()) {
            report.accept(List.of(
                    "No bot token is loaded. Set " + Secrets.DISCORD_CHAT_BOT_TOKEN
                            + " in " + Secrets.path() + " and run 'evictperf reload'."
            ));
            return;
        }

        if (!isSnowflake(guildId)) {
            report.accept(List.of(
                    "That is not a Discord server id. Run 'evictdiscordcmd setup' "
                            + "first - it finds the server itself - or pass the id: "
                            + "'evictperf setup <server-id>'."
            ));
            return;
        }

        List<DiscordGuildSetup.Wanted> wanted = List.of(new DiscordGuildSetup.Wanted(
                CHANNEL_KEY,
                CHANNEL_NAME,
                "Live tick rate of the hub and every match server."
        ));

        String currentToken = token;

        Thread worker = new Thread(
                () -> {
                    DiscordGuildSetup.Result result =
                            new DiscordGuildSetup(client, currentToken, guildId)
                                    .run(CATEGORY_NAME, wanted);

                    Core.app.post(() -> report.accept(applySetup(result)));
                },
                "evict-perf-setup"
        );
        worker.setDaemon(true);
        worker.start();
    }

    /** Stores what the setup created and turns it into console lines. */
    private List<String> applySetup(DiscordGuildSetup.Result result) {
        List<String> lines = new ArrayList<>();
        String channelId = result.idsByKey().get(CHANNEL_KEY);

        if (channelId != null && !channelId.isBlank()) {
            configureChannel(channelId);
        }

        if (!result.created().isEmpty()) {
            lines.add("Created: " + String.join(", ", result.created()));
        }

        if (!result.reused().isEmpty()) {
            lines.add("Already there, adopted: " + String.join(", ", result.reused()));
        }

        if (result.ok() && channelId != null) {
            lines.add(
                    "One report per server is being posted into #" + CHANNEL_NAME
                            + " now - the hub first, then a message per match "
                            + "server. It is hidden from @everyone, so give your "
                            + "staff role access."
            );
        } else if (!result.ok()) {
            lines.add("Stopped: " + result.error());
        }

        return lines;
    }

    /** One line per fact, for the console's checklist. */
    public List<String> statusLines() {
        List<String> lines = new ArrayList<>();

        lines.add("Bot token: " + (hasToken()
                ? "loaded from " + Secrets.path()
                : "NOT set (" + Secrets.DISCORD_CHAT_BOT_TOKEN + " in "
                + Secrets.path() + ")"));

        if (!isConfigured()) {
            lines.add("Channel: not set - run 'evictperf setup' to have the bot "
                    + "create it.");
            return lines;
        }

        lines.add("Channel: " + settings.perfChannel()
                + ", " + settings.perfMessages().size() + " message(s) posted");

        int live = liveServers();

        lines.add(String.format(
                Locale.ROOT,
                "Budget: %d request(s) per %ds - %d server(s) reporting, so each "
                        + "refreshes about every %.0fs",
                settings.perfRateRequests(),
                settings.perfRateSeconds(),
                live,
                refreshSeconds(live)
        ));

        for (Slot slot : slots.values()) {
            if (slot.sender.isBroken()) {
                lines.add("BROKEN " + slot.key + ": " + slot.sender.lastError());
            }
        }

        return lines;
    }

    /** How many servers are actually drawing a full report right now. */
    private int liveServers() {
        int live = 0;

        for (ServerPerf server : servers()) {
            if (server.hasData()) {
                live++;
            }
        }

        return live;
    }

    /**
     * How often one report comes round, given the budget and how many servers
     * are sharing it. The floor applies too: with one server the budget is not
     * the constraint, the minimum interval is.
     */
    private double refreshSeconds(int live) {
        if (live <= 0) {
            return MIN_MESSAGE_INTERVAL_MILLIS / 1000d;
        }

        double perRequestSeconds =
                (double) settings.perfRateSeconds() / settings.perfRateRequests();

        return Math.max(MIN_MESSAGE_INTERVAL_MILLIS / 1000d, live * perRequestSeconds);
    }

    /** A Discord snowflake: digits only, 15-22 of them. */
    private static boolean isSnowflake(String value) {
        if (value == null || value.length() < 15 || value.length() > 22) {
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
