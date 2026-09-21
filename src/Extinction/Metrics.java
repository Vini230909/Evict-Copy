// One throttled log line a minute with the hub's player and match counts, for reading the server without tailing the log.
package Extinction;

import Extinction.core.util.PluginLog;

import arc.util.Time;
import mindustry.gen.Groups;

public final class Metrics {

    private static final long INTERVAL_MS = 60_000L;

    private final Matches matches;

    private long lastReportMillis;

    public Metrics(Matches matches) {
        this.matches = matches;
    }

    // Called every frame from the hub's update loop; the wall-clock gate keeps the cost negligible.
    public void update() {
        if (Time.timeSinceMillis(lastReportMillis) < INTERVAL_MS) {
            return;
        }
        lastReportMillis = Time.millis();

        int hub = Groups.player.size();
        int inMatches = matches.connectedDuelPlayers();

        PluginLog.info(
                "metrics hubPlayers=@ duelPlayers=@ totalPlayers=@ activeMatches=@",
                hub, inMatches, hub + inMatches, matches.activeDuels().size()
        );
    }
}
