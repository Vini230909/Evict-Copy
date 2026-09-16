package vini.evictmap.moderation.lock;

import arc.func.Cons;
import arc.struct.Seq;
import arc.util.CommandHandler;
import mindustry.gen.Player;

/**
 * Stands in front of the server's client command handler and turns every
 * command a locked player types - except the few they may use - into the
 * reminder of how to get freed.
 *
 * <p>Commands are registered in many places (vanilla, this plugin, other
 * plugins) and not all through one framework, so the one place they all pass
 * is {@code netServer.clientCommands.handleMessage}. This wraps that handler:
 * everything is delegated to the real one - registering, listing, removing,
 * the prefix - and only {@code handleMessage} looks at who is asking first.
 * A refused command answers as {@code valid} so the server neither treats
 * the text as chat nor prints "unknown command" over the reminder.
 */
final class LockCommandGate extends CommandHandler {

    private final CommandHandler delegate;
    private final PlayerLock lock;

    LockCommandGate(CommandHandler delegate, PlayerLock lock) {
        super(delegate.getPrefix());
        this.delegate = delegate;
        this.lock = lock;
    }

    @Override
    public CommandResponse handleMessage(String message, Object params) {
        if (
                params instanceof Player player
                        && message != null
                        && message.startsWith(delegate.getPrefix())
                        && lock.isLocked(player.uuid())
                        && !PlayerLock.ALLOWED_COMMANDS.contains(commandName(message))
        ) {
            lock.remind(player);
            return new CommandResponse(ResponseType.valid, null, message);
        }

        return delegate.handleMessage(message, params);
    }

    @Override
    public CommandResponse handleMessage(String message) {
        return delegate.handleMessage(message);
    }

    @Override
    public void setPrefix(String prefix) {
        super.setPrefix(prefix);
        delegate.setPrefix(prefix);
    }

    @Override
    public String getPrefix() {
        return delegate.getPrefix();
    }

    @Override
    public void removeCommand(String text) {
        delegate.removeCommand(text);
    }

    @Override
    public <T> Command register(String text, String description, CommandRunner<T> runner) {
        return delegate.register(text, description, runner);
    }

    @Override
    public <T> Command register(String text, String params, String description, CommandRunner<T> runner) {
        return delegate.register(text, params, description, runner);
    }

    @Override
    public Command register(String text, String description, Cons<String[]> runner) {
        return delegate.register(text, description, runner);
    }

    @Override
    public Command register(String text, String params, String description, Cons<String[]> runner) {
        return delegate.register(text, params, description, runner);
    }

    @Override
    public Seq<Command> getCommandList() {
        return delegate.getCommandList();
    }

    /** {@code /play nerf} → {@code play}, lower-case. */
    private String commandName(String message) {
        String rest = message.substring(delegate.getPrefix().length()).trim();
        int space = rest.indexOf(' ');
        return (space < 0 ? rest : rest.substring(0, space)).toLowerCase();
    }
}
