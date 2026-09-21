// Round time: the round's runtime for /time and console 'round', plus each player's first-join timer.
package Extinction;

import Extinction.core.text.Text;
import Extinction.core.util.PluginLog;
import Extinction.round.TeamManager;

import mindustry.gen.Groups;
import mindustry.gen.Player;

import java.util.HashMap;
import java.util.Map;

public final class RoundTime {

    // When the player first joined this round, plus the paused time back then, so pauses never count for them.
    private record FirstJoin(long joinedAtMillis, long pausedMillisAtJoin) {
    }

    private final Map<String, FirstJoin> firstJoinByPlayerUuid = new HashMap<>();
    private final TeamManager teamManager;

    public RoundTime(TeamManager teamManager) {
        this.teamManager = teamManager;
    }

    public void beginRound() {
        firstJoinByPlayerUuid.clear();
        rememberConnectedPlayers();
    }

    public void handlePlayerJoin(Player player) {
        if (player != null) {
            firstJoinByPlayerUuid.putIfAbsent(
                    player.uuid(),
                    new FirstJoin(System.currentTimeMillis(), teamManager.roundPausedMillis())
            );
        }
    }

    public void rememberConnectedPlayers() {
        long currentMillis = System.currentTimeMillis();
        long pausedMillis = teamManager.roundPausedMillis();

        Groups.player.each(player -> {
            if (player != null) {
                firstJoinByPlayerUuid.putIfAbsent(player.uuid(), new FirstJoin(currentMillis, pausedMillis));
            }
        });
    }

    // /time: the round's runtime and the player's connected time since first joining this round.
    public Text show(Player player) {
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
                (currentMillis - firstJoin.joinedAtMillis()) - (pausedMillis - firstJoin.pausedMillisAtJoin())
        );

        return Text.of()
                .accent("Round time: ").white(roundTime)
                .add("\n")
                .accent("Your first-join time: ").white(formatDuration(personalMillis));
    }

    // Console 'round': the Fallen-team spawn assignment and the elapsed time.
    public void logStatus() {
        teamManager.logStatus();
        logElapsed();
    }

    public void logElapsed() {
        PluginLog.info("time = @", teamManager.roundRuntimeMillis() / 1000);
    }

    // Console 'round time <seconds>': sets the elapsed time.
    public void setElapsed(String seconds) {
        long parsedTime;
        try {
            parsedTime = Long.parseLong(seconds);
        } catch (NumberFormatException e) {
            PluginLog.err("time must be a long");
            return;
        }

        PluginLog.info("setting time to @", parsedTime);
        teamManager.setElapsedTimeMillis(parsedTime * 1000);
    }

    // Unknown join time: present since round start, whose pause counter was zero back then.
    private FirstJoin fallbackFirstJoin(long currentMillis, long pausedMillis) {
        long roundStartedAtMillis = teamManager.roundStartedAtMillis();

        if (teamManager.isRoundActiveForSystems() && roundStartedAtMillis > 0L) {
            return new FirstJoin(roundStartedAtMillis, 0L);
        }

        return new FirstJoin(currentMillis, pausedMillis);
    }

    // "1h 2m 3s"; hours and minutes only once they are non-zero.
    public static String formatDuration(long durationMillis) {
        long totalSeconds = Math.max(0L, durationMillis / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;

        StringBuilder result = new StringBuilder();

        if (hours > 0L) {
            result.append(hours).append("h ");
        }

        if (hours > 0L || minutes > 0L) {
            result.append(minutes).append("m ");
        }

        result.append(seconds).append("s");
        return result.toString();
    }
}
