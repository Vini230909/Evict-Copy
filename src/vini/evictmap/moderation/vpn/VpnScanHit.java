package vini.evictmap.moderation.vpn;

import arc.util.Strings;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * One join that came through a VPN or proxy: who, from where, what the lookup
 * said, and how old the account is.
 *
 * <p>The account facts are the point of the whole scan. The lookup alone only
 * says "this address is a VPN"; whether that is a regular who likes their
 * privacy or a fresh account that appeared right after a ban is what the
 * reader has to decide, so the line puts first-seen, playtime and join count
 * next to the verdict. A rule can be drawn from a week of such lines; it
 * cannot be drawn from the verdicts alone.
 *
 * @param firstSeenMillis when the plugin's database first saw the account, or
 *                        0 when it has no row for it yet
 * @param timesJoined     Mindustry's own join count for the account, this
 *                        join included
 */
public record VpnScanHit(
        String name,
        String uuid,
        String ip,
        VpnVerdict verdict,
        boolean cached,
        long firstSeenMillis,
        long playtimeMillis,
        int timesJoined
) {

    private static final DateTimeFormatter CONSOLE_TIME =
            DateTimeFormatter.ofPattern("MM-dd-yyyy HH:mm");

    /** The plain name, colour tags stripped, as the console shows names. */
    public String plainName() {
        String plain = Strings.stripColors(name == null ? "" : name).trim();
        return plain.isEmpty() ? "(unnamed)" : plain;
    }

    /** True when the plugin's database has never stored this account. */
    public boolean unknownAccount() {
        return firstSeenMillis <= 0L;
    }

    /** {@code 1 join} / {@code 12 joins}. */
    public String joins() {
        return timesJoined + (timesJoined == 1 ? " join" : " joins");
    }

    /** The console's one line, everything in it plain text. */
    public String consoleLine() {
        return "VPN scan: " + plainName()
                + " (" + uuid + ") from " + ip
                + " - " + verdict.flags()
                + " - " + verdict.network()
                + " - account: " + consoleAccount()
                + (cached ? " - cached verdict" : "")
                + " - log only, nothing was done";
    }

    private String consoleAccount() {
        if (unknownAccount()) {
            return "not stored yet, " + joins();
        }

        return "first seen "
                + CONSOLE_TIME.format(LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(firstSeenMillis),
                        ZoneId.systemDefault()
                ))
                + ", played " + playtime()
                + ", " + joins();
    }

    /** {@code 0 min} / {@code 45 min} / {@code 12 h 3 min}. */
    public String playtime() {
        long minutes = Math.max(0L, playtimeMillis) / 60_000L;

        if (minutes < 60L) {
            return minutes + " min";
        }

        return (minutes / 60L) + " h " + (minutes % 60L) + " min";
    }
}
