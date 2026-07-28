package vini.evictmap.moderation;

import java.util.List;

/**
 * The word list. Edit this file and rebuild - nothing else needs touching.
 *
 * <p>Write a word plainly and lowercase. Case, accents, other scripts, leetspeak
 * ({@code n1gg3r}, {@code f@ggot}), repeated letters ({@code niiigger}), colour
 * tags and punctuation wedged between the letters are all handled already, so
 * one entry covers every spelling of it. Entries under five letters are matched
 * with their letters adjacent only - allowing spacing on a short one bans
 * ordinary sentences.
 *
 * <p>Try anything new with {@code evictwordfilter test <text>} first. This bans
 * by itself.
 */
public final class BannedWords {

    /** Banned anywhere, including inside a longer word. */
    public static final List<String> WORDS = List.of(
            "nigger",
            "nigga",
            "niga",
            "niggre",
            "ni88er",
            "негр",
            "ниггер",
            "нигер",
            "нігер",
            "ніггер",
            "faggot",
            "hitler",
            "nazis",
            "sieg heil",
            "sieg hail",
            "kill blacks"
    );

    /**
     * Banned only as a word of its own. For an entry that is a normal part of
     * longer, harmless words: {@code negr} on its own is a slur, but as a
     * substring it is Montenegro, negro and negru; {@code niger} as a substring
     * is Nigeria; {@code nazi} as a substring is nazionale, nazik and the names
     * Nazir, Nazim and Nazia. The plural and nazism stay in {@link #WORDS},
     * where {@code nazis} covers them.
     */
    public static final List<String> WHOLE_WORDS = List.of(
            "niger",
            "negr",
            "nazi"
    );

    /**
     * Harmless words that contain a banned one. Each is cut out of the text
     * before the ban list is checked, and only where it is written normally.
     *
     * <p>Never put a slur here, or anything that contains one and nothing else:
     * allowing {@code nigger} would allow {@code xxniggerxx} with it.
     */
    public static final List<String> ALLOWED = List.of(
            "негромк",
            "негруб"
    );

    private BannedWords() {
    }
}
