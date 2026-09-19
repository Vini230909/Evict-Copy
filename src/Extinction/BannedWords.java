// The word list: edit, rebuild, nothing else. Try a new entry with 'wordfilter test' first.
package Extinction;

import java.util.List;

public final class BannedWords {

    // Write entries plainly and lowercase; case, accents, leetspeak, other
    // scripts, repeated letters and colour tags are folded away before matching.

    // Banned anywhere, including inside a longer word.
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

    // Banned only as a word of its own: 'negr' is Montenegro, 'nazi' is nazionale and Nazir.
    public static final List<String> WHOLE_WORDS = List.of(
            "negr"
    );

    // Banned in a player's name only; the same text in chat passes.
    // WORDS and WHOLE_WORDS ban in names too, this list only adds to them.
    public static final List<String> NAMES = List.of(
            "hitler",
            "stalin"
    );

    // Harmless words containing a banned one, cut out before the scan.
    // Never a slur and never one plus nothing: allowing 'nigger' allows 'xxniggerxx'.
    public static final List<String> ALLOWED = List.of(
            "негромк",
            "негруб"
    );

    private BannedWords() {
    }
}
