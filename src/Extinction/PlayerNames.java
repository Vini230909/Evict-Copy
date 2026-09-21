// How a live player's name is shown in chat and menus: their own [#rrggbb] colour or white, and long rosters cut.
package Extinction;

import mindustry.gen.Player;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PlayerNames {

    private static final Pattern HEX_COLOR_TAG =
            Pattern.compile("\\[#([0-9a-fA-F]{6})(?:[0-9a-fA-F]{2})?]");

    private PlayerNames() {
    }

    // Only a [#rrggbb] tag the player put in their own name is trusted; team colour and the menu swatch
    // are ignored, so a player without a coloured name never appears in an unrelated colour.
    public static String displayName(Player player) {
        if (player == null) {
            return "unknown";
        }

        String name = player.plainName();

        if (name == null || name.isBlank()) {
            name = "unnamed";
        }

        String color = explicitNameColor(player.name);

        return color == null
                ? "[white]" + name + "[]"
                : "[#" + color + "]" + name + "[]";
    }

    // Joins formatted names, showing at most maxShown and folding the rest into "+N more" so a big roster fits a menu.
    public static String joinShortened(
            List<String> names,
            String separator,
            int maxShown
    ) {
        if (names.size() <= maxShown) {
            return String.join(separator, names);
        }

        return String.join(separator, names.subList(0, maxShown))
                + "[lightgray] (+" + (names.size() - maxShown) + " more)[]";
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
