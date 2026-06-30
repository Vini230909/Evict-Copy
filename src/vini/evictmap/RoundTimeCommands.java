package vini.evictmap;

import arc.util.CommandHandler;
import mindustry.gen.Groups;
import mindustry.gen.Player;

import java.util.HashMap;
import java.util.Map;

/**
 * Player-facing time command and lightweight first-join timer.
 */
final class RoundTimeCommands {

    private final Map<String, Long> joinedAtMillisByPlayerUuid =
        new HashMap<>();
    private final TeamManager teamManager;

    RoundTimeCommands(TeamManager teamManager) {
        this.teamManager = teamManager;
    }

    void registerClientCommands(CommandHandler handler) {
        handler.register(
            "time",
            "Show round time and your time since first joining this round.",
                this::showTime
        );
    }

    void beginRound() {
        joinedAtMillisByPlayerUuid.clear();
        rememberConnectedPlayers();
    }

    void handlePlayerJoin(Player player) {
        if (player != null) {
            joinedAtMillisByPlayerUuid.putIfAbsent(
                player.uuid(),
                System.currentTimeMillis()
            );
        }
    }

    void rememberConnectedPlayers() {
        long currentMillis = System.currentTimeMillis();

        Groups.player.each(player -> {
            if (player != null) {
                joinedAtMillisByPlayerUuid.putIfAbsent(
                    player.uuid(),
                    currentMillis
                );
            }
        });
    }

    private void showTime(String[] args, Player player) {
        if (args.length != 0) {
            player.sendMessage("[scarlet]Use: /time[]");
            return;
        }

        long currentMillis = System.currentTimeMillis();
        Long joinedAtMillis =
            joinedAtMillisByPlayerUuid.get(player.uuid());

        if (joinedAtMillis == null) {
            joinedAtMillis = fallbackJoinTimeMillis(currentMillis);
            joinedAtMillisByPlayerUuid.put(player.uuid(), joinedAtMillis);
        }

        String roundTime = !teamManager.isRoundActiveForSystems()
            ? "not running"
            : formatDuration(teamManager.roundRuntimeMillis());

        player.sendMessage(
            "[accent]Round time: [white]"
                + roundTime
                + "[]\n[accent]Your first-join time: [white]"
                + formatDuration(currentMillis - joinedAtMillis)
                + "[]"
        );
    }

    private long fallbackJoinTimeMillis(long currentMillis) {
        long roundStartedAtMillis = teamManager.roundStartedAtMillis();

        if (
            teamManager.isRoundActiveForSystems()
                && roundStartedAtMillis > 0L
        ) {
            return roundStartedAtMillis;
        }

        return currentMillis;
    }

    private String formatDuration(long durationMillis) {
        return EvictConsoleCommands.formatDuration(durationMillis);
    }
}
