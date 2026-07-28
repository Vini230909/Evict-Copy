package vini.evictmap.discord;

import java.util.List;

/**
 * Renders chat-log content into Discord webhook JSON: plain chat lines, and
 * the lifecycle embeds that frame them (match start/end on a port channel,
 * round end on the hub channel).
 *
 * <p>Everything here is pure string building on already-sanitised text - the
 * caller passes names and lines through {@link DiscordFormat} first - so it
 * runs on whichever thread holds them.
 */
final class ChatLogMessage {

    private static final long COLOR_START = 0x3498DBL;
    private static final long COLOR_WIN = 0xF1C40FL;
    private static final long COLOR_NO_RESULT = 0x99AAB5L;
    private static final long COLOR_FALLEN = 0x992D22L;

    /** Discord's hard limit on one embed field value. */
    private static final int MAX_FIELD_VALUE = 1024;

    /** Names spelled out per roster field before "+N more". */
    private static final int MAX_ROSTER_NAMES = 12;

    private ChatLogMessage() {
    }

    /**
     * One or more mirrored lines as a plain message. {@code allowed_mentions}
     * is empty for the same reason as everywhere else: the content quotes
     * player-chosen text, and a player called {@code @everyone} must not ping
     * a Discord server by saying hello.
     */
    static String lines(String content) {
        return new DiscordJson.Obj()
                .raw("allowed_mentions", "{\"parse\":[]}")
                .str("content", content)
                .toString();
    }

    /**
     * The match-start embed on a port channel: mode and the full rosters, so
     * the channel reads start - chat - end, match for match.
     */
    static String matchStart(String modeLabel, List<List<String>> teamNames) {
        DiscordJson.Arr fields = new DiscordJson.Arr();

        if (teamNames.size() == 1) {
            fields.add(field("Players", rosterList(teamNames.get(0)), false));
        } else {
            for (int index = 0; index < teamNames.size(); index++) {
                fields.add(field(
                        "Team " + (index + 1),
                        rosterList(teamNames.get(index)),
                        true
                ));
            }
        }

        DiscordJson.Obj embed = new DiscordJson.Obj()
                .str("title", "⚔️ Match started — " + modeLabel)
                .num("color", COLOR_START)
                .raw("fields", fields.toString());

        return withEmbed(embed);
    }

    /**
     * The match-end embed on a port channel. A Ranked match's rating movement
     * follows as its own line once the database write lands, so this embed is
     * never held back waiting for it.
     */
    static String matchEnd(
            String modeLabel,
            String winners,
            String losers,
            long durationSeconds,
            String howItEnded,
            boolean decided
    ) {
        DiscordJson.Arr fields = new DiscordJson.Arr();

        if (winners != null && !winners.isBlank()) {
            fields.add(field("Winner", winners, true));
        }

        if (losers != null && !losers.isBlank()) {
            fields.add(field("Loser", losers, true));
        }

        if (durationSeconds > 0L) {
            fields.add(field(
                    "Duration",
                    DiscordFormat.duration(durationSeconds),
                    true
            ));
        }

        fields.add(field("Ended", orDash(howItEnded), true));

        DiscordJson.Obj embed = new DiscordJson.Obj()
                .str("title", "🏁 Match over — " + modeLabel)
                .num("color", decided ? COLOR_WIN : COLOR_NO_RESULT)
                .raw("fields", fields.toString());

        return withEmbed(embed);
    }

    /**
     * The round-end embed on the hub channel - the information one actually
     * wants after an Evict round: who won, with whom, how, and how long it
     * took. Deliberately without statistics; the hub round keeps none.
     */
    static String roundEnd(
            String winnerLabel,
            String memberLines,
            long durationSeconds,
            String howItWasWon,
            boolean fallenWon
    ) {
        DiscordJson.Arr fields = new DiscordJson.Arr();

        if (memberLines != null && !memberLines.isBlank()) {
            fields.add(field("Team", memberLines, true));
        }

        if (durationSeconds > 0L) {
            fields.add(field(
                    "Duration",
                    DiscordFormat.duration(durationSeconds),
                    true
            ));
        }

        fields.add(field("Victory", orDash(howItWasWon), false));

        DiscordJson.Obj embed = new DiscordJson.Obj()
                .str(
                        "title",
                        (fallenWon ? "☠️ Round over — " : "🏆 Round over — ")
                                + winnerLabel
                )
                .num("color", fallenWon ? COLOR_FALLEN : COLOR_WIN)
                .raw("fields", fields.toString());

        return withEmbed(embed);
    }

    /** One roster as a field value, capped so a huge FFA cannot blow it up. */
    private static String rosterList(List<String> names) {
        if (names.isEmpty()) {
            return "—";
        }

        StringBuilder text = new StringBuilder();
        int listed = 0;

        for (String name : names) {
            if (
                    listed >= MAX_ROSTER_NAMES
                            || text.length() + name.length() > MAX_FIELD_VALUE - 24
            ) {
                break;
            }

            if (!text.isEmpty()) {
                text.append('\n');
            }

            text.append(name);
            listed++;
        }

        if (listed < names.size()) {
            text.append("\n+ ").append(names.size() - listed).append(" more");
        }

        return text.toString();
    }

    /** Discord rejects an empty field value; a dash reads as "nothing". */
    private static String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static String withEmbed(DiscordJson.Obj embed) {
        return new DiscordJson.Obj()
                .raw("allowed_mentions", "{\"parse\":[]}")
                .raw("embeds", new DiscordJson.Arr().add(embed).toString())
                .toString();
    }

    private static DiscordJson.Obj field(String name, String value, boolean inline) {
        return new DiscordJson.Obj()
                .str("name", name)
                .str("value", value)
                .raw("inline", Boolean.toString(inline));
    }
}
