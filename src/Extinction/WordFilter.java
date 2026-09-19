// Bans a player for a word from BannedWords, in chat or in their name; the list is the policy.
package Extinction;

import Extinction.core.util.PluginLog;
import Extinction.moderation.ban.BanOrigin;
import Extinction.moderation.ban.BanRequest;
import Extinction.moderation.ban.BanScreen;
import Extinction.moderation.ban.WordFilterHit;

import arc.util.Strings;
import mindustry.Vars;
import mindustry.gen.Player;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public final class WordFilter {

    // Below this length letters must be adjacent: 'f?a?g' also spells "if a good game".
    private static final int SPACED_MATCH_MIN_LENGTH = 5;

    // What may sit between two letters. Not [^a-z0-9]: that would eat Cyrillic.
    private static final String SEPARATOR = "[^\\p{L}\\p{N}]{0,2}";

    // Nothing that reads as part of a word may touch a whole-word entry.
    private static final String WORD_START = "(?<![\\p{L}\\p{N}])";
    private static final String WORD_END = "(?![\\p{L}\\p{N}])";

    // One list entry and the pattern that finds it.
    private record Term(String word, Pattern pattern) {
    }

    private static final List<Term> BANNED = terms(BannedWords.WORDS, BannedWords.WHOLE_WORDS);
    private static final List<Term> NAMES = terms(BannedWords.NAMES, List.of());
    private static final List<Pattern> ALLOWED = allowed();

    // False on a duel worker, whose ban is forwarded to the hub.
    private final boolean hub;

    // Where the ban goes; the hit travels with it so the ban log can say what tripped.
    private final Consumer<BanRequest> banSeeder;

    // Which console log the hit was written to; the hub swaps a worker's label for its port.
    private final String server;

    // What the kicked player reads: the filter's reason plus how to appeal.
    private final BanScreen screen;

    private boolean installed;

    public WordFilter(boolean hub, Consumer<BanRequest> banSeeder, BanScreen screen) {
        this.hub = hub;
        this.banSeeder = banSeeder;
        this.screen = screen;
        this.server = hub ? BanOrigin.HUB : "this match server";
    }

    // Starts filtering chat. Must run before any other chat filter, or the
    // ranked spectator routing delivers the message this was to stop.
    public void install() {
        if (installed) {
            return;
        }

        if (Vars.netServer == null) {
            PluginLog.err("Word filter could not arm - no net server yet.");
            return;
        }

        installed = true;
        Vars.netServer.admins.addChatFilter(this::filterChat);

        PluginLog.info(
                "Word filter armed: @ word(s) banned anywhere plus @ banned in names only, automatic ban on chat and names.",
                wordCount(),
                nameWordCount()
        );
    }

    // Checks a joining player's name against the stricter list. True when they were banned.
    public boolean checkName(Player player) {
        if (player == null || !Config.wordFilter) {
            return false;
        }

        String word = findInName(player.name);

        if (word == null) {
            return false;
        }

        punish(player, word, WordFilterHit.Source.NAME, player.name);
        return true;
    }

    private String filterChat(Player player, String message) {
        if (player == null || message == null || !Config.wordFilter) {
            return message;
        }

        String word = find(message);

        if (word == null) {
            return message;
        }

        punish(player, word, WordFilterHit.Source.CHAT, message);
        return null;
    }

    private void punish(Player player, String word, WordFilterHit.Source source, String text) {
        // Logged before the ban, so the console line and the ban log's timestamp are one moment.
        PluginLog.info(
                "Word filter: @ (@) used '@' in their @ - @. Text: @",
                player.plainName(),
                player.uuid(),
                word,
                source.label(),
                hub ? "banning" : "banning here, ban forwarded to the hub",
                text
        );

        // Kicked before the ban is seeded, so they read the filter's reason, not the plain one.
        if (player.con != null && !player.con.kicked) {
            player.con.kick(screen.wordFilterMessage());
        }

        banSeeder.accept(BanRequest.wordFilter(
                player.uuid(),
                BanOrigin.now(BanOrigin.WORD_FILTER_ACTOR, server),
                WordFilterHit.of(source, word, text)
        ));
    }

    // How many words the filter is watching for everywhere.
    public static int wordCount() {
        return BANNED.size();
    }

    // How many words are banned in a player's name on top of those.
    public static int nameWordCount() {
        return NAMES.size();
    }

    // The list entry this text contains, as written in BannedWords. Null when clean.
    public static String find(String text) {
        return match(text, BANNED);
    }

    // The same for a name, held to the stricter list: every banned word plus NAMES.
    public static String findInName(String text) {
        String word = find(text);

        return word != null ? word : findNameOnly(text);
    }

    // Only NAMES: what a name may not carry but chat may. For 'wordfilter test'.
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

        // Allowances are cut out first, replaced by a space so neighbours never join.
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

    // Strips text to what it says: no colour tags, accents, invisible or lookalike letters.
    static String fold(String text) {
        String decomposed = Normalizer.normalize(Strings.stripColors(text), Normalizer.Form.NFKD);
        StringBuilder folded = new StringBuilder(decomposed.length());

        for (int index = 0; index < decomposed.length(); index++) {
            char character = Character.toLowerCase(decomposed.charAt(index));
            int type = Character.getType(character);

            // The accent the decomposition left behind, and zero-width joiners pasted into names.
            if (type == Character.NON_SPACING_MARK || type == Character.FORMAT) {
                continue;
            }

            folded.append(lookalike(character));
        }

        return folded.toString();
    }

    // Maps a character onto the Latin letter it is being used as.
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

    private static List<Term> terms(List<String> anywhere, List<String> wholeWords) {
        List<Term> terms = new ArrayList<>();

        add(terms, anywhere, false);
        add(terms, wholeWords, true);

        return List.copyOf(terms);
    }

    private static void add(List<Term> terms, List<String> words, boolean wholeWord) {
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
                patterns.add(compile(Pattern.quote(letters)));
            }
        }

        return List.copyOf(patterns);
    }

    // The letters of one entry, folded, spaces and punctuation dropped; empty for a letterless entry.
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

    // Every letter repeatable, separators once the entry is long enough, boundaries for whole words.
    // An allowance is instead the letters exactly and once: a widened allowance is a way through.
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

    private static Pattern compile(String expression) {
        return Pattern.compile(expression, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }
}
