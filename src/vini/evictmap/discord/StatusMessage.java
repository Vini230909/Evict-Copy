package vini.evictmap.discord;

import java.util.List;

/**
 * Renders a {@link StatusSnapshot} into the JSON body of a Discord webhook
 * message: two embeds, the live server status and the ranked ladder.
 *
 * <p>Pure string building with no I/O and no Mindustry state, so it runs on
 * whichever thread the sender is on.
 */
final class StatusMessage {

    /** Discord green / red / gold. */
    private static final long COLOR_ONLINE = 0x57F287L;
    private static final long COLOR_OFFLINE = 0xED4245L;
    private static final long COLOR_LADDER = 0xFEE75CL;

    /** Discord's hard limit on one embed field value. */
    private static final int MAX_FIELD_VALUE = 1024;

    /** Discord's hard limit on an embed description. */
    private static final int MAX_DESCRIPTION = 4096;

    /** Names listed per team before the rest become "+N more". */
    private static final int MAX_NAMES_PER_TEAM = 4;

    /**
     * Field names may not be empty, so the closing "Updated ..." row uses a
     * zero-width space as its name to render as a bare line.
     */
    private static final String BLANK_FIELD_NAME = "​";

    private StatusMessage() {
    }

    /**
     * The full webhook payload.
     *
     * <p>{@code allowed_mentions.parse} is an empty list, which tells Discord
     * to render every {@code @everyone}, {@code @here} and role mention in this
     * message as plain text. Player names reach this message, players choose
     * their own names, and without this field a player calling themselves
     * {@code @everyone} would ping the whole Discord server twice a minute for
     * as long as their match ran.
     */
    static String payload(StatusSnapshot snapshot) {
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

    private static DiscordJson.Obj statusEmbed(StatusSnapshot snapshot) {
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

    private static DiscordJson.Obj offlineEmbed(StatusSnapshot snapshot) {
        return new DiscordJson.Obj()
                .str("title", "🔴 " + serverTitle(snapshot) + " — Offline")
                .str(
                        "description",
                        "Last online "
                                + DiscordFormat.relativeTimestamp(snapshot.timestampSeconds())
                )
                .num("color", COLOR_OFFLINE);
    }

    private static DiscordJson.Obj ladderEmbed(StatusSnapshot snapshot) {
        return new DiscordJson.Obj()
                .str("title", "🏆 Ranked Ladder")
                .str("description", DiscordFormat.truncate(ladderValue(snapshot), MAX_DESCRIPTION))
                .num("color", COLOR_LADDER);
    }

    private static String serverTitle(StatusSnapshot snapshot) {
        String name = snapshot.serverName();

        if (name == null || name.isBlank()) {
            return "Evict";
        }

        return DiscordFormat.escapeMarkdown(
                DiscordFormat.truncate(name.trim(), 64)
        );
    }

    /**
     * Total players, against the slot cap when one is set. The lobby/match split
     * only appears while a match is actually running, so the quiet server does
     * not carry a line of zeroes.
     */
    private static String playersValue(StatusSnapshot snapshot) {
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

    private static String roundValue(StatusSnapshot snapshot) {
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

    private static String matchesTitle(StatusSnapshot snapshot) {
        if (snapshot.matches().isEmpty()) {
            return "Matches";
        }

        return "Matches — " + snapshot.usedMatchSlots()
                + " of " + snapshot.maxMatchSlots() + " slots";
    }

    private static String matchesValue(StatusSnapshot snapshot) {
        if (snapshot.matches().isEmpty()) {
            return "*none running*";
        }

        StringBuilder value = new StringBuilder();

        for (StatusSnapshot.Match match : snapshot.matches()) {
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

    /**
     * "A vs B", "A, B vs C, D", or for a crowded FFA "A, B, C, D +6 more".
     * Names arrive already cleaned by {@link DiscordFormat#playerName}.
     */
    private static String rosterText(List<List<String>> teams) {
        if (teams.isEmpty()) {
            return "*(empty)*";
        }

        // An FFA hands over one single-player team per participant, so joining
        // them with "vs" would produce a chain as long as the player list.
        // Flatten those into one shortened roll call instead.
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

    private static String ladderValue(StatusSnapshot snapshot) {
        if (snapshot.ladder().isEmpty()) {
            return "*No ranked matches played yet.*";
        }

        StringBuilder value = new StringBuilder();

        for (StatusSnapshot.LadderEntry entry : snapshot.ladder()) {
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
}
