package vini.evictmap.commands;

import arc.Core;
import arc.util.CommandHandler;
import mindustry.Vars;
import mindustry.gen.Player;
import vini.evictmap.core.text.Text;
import vini.evictmap.core.util.PluginLog;

/**
 * {@code /js} - the server console's {@code js} command, in chat.
 *
 * <p>One to one with the console: the script goes to the same
 * {@code Vars.mods.getScripts().runConsole(...)} the console calls, so anything
 * that works there works here. Only the output differs - the console prints the
 * return value to the log, this sends it back to the admin who typed it and to
 * nobody else.
 *
 * <p>Admin-only, and gated at execution like every other permission-gated
 * command. It is arbitrary code execution, so the script itself is written to
 * the console log with the name that ran it before it runs; the mirror picks up
 * the typed command as well.
 */
public final class JsCommands {

    /**
     * Cap on the returned text. A chat message is one packet and a script can
     * return a whole map's worth of text; the rest is cut with a marker rather
     * than risking the connection.
     */
    private static final int MAX_RESULT_LENGTH = 700;

    public void registerClientCommands(CommandHandler handler) {
        handler.<Player>register(
                "js",
                "<script...>",
                "Admin only: run a Javascript snippet, like the console's js.",
                this::handleJs
        );
    }

    private void handleJs(String[] args, Player player) {
        if (player == null) {
            return;
        }

        if (!player.admin) {
            player.sendMessage("[scarlet]Only admins can run scripts.[]");
            return;
        }

        String script = args.length == 0 ? "" : args[0].trim();

        if (script.isEmpty()) {
            Text.of().lightGray("Usage: ").white("/js <script>").sendTo(player);
            return;
        }

        PluginLog.info("@ ran /js: @", player.plainName(), script);

        /*
         * A script can touch anything - world tiles, teams, units - so it must
         * not run on whatever thread delivered the chat packet. Posting costs
         * one frame and puts it on the same main thread the console's js runs
         * on.
         */
        Core.app.post(() -> run(player, script));
    }

    private void run(Player player, String script) {
        String result;

        try {
            // runConsole never throws: a script error comes back as its text.
            result = Vars.mods.getScripts().runConsole(script);
        } catch (Throwable error) {
            PluginLog.err("/js failed.", error);
            result = String.valueOf(error);
        }

        // The frame the post cost is enough for a disconnect to land.
        if (player.con() != null && !player.con().isConnected()) {
            return;
        }

        Text.of().lightGray("> ").white(display(result)).sendTo(player);
    }

    /**
     * The return value as chat text: colour markup escaped (a result is data,
     * not formatting - {@code [1,2,3]} must not be read as a tag) and long
     * output cut.
     */
    private static String display(String result) {
        String text = result == null ? "null" : result;

        if (text.length() > MAX_RESULT_LENGTH) {
            text = text.substring(0, MAX_RESULT_LENGTH) + "... (cut)";
        }

        return text.replace("[", "[[");
    }
}
