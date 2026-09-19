package Extinction.metrics;

/**
 * One row of the performance table: a server, whether it is up, its numbers,
 * and how old they are.
 *
 * <p>The age is what makes a wedged server visible. A worker that has stopped
 * ticking also stops rewriting its status file, so its numbers freeze at
 * whatever they last were and would otherwise read as perfectly healthy; the
 * age keeps counting up instead. It is measured by the hub when it reads the
 * file, not by the worker when it wrote it - two processes, two clocks, and
 * only one of them is the one rendering the table.
 *
 * <p>{@code perf} is null for a pool slot with no worker running in it, which
 * is the normal state: workers are spawned per match and exit when empty.
 */
public record ServerPerf(
        String name,
        boolean running,
        PerfSnapshot perf,
        long ageMillis
) {

    /** A pool slot with nothing running in it. */
    public static ServerPerf idle(String name) {
        return new ServerPerf(name, false, null, 0L);
    }

    /** True when there are numbers to draw. */
    public boolean hasData() {
        return perf != null && perf.hasData();
    }
}
