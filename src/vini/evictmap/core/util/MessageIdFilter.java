package vini.evictmap.core.util;

import arc.util.Log;

/**
 * Cuts the two-character message id some modified clients append to every chat
 * message out of the console line - and only out of the console line.
 *
 * <p>Mindustry rejects a message identical to the one before it, so those
 * clients tag each message with an id built by their {@code InvisibleCharCoder}
 * from the range {@code U+0F80..U+107F}: {@code hello} arrives as
 * {@code helloၬ࿻}. Unreadable in the console, and it makes an otherwise
 * repeated line look different every time.
 *
 * <p>The message itself is left exactly as it was sent. The clients use the id
 * among themselves, so stripping it from the network message would break them;
 * this only makes the server's own log readable.
 *
 * <h2>Why the formatter and not the log handler</h2>
 *
 * Mindustry logs chat from {@code NetClient.sendChatMessage} with the message
 * as a format argument, twice and with different templates:
 *
 * <pre>
 * Log.info("&lt;&amp;fi@: @&amp;fr&gt;", "&amp;lk" + plainName, "&amp;lw" + message); // commands
 * Log.info("&amp;fi@: @",        "&amp;lc" + plainName, "&amp;lw" + message); // chat
 * </pre>
 *
 * By the time a {@link Log.LogHandler} sees the line the id is no longer at the
 * end of it - the command template closes with {@code &fr>}, and the server's
 * own handler appends {@code &fr} on top. Wrapping the {@link Log.LogFormatter}
 * instead hands us the arguments before any of that, where the message really
 * does end with its id, so "the last two characters" means what it says.
 *
 * <p>Every logged string argument is checked, not just chat: the test is narrow
 * enough (two characters, both inside one 256-codepoint range) that ordinary
 * log output cannot trip it, and player names carry the same tag.
 */
public final class MessageIdFilter {

    /** The range {@code InvisibleCharCoder} draws the id from, inclusive. */
    private static final int FIRST_CHARACTER = 0x0F80;
    private static final int LAST_CHARACTER = 0x107F;

    /** How many characters one id is. */
    private static final int ID_LENGTH = 2;

    private static boolean installed;

    private MessageIdFilter() {
    }

    /**
     * Wraps the current log formatter. Call once, from the plugin's init: the
     * dedicated server installs its own formatter in the {@code ServerControl}
     * constructor, which runs before mods are initialised, so the formatter
     * captured here is the real one and keeps doing the actual formatting.
     */
    public static void install() {
        if (installed) {
            return;
        }

        installed = true;

        Log.LogFormatter previous = Log.formatter;

        Log.formatter = (text, useColors, args) -> previous.format(
                text,
                useColors,
                stripArguments(args)
        );
    }

    /**
     * The message without its trailing id, or the message unchanged when it
     * does not carry one. Matching characters anywhere else are left alone -
     * only the last two are looked at, so a Tibetan or Burmese sentence keeps
     * every character it had.
     */
    public static String strip(String text) {
        if (text == null || text.length() < ID_LENGTH) {
            return text;
        }

        for (int index = text.length() - ID_LENGTH; index < text.length(); index++) {
            if (!isIdCharacter(text.charAt(index))) {
                return text;
            }
        }

        return text.substring(0, text.length() - ID_LENGTH);
    }

    private static boolean isIdCharacter(char character) {
        return character >= FIRST_CHARACTER && character <= LAST_CHARACTER;
    }

    /**
     * The log arguments with every string stripped. The array is only copied
     * once something actually changed - the arguments belong to the caller, and
     * {@link Log} hands the same shared empty array to every argument-less call.
     */
    private static Object[] stripArguments(Object[] args) {
        Object[] result = args;

        for (int index = 0; index < args.length; index++) {
            if (!(args[index] instanceof String value)) {
                continue;
            }

            String stripped = strip(value);

            if (!stripped.equals(value)) {
                if (result == args) {
                    result = args.clone();
                }

                result[index] = stripped;
            }
        }

        return result;
    }
}
