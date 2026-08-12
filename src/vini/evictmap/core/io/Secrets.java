package vini.evictmap.core.io;

import java.io.File;

/**
 * The server's one secrets file, {@code config/evict.env}, and the names of
 * the keys in it.
 *
 * <p>One place for every credential the plugin needs, so the next one is a
 * new key rather than a new file, a new command or a new properties entry.
 * Read-only from the plugin's side (see {@link EnvFile}) and hub-only in
 * practice: nothing copies it into a worker folder.
 */
public final class Secrets {

    /**
     * Bot token for Discord. Named for the chat mirror, which was the first
     * feature to need it; the {@code /ban} and {@code /unban} slash commands
     * use the same bot, because one bot with one token is one credential to
     * rotate rather than two.
     */
    public static final String DISCORD_CHAT_BOT_TOKEN =
            "DISCORD_CHAT_BOT_TOKEN";

    private static final String TEMPLATE = """
            # Evict server secrets. This file is only ever read by the plugin,
            # never written to - so nothing here ends up in the server log or
            # in a duel-worker folder. Keep it out of backups you share.
            #
            # Format: KEY=value, one per line. Paste the value after the "=".
            # Lines starting with # are ignored, an empty value counts as unset.
            # Reload without a restart: 'evictchatlog reload'.

            # Discord bot: mirrors chat into the staff channels, and answers
            # the /ban and /unban slash commands ('evictdiscordcmd').
            # Discord Developer Portal > your app > Bot > Reset Token.
            # Invite the bot with both the 'bot' and 'applications.commands'
            # scopes, or the slash commands cannot be registered.
            DISCORD_CHAT_BOT_TOKEN=
            """;

    private static final EnvFile FILE =
            new EnvFile(new File("config/evict.env"), TEMPLATE);

    private Secrets() {
    }

    /** The shared file, for reloads and key listings. */
    public static EnvFile file() {
        return FILE;
    }

    public static String path() {
        return FILE.path();
    }

    /** Re-reads the file; returns how many keys it holds. */
    public static int reload() {
        return FILE.reload();
    }

    public static String get(String key) {
        return FILE.get(key);
    }
}
