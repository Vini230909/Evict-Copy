package vini.evictmap.core.text;

import arc.util.Strings;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import vini.evictmap.PlayerNameFormatter;

/**
 * Fluent builder for coloured chat strings.
 *
 * <p>A message is assembled from typed fragments, so a player is always rendered
 * with their own name colour and a colour tag can never be left unclosed. It
 * replaces hundreds of hand-concatenated
 * {@code "[scarlet]...[]" + name + "..."} strings scattered through the code.
 *
 * <pre>{@code
 * Text.of().scarlet("Warned ").player(target).scarlet(" - ").white(reason).sendTo(sender);
 * }</pre>
 *
 * <p>Every colour method wraps its fragment and closes the tag automatically;
 * {@link #add} appends raw markup untouched when you really need it.
 */
public final class Text {

    private final StringBuilder sb = new StringBuilder();

    private Text() {
    }

    public static Text of() {
        return new Text();
    }

    /** Starts a builder seeded with raw markup. */
    public static Text of(String raw) {
        return new Text().add(raw);
    }

    /** Appends raw markup with no colour wrapping. */
    public Text add(String raw) {
        sb.append(raw);
        return this;
    }

    /** Appends {@code value} wrapped in {@code tag} and a closing {@code []}. */
    public Text color(String tag, Object value) {
        sb.append(tag).append(value).append(Colors.RESET);
        return this;
    }

    public Text accent(Object value) {
        return color(Colors.ACCENT, value);
    }

    public Text white(Object value) {
        return color(Colors.WHITE, value);
    }

    public Text scarlet(Object value) {
        return color(Colors.SCARLET, value);
    }

    public Text gray(Object value) {
        return color(Colors.GRAY, value);
    }

    public Text lightGray(Object value) {
        return color(Colors.LIGHT_GRAY, value);
    }

    public Text green(Object value) {
        return color(Colors.GREEN, value);
    }

    public Text gold(Object value) {
        return color(Colors.GOLD, value);
    }

    public Text orange(Object value) {
        return color(Colors.ORANGE, value);
    }

    public Text cyan(Object value) {
        return color(Colors.CYAN, value);
    }

    /** Appends a player's colour-formatted display name. */
    public Text player(Player player) {
        sb.append(PlayerNameFormatter.displayName(player));
        return this;
    }

    /** Appends a number in the accent colour - the plugin's default for values. */
    public Text num(Object number) {
        return color(Colors.ACCENT, number);
    }

    /** Appends a fixed-precision float in the accent colour. */
    public Text num(float value, int decimals) {
        return num(Strings.fixed(value, decimals));
    }

    public String str() {
        return sb.toString();
    }

    @Override
    public String toString() {
        return str();
    }

    /** Sends the built message to one player (no-op if {@code player} is null). */
    public void sendTo(Player player) {
        if (player != null) {
            player.sendMessage(str());
        }
    }

    /** Broadcasts the built message to every connected player. */
    public void sendAll() {
        Call.sendMessage(str());
    }

    /** Sends the built message only to players matching {@code team} id. */
    public void sendToTeam(int teamId) {
        String message = str();
        Groups.player.each(
                player -> player != null && player.team().id == teamId,
                player -> player.sendMessage(message)
        );
    }
}
