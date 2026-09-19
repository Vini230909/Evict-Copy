package Extinction.metrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Answers the question the counters cannot: <em>what</em> is the tick spending
 * itself on. A daemon thread takes the game thread's stack trace a few dozen
 * times a second and folds each one down to a single label, so a minute of
 * samples adds up to a share per code path - "41% of the busy time was in
 * pathfinding" - without a profiler attached to a live server.
 *
 * <p>Counters tell you a server is carrying 400 units and 12k buildings. They
 * do not tell you whether the tick is going into unit AI, power graphs, block
 * updates or the plugin's own code, and on a modded PvP server those are very
 * different problems with very different fixes. This is the part that names it.
 *
 * <h2>Idle samples are not hotspots</h2>
 * A dedicated server paces itself to 60 TPS, so most of the wall clock is the
 * game thread waiting for its next tick. Counting those samples would bury
 * every real hotspot under the sleep that follows it, so a sample where the
 * thread is not runnable - or is sitting in a sleep/park/wait frame - is
 * counted as idle and kept out of the shares. What is left over is
 * {@link #busyPercent()}, which is itself the load-headroom number: the share
 * of the time the tick was genuinely working.
 *
 * <h2>Cost</h2>
 * {@code Thread.getStackTrace()} on another thread is not free, which is why
 * this samples at {@value #SAMPLE_INTERVAL_MILLIS} ms rather than continuously,
 * and why it can be switched off ({@code evictperf profile off}) on a server
 * where every last percent matters. Samples live in a fixed ring covering the
 * last minute, so the profile is always recent and never grows.
 */
public final class StackSampler {

    /** Gap between two stack traces of the game thread. */
    private static final long SAMPLE_INTERVAL_MILLIS = 50L;

    /** How much history the ring holds: one minute at the sample interval. */
    private static final int WINDOW_SAMPLES =
            (int) (60_000L / SAMPLE_INTERVAL_MILLIS);

    /** How long the thread waits between checks while switched off. */
    private static final long DISABLED_POLL_MILLIS = 500L;

    /** Stand-in label for a sample in which the tick was not working. */
    private static final String IDLE = "";

    /** Packages whose frames are worth naming; anything else is scaffolding. */
    private static final String[] INTERESTING_PACKAGES = {
            "mindustry.",
            "Extinction.",
    };

    /** Frames that mean "waiting", whatever the thread state claims. */
    private static final String[] WAITING_METHODS = {
            "java.lang.Thread.sleep",
            "java.lang.Thread.onSpinWait",
            "java.lang.Object.wait",
            "jdk.internal.misc.Unsafe.park",
            "sun.misc.Unsafe.park",
            "java.net.SocketInputStream.socketRead",
            "sun.nio.ch.Net.poll",
    };

    private final Thread gameThread;

    /**
     * The last minute of samples, oldest overwritten first. Written by the
     * sampler thread and read by whoever asks for a profile, both under this
     * object's own lock - the ring is small and the reader is rare.
     *
     * <p>Two rings, one index: every sample is counted twice over, once as the
     * method it was in and once as the subsystem that method belongs to. Both
     * views are wanted - the subsystem is what a reader can act on, the method
     * is what someone fixing it needs - and classifying at sample time costs
     * one pass over a stack that has already been walked.
     */
    private final String[] ring = new String[WINDOW_SAMPLES];
    private final String[] subsystemRing = new String[WINDOW_SAMPLES];
    private int nextIndex;
    private int filled;

    private volatile boolean enabled = true;
    private volatile boolean running;
    private Thread thread;

    /**
     * @param gameThread the thread the game loop runs on; capture it from the
     *                   main thread itself, never guess it by name
     */
    public StackSampler(Thread gameThread) {
        this.gameThread = gameThread;
    }

    /** Starts the sampling thread; a second call does nothing. */
    public synchronized void start() {
        if (running) {
            return;
        }

        running = true;
        thread = new Thread(this::loop, "evict-perf-sampler");
        thread.setDaemon(true);
        // Below the game loop on purpose: a profiler must never take the
        // scheduler's attention away from the thing it is measuring.
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Switches sampling on or off at runtime. Switching off clears the ring:
     * a profile stitched together across a gap would describe two different
     * moments as if they were one.
     */
    public void setEnabled(boolean value) {
        enabled = value;

        if (!value) {
            synchronized (this) {
                nextIndex = 0;
                filled = 0;
            }
        }
    }

    /** How many samples the current window holds. */
    public synchronized int sampleCount() {
        return filled;
    }

    /**
     * Share of the window in which the game thread was actually running game
     * code, 0 when nothing has been sampled yet.
     */
    public synchronized double busyPercent() {
        if (filled == 0) {
            return 0d;
        }

        int busy = 0;

        for (int index = 0; index < filled; index++) {
            if (!IDLE.equals(ring[index])) {
                busy++;
            }
        }

        return 100d * busy / filled;
    }

    /**
     * The busiest code paths in the window, largest share first, as percentages
     * of the busy samples. Empty while the server is idle or the profiler is
     * off.
     */
    public synchronized List<PerfSnapshot.Hotspot> hotspots(int limit) {
        return tally(ring, limit);
    }

    /**
     * The same window rolled up into named subsystems - pathfinding, units,
     * blocks, the plugin - which is the form a reader can act on without
     * knowing the codebase.
     */
    public synchronized List<PerfSnapshot.Hotspot> subsystems(int limit) {
        return tally(subsystemRing, limit);
    }

    private List<PerfSnapshot.Hotspot> tally(String[] samples, int limit) {
        Map<String, Integer> counts = new HashMap<>();
        int busy = 0;

        for (int index = 0; index < filled; index++) {
            String label = samples[index];

            if (label == null || IDLE.equals(label)) {
                continue;
            }

            busy++;
            counts.merge(label, 1, Integer::sum);
        }

        if (busy == 0) {
            return List.of();
        }

        List<PerfSnapshot.Hotspot> hotspots = new ArrayList<>(counts.size());

        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            hotspots.add(new PerfSnapshot.Hotspot(
                    entry.getKey(),
                    100d * entry.getValue() / busy
            ));
        }

        hotspots.sort(Comparator
                .comparingDouble(PerfSnapshot.Hotspot::percent)
                .reversed());

        return hotspots.size() <= limit
                ? List.copyOf(hotspots)
                : List.copyOf(hotspots.subList(0, limit));
    }

    private void loop() {
        while (running) {
            try {
                if (!enabled) {
                    Thread.sleep(DISABLED_POLL_MILLIS);
                    continue;
                }

                // One stack per sample, classified twice: the state has to be
                // read beside the trace it belongs to, not after it.
                Thread.State state = gameThread.getState();
                StackTraceElement[] stack = gameThread.getStackTrace();
                String label = label(state, stack);

                record(label, IDLE.equals(label)
                        ? IDLE
                        : Subsystems.classify(stack));

                Thread.sleep(SAMPLE_INTERVAL_MILLIS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception exception) {
                // A sampler must never be the thing that takes a server down.
                // A stack trace that could not be taken is one lost sample.
            }
        }
    }

    private synchronized void record(String label, String subsystem) {
        ring[nextIndex] = label;
        subsystemRing[nextIndex] = subsystem;
        nextIndex = (nextIndex + 1) % ring.length;

        if (filled < ring.length) {
            filled++;
        }
    }

    /**
     * Folds one stack trace down to the label it counts under: the topmost
     * frame that belongs to the game or the plugin, which is the deepest point
     * that means anything to a reader. A stack with no such frame at all is
     * labelled by its own top frame, so an unexpected hotspot in a library is
     * still visible rather than silently dropped.
     */
    private static String label(Thread.State state, StackTraceElement[] stack) {
        if (stack.length == 0) {
            return IDLE;
        }

        if (state != Thread.State.RUNNABLE || isWaiting(stack)) {
            return IDLE;
        }

        for (StackTraceElement frame : stack) {
            if (isInteresting(frame.getClassName())) {
                return shortName(frame);
            }
        }

        return shortName(stack[0]);
    }

    /**
     * A runnable thread parked in a socket read or a sleep is still waiting -
     * the state alone would count the pacing sleep as work and drown every
     * real hotspot in it.
     */
    private static boolean isWaiting(StackTraceElement[] stack) {
        int depth = Math.min(stack.length, 4);

        for (int index = 0; index < depth; index++) {
            String frame = stack[index].getClassName()
                    + "." + stack[index].getMethodName();

            for (String waiting : WAITING_METHODS) {
                if (frame.startsWith(waiting)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean isInteresting(String className) {
        for (String prefix : INTERESTING_PACKAGES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }

        return false;
    }

    /** {@code Pathfinder.updateFrontier} - class without its package, method. */
    private static String shortName(StackTraceElement frame) {
        String className = frame.getClassName();
        int lastDot = className.lastIndexOf('.');

        if (lastDot >= 0) {
            className = className.substring(lastDot + 1);
        }

        return className + "." + frame.getMethodName();
    }
}
