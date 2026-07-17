package vini.evictmap.metrics;

import arc.util.Time;
import mindustry.gen.Groups;
import vini.evictmap.core.util.PluginLog;

import java.util.function.IntSupplier;

/**
 * Periodic operational metrics for the hub: one throttled log line with the
 * numbers you actually want when diagnosing the server (hub players, players
 * inside duel workers, and how many matches are running) without tailing the
 * whole log.
 *
 * <p>Driven from the plugin's {@code Trigger.update} loop and gated by a
 * wall-clock interval so the per-frame cost is negligible.
 */
public final class MetricsReporter {

    private static final long INTERVAL_MS = 60_000L;

    private final IntSupplier activeMatches;
    private final IntSupplier playersInMatches;

    private long lastReportMillis;

    public MetricsReporter(IntSupplier activeMatches, IntSupplier playersInMatches) {
        this.activeMatches = activeMatches;
        this.playersInMatches = playersInMatches;
    }

    public void update() {
        if (Time.timeSinceMillis(lastReportMillis) < INTERVAL_MS) {
            return;
        }
        lastReportMillis = Time.millis();

        int hub = Groups.player.size();
        int inMatches = playersInMatches.getAsInt();

        PluginLog.info(
                "metrics hubPlayers=@ duelPlayers=@ totalPlayers=@ activeMatches=@",
                hub, inMatches, hub + inMatches, activeMatches.getAsInt()
        );
    }
}
