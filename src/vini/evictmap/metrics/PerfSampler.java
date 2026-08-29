package vini.evictmap.metrics;

import mindustry.gen.Groups;
import mindustry.net.NetConnection;
import vini.evictmap.core.util.PluginLog;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

/**
 * Measures what a server is doing to itself: tick rate and tick-period spread,
 * how much of the tick is real work, what the world is carrying, how much heap
 * and garbage collection it costs, and how much of it is going out over the
 * network.
 *
 * <p>Runs on the hub and on every match server - it touches no gameplay, only
 * the game's own groups - which is what makes one Discord table able to show
 * all of them side by side. The hub renders its own snapshot directly; a
 * worker's rides {@code status.properties} to the hub (see {@link
 * PerfSnapshot}).
 *
 * <p>{@link #update()} is called from the plugin's per-frame trigger and costs
 * one {@code nanoTime} plus an array write. Everything expensive - percentiles,
 * garbage-collector beans, the profile - happens in {@link #snapshot()}, which
 * runs once a second at most, and the rate figures are recomputed no more often
 * than that however often a snapshot is asked for.
 *
 * <p>Main thread only: it reads the entity groups. The profiler thread it owns
 * is the one exception, and it only ever reads stack traces.
 */
public final class PerfSampler {

    /**
     * Tick periods kept for the mean/p95/worst. 600 ticks is ten seconds at
     * full speed and half a minute on a server that has already lost its tick
     * rate - in both cases a window that describes now, not five minutes ago.
     */
    private static final int PERIOD_WINDOW = 600;

    /** A tick period above this is a stall, not a slow tick, and is capped. */
    private static final double MAX_PERIOD_MILLIS = 60_000d;

    /** Shortest gap between two recomputes of the GC and network rates. */
    private static final long RATE_WINDOW_MILLIS = 1_000L;

    /** Hotspots kept in a snapshot; the console prints the full table. */
    private static final int SNAPSHOT_HOTSPOTS = 5;

    /**
     * The network counter, if this Mindustry build has one. Static and resolved
     * once for the whole process - the hub and a worker each have one sampler,
     * but the answer is a property of the jar, not of the sampler.
     */
    private static Field snapshotsSent;
    private static boolean snapshotsSentResolved;

    private final StackSampler profiler;

    /** Tick periods in milliseconds, oldest overwritten first. */
    private final double[] periods = new double[PERIOD_WINDOW];
    private int nextPeriod;
    private int filledPeriods;

    private long lastTickNanos;

    /** Set once a capture has failed, so the log carries it exactly once. */
    private boolean captureFailed;

    /** Baselines for the rate figures, and the numbers last computed from them. */
    private long rateWindowStartMillis;
    private long lastGcCount;
    private long lastGcMillis;
    private long lastSnapshotsSent;
    private double gcPerMinute;
    private double gcMillisPerMinute;
    private double snapshotsPerSecond;

    /**
     * @param gameThread the thread the game loop runs on - pass
     *                   {@code Thread.currentThread()} from the main thread,
     *                   never a thread looked up by name
     */
    public PerfSampler(Thread gameThread) {
        this.profiler = new StackSampler(gameThread);
    }

    /** Starts the profiler thread. Idempotent. */
    public void start() {
        profiler.start();
        rateWindowStartMillis = System.currentTimeMillis();
        lastGcCount = totalGcCount();
        lastGcMillis = totalGcMillis();
        lastSnapshotsSent = totalSnapshotsSent();
    }

    /** One tick. Records how long it has been since the previous one. */
    public void update() {
        long now = System.nanoTime();

        if (lastTickNanos != 0L) {
            double millis = (now - lastTickNanos) / 1_000_000d;

            periods[nextPeriod] = Math.min(millis, MAX_PERIOD_MILLIS);
            nextPeriod = (nextPeriod + 1) % periods.length;

            if (filledPeriods < periods.length) {
                filledPeriods++;
            }
        }

        lastTickNanos = now;
    }

    /** Whether the stack profiler is currently sampling. */
    public boolean isProfiling() {
        return profiler.isEnabled();
    }

    /** Switches the stack profiler on or off; the rest keeps measuring either way. */
    public void setProfiling(boolean enabled) {
        profiler.setEnabled(enabled);
    }

    /** How many profiler samples the current window holds. */
    public int profileSamples() {
        return profiler.sampleCount();
    }

    /** The full profile, largest share of busy time first - for the console. */
    public List<PerfSnapshot.Hotspot> profile(int limit) {
        return profiler.hotspots(limit);
    }

    /** The same window rolled up into named subsystems - for the console. */
    public List<PerfSnapshot.Hotspot> subsystemProfile(int limit) {
        return profiler.subsystems(limit);
    }

    /**
     * Everything measurable about this server right now - and never an
     * exception, whatever happens inside.
     *
     * <p>This is called from the hub's game loop, from a worker's status write
     * and from the console, and it reads game internals that differ between
     * Mindustry builds. One of them differing threw an {@link Error} onto the
     * game loop and put a live server into a restart loop; a diagnostic that
     * can do that is worse than no diagnostic. A failure here reports no data,
     * which shows up as a server with nothing to say rather than as a lie.
     */
    public PerfSnapshot snapshot() {
        try {
            return capture();
        } catch (Throwable error) {
            if (!captureFailed) {
                captureFailed = true;
                PluginLog.err(
                        "Performance sampling failed and is reporting no data; "
                                + "the server itself is unaffected.",
                        error
                );
            }

            return PerfSnapshot.empty();
        }
    }

