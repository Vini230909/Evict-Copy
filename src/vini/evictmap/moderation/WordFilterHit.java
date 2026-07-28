package vini.evictmap.moderation;

/**
 * What the word filter saw: which entry tripped, where it stood and the
 * offending text itself. When and on which server it happened is carried
 * separately by {@link BanOrigin}, which every ban has.
 *
 * <p>Plain data with no Mindustry types: it travels from a worker's status file
 * to the hub and from the hub to Discord.
 */
public record WordFilterHit(Source source, String word, String text) {

    /** Where the banned word stood. */
    public enum Source {

        /** In a chat message; {@code text} is the whole message. */
        CHAT,

        /** In the player's name; {@code text} is the name. */
        NAME;

        public String label() {
            return this == CHAT ? "chat message" : "player name";
        }

        public String key() {
            return this == CHAT ? "chat" : "name";
        }

        static Source parse(String value) {
            return "name".equalsIgnoreCase(value) ? NAME : CHAT;
        }
    }

    /**
     * Chat is capped well below this; the cap only stops a pathological name or
     * a hand-edited status file from bloating the log entry.
     */
    private static final int MAX_TEXT = 400;

    public static WordFilterHit of(Source source, String word, String text) {
        return new WordFilterHit(source, word == null ? "" : word, trim(text));
    }

    /** Rebuilds a hit a match server published. Null when it published none. */
    public static WordFilterHit fromWorker(String source, String word, String text) {
        if (word == null || word.isBlank()) {
            return null;
        }

        return new WordFilterHit(Source.parse(source), word, trim(text));
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
