package vini.evictmap;

import arc.graphics.Color;
import mindustry.gen.Player;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Formats live player names for chat/menu output.
 */
final class PlayerNameFormatter {

    private static final Pattern HEX_COLOR_TAG =
        Pattern.compile("\\[#([0-9a-fA-F]{6})(?:[0-9a-fA-F]{2})?\\]");

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

        if (color == null && player.team() != null) {
            color = colorHex(player.team().color);
        }

        return color == null
            ? name
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

    private static String colorHex(Color color) {
        if (color == null) {
            return null;
        }

        int red = Math.round(color.r * 255f);
        int green = Math.round(color.g * 255f);
        int blue = Math.round(color.b * 255f);

        return String.format(
            Locale.ROOT,
            "%02x%02x%02x",
            clampColor(red),
            clampColor(green),
            clampColor(blue)
        );
    }

    private static int clampColor(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