    private PerfSnapshot capture() {
        refreshRates();

        double[] sorted = sortedPeriods();
        double mean = mean(sorted);
        Runtime runtime = Runtime.getRuntime();

        return new PerfSnapshot(
                mean <= 0d ? 0d : 1_000d / mean,
                mean,
                percentile(sorted, 0.95d),
                sorted.length == 0 ? 0d : sorted[sorted.length - 1],
                profiler.busyPercent(),
                Groups.unit.size(),
                Groups.build.size(),
                Groups.bullet.size(),
                Groups.powerGraph.size(),
                Groups.player.size(),
                runtime.totalMemory() - runtime.freeMemory(),
                runtime.maxMemory(),
                gcPerMinute,
                gcMillisPerMinute,
                snapshotsPerSecond,
                profiler.hotspots(SNAPSHOT_HOTSPOTS),
                profiler.subsystems(PerfSnapshot.WIRE_SUBSYSTEMS)
        );
    }

    /**
     * Turns the garbage collector and network counters into rates. Both are
     * running totals, so they only mean something as a difference over a known
     * stretch of time; recomputing them on every call would divide by a few
     * milliseconds and produce noise, so a call inside the window reuses what
     * the last one worked out.
     */
    private void refreshRates() {
        long now = System.currentTimeMillis();
        long elapsed = now - rateWindowStartMillis;

        if (elapsed < RATE_WINDOW_MILLIS) {
            return;
        }

        long gcCount = totalGcCount();
        long gcMillis = totalGcMillis();
        long snapshots = totalSnapshotsSent();

        gcPerMinute = 60_000d * Math.max(0L, gcCount - lastGcCount) / elapsed;
        gcMillisPerMinute = 60_000d * Math.max(0L, gcMillis - lastGcMillis) / elapsed;
        snapshotsPerSecond =
                1_000d * Math.max(0L, snapshots - lastSnapshotsSent) / elapsed;

        rateWindowStartMillis = now;
        lastGcCount = gcCount;
        lastGcMillis = gcMillis;
        lastSnapshotsSent = snapshots;
    }

    private double[] sortedPeriods() {
        double[] copy = Arrays.copyOf(periods, filledPeriods);
        Arrays.sort(copy);
        return copy;
    }

    private static double mean(double[] values) {
        if (values.length == 0) {
            return 0d;
        }

        double total = 0d;

        for (double value : values) {
            total += value;
        }

        return total / values.length;
    }

    private static double percentile(double[] sorted, double fraction) {
        if (sorted.length == 0) {
            return 0d;
        }

        int index = (int) Math.ceil(fraction * sorted.length) - 1;

        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    private static long totalGcCount() {
        long total = 0L;

        for (GarbageCollectorMXBean bean
                : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = bean.getCollectionCount();

            if (count > 0L) {
                total += count;
            }
        }

        return total;
    }

    private static long totalGcMillis() {
        long total = 0L;

        for (GarbageCollectorMXBean bean
                : ManagementFactory.getGarbageCollectorMXBeans()) {
            long millis = bean.getCollectionTime();

            if (millis > 0L) {
                total += millis;
            }
        }

        return total;
    }

    /**
     * Snapshots sent to the connected players, summed. Arc exposes no byte
     * counters, so this is the closest honest measure of what the server is
     * pushing out - and the world stream is the failure mode this server has
     * actually hit before (see the Extinction wave's pacing).
     *
     * <p>Read by reflection, and that is not defensiveness for its own sake:
     * {@code NetConnection.snapshotsSent} exists in the Mindustry jar this
     * plugin compiles against but not in every server build it is deployed on,
     * and a direct field access against a build without it throws
     * {@link NoSuchFieldError} on the game loop - which took the server down
     * once already. Looked up once; when it is not there the figure is simply
     * reported as zero and everything else keeps working.
     */
    private static long totalSnapshotsSent() {
        Field field = snapshotsSentField();

        if (field == null) {
            return 0L;
        }

        long[] total = {0L};

        try {
            Groups.player.each(player -> {
                NetConnection connection = player == null ? null : player.con;

                if (connection == null) {
                    return;
                }

                try {
                    total[0] += Math.max(0, field.getInt(connection));
                } catch (IllegalAccessException ignored) {
                    // Checked when the field was resolved; nothing to do here.
                }
            });
        } catch (Throwable error) {
            // Throwable, not Exception: a measurement must never be able to
            // stop the server it is measuring, and the failure that did stop it
            // was an Error. The console asks for a snapshot from its own
            // thread too, so this walk can race a player joining or leaving.
            return 0L;
        }

        return total[0];
    }

    /**
     * The counter field if this Mindustry build has one, resolved once. The
     * {@link #snapshotsSentResolved} flag is what makes it once: a missing
     * field must not be looked up again every second forever.
     */
    private static Field snapshotsSentField() {
        if (snapshotsSentResolved) {
            return snapshotsSent;
        }

        snapshotsSentResolved = true;

        try {
            Field field = NetConnection.class.getField("snapshotsSent");

            if (field.getType() == int.class) {
                snapshotsSent = field;
            }
        } catch (Throwable error) {
            PluginLog.info(
                    "This Mindustry build has no NetConnection.snapshotsSent; "
                            + "the performance reports will show 0 snapshots/s."
            );
        }

        return snapshotsSent;
    }
}
