package vini.evictmap.moderation;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Who decided a ban, on which server, and when its console line was written.
 *
 * <p>The timestamp is deliberately the console's own format
 * ({@code MM-dd-yyyy HH:mm:ss}, the server's local time) rather than a Discord
 * timestamp: the point of carrying it into the ban log is to be able to open
 * the matching log afterwards and find the incident by searching for that time.
 * {@code server} says which log that is - the hub's, or one match server's.
 *
 * <p>Plain data with no Mindustry types: it travels from a worker's status file
 * to the hub, and from the hub to Discord on another thread.
 */
public record BanOrigin(String actor, String server, String consoleTime) {

    /** The word filter is not a person, but it is an actor like any other. */
    public static final String WORD_FILTER_ACTOR = "the word filter";

    /** Shown when a match server forwards a ban nobody signed. */
    public static final String UNKNOWN_ADMIN = "an admin";

    public static final String HUB = "the hub";

    private static final DateTimeFormatter CONSOLE_TIME =
            DateTimeFormatter.ofPattern("MM-dd-yyyy HH:mm:ss");

    /** Stamped the moment the ban is decided, on the server deciding it. */
    public static BanOrigin now(String actor, String server) {
        return new BanOrigin(
                actor == null || actor.isBlank() ? UNKNOWN_ADMIN : actor,
                server,
                CONSOLE_TIME.format(LocalDateTime.now())
        );
    }

    /** Rebuilds what a match server published, tagged with its own log. */
    public static BanOrigin fromWorker(String actor, int port, String consoleTime) {
        return new BanOrigin(
                actor == null || actor.isBlank() ? UNKNOWN_ADMIN : actor,
                matchServer(port),
                consoleTime == null || consoleTime.isBlank()
                        ? CONSOLE_TIME.format(LocalDateTime.now())
                        : consoleTime
        );
    }

    /** The label for one match server's console log. */
    public static String matchServer(int port) {
        return "match server on port " + port;
    }

    /** True when this ban was decided somewhere other than the hub. */
    public boolean fromMatchServer() {
        return !HUB.equals(server);
    }
}
