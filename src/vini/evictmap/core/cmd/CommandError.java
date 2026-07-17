package vini.evictmap.core.cmd;

import vini.evictmap.core.text.Text;

/**
 * Thrown to abort a command with a message shown to whoever ran it.
 *
 * <p>The {@code fail(...)} escape hatch: a handler (or an argument parser) throws
 * it instead of hand-writing a {@code sendMessage} followed by {@code return}.
 * The registry catches it and prints the message, so the happy path of a handler
 * reads top-to-bottom without early-return noise.
 */
public final class CommandError extends RuntimeException {

    private final String display;

    public CommandError(String display) {
        super(display);
        this.display = display;
    }

    public CommandError(Text text) {
        this(text.str());
    }

    /** The already-coloured message to show the sender. */
    public String display() {
        return display;
    }
}
