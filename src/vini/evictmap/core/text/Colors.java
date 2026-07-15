package vini.evictmap.core.text;

/**
 * Named constants for the Mindustry colour markup tags used across the plugin.
 *
 * <p>Before this class the codebase carried ~275 inline {@code [scarlet]} /
 * {@code [accent]} literals; a single typo ({@code [scralet]}) silently shipped
 * a broken tag. Referencing the tags by name keeps colours consistent and gives
 * one place to retune the palette. Prefer building messages with {@link Text},
 * which uses these under the hood; reach for the raw constants only for the odd
 * literal.
 */
public final class Colors {

    /** Resets to the surrounding/default colour. */
    public static final String RESET = "[]";

    /** The plugin's default emphasis colour for informational text. */
    public static final String ACCENT = "[accent]";
    /** Errors, warnings and destructive outcomes. */
    public static final String SCARLET = "[scarlet]";
    /** Neutral body text and player-typed values. */
    public static final String WHITE = "[white]";
    /** Secondary / de-emphasised text. */
    public static final String LIGHT_GRAY = "[lightgray]";
    public static final String GRAY = "[gray]";
    /** Positive outcomes and highlights. */
    public static final String GREEN = "[green]";
    public static final String GOLD = "[gold]";
    public static final String ORANGE = "[orange]";
    public static final String RED = "[red]";
    public static final String YELLOW = "[yellow]";
    public static final String CYAN = "[cyan]";
    public static final String CORAL = "[coral]";
    public static final String SKY = "[sky]";

    private Colors() {
    }

    /** Wraps {@code value} in an explicit {@code [#rrggbb]} colour tag. */
    public static String hex(String rrggbb) {
        return "[#" + rrggbb + "]";
    }
}
