package vini.evictmap.discord;

import arc.util.Strings;

/**
 * Turns server-side values into text that is safe to put in a Discord message.
 *
 * <p>The important job here is {@link #playerName}. Mindustry names are chosen
 * by the player, so they arrive carrying Mindustry colour tags, Discord
 * markdown characters, control characters and whatever else fits in a name
 * field. Dropped into a message unprocessed they break the layout (a stray
 * {@code **} bolds the rest of the line) or impersonate formatting.
 *
 * <p>Mentions are <em>not</em> handled here: a name containing
 * {@code @everyone} is defused by the payload's {@code allowed_mentions}
 * (see {@link StatusMessage}), which is a guarantee from Discord rather than a
 * string filter that a clever name could slip past.
 */
public final class DiscordFormat {

    /** Longest player name rendered before it is cut short. */
    private static final int MAX_NAME_LENGTH = 24;

    /** Characters Discord reads as markdown, escaped with a backslash. */
    private static final String MARKDOWN_SPECIALS = "\\*_~`|";

    private DiscordFormat() {
    }

    /**
     * A player name, safe for a Discord message and still recognisable as the
     * name the player chose.
     *
     * <p>Order matters: the truncation happens before escaping, otherwise a cut
     * could land between a backslash and the character it escapes and the
     * backslash would escape whatever followed it instead.
     */
    public static String playerName(String raw) {
        if (raw == null) {
            return "(unnamed)";
        }

        String cleaned = Strings.stripColors(raw);
        cleaned = stripControlCharacters(cleaned);
        cleaned = cleaned.replaceAll("\\s+", " ").trim();

        if (cleaned.isEmpty()) {
            return "(unnamed)";
        }

        if (cleaned.length() > MAX_NAME_LENGTH) {
            cleaned = cleaned.substring(0, MAX_NAME_LENGTH) + "…";
        }

        return escapeMarkdown(cleaned);
    }

    /**
     * Backslash-escapes Discord's markdown characters so the text renders
     * literally. Used for names and for the admin-set server name.
     */
    public static String escapeMarkdown(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        StringBuilder escaped = new StringBuilder(text.length() + 8);

        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);

            if (MARKDOWN_SPECIALS.indexOf(character) >= 0) {
                escaped.append('\\');
            }

            escaped.append(character);
        }

        return escaped.toString();
    }

    /**
     * Replaces control characters (newlines above all) with spaces. A name
     * carrying a line break would otherwise split one message line into two and
     * wreck the alignment of everything after it.
     */
    private static String stripControlCharacters(String text) {
        StringBuilder result = new StringBuilder(text.length());

        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            result.append(Character.isISOControl(character) ? ' ' : character);
        }

        return result.toString();
    }

    /** Seconds as {@code H:MM:SS}, or {@code MM:SS} below an hour. */
    public static String duration(long totalSeconds) {
        long seconds = Math.max(0L, totalSeconds);
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long remainder = seconds % 60L;

        if (hours > 0L) {
            return String.format("%02d:%02d:%02d", hours, minutes, remainder);
        }

        return String.format("%02d:%02d", minutes, remainder);
    }

    /**
     * A Discord relative timestamp, e.g. {@code <t:1753290000:R>}, which every
     * client renders as "3 minutes ago" and keeps counting up on its own. That
     * is what lets one edit every 30 seconds still read as live, and what makes
     * a stuck server obvious: the number simply keeps growing.
     */
    public static String relativeTimestamp(long epochSeconds) {
        return "<t:" + epochSeconds + ":R>";
    }

    /** Cuts text to a hard character budget, marking it when something is lost. */
    public static String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }

        if (text.length() <= maxLength) {
            return text;
        }

        return text.substring(0, Math.max(0, maxLength - 1)) + "…";
    }
}
