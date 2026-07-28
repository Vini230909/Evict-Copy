package vini.evictmap.discord;

import vini.evictmap.moderation.BanOrigin;
import vini.evictmap.moderation.BanReport;
import vini.evictmap.moderation.WordFilterHit;

import java.util.List;

/**
 * Renders a {@link BanReport} into the JSON body of a Discord webhook message:
 * one embed per moderation action, listing every name, account and address the
 * ban covered.
 *
 * <p>Pure string building, no I/O and no Mindustry state, so it runs on
 * whichever thread the sender happens to be on.
 */
final class BanLogMessage {

    private static final long COLOR_BAN = 0xED4245L;
    private static final long COLOR_WORD_FILTER = 0xE67E22L;
    private static final long COLOR_UNBAN = 0x57F287L;
    private static final long COLOR_IMPORT = 0x99AAB5L;

    /** Discord's hard limit on one embed field value. */
    private static final int MAX_FIELD_VALUE = 1024;

    /** Entries listed per import summary before a second one is started. */
    static final int IMPORT_SUMMARY_CHUNK = 20;

    /** Field names may not be empty; a zero-width space renders as a bare line. */
    private static final String BLANK_FIELD_NAME = "​";

    private BanLogMessage() {
    }

    /**
     * One action.
     *
     * <p>{@code allowed_mentions.parse} is empty for the same reason as in the
     * status message: player names go into this message, players choose their
     * own names, and a player called {@code @everyone} must not be able to ping
     * a Discord server by getting themselves banned.
     */
    static String payload(BanReport report, long timestampSeconds) {
        DiscordJson.Arr fields = new DiscordJson.Arr();

        addStoryFields(fields, report);

        fields.add(field(
                "Names (" + report.names().size() + ")",
                nameList(report.names()),
                false
        ));
        fields.add(field(
                "UUIDs (" + report.uuids().size() + ")",
                codeList(report.uuids()),
                false
        ));
        fields.add(field(
                "IPs (" + report.ips().size() + ")",
                codeList(report.ips()),
                false
        ));
        fields.add(field(
                BLANK_FIELD_NAME,
                "Logged " + DiscordFormat.relativeTimestamp(timestampSeconds),
                false
        ));

        DiscordJson.Obj embed = new DiscordJson.Obj()
                .str("title", title(report))
                .num("color", color(report.kind()))
                .raw("fields", fields.toString());

        return new DiscordJson.Obj()
                .raw("allowed_mentions", "{\"parse\":[]}")
                .raw("embeds", new DiscordJson.Arr().add(embed).toString())
                .toString();
    }

    /**
     * A batch of imported old bans, collapsed into one embed.
     *
     * <p>Used when the one-off import turns up more entries than is reasonable
     * to post one at a time: a server with a long ban history would otherwise
     * push a hundred messages into the channel the first time it starts with
     * this feature, and Discord would rate-limit most of them anyway.
     */
    static String importSummary(
            List<BanReport> reports,
            int part,
            int parts,
            long timestampSeconds
    ) {
        StringBuilder lines = new StringBuilder();

        for (BanReport report : reports) {
            lines.append("• ")
                    .append(DiscordFormat.playerName(report.seedLabel()))
                    .append(" — ")
                    .append(report.uuids().size())
                    .append(report.uuids().size() == 1 ? " account, " : " accounts, ")
                    .append(report.ips().size())
                    .append(report.ips().size() == 1 ? " IP" : " IPs")
                    .append('\n');
        }

        DiscordJson.Arr fields = new DiscordJson.Arr();
        fields.add(field(
                "Bans (" + reports.size() + ")",
                DiscordFormat.truncate(lines.toString().trim(), MAX_FIELD_VALUE),
                false
        ));
        fields.add(field(
                BLANK_FIELD_NAME,
                "Logged " + DiscordFormat.relativeTimestamp(timestampSeconds),
                false
        ));

        String suffix = parts > 1 ? " (" + part + "/" + parts + ")" : "";

        DiscordJson.Obj embed = new DiscordJson.Obj()
                .str("title", "📥 Existing bans imported" + suffix)
                .str(
                        "description",
                        "Bans that predate the ban cascade, now widened to the "
                                + "accounts and addresses linked to them."
                )
                .num("color", COLOR_IMPORT)
                .raw("fields", fields.toString());

        return new DiscordJson.Obj()
                .raw("allowed_mentions", "{\"parse\":[]}")
                .raw("embeds", new DiscordJson.Arr().add(embed).toString())
                .toString();
    }

