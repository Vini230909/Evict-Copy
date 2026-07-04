package vini.evictmap.commands;

import vini.evictmap.*;

import arc.util.CommandHandler;
import mindustry.gen.Groups;
import mindustry.gen.Player;

import java.util.HashMap;
import java.util.Map;

/**
 * Player-facing time command and lightweight first-join timer.
 */
public final class RoundTimeCommands {

    /**
     * When the player first joined this round, plus the round's paused time
     * already accumulated back then, so time the game spends paused never
     * counts toward their personal timer.
     */
    private record FirstJoin(long joinedAtMillis, long pausedMillisAtJoin) {
    }

    private final Map<String, FirstJoin> firstJoinByPlayerUuid =
            new HashMap<>();
    private final TeamManager teamManager;

    public RoundTimeCommands(TeamManager teamManager) {
        this.teamManager = teamManager;
    }

    void registerClientCommands(CommandHandler handler) {
        handler.register(
                "time",
                "Show round time and your time since first joining this round.",
                this::showTime
        );
    }

    public void beginRound() {
        firstJoinByPlayerUuid.clear();
        rememberConnectedPlayers();
    }

    public void handlePlayerJoin(Player player) {
        if (player != null) {
            firstJoinByPlayerUuid.putIfAbsent(
                    player.uuid(),
                    new FirstJoin(
                            System.currentTimeMillis(),
                            teamManager.roundPausedMillis()
                    )
            );
        }
    }

    public void rememberConnectedPlayers() {
        long currentMillis = System.currentTimeMillis();
        long pausedMillis = teamManager.roundPausedMillis();

        Groups.player.each(player -> {
            if (player != null) {
                firstJoinByPlayerUuid.putIfAbsent(
                        player.uuid(),
                        new FirstJoin(currentMillis, pausedMillis)
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
        long pausedMillis = teamManager.roundPausedMillis();
        FirstJoin firstJoin = firstJoinByPlayerUuid.get(player.uuid());

        if (firstJoin == null) {
            firstJoin = fallbackFirstJoin(currentMillis, pausedMillis);
            firstJoinByPlayerUuid.put(player.uuid(), firstJoin);
        }

        String roundTime = !teamManager.isRoundActiveForSystems()
                ? "not running"
                : formatDuration(teamManager.roundRuntimeMillis());

        long personalMillis = Math.max(
                0L,
                (currentMillis - firstJoin.joinedAtMillis())
                        - (pausedMillis - firstJoin.pausedMillisAtJoin())
        );

        player.sendMessage(
                "[accent]Round time: [white]"
                        + roundTime
                        + "[]\n[accent]Your first-join time: [white]"
                        + formatDuration(personalMillis)
                        + "[]"
        );
    }

    private FirstJoin fallbackFirstJoin(
            long currentMillis,
            long pausedMillis
    ) {
        long roundStartedAtMillis = teamManager.roundStartedAtMillis();

        if (
                teamManager.isRoundActiveForSystems()
                        && roundStartedAtMillis > 0L
        ) {
            // Unknown join time: treat the player as present since round
            // start, matching the pause-corrected round time (the round's
            // pause counter also started at zero back then).
            return new FirstJoin(roundStartedAtMillis, 0L);
        }

        return new FirstJoin(currentMillis, pausedMillis);
    }

    private String formatDuration(long durationMillis) {
        return ConsoleCommands.formatDuration(durationMillis);
    }
}
