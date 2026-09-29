// The live Discord status message: players, round, Extinction timer, matches, a queued restart and the ranked ladder.
package Extinction;

import Extinction.core.util.PluginLog;
import Extinction.data.PlayerDataManager;
import Extinction.discord.DiscordFormat;
import Extinction.discord.DiscordJson;
import Extinction.discord.DiscordWebhook;
import Extinction.round.TeamManager;
import arc.Core;
import arc.util.Time;
import mindustry.Vars;
import mindustry.gen.Groups;
import mindustry.net.Administration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

// Hub only: four workers editing one message would fight. Refreshed on a fixed interval on purpose (see GAMEPLAY.md).
public final class DiscordStatus {

    private static final long UPDATE_INTERVAL_MILLIS = 30_000L;

    // The ladder only moves when a ranked match finishes, so it is re-read on its own slow cadence.
    private static final long LADDER_REFRESH_MILLIS = 300_000L;

    private static final int LADDER_SIZE = 10;

    // Budget for the farewell message; the JVM is already on its way out.
    private static final Duration OFFLINE_SEND_TIMEOUT = Duration.ofSeconds(3);

    // Discord green / red / gold.
    private static final long COLOR_ONLINE = 0x57F287L;
    private static final long COLOR_OFFLINE = 0xED4245L;
    private static final long COLOR_LADDER = 0xFEE75CL;

    // Discord's hard limits on one embed field value and on an embed description.
    private static final int MAX_FIELD_VALUE = 1024;
    private static final int MAX_DESCRIPTION = 4096;

    // Names listed per team before the rest become "+N more".
    private static final int MAX_NAMES_PER_TEAM = 4;

    // Field names may not be empty; a zero-width space renders as a bare line.
    private static final String BLANK_FIELD_NAME = "​";

    private final PlayerDataManager playerDataManager;
    private final TeamManager teamManager;
    private final ExtinctionWave extinction;
    private final Matches pool;
    private final Restart restart;

    // The webhook thread cannot write the properties file: the main thread saves it too.
    private final DiscordWebhook webhook = new DiscordWebhook(messageId -> Core.app.post(() -> rememberMessageId(messageId)));

    private long lastUpdateMillis;
    private long lastLadderRefreshMillis;
    private List<LadderEntry> ladder = List.of();
    private boolean ladderQueryInFlight;
    private boolean started;

    public DiscordStatus(PlayerDataManager playerDataManager, TeamManager teamManager, ExtinctionWave extinction, Matches pool, Restart restart) {
        this.playerDataManager = playerDataManager;
        this.teamManager = teamManager;
        this.extinction = extinction;
        this.pool = pool;
        this.restart = restart;
    }

    // Hub-only startup: adopt the stored webhook and arrange the goodbye.
    public void start() {
        if (started) {
            return;
        }

        started = true;

        // Registered even with no webhook yet: one set later with 'discordstatus <url>' must still say goodbye.
        Runtime.getRuntime().addShutdownHook(
                new Thread(this::publishOffline, "evict-discord-offline")
        );

        webhook.configure(Config.discordWebhookUrl, Config.discordMessageId);

        if (!webhook.isConfigured()) {
            return;
        }

        PluginLog.info(
                "Discord status reporting is on (@).",
                Config.discordMessageId.isBlank()
                        ? "a new message will be posted"
                        : "editing message " + Config.discordMessageId
        );
    }

    // Called every frame from the hub's update trigger; throttled internally.
    public void update() {
        if (!started || !webhook.isConfigured() || webhook.isBroken()) {
            return;
        }

        refreshLadderIfDue();

        if (Time.timeSinceMillis(lastUpdateMillis) < UPDATE_INTERVAL_MILLIS) {
            return;
        }

        lastUpdateMillis = Time.millis();
        webhook.publish(payload(capture(true)));
    }

    // Wired to every rating change, so a finished ranked match shows on the ladder now, like /info and /top.
    public void markLadderStale() {
        lastLadderRefreshMillis = 0L;
    }

    // 'discordstatus <url>': the old message id belongs to the old channel, so a fresh message is posted.
    public boolean configure(String webhookUrl) {
        String trimmed = webhookUrl == null ? "" : webhookUrl.trim();

        if (!isWebhookUrl(trimmed)) {
            return false;
        }

        Config.discordWebhookUrl = trimmed;
        Config.discordMessageId = "";
        Config.save();
        webhook.configure(trimmed, "");
        publishNow();
        return true;
    }

    // 'discordstatus off': marks the server offline in Discord and stops updating.
    public void disable() {
        if (webhook.isConfigured()) {
            publishOffline();
        }

        Config.discordWebhookUrl = "";
        Config.discordMessageId = "";
        Config.save();
        webhook.configure("", "");
    }

    // 'discordstatus test': an immediate refresh, ignoring the interval.
    public void publishNow() {
        if (!webhook.isConfigured()) {
            return;
        }

        lastUpdateMillis = Time.millis();
        webhook.publish(payload(capture(true)));
    }

