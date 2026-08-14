package vini.evictmap.moderation;

import arc.util.Strings;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Decides whether text contains a word from {@link BannedWords}. Pure and
 * static, so it can be tried out on its own with {@code evictwordfilter test}.
 *
 * <p>Nobody types the word straight out once they know it is filtered, so a
 * plain {@code contains} is useless. Text and word list go through the same
 * fold (see {@link #fold}), then each entry is matched by a pattern in which
 * every letter repeats: {@code niiigger} matches, the country Niger with its
 * one {@code g} does not.
 */
public final class WordMatcher {

    /**
     * Shortest entry allowed to match across separators. Below this the letters
     * must be adjacent: {@code f?a?g} also spells the middle of "if a good
     * game", and a false positive here is an automatic ban.
     */
    private static final int SPACED_MATCH_MIN_LENGTH = 5;

    /**
     * What may sit between two letters. Not {@code [^a-z0-9]}: the gaps would
     * eat Cyrillic, and a Russian entry would match any Russian sentence.
     */
    private static final String SEPARATOR = "[^\\p{L}\\p{N}]{0,2}";

    /** Nothing that reads as part of a word may touch a whole-word entry. */
    private static final String WORD_START = "(?<![\\p{L}\\p{N}])";
    private static final String WORD_END = "(?![\\p{L}\\p{N}])";

    /** One list entry and the pattern that finds it. */
    private record Term(String word, Pattern pattern) {
    }

    private static final List<Term> BANNED = banned();
    private static final List<Term> NAMES = names();
    private static final List<Pattern> ALLOWED = allowed();

    private WordMatcher() {
    }

    /** How many words the filter is watching for everywhere. */
    public static int wordCount() {
        return BANNED.size();
    }

    /** How many words are banned in a player's name on top of those. */
    public static int nameWordCount() {
        return NAMES.size();
    }

    /**
     * @return the list entry this text contains, as written in
     *         {@link BannedWords} - never the player's own spelling. Null when
     *         the text is clean.
     */
    public static String find(String text) {
        return match(text, BANNED);
    }

    /**
     * The same for a player's name, which is held to the stricter list: every
     * banned word plus {@link BannedWords#NAMES}.
     */
    public static String findInName(String text) {
        String word = find(text);

        return word != null ? word : findNameOnly(text);
    }

    /**
     * Only {@link BannedWords#NAMES} - what a name may not carry but chat may.
     * For {@code evictwordfilter test}, so it can say which list decided.
     */
    public static String findNameOnly(String text) {
        return match(text, NAMES);
    }

    private static String match(String text, List<Term> terms) {
        if (text == null || text.isBlank() || terms.isEmpty()) {
            return null;
        }

        String folded = fold(text);

        if (folded.isBlank()) {
            return null;
        }

        // Allowances are cut out first. Replaced by a space, not removed:
        // removing could join the two neighbours into a word neither of them is.
        for (Pattern allowed : ALLOWED) {
            folded = allowed.matcher(folded).replaceAll(" ");
        }

        for (Term term : terms) {
            if (term.pattern().matcher(folded).find()) {
                return term.word();
            }
        }

        return null;
    }

    /**
     * Strips text down to what it says: no colour tags, accents, invisible
     * characters or lookalike letters, lower case.
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

            // The accent the decomposition left behind, and the zero-width
            // joiners pasted into names to break string comparisons.
            if (type == Character.NON_SPACING_MARK || type == Character.FORMAT) {
                continue;
            }

            folded.append(lookalike(character));
        }

        return folded.toString();
    }

    /** Maps a character onto the Latin letter it is being used as. */
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

    /** The name-only entries, matched anywhere in the name like {@code WORDS}. */
    private static List<Term> names() {
        List<Term> terms = new ArrayList<>();

        add(terms, BannedWords.NAMES, false);

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
     * The letters of one list entry, folded; spaces and punctuation dropped, so
     * a multi-word entry is matched as one run of letters. Empty for an entry
     * without letters, which would compile to a pattern matching everything.
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
     * Pattern for a banned entry: every letter repeatable, separators once the
     * entry is long enough to survive them, boundaries for a whole-word entry.
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

            // Folded, so always a letter or digit and never needs escaping.
            expression.append(letters.charAt(index)).append('+');
        }

        if (wholeWord) {
            expression.append(WORD_END);
        }

        return compile(expression.toString());
    }

    /**
     * Pattern for an allowed entry: the letters exactly, adjacent, once each.
     * Stricter than a banned entry on purpose - an allowance is a hole in the
     * filter, and a widened one is a way through it. Repeatable letters would
     * let "niggeria" match the allowance for "nigeria" and carry the slur out
     * of the text with it.
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
