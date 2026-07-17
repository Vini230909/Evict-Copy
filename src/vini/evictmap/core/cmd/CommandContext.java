package vini.evictmap.core.cmd;

import arc.util.Strings;
import mindustry.gen.Player;
import vini.evictmap.core.text.Colors;
import vini.evictmap.core.text.Text;
import vini.evictmap.core.util.PluginLog;

import java.util.HashMap;
import java.util.Map;

/**
 * Everything a command handler needs, injected in one object.
 *
 * <p>A handler reads typed arguments by name ({@link #player}, {@link #getInt},
 * {@link #str}) and replies through {@link #reply} / {@link #success} /
 * {@link #error} / {@link #fail}, which transparently target a player's chat or
 * the server console (colour tags are stripped for the console log).
 */
public final class CommandContext {

    private final Player sender;
    private final String[] raw;
    private final Map<String, Object> values = new HashMap<>();

    CommandContext(Player sender, String[] raw) {
        this.sender = sender;
        this.raw = raw;
    }

    void put(String name, Object value) {
        values.put(name, value);
    }

    /** The player who ran the command, or {@code null} for the server console. */
    public Player sender() {
        return sender;
    }

    public boolean isConsole() {
        return sender == null;
    }

    public String[] raw() {
        return raw;
    }

    public boolean has(String name) {
        return values.get(name) != null;
    }

    public Object get(String name) {
        return values.get(name);
    }

    public Player player(String name) {
        return (Player) values.get(name);
    }

    public String str(String name) {
        Object value = values.get(name);
        return value == null ? null : value.toString();
    }

    public String str(String name, String fallback) {
        String value = str(name);
        return value == null ? fallback : value;
    }

    public int getInt(String name, int fallback) {
        Object value = values.get(name);
        return value instanceof Integer ? (Integer) value : fallback;
    }

    public long getLong(String name, long fallback) {
        Object value = values.get(name);
        return value instanceof Long ? (Long) value : fallback;
    }

    public float getFloat(String name, float fallback) {
        Object value = values.get(name);
        return value instanceof Float ? (Float) value : fallback;
    }

    public boolean getBool(String name, boolean fallback) {
        Object value = values.get(name);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    // --- output ---------------------------------------------------------

    /** Sends a raw (already-coloured) message to the sender or the console. */
    public void reply(String message) {
        if (sender != null) {
            sender.sendMessage(message);
        } else {
            PluginLog.info(Strings.stripColors(message));
        }
    }

    public void reply(Text text) {
        reply(text.str());
    }

    public void success(String message) {
        reply(Colors.GREEN + message + Colors.RESET);
    }

    public void error(String message) {
        reply(Colors.SCARLET + message + Colors.RESET);
    }

    /** Aborts the command, showing {@code message} to the sender. */
    public void fail(String message) {
        throw new CommandError(Colors.SCARLET + message + Colors.RESET);
    }

    public void fail(Text text) {
        throw new CommandError(text);
    }
}