    // 'discordstatus': one line describing the current wiring.
    public String statusLine() {
        if (!webhook.isConfigured()) {
            return "not set (use 'discordstatus <webhook-url>')";
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

    // Main thread: remembers the message being edited across restarts; saves only when it changed.
    private void rememberMessageId(String messageId) {
        String cleaned = messageId == null ? "" : messageId.trim();

        if (cleaned.equals(Config.discordMessageId)) {
            return;
        }

        Config.discordMessageId = cleaned;
        Config.save();
    }

    // Sent synchronously: the process is stopping and an async send would never leave the JVM.
    private void publishOffline() {
        webhook.publishBlocking(payload(capture(false)), OFFLINE_SEND_TIMEOUT);
    }

    // Main thread only (it walks Mindustry groups), except the offline capture, which runs after the game loop stopped.
    private Snapshot capture(boolean online) {
        int duelPlayers = pool.connectedDuelPlayers();
        List<Match> matches = captureMatches();

        return new Snapshot(
                online,
                Administration.Config.serverName.string(),
                Groups.player.size(),
                duelPlayers,
                Math.max(0, Vars.netServer.admins.getPlayerLimit()),
                teamManager.roundRuntimeMillis() / 1000L,
                (long) extinction.secondsUntilExtinction(),
                extinction.hasBegun(),
                restart.isQueued(),
                matches.size(),
                Config.duelMaxWorkers,
                matches,
                ladder,
                System.currentTimeMillis() / 1000L
        );
    }

    private List<Match> captureMatches() {
        List<Match> matches = new ArrayList<>();

        for (Matches.MatchStatus status : pool.matchStatuses()) {
            List<List<String>> teams = new ArrayList<>();

            for (List<String> roster : status.teamNames()) {
                List<String> names = new ArrayList<>();

                for (String name : roster) {
                    names.add(DiscordFormat.playerName(name));
                }

                teams.add(names);
            }

            matches.add(new Match(status.slot(), status.modeLabel(), teams, status.seconds()));
        }

        return matches;
    }

    // The read is async and lands on the main thread; until then the message shows the previous ladder.
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
            List<LadderEntry> entries = new ArrayList<>();
            int rank = 1;

            for (PlayerDataManager.PlayerInfo player : players) {
                entries.add(new LadderEntry(rank++, DiscordFormat.playerName(player.lastName()), player.elo()));
            }

            ladder = List.copyOf(entries);
            ladderQueryInFlight = false;
        });
    }

    // Players choose their names, so nothing in the message may ping (a player called @everyone).
    private static String payload(Snapshot snapshot) {
        DiscordJson.Arr embeds = new DiscordJson.Arr();
        embeds.add(snapshot.online() ? statusEmbed(snapshot) : offlineEmbed(snapshot));

        if (snapshot.online()) {
            embeds.add(ladderEmbed(snapshot));
        }

        return new DiscordJson.Obj()
                .raw("allowed_mentions", "{\"parse\":[]}")
                .raw("embeds", embeds.toString())
                .toString();
    }

    private static DiscordJson.Obj statusEmbed(Snapshot snapshot) {
        DiscordJson.Arr fields = new DiscordJson.Arr();

        fields.add(field("Players", playersValue(snapshot), true));
        fields.add(field("Round", roundValue(snapshot), true));
        fields.add(field(matchesTitle(snapshot), matchesValue(snapshot), false));

        if (snapshot.restartQueued()) {
            fields.add(field(
                    "⚠️ Restart queued",
                    "The server restarts for an update at the next safe moment.",
                    false
            ));
        }

        fields.add(field(
                BLANK_FIELD_NAME,
                "Updated " + DiscordFormat.relativeTimestamp(snapshot.timestampSeconds()),
                false
        ));

        return new DiscordJson.Obj()
                .str("title", "🟢 " + serverTitle(snapshot) + " — Online")
                .num("color", COLOR_ONLINE)
                .raw("fields", fields.toString());
    }

    private static DiscordJson.Obj offlineEmbed(Snapshot snapshot) {
        return new DiscordJson.Obj()
                .str("title", "🔴 " + serverTitle(snapshot) + " — Offline")
                .str("description", "Last online " + DiscordFormat.relativeTimestamp(snapshot.timestampSeconds()))
                .num("color", COLOR_OFFLINE);
    }

    private static DiscordJson.Obj ladderEmbed(Snapshot snapshot) {
        return new DiscordJson.Obj()
                .str("title", "🏆 Ranked Ladder")
                .str("description", DiscordFormat.truncate(ladderValue(snapshot), MAX_DESCRIPTION))
                .num("color", COLOR_LADDER);
    }

    private static String serverTitle(Snapshot snapshot) {
        String name = snapshot.serverName();

        if (name == null || name.isBlank()) {
            return "Evict";
        }

        return DiscordFormat.escapeMarkdown(DiscordFormat.truncate(name.trim(), 64));
    }

    // Total against the slot cap; the lobby/match split only while a match runs, so a quiet server shows no zeroes.
    private static String playersValue(Snapshot snapshot) {
        StringBuilder value = new StringBuilder();
        value.append("**").append(snapshot.totalPlayers()).append("**");

        if (snapshot.playerLimit() > 0) {
            value.append(" / ").append(snapshot.playerLimit());
        }

        if (snapshot.duelPlayers() > 0) {
            value.append("\n")
                    .append(snapshot.hubPlayers()).append(" in lobby · ")
                    .append(snapshot.duelPlayers()).append(" in matches");
        }

        return value.toString();
    }

    private static String roundValue(Snapshot snapshot) {
        StringBuilder value = new StringBuilder();
        value.append(DiscordFormat.duration(snapshot.roundSeconds()));

        if (snapshot.extinctionBegun()) {
            value.append("\nExtinction in progress");
        } else {
            value.append("\nExtinction in ")
                    .append(DiscordFormat.duration(snapshot.extinctionInSeconds()));
        }

        return value.toString();
    }

    private static String matchesTitle(Snapshot snapshot) {
        if (snapshot.matches().isEmpty()) {
            return "Matches";
        }

        return "Matches — " + snapshot.usedMatchSlots()
                + " of " + snapshot.maxMatchSlots() + " slots";
    }

    private static String matchesValue(Snapshot snapshot) {
        if (snapshot.matches().isEmpty()) {
            return "*none running*";
        }

        StringBuilder value = new StringBuilder();

        for (Match match : snapshot.matches()) {
            if (!value.isEmpty()) {
                value.append("\n");
            }

            value.append("`").append(match.slot()).append("` ")
                    .append("**").append(DiscordFormat.escapeMarkdown(match.modeLabel())).append("** · ")
                    .append(rosterText(match.teams()))
                    .append(" · `").append(DiscordFormat.duration(match.seconds())).append("`");
        }

        return DiscordFormat.truncate(value.toString(), MAX_FIELD_VALUE);
    }

    // "A vs B", "A, B vs C, D", or for a crowded FFA "A, B, C, D +6 more". Names arrive already cleaned.
    private static String rosterText(List<List<String>> teams) {
        if (teams.isEmpty()) {
            return "*(empty)*";
        }

        // An FFA is one single-player team each; "vs" between them would be as long as the player list.
        boolean allSolo = teams.stream().allMatch(team -> team.size() <= 1);

        if (allSolo && teams.size() > 2) {
            return shortenNames(teams.stream().flatMap(List::stream).toList());
        }

        StringBuilder text = new StringBuilder();

        for (List<String> team : teams) {
            if (!text.isEmpty()) {
                text.append(" vs ");
            }

            text.append(shortenNames(team));
        }

        return text.toString();
    }

    private static String shortenNames(List<String> names) {
        if (names.isEmpty()) {
            return "*(empty)*";
        }

        if (names.size() <= MAX_NAMES_PER_TEAM) {
            return String.join(", ", names);
        }

        return String.join(", ", names.subList(0, MAX_NAMES_PER_TEAM))
                + " +" + (names.size() - MAX_NAMES_PER_TEAM) + " more";
    }

    private static String ladderValue(Snapshot snapshot) {
        if (snapshot.ladder().isEmpty()) {
            return "*No ranked matches played yet.*";
        }

        StringBuilder value = new StringBuilder();

        for (LadderEntry entry : snapshot.ladder()) {
            if (!value.isEmpty()) {
                value.append("\n");
            }

            value.append("`").append(String.format("%2d", entry.rank())).append(".` ")
                    .append("**").append(entry.name()).append("** — ")
                    .append(entry.elo());
        }

        return value.toString();
    }

    private static DiscordJson.Obj field(String name, String value, boolean inline) {
        return new DiscordJson.Obj()
                .str("name", name)
                .str("value", value)
                .raw("inline", Boolean.toString(inline));
    }

    // A typo fails at the console instead of turning into a silent 404 every 30 seconds.
    private static boolean isWebhookUrl(String url) {
        return url.startsWith("https://")
                && url.contains("/api/webhooks/")
                && !url.endsWith("/api/webhooks/");
    }

    // Everything the message shows at one instant; online is false only for the farewell, playerLimit 0 = uncapped.
    private record Snapshot(
            boolean online,
            String serverName,
            int hubPlayers,
            int duelPlayers,
            int playerLimit,
            long roundSeconds,
            long extinctionInSeconds,
            boolean extinctionBegun,
            boolean restartQueued,
            int usedMatchSlots,
            int maxMatchSlots,
            List<Match> matches,
            List<LadderEntry> ladder,
            long timestampSeconds
    ) {

        int totalPlayers() {
            return hubPlayers + duelPlayers;
        }
    }

    // One running match: slot is the 1-based pool slot, deliberately not the port.
    private record Match(int slot, String modeLabel, List<List<String>> teams, long seconds) {
    }

    // One ranked ladder row; rank is 1-based.
    private record LadderEntry(int rank, String name, int elo) {
    }
}
