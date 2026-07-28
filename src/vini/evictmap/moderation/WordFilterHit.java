package vini.evictmap.moderation;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * What the word filter saw when it banned somebody: which entry tripped, where
 * it stood, the offending text itself and when the server console wrote the
 * line about it.
 *
 * <p>The timestamp is deliberately the console's own format
 * ({@code MM-dd-yyyy HH:mm:ss}, the server's local time), not a Discord
 * timestamp: the point of putting it in the ban log is to be able to open the
 * matching log file afterwards and find the incident by searching for that
 * time. {@code origin} says which log - the hub's, or one match server's.
 *
 * <p>Plain data with no Mindustry types: it travels from a worker's status file
 * to the hub and from the hub to a Discord thread.
 */
public record WordFilterHit(
        Source source,
        String word,
        String text,
        String consoleTime,
        String origin
) {

    /** Where the banned word stood. */
    public enum Source {

        /** In a chat message; {@code text} is the whole message. */
        CHAT,

        /** In the player's name; {@code text} is the name. */
        NAME;

        public String label() {
            return this == CHAT ? "chat message" : "player name";
        }

        static Source parse(String value) {
            return "name".equalsIgnoreCase(value) ? NAME : CHAT;
        }

        public String key() {
            return this == CHAT ? "chat" : "name";
        }
    }

    /** The console's timestamp format, so a log search on it finds the line. */
    private static final DateTimeFormatter CONSOLE_TIME =
            DateTimeFormatter.ofPattern("MM-dd-yyyy HH:mm:ss");

    /**
     * Chat is capped well below this; the cap only stops a pathological name or
     * a hand-edited status file from bloating the log entry.
     */
    private static final int MAX_TEXT = 400;

    /** Stamped the moment the filter hits, on the server that saw it. */
    public static WordFilterHit now(
            Source source,
            String word,
            String text,
            String origin
    ) {
        return new WordFilterHit(
                source,
                word == null ? "" : word,
                trim(text),
                CONSOLE_TIME.format(LocalDateTime.now()),
                origin
        );
    }

    /** Rebuilds a hit a match server published, tagged with its own log. */
    public static WordFilterHit fromWorker(
            String source,
            String word,
            String text,
            String consoleTime,
            String origin
    ) {
        if (word == null || word.isBlank()) {
            return null;
        }

        return new WordFilterHit(
                Source.parse(source),
                word,
                trim(text),
                consoleTime == null || consoleTime.isBlank() ? "unknown" : consoleTime,
                origin
        );
    }

    private static String trim(String text) {
        if (text == null) {
            return "";
        }

        String cleaned = text.strip();

        return cleaned.length() <= MAX_TEXT
                ? cleaned
                : cleaned.substring(0, MAX_TEXT) + "…";
    }
}
