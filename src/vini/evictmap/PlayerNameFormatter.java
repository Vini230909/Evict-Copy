package vini.evictmap;

import mindustry.gen.Player;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Formats live player names for chat/menu output.
 */
public final class PlayerNameFormatter {

    private static final Pattern HEX_COLOR_TAG =
            Pattern.compile("\\[#([0-9a-fA-F]{6})(?:[0-9a-fA-F]{2})?]");

    private PlayerNameFormatter() {
    }

    public static String displayName(Player player) {
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

    /**
     * Joins already-formatted display names with {@code separator}, capping
     * how many are shown so a big FFA/Teams roster never blows up a /v or /h
     * menu into an unreadable wall of text; the rest are folded into a
     * trailing "+N more".
     */
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
