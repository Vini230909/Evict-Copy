package vini.evictmap.discord;

import java.util.ArrayList;
import java.util.List;

/**
 * A minimal JSON writer, just enough for a Discord webhook payload.
 *
 * <p>The plugin has no JSON serialiser on its classpath (Arc's
 * {@code Jval} reads JSON, and pulling a whole library in for two object
 * shapes is not worth the jar size), so the payload is built by hand. Only
 * {@link #string} really matters: every value that reaches Discord passes
 * through it, so a quote or backslash in a player name can never break out of
 * its string and change the shape of the request.
 */
final class DiscordJson {

    private DiscordJson() {
    }

    /** A JSON string literal, quoted and escaped. */
    static String string(String raw) {
        if (raw == null) {
            return "null";
        }

        StringBuilder json = new StringBuilder(raw.length() + 16);
        json.append('"');

        for (int index = 0; index < raw.length(); index++) {
            char character = raw.charAt(index);

            switch (character) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                default -> {
                    if (character < 0x20) {
                        json.append(String.format("\\u%04x", (int) character));
                    } else {
                        json.append(character);
                    }
                }
            }
        }

        return json.append('"').toString();
    }

    /** A JSON object built field by field, in insertion order. */
    static final class Obj {

        private final List<String> fields = new ArrayList<>();

        /** Adds a field whose value is already JSON (object, array, number). */
        Obj raw(String key, String jsonValue) {
            fields.add(string(key) + ":" + jsonValue);
            return this;
        }

        Obj str(String key, String value) {
            return raw(key, string(value));
        }

        Obj num(String key, long value) {
            return raw(key, Long.toString(value));
        }

        @Override
        public String toString() {
            return "{" + String.join(",", fields) + "}";
        }
    }

    /** A JSON array of already-serialised values. */
    static final class Arr {

        private final List<String> values = new ArrayList<>();

        Arr add(Object jsonValue) {
            values.add(jsonValue.toString());
            return this;
        }

        boolean isEmpty() {
            return values.isEmpty();
        }

        @Override
        public String toString() {
            return "[" + String.join(",", values) + "]";
        }
    }
}
