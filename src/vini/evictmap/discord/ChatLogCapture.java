package vini.evictmap.discord;

import arc.Events;
import arc.util.CommandHandler;
import mindustry.Vars;
import mindustry.game.EventType.PlayerChatEvent;
import mindustry.game.EventType.PlayerJoin;
import mindustry.game.EventType.PlayerLeave;
import mindustry.gen.Player;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Turns what players do into chat-log lines: chat messages, commands, joins
 * and leaves. Runs on the hub (lines go straight to the
 * {@link ChatLogReporter}) and on a worker (lines go to its {@code chat.log}
 * file for the hub to relay) - only the sink differs.
 *
 * <p>Everything automatic - countdowns, captures, Extinction warnings, the
 * plugin's own broadcasts - is deliberately absent. The mirror shows what
 * players did; lifecycle embeds and the ban announcement are added by their
 * own systems.
 *
 * <p>Capture points, matched to how Mindustry v157 processes a message
 * ({@code NetClient.sendChatMessage} server-side):
 * <ul>
 * <li>{@link PlayerChatEvent} fires first, for every raw message including
 * commands, before any chat filter. It is used only for messages that name a
 * registered command ({@code Name: /play ...}) - including {@code /t}, whose
 * text would otherwise be lost on a worker, where the overridden /t never
 * runs the filter chain.</li>
 * <li>A chat filter, registered before every other one, logs normal chat.
 * Sitting first means the mirror shows even a message the word filter then
 * drops - the mirror is a staff channel, and the ban announcement follows
 * right after - and on a worker it sees viewer chat before the ranked
 * spectator routing reroutes it.</li>
 * <li>Vanilla {@code /t} runs the filter chain on its message text, which
 * would mirror hub team chat twice. The event capture marks the player and
 * the filter skips exactly one call for them; the mark is cleared at the next
 * raw message, so a command that never re-enters the filter chain cannot
 * swallow later chat.</li>
 * </ul>
 */
public final class ChatLogCapture {

    private final Consumer<String> sink;

    /**
     * Players whose next chat-filter call is a registered command re-entering
     * the filter chain (/t) rather than a new chat message. See the class doc.
     */
    private final Set<String> skipNextFilterUuids = new HashSet<>();

    /**
     * Players whose join was mirrored, so a leave without a mirrored join
     * (a name the word filter banned on sight) stays silent too.
     */
    private final Set<String> joinedUuids = new HashSet<>();

    private boolean filterInstalled;
    private boolean eventsInstalled;

    public ChatLogCapture(Consumer<String> sink) {
        this.sink = sink;
    }

    /**
     * Registers the chat filter. Order matters: before every other filter,
     * so the mirror sees the message even when the word filter drops it or
     * the worker's ranked spectator routing reroutes it.
     */
    public void installChatFilter() {
        if (filterInstalled || Vars.netServer == null) {
            return;
        }

        filterInstalled = true;
        Vars.netServer.admins.addChatFilter(this::filterChat);
    }

    /**
     * Registers the event listeners. Called late in init so the join listener
     * runs after the plugin's own join handling - a player the word filter
     * kicked on sight is already marked kicked by then and stays unmirrored.
     */
    public void installEvents() {
        if (eventsInstalled) {
            return;
        }

        eventsInstalled = true;

        Events.on(PlayerChatEvent.class, event ->
                handleRawMessage(event.player, event.message)
        );

        Events.on(PlayerJoin.class, event -> handleJoin(event.player));
        Events.on(PlayerLeave.class, event -> handleLeave(event.player));
    }

    /**
     * Every raw incoming message, commands included, before any filter. Only
     * registered commands are mirrored here; anything else either reaches the
     * chat filter (and is mirrored there) or was never visible to anyone.
     */
    private void handleRawMessage(Player player, String message) {
        if (player == null || message == null) {
            return;
        }

        // A stale mark means the previous command never re-entered the filter
        // chain; clearing it here keeps it from swallowing this message.
        skipNextFilterUuids.remove(player.uuid());

        if (isRegisteredCommand(message)) {
            skipNextFilterUuids.add(player.uuid());
            emit(name(player) + ": " + DiscordFormat.playerText(message));
        }
    }

    private String filterChat(Player player, String message) {
        if (player == null || message == null) {
            return message;
        }

        if (!skipNextFilterUuids.remove(player.uuid())) {
            emit(name(player) + ": " + DiscordFormat.playerText(message));
        }

        return message;
    }

    private void handleJoin(Player player) {
        if (player == null) {
            return;
        }

        // Kicked already (the word filter banned the name on sight): nobody
        // got to see them, so the mirror does not either.
        if (player.con != null && player.con.kicked) {
            return;
        }

        joinedUuids.add(player.uuid());
        emit(name(player) + " joined.");
    }

    private void handleLeave(Player player) {
        if (player == null || !joinedUuids.remove(player.uuid())) {
            return;
        }

        emit(name(player) + " left.");
    }

    /**
     * Whether the message names a registered client command. An unknown
     * "/word" falls through to normal chat in vanilla and is mirrored by the
     * chat filter instead.
     */
    private boolean isRegisteredCommand(String message) {
        if (Vars.netServer == null) {
            return false;
        }

        String prefix = Vars.netServer.clientCommands.getPrefix();

        if (!message.startsWith(prefix)) {
            return false;
        }

        String text = message.substring(prefix.length());
        int space = text.indexOf(' ');
        String commandName = (space < 0 ? text : text.substring(0, space)).trim();

        if (commandName.isEmpty()) {
            return false;
        }

        for (CommandHandler.Command command
                : Vars.netServer.clientCommands.getCommandList()) {
            if (command.text.equalsIgnoreCase(commandName)) {
                return true;
            }
        }

        return false;
    }

    private static String name(Player player) {
        return DiscordFormat.playerName(player.name);
    }

    private void emit(String line) {
        try {
            sink.accept(line);
        } catch (Exception ignored) {
            // The mirror must never break chat handling.
        }
    }
}
