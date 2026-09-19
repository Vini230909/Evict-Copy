package Extinction.discord;

import Extinction.metrics.PerfSnapshot;
import Extinction.metrics.ServerPerf;
import Extinction.metrics.TpsHistory;

import java.util.List;
import java.util.Locale;

/**
 * Renders one server's performance into its own Discord message: a tick-rate
 * trend line, the numbers grouped by what they describe, and a bar chart of
 * where the tick time actually went.
 *
 * <p>One message per server rather than one shared table. A table row has room
 * for numbers and nothing else, and numbers alone do not answer the question
 * this channel exists for - a message has room for the trend and the breakdown,
 * which is where the answer is.
 *
 * <p>A server that is not running gets a single short line instead. There is
 * nothing to measure in an empty match slot, and drawing it as a full but empty
 * report would suggest otherwise; it also keeps that message out of the send
 * budget, since its text only changes when the slot does.
 *
 * <p>Everything that has to line up - the sparkline, the bars, the columns -
 * lives inside a code block, which is the only way Discord renders monospace on
 * every client. Nothing in it is player-supplied.
 *
 * <p>Pure string building, no I/O and no Mindustry state, so it runs on
 * whichever thread the sender is on.
 */
final class PerfMessage {

    /** Discord green / gold / red. */
    private static final long COLOR_HEALTHY = 0x57F287L;
    private static final long COLOR_DEGRADED = 0xFEE75CL;
    private static final long COLOR_SEVERE = 0xED4245L;

    /** Below this the server is no longer keeping up. */
    private static final double TPS_DEGRADED = 55d;

    /** Below this it is bad enough to shout about. */
    private static final double TPS_SEVERE = 45d;

    /** What a full-height sparkline column means. */
    private static final double TPS_TARGET = 60d;

    /** Sparkline width in characters. */
    private static final int SPARK_WIDTH = 40;

    /** Bar chart width in characters. */
    private static final int BAR_WIDTH = 16;

    /** Subsystems drawn as bars; whatever is left over is folded into one row. */
    private static final int BARS = 6;

    /** Discord's hard limit on an embed description. */
    private static final int MAX_DESCRIPTION = 4096;

    /** Eight levels of height, for the trend line. */
    private static final char[] SPARK = {
            '▁', '▂', '▃', '▄',
            '▅', '▆', '▇', '█'
    };

    /** Eighths of a cell, so a bar can end between two characters. */
    private static final char[] PARTIAL = {
            ' ', '▏', '▎', '▍',
            '▌', '▋', '▊', '▉'
    };

    private static final String FULL_BLOCK = "█";

    private PerfMessage() {
    }

    /**
     * One server's message.
     *
     * <p>Both {@code content} and {@code embeds} are always set, even when one
     * of them is empty: the same message flips between a full report and a
     * one-line offline notice as its worker comes and goes, and an edit that
     * omitted a field would leave the previous state's half standing underneath
     * the new one.
     *
     * <p>{@code allowed_mentions.parse} is empty for the same reason it is on
     * every other payload here: this one carries no player names today, and the
     * guarantee should not depend on that staying true.
     */
    static String payload(
            ServerPerf server,
            TpsHistory history,
            long timestampSeconds
    ) {
        DiscordJson.Obj payload = new DiscordJson.Obj()
                .raw("allowed_mentions", "{\"parse\":[]}");

        if (!server.hasData()) {
            return payload
                    .str("content", shortLine(server))
                    .raw("embeds", "[]")
                    .toString();
        }

        DiscordJson.Arr embeds = new DiscordJson.Arr();
        embeds.add(report(server, history, timestampSeconds));

        return payload
                .str("content", "")
                .raw("embeds", embeds.toString())
                .toString();
    }

    /** The whole message for a slot with nothing running in it. */
    private static String shortLine(ServerPerf server) {
        return server.running()
                ? "🟡 `" + server.name() + "` — starting up"
                : "⚫ `" + server.name() + "` — offline";
    }

    private static DiscordJson.Obj report(
            ServerPerf server,
            TpsHistory history,
            long timestampSeconds
    ) {
        PerfSnapshot perf = server.perf();
        StringBuilder body = new StringBuilder();

        body.append("```\n")
                .append(sparkline(history, perf.tps()))
                .append('\n')
                .append(numbers(perf))
                .append('\n')
                .append(breakdown(perf))
                .append("```");

        StringBuilder footer = new StringBuilder("Updated ")
                .append(DiscordFormat.relativeTimestamp(timestampSeconds));

        if (server.ageMillis() >= 5_000L) {
            // Only worth saying when it is several polls old: a number that is
            // always there stops being read, and this one is a warning.
            footer.append(" · data ")
                    .append(server.ageMillis() / 1000L)
                    .append("s old");
        }

        body.append('\n').append(footer);

        return new DiscordJson.Obj()
                .str("title", title(server, perf))
                .str("description", DiscordFormat.truncate(
                        body.toString(),
                        MAX_DESCRIPTION
                ))
                .num("color", color(perf.tps()));
    }

    private static String title(ServerPerf server, PerfSnapshot perf) {
        String light = perf.tps() < TPS_SEVERE
                ? "🔴"
                : perf.tps() < TPS_DEGRADED ? "🟡" : "🟢";

        return String.format(
                Locale.ROOT,
                "%s %s — %.1f TPS",
                light,
                server.name(),
                perf.tps()
        );
    }

