package vini.evictmap.metrics;

import java.util.Arrays;

/**
 * A few minutes of one server's tick rate, so its message can show a trend and
 * not just a number.
 *
 * <p>A single TPS reading says a server is slow; a line says whether it is
 * falling, holding or coming back, which is the difference between "look now"
 * and "it already recovered". The reading is a percentage of a target, so the
 * shape carries almost all of the meaning and a handful of characters is enough
 * to draw it.
 *
 * <p>Kept on the hub for every server, including the workers: the hub already
 * receives a fresh reading from each of them roughly once a second, so the
 * history costs no wire format of its own and works the same for a process that
 * cannot keep one (a worker lives for one match).
 *
 * <p>Not synchronised: filled and read on the main thread only.
 */
public final class TpsHistory {

    /** Readings kept. At roughly one a second this is about five minutes. */
    private static final int WINDOW = 300;

    /** Shortest gap between two kept readings, so a burst cannot flood it. */
    private static final long MIN_GAP_MILLIS = 900L;

    private final double[] values = new double[WINDOW];
    private int next;
    private int filled;
    private long lastRecordMillis;

    /**
     * Adds a reading, unless one was taken a moment ago. The gap check is what
     * keeps the window a fixed span of time rather than a fixed number of
     * refreshes, which would stretch and shrink with the send pacing.
     */
    public void record(double tps, long nowMillis) {
        if (nowMillis - lastRecordMillis < MIN_GAP_MILLIS) {
            return;
        }

        lastRecordMillis = nowMillis;
        values[next] = tps;
        next = (next + 1) % values.length;

        if (filled < values.length) {
            filled++;
        }
    }

    /** Drops the history - a slot whose worker has gone. */
    public void clear() {
        next = 0;
        filled = 0;
        lastRecordMillis = 0L;
    }

    public boolean isEmpty() {
        return filled == 0;
    }

    /**
     * The readings, oldest first. A copy, because the caller renders it while
     * the next tick may already be writing.
     */
    public double[] values() {
        double[] ordered = new double[filled];

        for (int index = 0; index < filled; index++) {
            // The ring is only partly filled until it wraps; before that the
            // oldest reading sits at 0, afterwards it sits just past the head.
            int source = filled < values.length
                    ? index
                    : (next + index) % values.length;

            ordered[index] = values[source];
        }

        return ordered;
    }

    /** The lowest reading in the window - the worst moment the line shows. */
    public double min() {
        double[] ordered = values();

        return ordered.length == 0
                ? 0d
                : Arrays.stream(ordered).min().orElse(0d);
    }

    /** How many seconds of history there is, roughly, for the caption. */
    public int spanSeconds() {
        return filled;
    }
}
