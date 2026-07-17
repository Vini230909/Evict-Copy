package vini.evictmap.core.cmd;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * An immutable command definition: name, aliases, typed arguments, permission,
 * where it registers (client and/or console) and the handler.
 *
 * <p>Commands are plain data built with a small fluent builder, so a whole
 * command reads as one declaration instead of a lambda buried in a 300-line
 * {@code register()} method:
 *
 * <pre>{@code
 * commands.command("warn")
 *     .description("Warn a player.")
 *     .args("target:player", "reason:text?")
 *     .perm(Perm.ADMIN)
 *     .client()
 *     .run(ctx -> ctx.success("Warned " + ctx.player("target").plainName()));
 * }</pre>
 */
public final class Command {

    final String name;
    final List<String> aliases;
    final String description;
    final List<Arg> args;
    final Perm perm;
    final Consumer<CommandContext> handler;
    final boolean client;
    final boolean console;

    private Command(Builder builder) {
        this.name = builder.name;
        this.aliases = List.copyOf(builder.aliases);
        this.description = builder.description;
        this.args = List.copyOf(builder.args);
        this.perm = builder.perm;
        this.handler = builder.handler;
        this.client = builder.client;
        this.console = builder.console;
    }

    /** The Mindustry params string, e.g. {@code "<target> [reason...]"}. */
    String params() {
        StringBuilder builder = new StringBuilder();
        for (Arg arg : args) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(arg.token());
        }
        return builder.toString();
    }

    public static Builder builder(String name) {
        return new Builder(name, null);
    }

    /** Mutable builder; {@link #run} finalises the command (and registers it if attached to a {@link Commands}). */
    public static final class Builder {

        private final Commands registry;
        private final String name;
        private final List<String> aliases = new ArrayList<>();
        private final List<Arg> args = new ArrayList<>();
        private String description = "";
        private Perm perm = Perm.EVERYONE;
        private Consumer<CommandContext> handler = ctx -> { };
        private boolean client;
        private boolean console;

        Builder(String name, Commands registry) {
            this.name = name;
            this.registry = registry;
        }

        public Builder aliases(String... names) {
            aliases.addAll(Arrays.asList(names));
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder args(String... specs) {
            for (String spec : specs) {
                args.add(Arg.parse(spec));
            }
            return this;
        }

        public Builder perm(Perm perm) {
            this.perm = perm;
            return this;
        }

        public Builder client() {
            this.client = true;
            return this;
        }

        public Builder console() {
            this.console = true;
            return this;
        }

        public Builder both() {
            this.client = true;
            this.console = true;
            return this;
        }

        /** Sets the handler, builds the command, and registers it if attached. */
        public Command run(Consumer<CommandContext> handler) {
            this.handler = handler;
            if (!client && !console) {
                client = true;
            }
            Command command = new Command(this);
            if (registry != null) {
                registry.commands.add(command);
            }
            return command;
        }
    }
}
