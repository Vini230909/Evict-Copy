package vini.evictmap.core.cmd;

import arc.func.Cons;
import arc.util.CommandHandler;
import mindustry.gen.Player;
import vini.evictmap.core.text.Msg;
import vini.evictmap.core.util.PluginLog;

import java.util.ArrayList;
import java.util.List;

/**
 * A collection of {@link Command}s and the bridge onto Mindustry's
 * {@link CommandHandler}.
 *
 * <p>Handlers describe commands with {@link #command(String)}; installing wires
 * each into the client handler ({@code CommandRunner<Player>}) or the console
 * handler ({@code Cons<String[]>}), doing permission checks, typed argument
 * parsing and {@link CommandError} handling once, centrally - so the ~319-line
 * hand-rolled {@code ConsoleCommands.register()} becomes a list of declarations.
 */
public final class Commands {

    final List<Command> commands = new ArrayList<>();

    /** Begins a fluent command declaration that auto-registers on {@code run(...)}. */
    public Command.Builder command(String name) {
        return new Command.Builder(name, this);
    }

    public List<Command> all() {
        return commands;
    }

    /** Registers every client-facing command with a client {@link CommandHandler}. */
    public void installClient(CommandHandler handler) {
        for (Command command : commands) {
            if (!command.client) {
                continue;
            }
            CommandHandler.CommandRunner<Player> runner =
                    (args, player) -> execute(command, args, player);
            handler.<Player>register(command.name, command.params(), command.description, runner);
            for (String alias : command.aliases) {
                handler.<Player>register(alias, command.params(), command.description, runner);
            }
        }
    }

    /** Registers every console command with the server {@link CommandHandler}. */
    public void installConsole(CommandHandler handler) {
        for (Command command : commands) {
            if (!command.console) {
                continue;
            }
            Cons<String[]> runner = args -> execute(command, args, null);
            handler.register(command.name, command.params(), command.description, runner);
            for (String alias : command.aliases) {
                handler.register(alias, command.params(), command.description, runner);
            }
        }
    }

    private void execute(Command command, String[] args, Player sender) {
        CommandContext context = new CommandContext(sender, args);

        if (!command.perm.allows(sender)) {
            context.reply(Msg.noPermission());
            return;
        }

        try {
            parseArgs(command, args, context);
            command.handler.accept(context);
        } catch (CommandError error) {
            context.reply(error.display());
        } catch (Exception exception) {
            PluginLog.err("Command /@ failed: @", command.name, exception);
            context.error("Something went wrong running that command.");
        }
    }

    private void parseArgs(Command command, String[] args, CommandContext context) {
        for (int i = 0; i < command.args.size(); i++) {
            Arg arg = command.args.get(i);
            if (i >= args.length) {
                if (arg.optional) {
                    continue;
                }
                throw new CommandError("[scarlet]Missing argument: [white]" + arg.name);
            }
            context.put(arg.name, arg.type.parse(args[i], context));
        }
    }
}
