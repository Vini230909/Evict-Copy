package vini.evictmap.moderation;

import arc.util.Strings;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Decides whether a piece of text contains one of {@link BannedWords#WORDS}.
 * Pure and static: no Mindustry state, no side effects, so it can be reasoned
 * about (and tried out with {@code evictwordfilter test}) on its own.
 *
 * <p>A plain {@code contains} would be useless here - nobody types the word
 * straight out once they know it is filtered. Text is therefore folded first:
 * colour tags removed, accents and compatibility forms decomposed away,
 * invisible characters dropped, letters from other scripts that are drawn like
 * Latin ones mapped onto their Latin twin, digits and symbols used as letters
 * ({@code 1}, {@code 3}, {@code @}, {@code $}) mapped back, everything
 * lowercased. Both the text and the word list go through the same fold, so one
 * plain entry covers every dressed-up spelling of it.
 *
 * <p>What is left is matched with a pattern built per word rather than a
 * substring search, because the two remaining tricks - repeating a letter and
 * wedging punctuation between them - are cheap to express and expensive to
 * enumerate. Each letter becomes {@code x+}, so repeats collapse while the
 * number of <em>distinct</em> letters still has to be right: {@code niiigger}
 * matches, and the country Niger, with its one {@code g}, does not.
 *
 * <p>{@link BannedWords#WHOLE_WORDS} entries additionally have to stand as a
 * word of their own. An entry that is a normal part of longer harmless words -
 * {@code negr} inside Montenegro and negro, {@code niger} inside Nigeria - is
 * useless as a substring and fine as a word, which is what the boundaries are
 * for.
 *
 * <p>Separators between letters are only allowed for words of
 * {@value #SPACED_MATCH_MIN_LENGTH} letters or more. On a short word that
 * allowance is a false-positive machine - {@code f?a?g} also spells the middle
 * of "if a good game" - and a false positive here is an automatic ban.
 * Separators are non-letters only, so a run of letters in another script can
 * never be swallowed as if it were punctuation.
 */
public final class WordMatcher {

    /**
     * Shortest word allowed to match across separators. Below this a word is
     * only matched with its letters adjacent.
     */
    private static final int SPACED_MATCH_MIN_LENGTH = 5;

    /**
     * What may sit between two letters of a spaced-out word: up to two
     * characters that are neither letters nor digits. Deliberately not
     * {@code [^a-z0-9]} - that would let the gaps eat Cyrillic or Greek
     * letters, and a five-letter Russian slur would start matching any Russian
     * sentence with those letters somewhere in it.
     */
    private static final String SEPARATOR = "[^\\p{L}\\p{N}]{0,2}";

    /** Nothing that reads as part of a word may touch a whole-word entry. */
    private static final String WORD_START = "(?<![\\p{L}\\p{N}])";
    private static final String WORD_END = "(?![\\p{L}\\p{N}])";

    /** One list entry and the pattern that finds it. */
    private record Term(String word, Pattern pattern) {
    }

    private static final List<Term> BANNED = banned();
    private static final List<Pattern> ALLOWED = allowed();

    private WordMatcher() {
    }

    /** How many words the filter is watching for. */
    public static int wordCount() {
        return BANNED.size();
    }

    /**
     * The banned word this text contains, or null if it is clean.
     *
     * @return the list entry as it is written in {@link BannedWords}, which is
     *         what gets logged - never the player's own spelling
     */
    public static String find(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        String folded = fold(text);

        if (folded.isBlank()) {
            return null;
        }

        // Harmless words that contain a banned one are cut out first, so the
        // Russian for "quietly" no longer carries the slur inside it into the
        // scan below. Replaced by a space rather than nothing: removing it
        // outright could join its two neighbours into a word neither of them is.
        for (Pattern allowed : ALLOWED) {
            folded = allowed.matcher(folded).replaceAll(" ");
        }

        for (Term term : BANNED) {
            if (term.pattern().matcher(folded).find()) {
                return term.word();
            }
        }

        return null;
    }

    /**
     * Strips a piece of text down to what it actually says: no colour tags, no
     * accents, no invisible characters, no lookalike letters, lower case.
     */
    static String fold(String text) {
        String decomposed = Normalizer.normalize(
                Strings.stripColors(text),
                Normalizer.Form.NFKD
        );

        StringBuilder folded = new StringBuilder(decomposed.length());

        for (int index = 0; index < decomposed.length(); index++) {
            char character = Character.toLowerCase(decomposed.charAt(index));
            int type = Character.getType(character);

            // The accent left behind by the decomposition, and the zero-width
            // joiners and direction marks people paste into names to break
            // string comparisons. Neither is part of what the text says.
            if (type == Character.NON_SPACING_MARK || type == Character.FORMAT) {
                continue;
            }

            folded.append(lookalike(character));
        }

        return folded.toString();
    }

    /**
     * Maps a character onto the Latin letter it is being used as. Covers the
     * digits and symbols of leetspeak and the Cyrillic and Greek letters that
     * are drawn identically to Latin ones - both are a copy-paste away and both
     * defeat a plain comparison.
     */
    private static char lookalike(char character) {
        return switch (character) {
            // Leetspeak.
            case '0' -> 'o';
            case '1' -> 'i';
            case '2' -> 'z';
            case '3' -> 'e';
            case '4' -> 'a';
            case '5' -> 's';
            case '6' -> 'g';
            case '7' -> 't';
            case '8' -> 'b';
            case '9' -> 'g';
            case '@' -> 'a';
            case '$' -> 's';
            case '!' -> 'i';
            case '|' -> 'i';
            case '+' -> 't';

            // Cyrillic letters drawn like Latin ones.
            case 'а' -> 'a';
            case 'в' -> 'b';
            case 'с' -> 'c';
            case 'е' -> 'e';
            case 'н' -> 'h';
            case 'і' -> 'i';
            case 'ј' -> 'j';
            case 'к' -> 'k';
            case 'м' -> 'm';
            case 'о' -> 'o';
            case 'р' -> 'p';
            case 'ѕ' -> 's';
            case 'т' -> 't';
            case 'у' -> 'y';
            case 'х' -> 'x';

            // Greek letters drawn like Latin ones.
            case 'α' -> 'a';
            case 'β' -> 'b';
            case 'ε' -> 'e';
            case 'ι' -> 'i';
            case 'κ' -> 'k';
            case 'ο' -> 'o';
            case 'ρ' -> 'p';
            case 'τ' -> 't';
            case 'υ' -> 'y';
            case 'χ' -> 'x';

            default -> character;
        };
    }

    private static List<Term> banned() {
        List<Term> terms = new ArrayList<>();

        add(terms, BannedWords.WORDS, false);
        add(terms, BannedWords.WHOLE_WORDS, true);

        return List.copyOf(terms);
    }

    private static void add(
            List<Term> terms,
            List<String> words,
            boolean wholeWord
    ) {
        for (String word : words) {
            String letters = letters(word);

            if (!letters.isEmpty()) {
                terms.add(new Term(word, bannedPattern(letters, wholeWord)));
            }
        }
    }

    private static List<Pattern> allowed() {
        List<Pattern> patterns = new ArrayList<>(BannedWords.ALLOWED.size());

        for (String word : BannedWords.ALLOWED) {
            String letters = letters(word);

            if (!letters.isEmpty()) {
                patterns.add(allowedPattern(letters));
            }
        }

        return List.copyOf(patterns);
    }

    /**
     * The letters of one list entry, folded. Spaces and punctuation in the
     * entry are dropped: a multi-word entry is matched as one run of letters,
     * with the gap between its words handled by the separator allowance like
     * any other gap. Empty for an entry with no letters at all, which would
     * otherwise compile to a pattern matching everything.
     */
    private static String letters(String word) {
        String folded = fold(word);
        StringBuilder letters = new StringBuilder(folded.length());

        for (int index = 0; index < folded.length(); index++) {
            char character = folded.charAt(index);

            if (Character.isLetterOrDigit(character)) {
                letters.append(character);
            }
        }

        return letters.toString();
    }

    /**
     * Pattern for a banned entry: every letter repeatable, separators allowed
     * between them once the entry is long enough to survive it, and - for a
     * whole-word entry - nothing that reads as part of a word on either side.
     */
    private static Pattern bannedPattern(String letters, boolean wholeWord) {
        boolean spaced = letters.length() >= SPACED_MATCH_MIN_LENGTH;
        StringBuilder expression = new StringBuilder();

        if (wholeWord) {
            expression.append(WORD_START);
        }

        for (int index = 0; index < letters.length(); index++) {
            if (spaced && index > 0) {
                expression.append(SEPARATOR);
            }

            // Every letter is folded and therefore a letter or a digit, so it
            // needs no escaping; the trailing + is what absorbs "niiigger".
            expression.append(letters.charAt(index)).append('+');
        }

        if (wholeWord) {
            expression.append(WORD_END);
        }

        return compile(expression.toString());
    }

    /**
     * Pattern for an allowed entry: the letters exactly, adjacent, once each.
     *
     * <p>Deliberately stricter than a banned entry, because an allowed entry is
     * a hole in the filter and every allowance widened is a way through it.
     * Repeatable letters would make "niggeria" match the allowance for
     * "nigeria" and take the slur inside it out of the text with it; separators
     * would let a slur be excused by typing four unrelated characters after it.
     * A word is only harmless when it is written as that word.
     */
    private static Pattern allowedPattern(String letters) {
        return compile(Pattern.quote(letters));
    }

    private static Pattern compile(String expression) {
        return Pattern.compile(
                expression,
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
        );
    }
}
