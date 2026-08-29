package vini.evictmap.metrics;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * One server's performance at one instant: how fast it is ticking, how hard it
 * is working, what it is carrying, and - when the profiler has something to
 * say - what the tick was actually spending itself on.
 *
 * <p>The same record on both sides of the process boundary. The hub fills it
 * from its own {@link PerfSampler}; a worker writes its own into
 * {@code status.properties} ({@link #write}) - the file the hub already polls -
 * and the hub reads it back ({@link #read}). No new IPC channel: the numbers
 * ride the file that already carries playtime and ban requests.
 *
 * <p>On the wire every key is prefixed {@code perf.}, so an older hub reading
 * a newer worker's file (or the other way round) simply finds nothing and shows
 * the row as unknown rather than failing.
 *
 * <h2>Reading the numbers</h2>
 * <ul>
 *   <li>{@code tps} is measured here rather than taken from the backend's frame
 *       counter: ticks counted over a known wall-clock window.</li>
 *   <li>{@code tickWorstMs} is the one to watch while the server is healthy. A
 *       dedicated server paces itself to 60 TPS, so at full speed the tick
 *       <em>period</em> is mostly the pacing sleep and the mean says little; a
 *       180 ms worst against a 17 ms mean is a stall, while a mean that has
 *       climbed to 28 ms with a 31 ms worst is steady overload. Two different
 *       investigations, told apart at a glance.</li>
 *   <li>{@code busyPercent} is the real load headroom: the share of profiler
 *       samples in which the main thread was running game code instead of
 *       waiting for its next tick. 12% is an idle server, 95% is one about to
 *       lose its tick rate.</li>
 *   <li>{@code hotspots} are shares <em>of the busy samples</em> - "of the time
 *       the tick was actually working, 41% of it was pathfinding". {@code
 *       subsystems} is the same window rolled up into named areas
 *       (see {@link Subsystems}), which is the form the Discord message draws
 *       as a bar chart and the form a reader can act on.</li>
 * </ul>
 */
public record PerfSnapshot(
        double tps,
        double tickMeanMs,
        double tickP95Ms,
        double tickWorstMs,
        double busyPercent,
        int units,
        int buildings,
        int bullets,
        int powerGraphs,
        int players,
        long heapUsedBytes,
        long heapMaxBytes,
        double gcPerMinute,
        double gcMillisPerMinute,
        double snapshotsPerSecond,
        List<Hotspot> hotspots,
        List<Hotspot> subsystems
) {

    /** Wire prefix, so perf keys can never collide with the status file's own. */
    private static final String PREFIX = "perf.";

    /** How many hotspots travel to the hub; the console shows the full table. */
    public static final int WIRE_HOTSPOTS = 5;

    /**
     * How many subsystems travel to the hub. More than the hotspots, because
     * this is the breakdown the message draws as a bar chart and a chart with
     * three bars and a large "Other" says nothing.
     */
    public static final int WIRE_SUBSYSTEMS = 8;

    /** One place the tick was found spending its time, as a share of busy samples. */
    public record Hotspot(String label, double percent) {
    }

    public PerfSnapshot {
        hotspots = List.copyOf(hotspots);
        subsystems = List.copyOf(subsystems);
    }

    /** Nothing measured yet - a server that has only just come up. */
    public static PerfSnapshot empty() {
        return new PerfSnapshot(
                0d, 0d, 0d, 0d, 0d,
                0, 0, 0, 0, 0,
                0L, 0L,
                0d, 0d, 0d,
                List.of(),
                List.of()
        );
    }

    /** True once the sampler has measured a tick rate worth showing. */
    public boolean hasData() {
        return tps > 0d;
    }

    /** Writes every field into the status file the hub already polls. */
    public void write(Properties properties) {
        properties.setProperty(PREFIX + "tps", format(tps));
        properties.setProperty(PREFIX + "tickMean", format(tickMeanMs));
        properties.setProperty(PREFIX + "tickP95", format(tickP95Ms));
        properties.setProperty(PREFIX + "tickWorst", format(tickWorstMs));
        properties.setProperty(PREFIX + "busy", format(busyPercent));
        properties.setProperty(PREFIX + "units", Integer.toString(units));
        properties.setProperty(PREFIX + "buildings", Integer.toString(buildings));
        properties.setProperty(PREFIX + "bullets", Integer.toString(bullets));
        properties.setProperty(PREFIX + "powerGraphs", Integer.toString(powerGraphs));
        properties.setProperty(PREFIX + "players", Integer.toString(players));
        properties.setProperty(PREFIX + "heapUsed", Long.toString(heapUsedBytes));
        properties.setProperty(PREFIX + "heapMax", Long.toString(heapMaxBytes));
        properties.setProperty(PREFIX + "gcCount", format(gcPerMinute));
        properties.setProperty(PREFIX + "gcMillis", format(gcMillisPerMinute));
        properties.setProperty(PREFIX + "snapshots", format(snapshotsPerSecond));
        properties.setProperty(PREFIX + "hot", pack(hotspots, WIRE_HOTSPOTS));
        properties.setProperty(PREFIX + "sub", pack(subsystems, WIRE_SUBSYSTEMS));
    }

    /**
     * Reads a worker's numbers back, or null when the file carries none - an
     * older worker jar, or one that has not written its first status yet.
     */
    public static PerfSnapshot read(Properties properties) {
        String tps = properties.getProperty(PREFIX + "tps");

        if (tps == null || tps.isBlank()) {
            return null;
        }

        return new PerfSnapshot(
                readDouble(properties, "tps"),
                readDouble(properties, "tickMean"),
                readDouble(properties, "tickP95"),
                readDouble(properties, "tickWorst"),
                readDouble(properties, "busy"),
                readInt(properties, "units"),
                readInt(properties, "buildings"),
                readInt(properties, "bullets"),
                readInt(properties, "powerGraphs"),
                readInt(properties, "players"),
                (long) readDouble(properties, "heapUsed"),
                (long) readDouble(properties, "heapMax"),
                readDouble(properties, "gcCount"),
                readDouble(properties, "gcMillis"),
                readDouble(properties, "snapshots"),
                unpack(properties.getProperty(PREFIX + "hot", "")),
                unpack(properties.getProperty(PREFIX + "sub", ""))
        );
    }

    /**
     * {@code label:percent} pairs separated by a pipe. Separators are cut out
     * of the labels rather than escaped: a method name never contains one, and
     * a slightly mangled label is worth less than a parser that cannot be
     * confused by one.
     */
    private static String pack(List<Hotspot> entries, int limit) {
        StringBuilder packed = new StringBuilder();
        int written = 0;

        for (Hotspot hotspot : entries) {
            if (written >= limit) {
                break;
            }

            if (packed.length() > 0) {
                packed.append('|');
            }

            packed.append(hotspot.label().replace("|", "").replace(":", "."))
                    .append(':')
                    .append(format(hotspot.percent()));
            written++;
        }

        return packed.toString();
    }

    private static List<Hotspot> unpack(String packed) {
        List<Hotspot> hotspots = new ArrayList<>();

        if (packed == null || packed.isBlank()) {
            return hotspots;
        }

        for (String entry : packed.split("\\|")) {
            int split = entry.lastIndexOf(':');

            if (split <= 0) {
                continue;
            }

            hotspots.add(new Hotspot(
                    entry.substring(0, split),
                    parseDouble(entry.substring(split + 1))
            ));
        }

        return hotspots;
    }

    private static double readDouble(Properties properties, String key) {
        return parseDouble(properties.getProperty(PREFIX + key, ""));
    }

    private static int readInt(Properties properties, String key) {
        return (int) parseDouble(properties.getProperty(PREFIX + key, ""));
    }

    private static double parseDouble(String value) {
        if (value == null || value.isBlank()) {
            return 0d;
        }

        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException exception) {
            return 0d;
        }
    }

    /**
     * One decimal, always with a dot. The file is written by one JVM and read
     * by another, and a locale-aware format would write "59,9" on one of them
     * and fail to parse on the other.
     */
    private static String format(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
