package vini.evictmap.core.cmd;

import mindustry.gen.Player;
import vini.evictmap.core.text.Text;
import vini.evictmap.core.util.Players;

/**
 * A typed command argument. Turns a raw token into a value of type {@code T},
 * throwing a {@link CommandError} with a friendly message when the token is
 * invalid.
 *
 * <p>A command declares an argument as {@code "target:player"} and the handler
 * reads a fully-resolved {@link Player} out of the context, with the "no such
 * player" error produced here in one place rather than in every command.
 */
public final class ArgType<T> {

    @FunctionalInterface
    public interface Parser<T> {
        T parse(String raw, CommandContext context);
    }

    public static final ArgType<String> STRING = new ArgType<>("string", false, (raw, ctx) -> raw);

    /** Greedy: consumes the rest of the line as a single string. */
    public static final ArgType<String> TEXT = new ArgType<>("text", true, (raw, ctx) -> raw);

    public static final ArgType<Integer> INT = new ArgType<>("int", false, (raw, ctx) -> {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new CommandError(Text.of().white(raw).scarlet(" is not a whole number."));
        }
    });

    public static final ArgType<Long> LONG = new ArgType<>("long", false, (raw, ctx) -> {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new CommandError(Text.of().white(raw).scarlet(" is not a whole number."));
        }
    });

    public static final ArgType<Float> FLOAT = new ArgType<>("float", false, (raw, ctx) -> {
        try {
            return Float.parseFloat(raw.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new CommandError(Text.of().white(raw).scarlet(" is not a number."));
        }
    });

    public static final ArgType<Boolean> BOOL = new ArgType<>("bool", false, (raw, ctx) -> {
        String v = raw.trim().toLowerCase();
        if (v.equals("on") || v.equals("true") || v.equals("yes") || v.equals("1")) {
            return Boolean.TRUE;
        }
        if (v.equals("off") || v.equals("false") || v.equals("no") || v.equals("0")) {
            return Boolean.FALSE;
        }
        throw new CommandError(Text.of().white(raw).scarlet(" is not on/off."));
    });

    /** An online player, resolved by exact or unique partial name. */
    public static final ArgType<Player> PLAYER = new ArgType<>("player", false, (raw, ctx) -> {
        Player player = Players.resolveOnline(raw);
        if (player == null) {
            throw new CommandError(
                    Text.of().scarlet("No single online player matches ").white(raw).scarlet(".")
            );
        }
        return player;
    });

    private final String id;
    private final boolean greedy;
    private final Parser<T> parser;

    private ArgType(String id, boolean greedy, Parser<T> parser) {
        this.id = id;
        this.greedy = greedy;
        this.parser = parser;
    }

    public T parse(String raw, CommandContext context) {
        return parser.parse(raw, context);
    }

    public String id() {
        return id;
    }

    /** A greedy type swallows the remaining line as one token. */
    public boolean greedy() {
        return greedy;
    }

    static ArgType<?> byId(String id) {
        switch (id.toLowerCase()) {
            case "string": return STRING;
            case "text": return TEXT;
            case "int": return INT;
            case "long": return LONG;
            case "float": return FLOAT;
            case "bool":
            case "boolean": return BOOL;
            case "player": return PLAYER;
            default:
                throw new IllegalArgumentException("Unknown command arg type: " + id);
        }
    }
}
