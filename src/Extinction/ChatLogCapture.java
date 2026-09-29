// Captures player chat, commands, joins and leaves for the Discord mirror.
package Extinction;

import Extinction.discord.DiscordFormat;

import arc.util.CommandHandler;
import mindustry.Vars;
import mindustry.gen.Player;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

public final class ChatLogCapture {

    private final Consumer<String> sink;

    private final Set<String> skipNextFilterUuids = new HashSet<>();

    private final Set<String> joinedUuids = new HashSet<>();

    private boolean filterInstalled;

    public ChatLogCapture(Consumer<String> sink) {
        this.sink = sink;
    }

    public void installChatFilter() {
        if (filterInstalled || Vars.netServer == null) {
            return;
        }

        filterInstalled = true;
        Vars.netServer.admins.addChatFilter(this::filterChat);
    }

    public void handleRawMessage(Player player, String message) {
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

    public void handleJoin(Player player) {
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

    public void handleLeave(Player player) {
        if (player == null || !joinedUuids.remove(player.uuid())) {
            return;
        }

        emit(name(player) + " left.");
    }

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

    public void setLockMarker(java.util.function.Predicate<Player> locked) {
        this.locked = locked;
    }

    private java.util.function.Predicate<Player> locked = player -> false;

    private String name(Player player) {
        String name = DiscordFormat.playerName(
                Extinction.PlayerLock.stripPrefix(player.name)
        );

        return locked.test(player) ? "🔒 " + name : name;
    }

    private void emit(String line) {
        try {
            sink.accept(line);
        } catch (Exception ignored) {
            // The mirror must never break chat handling.
        }
    }
}
