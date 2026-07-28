package vini.evictmap.moderation;

import mindustry.Vars;
import mindustry.gen.Player;
import vini.evictmap.core.util.PluginLog;
import vini.evictmap.gen.EvictSettings;

import java.util.function.BiConsumer;

/**
 * Bans a player for a word from {@link BannedWords}, in chat or in their name.
 * The list is the policy; this is only the enforcement.
 *
 * <p>The hub bans through {@link BanManager}; a match server may not decide
 * bans, so it kicks and asks the hub instead.
 */
public final class WordFilter {

    private final EvictSettings settings;

    /** False on a duel worker, which asks the hub instead of banning. */
    private final boolean hub;

    /**
     * Hub: {@code banPlayerID}. Worker: a request for the hub to act on. The
     * hit travels with the account so the ban log can say what tripped, and
     * where in the console log to find it.
     */
    private final BiConsumer<String, WordFilterHit> banSeeder;

    /**
     * Which console log the hit was written to. A worker's is replaced by the
     * hub with the match server's port once the request arrives there.
     */
    private final String origin;

    private boolean installed;

    public WordFilter(
            EvictSettings settings,
            boolean hub,
            BiConsumer<String, WordFilterHit> banSeeder
    ) {
        this.settings = settings;
        this.hub = hub;
        this.banSeeder = banSeeder;
        this.origin = hub ? "hub console" : "match server";
    }

    /**
     * Starts filtering chat. Must run before any other chat filter, or the
     * ranked spectator routing delivers the message this was to stop.
     */
    public void install() {
        if (installed) {
            return;
        }

        if (Vars.netServer == null) {
            // Loud: a moderation feature that silently did not arm is worse
            // than one switched off on purpose.
            PluginLog.err("Word filter could not arm - no net server yet.");
            return;
        }

        installed = true;
        Vars.netServer.admins.addChatFilter(this::filterChat);

        PluginLog.info(
                "Word filter armed: @ word(s), automatic ban on chat and names.",
                WordMatcher.wordCount()
        );
    }

    /**
     * Checks a joining player's name.
     *
     * @return true when they were banned, so the caller can skip the join
     */
    public boolean checkName(Player player) {
        if (player == null || !settings.wordFilterEnabled()) {
            return false;
        }

        String word = WordMatcher.find(player.name);

        if (word == null) {
            return false;
        }

        punish(player, word, WordFilterHit.Source.NAME, player.name);
        return true;
    }

    private String filterChat(Player player, String message) {
        if (player == null || message == null || !settings.wordFilterEnabled()) {
            return message;
        }

        String word = WordMatcher.find(message);

        if (word == null) {
            return message;
        }

        punish(player, word, WordFilterHit.Source.CHAT, message);
        return null;
    }

    private void punish(
            Player player,
            String word,
            WordFilterHit.Source source,
            String text
    ) {
        // Logged before the ban, so the console line and the timestamp the ban
        // log carries are the same moment.
        PluginLog.info(
                "Word filter: @ (@) used '@' in their @ - @. Text: @",
                player.plainName(),
                player.uuid(),
                word,
                source.label(),
                hub ? "banning" : "kicked, ban requested from the hub",
                text
        );

        banSeeder.accept(
                player.uuid(),
                WordFilterHit.now(source, word, text, origin)
        );

        // The hub's ban already kicked them; a worker's request only lands on
        // the next poll, so it kicks here rather than wait.
        if (player.con != null && !player.con.kicked) {
            player.con.kick(
                    "You were banned for using a word that is not allowed on this server."
            );
        }
    }

    /** For {@code evictwordfilter test}. Null when the text is clean. */
    public static String test(String text) {
        return WordMatcher.find(text);
    }
}
