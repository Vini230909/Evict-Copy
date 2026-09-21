// /js: runs a chat-typed script through the console's js runner and sends the result to the admin who typed it.
package Extinction;

import Extinction.core.text.Text;
import Extinction.core.util.PluginLog;

import arc.Core;
import mindustry.Vars;
import mindustry.gen.Player;

public final class Js {

    // A chat message is one packet; a script can return a whole map's worth of text, so it is cut here.
    private static final int MAX_RESULT_LENGTH = 700;

    private Js() {
    }

    // Logs the script with its runner's name (an audit line for arbitrary code), then runs it next frame.
    public static void run(Player player, String script) {
        PluginLog.info("@ ran /js: @", player.plainName(), script);

        // A script can touch anything, so it is posted onto the main thread the console's js runs on.
        Core.app.post(() -> runNow(player, script));
    }

    private static void runNow(Player player, String script) {
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

    // The result as chat text: colour markup escaped ([1,2,3] is data, not a tag) and long output cut.
    private static String display(String result) {
        String text = result == null ? "null" : result;

        if (text.length() > MAX_RESULT_LENGTH) {
            text = text.substring(0, MAX_RESULT_LENGTH) + "... (cut)";
        }

        return text.replace("[", "[[");
    }
}
