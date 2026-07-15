package vini.evictmap.core.util;

/**
 * Tick/time conversions. Mindustry's simulation runs at 60 ticks per second, so
 * {@code Time.run(Ticks.seconds(5), ...)} reads better than a bare {@code 300f}
 * or an inline {@code 5 * 60f}.
 */
public final class Ticks {

    public static final float PER_SECOND = 60f;

    private Ticks() {
    }

    public static float seconds(double seconds) {
        return (float) (seconds * PER_SECOND);
    }

    public static float minutes(double minutes) {
        return seconds(minutes * 60d);
    }

    /** Converts a tick duration back to whole seconds. */
    public static long toSeconds(float ticks) {
        return (long) (ticks / PER_SECOND);
    }
}