    /**
     * Why the ban happened, above the accounts it hit.
     *
     * <p>For the word filter: the word and the offending text, which is what
     * makes the entry reviewable on its own - a filter that bans without saying
     * what for cannot be checked for false positives. For every ban that
     * carries one: who decided it, on which server, and the console time in
     * that server's own log format, so an admin can open the right log and
     * search for the line.
     */
    private static void addStoryFields(DiscordJson.Arr fields, BanReport report) {
        WordFilterHit hit = report.wordFilterHit();

        if (hit != null) {
            fields.add(field("Word", codeSpan(hit.word()), true));
            fields.add(field("Found in", hit.source().label(), true));

            fields.add(field(
                    hit.source() == WordFilterHit.Source.CHAT ? "Message" : "Name",
                    DiscordFormat.playerText(hit.text()),
                    false
            ));
        }

        BanOrigin origin = report.origin();

        if (origin == null) {
            return;
        }

        fields.add(field("Banned by", DiscordFormat.playerName(origin.actor()), true));
        fields.add(field("Server", origin.server(), true));
        fields.add(field("Console time", codeSpan(origin.consoleTime()), false));
    }

    /**
     * A short value in a code span. Escaping is the wrong tool inside one - a
     * backslash renders literally there - so a backtick is replaced instead,
     * which is the only character that could end the span early.
     */
    private static String codeSpan(String value) {
        String cleaned = value == null ? "" : value.replace('`', '\'').trim();

        return cleaned.isEmpty() ? "—" : "`" + cleaned + "`";
    }

    private static String title(BanReport report) {
        String subject = DiscordFormat.playerName(report.seedLabel());

        return switch (report.kind()) {
            case BAN -> "🔨 Ban — " + subject;
            case WORD_FILTER -> "🤖 Word filter ban — " + subject;
            case UNBAN -> "♻️ Unban — " + subject;
            case IMPORT -> "📥 Imported ban — " + subject;
        };
    }

    private static long color(BanReport.Kind kind) {
        return switch (kind) {
            case BAN -> COLOR_BAN;
            case WORD_FILTER -> COLOR_WORD_FILTER;
            case UNBAN -> COLOR_UNBAN;
            case IMPORT -> COLOR_IMPORT;
        };
    }

    /**
     * Player names, one per line. Not wrapped in code spans: a name may contain
     * a backtick, which would end the span and let the rest of the name format
     * the message. {@link DiscordFormat#playerName} escapes instead.
     */
    private static String nameList(List<String> names) {
        if (names.isEmpty()) {
            return "—";
        }

        StringBuilder text = new StringBuilder();
        int listed = 0;

        for (String name : names) {
            String line = DiscordFormat.playerName(name) + "\n";

            if (text.length() + line.length() > MAX_FIELD_VALUE - 24) {
                break;
            }

            text.append(line);
            listed++;
        }

        return withRemainder(text, listed, names.size());
    }

    /**
     * UUIDs and addresses, one per line in code spans. Both are server-issued
     * or network-issued, so neither can carry a backtick of its own.
     */
    private static String codeList(List<String> values) {
        if (values.isEmpty()) {
            return "—";
        }

        StringBuilder text = new StringBuilder();
        int listed = 0;

        for (String value : values) {
            String line = "`" + value + "`\n";

            if (text.length() + line.length() > MAX_FIELD_VALUE - 24) {
                break;
            }

            text.append(line);
            listed++;
        }

        return withRemainder(text, listed, values.size());
    }

    private static String withRemainder(StringBuilder text, int listed, int total) {
        if (listed < total) {
            text.append("+ ").append(total - listed).append(" more");
        }

        return text.toString().trim();
    }

    private static DiscordJson.Obj field(String name, String value, boolean inline) {
        return new DiscordJson.Obj()
                .str("name", name)
                .str("value", value)
                .raw("inline", Boolean.toString(inline));
    }
}
