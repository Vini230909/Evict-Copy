package vini.evictmap.discord;

import arc.Core;
import arc.util.Time;
import mindustry.Vars;
import mindustry.gen.Groups;
import mindustry.net.Administration;
import vini.evictmap.RestartManager;
import vini.evictmap.core.util.PluginLog;
import vini.evictmap.data.PlayerDataManager;
import vini.evictmap.duel.DuelServerManager;
import vini.evictmap.gameplay.ExtinctionManager;
import vini.evictmap.gen.EvictSettings;
import vini.evictmap.round.TeamManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps one Discord message in sync with the live server: players, round and
 * Extinction timer, running matches, a queued restart and the ranked ladder.
 *
 * <p>Hub only. Every duel worker runs this same jar, and four workers editing
 * the same message would fight each other, so {@link #start} is simply never
 * called on a worker.
 *
 * <p>The message is refreshed on a fixed interval whether or not anything
 * changed. That is deliberate: the "Updated ..." line is a Discord relative
 * timestamp that counts up by itself, so a steady refresh keeps it reading
 * "seconds ago" while the server is healthy, and lets it grow into a visible
 * fault indicator the moment the server, the network or the plugin stops. An
 * edit notifies nobody, so the only cost is one small request a minute.
 */
public final class DiscordStatusReporter {

    /** How often the message is refreshed. */
    private static final long UPDATE_INTERVAL_MILLIS = 30_000L;

    /**
     * How often the ranked ladder is re-queried. It only moves when a ranked
     * match finishes, so re-reading it every refresh would be 120 pointless
     * database round trips an hour.
     */
    private static final long LADDER_REFRESH_MILLIS = 300_000L;

    /** Ladder places shown. */
    private static final int LADDER_SIZE = 10;

    /** Budget for the farewell message; the JVM is already on its way out. */
    private static final Duration OFFLINE_SEND_TIMEOUT = Duration.ofSeconds(3);

    private final EvictSettings settings;
    private final PlayerDataManager playerDataManager;
    private final TeamManager teamManager;
    private final ExtinctionManager extinctionManager;
    private final DuelServerManager duelServerManager;
    private final RestartManager restartManager;

    private final DiscordWebhook webhook;

    private long lastUpdateMillis;
    private long lastLadderRefreshMillis;
    private List<StatusSnapshot.LadderEntry> ladder = List.of();
    private boolean ladderQueryInFlight;
    private boolean started;

    public DiscordStatusReporter(
            EvictSettings settings,
            PlayerDataManager playerDataManager,
            TeamManager teamManager,
            ExtinctionManager extinctionManager,
            DuelServerManager duelServerManager,
            RestartManager restartManager
    ) {
        this.settings = settings;
        this.playerDataManager = playerDataManager;
        this.teamManager = teamManager;
        this.extinctionManager = extinctionManager;
        this.duelServerManager = duelServerManager;
        this.restartManager = restartManager;

        // The webhook thread cannot write the settings file: the main thread
        // saves it too. Hop back before persisting a newly created message id.
        this.webhook = new DiscordWebhook(
                messageId -> Core.app.post(() -> settings.setDiscordMessageId(messageId))
        );
    }

    /** Hub-only startup: adopt the stored webhook and arrange the goodbye. */
    public void start() {
        if (started) {
            return;
        }

        started = true;

        // Registered whether or not a webhook is set yet: one may be set later
        // with 'evictdiscord <url>', and that must not leave the server with no
        // way to say goodbye until the next restart. The hook itself does
        // nothing when there is nothing configured.
        Runtime.getRuntime().addShutdownHook(
                new Thread(this::publishOffline, "evict-discord-offline")
        );

        webhook.configure(settings.discordWebhookUrl(), settings.discordMessageId());

        if (!webhook.isConfigured()) {
            return;
        }

        PluginLog.info(
                "Discord status reporting is on (@).",
                settings.discordMessageId().isBlank()
                        ? "a new message will be posted"
                        : "editing message " + settings.discordMessageId()
        );
    }

    /** Called every frame from the hub's update trigger; throttled internally. */
    public void update() {
        if (!started || !webhook.isConfigured() || webhook.isBroken()) {
            return;
        }

        refreshLadderIfDue();

        if (Time.timeSinceMillis(lastUpdateMillis) < UPDATE_INTERVAL_MILLIS) {
            return;
        }

        lastUpdateMillis = Time.millis();
        webhook.publish(capture(true));
    }

    /**
     * Drops the cached ladder so the next update re-queries it. Wired to every
     * rating change: without it a finished ranked match would keep showing the
     * pre-match ratings for up to {@link #LADDER_REFRESH_MILLIS}, disagreeing
     * with /info and /leaderboard the whole time.
     */
    public void markLadderStale() {
        lastLadderRefreshMillis = 0L;
    }

    /**
     * Points the reporter at a new webhook. The stored message id is dropped
     * with it - the old id belongs to the old channel - so the next send posts
     * a fresh message and stores its id.
     */
    public boolean configure(String webhookUrl) {
        String trimmed = webhookUrl == null ? "" : webhookUrl.trim();

        if (!isWebhookUrl(trimmed)) {
            return false;
        }

        settings.setDiscordWebhook(trimmed, "");
        webhook.configure(trimmed, "");
        publishNow();
        return true;
    }

    /** Marks the server offline in Discord and stops updating. */
    public void disable() {
        if (webhook.isConfigured()) {
            publishOffline();
        }

        settings.setDiscordWebhook("", "");
        webhook.configure("", "");
    }

    /** Forces an immediate refresh, ignoring the interval. */
    public void publishNow() {
        if (!webhook.isConfigured()) {
            return;
        }

        lastUpdateMillis = Time.millis();
        webhook.publish(capture(true));
    }

    /** One line describing the current wiring, for the console command. */
    public String statusLine() {
        if (!webhook.isConfigured()) {
            return "not set (use 'evictdiscord <webhook-url>')";
        }

        StringBuilder status = new StringBuilder();
        status.append(webhook.isBroken() ? "BROKEN" : "on");
        status.append(", message=")
                .append(webhook.messageId().isBlank() ? "not posted yet" : webhook.messageId());
        status.append(", updates every ")
                .append(UPDATE_INTERVAL_MILLIS / 1000L).append("s");

        if (webhook.lastSuccessMillis() > 0L) {
            status.append(", last success ")
                    .append(Time.timeSinceMillis(webhook.lastSuccessMillis()) / 1000L)
                    .append("s ago");
        } else {
            status.append(", no successful update yet");
        }

        if (!webhook.lastError().isBlank()) {
            status.append(", last error: ").append(webhook.lastError());
        }

        return status.toString();
    }

    /**
     * The farewell edit, sent synchronously because the process is stopping and
     * an async send would never leave the JVM. A hard crash cannot reach this,
     * which is exactly what the counting "Updated ..." line is there for.
     */
    private void publishOffline() {
        webhook.publishBlocking(capture(false), OFFLINE_SEND_TIMEOUT);
    }

    /**
     * Reads the live server state. Main-thread only: it walks Mindustry groups
     * and the duel worker table. The resulting snapshot is immutable and is the
     * only thing handed to the sender.
     *
     * <p>The exception is {@link #publishOffline}, which runs on a shutdown
     * hook. By then the game loop has stopped, so nothing is concurrently
     * mutating what it reads, and the offline message uses almost none of it.
     */
    private StatusSnapshot capture(boolean online) {
        int duelPlayers = duelServerManager.connectedDuelPlayers();
        List<StatusSnapshot.Match> matches = captureMatches();

        return new StatusSnapshot(
                online,
                Administration.Config.serverName.string(),
                Groups.player.size(),
                duelPlayers,
                Math.max(0, Vars.netServer.admins.getPlayerLimit()),
                teamManager.roundRuntimeMillis() / 1000L,
                (long) extinctionManager.secondsUntilExtinction(),
                extinctionManager.hasBegun(),
                restartManager.isQueued(),
                matches.size(),
                settings.duelMaxWorkers(),
                matches,
                ladder,
                System.currentTimeMillis() / 1000L
        );
    }

    private List<StatusSnapshot.Match> captureMatches() {
        List<StatusSnapshot.Match> matches = new ArrayList<>();

        for (DuelServerManager.MatchStatus status : duelServerManager.matchStatuses()) {
            List<List<String>> teams = new ArrayList<>();

            for (List<String> roster : status.teamNames()) {
                List<String> names = new ArrayList<>();

                for (String name : roster) {
                    names.add(DiscordFormat.playerName(name));
                }

                teams.add(names);
            }

            matches.add(new StatusSnapshot.Match(
                    status.slot(),
                    status.modeLabel(),
                    teams,
                    status.seconds()
            ));
        }

        return matches;
    }

    /**
     * Re-queries the ranked ladder on its own slow cadence. The database read
     * is asynchronous and delivered back on the main thread, so the message
     * simply keeps showing the previous ladder until the new one lands.
     */
    private void refreshLadderIfDue() {
        if (ladderQueryInFlight) {
            return;
        }

        if (lastLadderRefreshMillis != 0L
                && Time.timeSinceMillis(lastLadderRefreshMillis) < LADDER_REFRESH_MILLIS) {
            return;
        }

        lastLadderRefreshMillis = Time.millis();
        ladderQueryInFlight = true;

        playerDataManager.topRankedByElo(LADDER_SIZE, players -> {
            List<StatusSnapshot.LadderEntry> entries = new ArrayList<>();
            int rank = 1;

            for (PlayerDataManager.PlayerInfo player : players) {
                entries.add(new StatusSnapshot.LadderEntry(
                        rank++,
                        DiscordFormat.playerName(player.lastName()),
                        player.elo()
                ));
            }

            ladder = List.copyOf(entries);
            ladderQueryInFlight = false;
        });
    }

    /**
     * Rejects anything that is not a Discord webhook URL, so a typo fails at
     * the console instead of turning into a silent 404 every 30 seconds.
     */
    private static boolean isWebhookUrl(String url) {
        return url.startsWith("https://")
                && url.contains("/api/webhooks/")
                && !url.endsWith("/api/webhooks/");
    }
}
