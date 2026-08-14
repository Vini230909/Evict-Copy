package vini.evictmap.moderation;

import java.util.List;

/**
 * The word list. Edit this file and rebuild - nothing else needs touching.
 *
 * <p>Write a word plainly and lowercase. Case, accents, other scripts,
 * leetspeak, repeated letters, colour tags and punctuation between the letters
 * are handled already, so one entry covers every spelling of it. Try anything
 * new with {@code evictwordfilter test <text>} first - this bans by itself.
 */
public final class BannedWords {

    /** Banned anywhere, including inside a longer word. */
    public static final List<String> WORDS = List.of(
            "nigge",
            "nigga",
            "niga",
            "niggre",
            "ni88er",
            "niggu",
            "негр",
            "ниггер",
            "нигер",
            "нігер",
            "ніггер",
            "faggot",
            "sieg heil",
            "sieg hail",
            "kill blacks",
            "chink"
    );

    /**
     * Banned only as a word of its own, for entries that are a normal part of
     * harmless longer words: {@code negr} is Montenegro and negro, {@code niger}
     * is Nigeria, {@code nazi} is nazionale and the name Nazir.
     */
    public static final List<String> WHOLE_WORDS = List.of(
            "negr"
    );

    /**
     * Banned in a player's name only - a name carrying one of these is an
     * automatic ban on join, while the same text in chat passes. For names
     * nobody has a reason to wear: impersonation, advertising, a slur that is
     * fine to talk *about* but not to be called.
     *
     * <p>{@link #WORDS} and {@link #WHOLE_WORDS} still ban in names as well;
     * this list only adds to them. Matched anywhere in the name, like
     * {@link #WORDS}, and {@link #ALLOWED} cuts out of it the same way.
     */
    public static final List<String> NAMES = List.of(
            "hitler",
            "stalin"
    );

    /**
     * Harmless words that contain a banned one, cut out before the scan. Here:
     * the Russian for "quietly" and "not rude", both containing {@code негр}.
     *
     * <p>Never a slur, and never anything that contains one and nothing else -
     * allowing {@code nigger} would allow {@code xxniggerxx} with it.
     */
    public static final List<String> ALLOWED = List.of(
            "негромк",
            "негруб"
    );

    private BannedWords() {
    }
}
