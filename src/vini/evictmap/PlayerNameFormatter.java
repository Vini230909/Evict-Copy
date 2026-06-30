package vini.evictmap;

import mindustry.gen.Player;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Formats live player names for chat/menu output.
 */
final class PlayerNameFormatter {

    private static final Pattern HEX_COLOR_TAG =
        Pattern.compile("\\[#([0-9a-fA-F]{6})(?:[0-9a-fA-F]{2})?]");

    private PlayerNameFormatter() {
    }

    static String displayName(Player player) {
        if (player == null) {
            return "unknown";
        }

        String name = player.plainName();

        if (name == null || name.isBlank()) {
            name = "unnamed";
        }

        String color = explicitNameColor(player.name);

        /*
         * Only an explicit [#rrggbb] tag the player put in their own name is
         * trusted. Everything else - team color, the color swatch picked in
         * the multiplayer menu - is ignored and the name is shown white, so a
         * player without a colored name never appears in an unrelated color.
         */
        return color == null
            ? "[white]" + name + "[]"
            : "[#" + color + "]" + name + "[]";
    }

    private static String explicitNameColor(String rawName) {
        if (rawName == null) {
            return null;
        }

        Matcher matcher = HEX_COLOR_TAG.matcher(rawName);

        return matcher.find()
            ? matcher.group(1).toLowerCase(Locale.ROOT)
            : null;
    }
}