    /**
     * The tick rate over the last few minutes, on a fixed 0-60 scale rather
     * than an automatic one: a flat healthy line has to look flat. Each column
     * is the <em>lowest</em> reading in its slice of time, so a dip survives
     * being squeezed into 40 characters - showing the moment things went wrong
     * is the whole point of the line.
     */
    private static String sparkline(TpsHistory history, double currentTps) {
        double[] values = history.values();

        if (values.length < 2) {
            return String.format(
                    Locale.ROOT,
                    "TPS  %.0f now  (trend still filling)\n",
                    currentTps
            );
        }

        StringBuilder line = new StringBuilder("TPS  ");
        int columns = Math.min(SPARK_WIDTH, values.length);
        int perColumn = values.length / columns;

        for (int column = 0; column < columns; column++) {
            int from = column * perColumn;
            int to = column == columns - 1 ? values.length : from + perColumn;
            double lowest = Double.MAX_VALUE;

            for (int index = from; index < to; index++) {
                lowest = Math.min(lowest, values[index]);
            }

            line.append(spark(lowest));
        }

        return line.append(String.format(
                Locale.ROOT,
                "  %.0f now · %.0f low · last %s\n",
                currentTps,
                history.min(),
                span(history.spanSeconds())
        )).toString();
    }

    /** The numbers, grouped by what they describe rather than in one run-on row. */
    private static String numbers(PerfSnapshot perf) {
        StringBuilder numbers = new StringBuilder();

        numbers.append(String.format(
                Locale.ROOT,
                "Tick     mean %.1fms · p95 %.0fms · worst %.0fms · busy %.0f%%\n",
                perf.tickMeanMs(),
                perf.tickP95Ms(),
                perf.tickWorstMs(),
                perf.busyPercent()
        ));

        numbers.append(String.format(
                Locale.ROOT,
                "Load     %s units · %s buildings · %s bullets · %d players\n",
                count(perf.units()),
                count(perf.buildings()),
                count(perf.bullets()),
                perf.players()
        ));

        numbers.append(String.format(
                Locale.ROOT,
                "Memory   %.1f / %.1f GB · GC %.0f/min, %.0fms/min\n",
                perf.heapUsedBytes() / (1024d * 1024d * 1024d),
                perf.heapMaxBytes() / (1024d * 1024d * 1024d),
                perf.gcPerMinute(),
                perf.gcMillisPerMinute()
        ));

        numbers.append(String.format(
                Locale.ROOT,
                "Network  %.0f snapshots/s · %d power graphs\n",
                perf.snapshotsPerSecond(),
                perf.powerGraphs()
        ));

        return numbers.toString();
    }

    /**
     * Where the tick time went, as bars. Shares are of the time the server was
     * actually working, not of the wall clock - on a healthy server most of the
     * wall clock is the pacing sleep, and a chart that counted it would read 90%
     * idle and say nothing.
     */
    private static String breakdown(PerfSnapshot perf) {
        List<PerfSnapshot.Hotspot> subsystems = perf.subsystems();

        if (subsystems.isEmpty()) {
            return "Where the tick went   (profiler off, or still filling)\n";
        }

        StringBuilder chart = new StringBuilder("Where the tick went, last 60s\n");
        double charted = 0d;
        int drawn = 0;

        for (PerfSnapshot.Hotspot subsystem : subsystems) {
            if (drawn >= BARS) {
                break;
            }

            chart.append(row(subsystem.label(), subsystem.percent()));
            charted += subsystem.percent();
            drawn++;
        }

        double rest = 100d - charted;

        if (rest >= 1d) {
            chart.append(row("everything else", rest));
        }

        return chart.toString();
    }

    private static String row(String label, double percent) {
        return String.format(
                Locale.ROOT,
                "  %-15s %s %3.0f%%\n",
                DiscordFormat.truncate(label, 15),
                bar(percent, BAR_WIDTH),
                percent
        );
    }

    /** A percentage as a bar, to an eighth of a character. */
    private static String bar(double percent, int width) {
        double clamped = Math.max(0d, Math.min(100d, percent));
        double cells = clamped / 100d * width;
        int full = (int) cells;
        int eighths = (int) Math.round((cells - full) * 8d);

        if (eighths == 8) {
            full++;
            eighths = 0;
        }

        StringBuilder drawn = new StringBuilder(width);
        drawn.append(FULL_BLOCK.repeat(Math.min(full, width)));

        if (drawn.length() < width && eighths > 0) {
            drawn.append(PARTIAL[eighths]);
        }

        while (drawn.length() < width) {
            drawn.append(' ');
        }

        return drawn.toString();
    }

    private static char spark(double tps) {
        double fraction = Math.max(0d, Math.min(1d, tps / TPS_TARGET));
        int level = (int) Math.round(fraction * (SPARK.length - 1));

        return SPARK[Math.max(0, Math.min(SPARK.length - 1, level))];
    }

    private static String span(int seconds) {
        return seconds >= 120 ? (seconds / 60) + "m" : seconds + "s";
    }

    private static long color(double tps) {
        if (tps < TPS_SEVERE) {
            return COLOR_SEVERE;
        }

        return tps < TPS_DEGRADED ? COLOR_DEGRADED : COLOR_HEALTHY;
    }

    /** Thousands as {@code 12.4k}, so a wide count never breaks the line. */
    private static String count(int value) {
        if (value < 10_000) {
            return Integer.toString(value);
        }

        return String.format(Locale.ROOT, "%.1fk", value / 1000d);
    }
}
