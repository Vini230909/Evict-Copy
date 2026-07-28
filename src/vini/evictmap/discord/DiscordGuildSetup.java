package vini.evictmap.discord;

import arc.util.serialization.Jval;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Creates the chat mirror's Discord channels with the bot, so the eleven
 * channels and their ids do not have to be made and copied by hand - the one
 * genuinely tedious part of setting the mirror up, and the one where a
 * mis-pasted id goes unnoticed until a match is missing from the log.
 *
 * <p>Idempotent: an existing category or channel of the same name is adopted
 * rather than duplicated, so running it again after raising
 * {@code maxWorkers} only creates the ports that are missing.
 *
 * <p>Every channel is created denying {@code VIEW_CHANNEL} to
 * {@code @everyone} - these channels carry every line players type, and
 * staff-only is not something to remember afterwards - plus an explicit allow
 * for the bot itself, which that deny would otherwise lock out of its own
 * channels.
 *
 * <p>Blocking, so the caller runs it off the main thread. Requests are paced
 * and 429s are waited out: creating a dozen channels at once is exactly what
 * Discord rate-limits.
 */
final class DiscordGuildSetup {

    private static final String API = "https://discord.com/api/v10";

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    /** Gap between two channel creations. */
    private static final long PACE_MILLIS = 600L;

    /** Attempts per request before giving up on it. */
    private static final int MAX_ATTEMPTS = 4;

    /** Longest a single 429 is waited out before the run is abandoned. */
    private static final long MAX_RETRY_WAIT_MILLIS = 30_000L;

    private static final int TYPE_TEXT = 0;
    private static final int TYPE_CATEGORY = 4;

    /** VIEW_CHANNEL (1 &lt;&lt; 10) and SEND_MESSAGES (1 &lt;&lt; 11). */
    private static final long VIEW_CHANNEL = 1L << 10;
    private static final long SEND_MESSAGES = 1L << 11;

    /** One channel the mirror wants, by the key the reporter stores it under. */
    record Wanted(String key, String name, String topic) {
    }

    /**
     * What one run did. {@code idsByKey} carries only the channels that now
     * exist - the caller stores those even when a later step failed, so a
     * partial run is not lost work.
     */
    record Result(
            boolean ok,
            String error,
            Map<String, String> idsByKey,
            List<String> created,
            List<String> reused
    ) {
    }

    private final HttpClient client;
    private final String token;
    private final String guildId;

    DiscordGuildSetup(HttpClient client, String token, String guildId) {
        this.client = client;
        this.token = token;
        this.guildId = guildId;
    }

    Result run(String categoryName, List<Wanted> wanted) {
        Map<String, String> ids = new LinkedHashMap<>();
        List<String> created = new ArrayList<>();
        List<String> reused = new ArrayList<>();

        String botUserId;
        Jval existing;

        try {
            botUserId = request("GET", "/users/@me", null).getString("id", "");

            if (botUserId.isEmpty()) {
                return failed("Discord did not name the bot user.", ids, created, reused);
            }

            existing = request("GET", "/guilds/" + guildId + "/channels", null);
        } catch (SetupException exception) {
            return failed(exception.getMessage(), ids, created, reused);
        }

        String overwrites = overwrites(botUserId);

        try {
            String categoryId = findExisting(existing, categoryName, TYPE_CATEGORY);

            if (categoryId == null) {
                categoryId = create(new DiscordJson.Obj()
                        .str("name", categoryName)
                        .num("type", TYPE_CATEGORY)
                        .raw("permission_overwrites", overwrites));
                created.add(categoryName);
            } else {
                reused.add(categoryName);
            }

            for (Wanted channel : wanted) {
                String channelId = findExisting(existing, channel.name(), TYPE_TEXT);

                if (channelId != null) {
                    ids.put(channel.key(), channelId);
                    reused.add(channel.name());
                    continue;
                }

                ids.put(channel.key(), create(new DiscordJson.Obj()
                        .str("name", channel.name())
                        .num("type", TYPE_TEXT)
                        .str("parent_id", categoryId)
                        .str("topic", channel.topic())
                        .raw("permission_overwrites", overwrites)));
                created.add(channel.name());
            }
        } catch (SetupException exception) {
            return failed(exception.getMessage(), ids, created, reused);
        }

        return new Result(true, "", ids, created, reused);
    }

    /**
     * Staff-only, and still reachable by the bot: deny the whole server the
     * channel, allow the bot itself back in. Without the second half the bot
     * would be locked out of the channels it just made.
     */
    private String overwrites(String botUserId) {
        return new DiscordJson.Arr()
                .add(new DiscordJson.Obj()
                        .str("id", guildId)
                        .num("type", 0)
                        .str("deny", Long.toString(VIEW_CHANNEL)))
                .add(new DiscordJson.Obj()
                        .str("id", botUserId)
                        .num("type", 1)
                        .str("allow", Long.toString(VIEW_CHANNEL | SEND_MESSAGES)))
                .toString();
    }

    /** The id of an existing channel with this name and type, or null. */
    private static String findExisting(Jval channels, String name, int type) {
        if (channels == null || !channels.isArray()) {
            return null;
        }

        for (Jval channel : channels.asArray()) {
            if (
                    channel.isObject()
                            && channel.getInt("type", -1) == type
                            && channel.getString("name", "").equalsIgnoreCase(name)
            ) {
                return channel.getString("id", "");
            }
        }

        return null;
    }

    private String create(DiscordJson.Obj payload) throws SetupException {
        sleep(PACE_MILLIS);

        String id = request(
                "POST",
                "/guilds/" + guildId + "/channels",
                payload.toString()
        ).getString("id", "");

        if (id.isEmpty()) {
            throw new SetupException("Discord created a channel but named no id.");
        }

        return id;
    }

    /**
     * One API call, retried while Discord asks us to wait. Anything else that
     * is not a success is final - a wrong token or a missing permission does
     * not improve by being asked again.
     */
    private Jval request(String method, String path, String body)
            throws SetupException {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create(API + path))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bot " + token)
                    .header("User-Agent", "DiscordBot (EvictMapGenerator chat mirror)");

            HttpResponse<String> response;

            try {
                response = client.send(
                        body == null
                                ? request.GET().build()
                                : request.POST(
                                        HttpRequest.BodyPublishers.ofString(body)
                                ).build(),
                        HttpResponse.BodyHandlers.ofString()
                );
            } catch (Exception exception) {
                throw new SetupException("could not reach Discord: " + exception);
            }

            int status = response.statusCode();

            if (status >= 200 && status < 300) {
                try {
                    return Jval.read(response.body());
                } catch (Exception exception) {
                    throw new SetupException("Discord sent an unreadable reply.");
                }
            }

            if (status == 429 && attempt < MAX_ATTEMPTS) {
                sleep(retryAfterMillis(response.body()));
                continue;
            }

            throw new SetupException(explain(status, response.body()));
        }

        throw new SetupException("Discord kept rate-limiting the setup; try again shortly.");
    }

    private static String explain(int status, String body) {
        return switch (status) {
            case 401 -> "the bot token was rejected (HTTP 401) - check "
                    + "DISCORD_CHAT_BOT_TOKEN and run 'evictchatlog reload'.";
            case 403 -> "the bot may not manage channels here (HTTP 403) - "
                    + "invite it with the Manage Channels permission.";
            case 404 -> "no such server (HTTP 404) - check the server id, and "
                    + "that the bot is a member of it.";
            case 429 -> "Discord is rate-limiting channel creation; try again "
                    + "in a few minutes.";
            default -> "Discord refused with HTTP " + status + ": "
                    + DiscordFormat.truncate(body == null ? "" : body, 200);
        };
    }

    private static long retryAfterMillis(String body) {
        try {
            double seconds = Jval.read(body).getDouble("retry_after", 0d);

            if (seconds > 0d) {
                return Math.min(
                        MAX_RETRY_WAIT_MILLIS,
                        (long) Math.ceil(seconds * 1000d)
                );
            }
        } catch (Exception ignored) {
            // Fall through to the default wait.
        }

        return 5_000L;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static Result failed(
            String error,
            Map<String, String> ids,
            List<String> created,
            List<String> reused
    ) {
        return new Result(false, error, ids, created, reused);
    }

    /** A step that cannot be retried into working. */
    private static final class SetupException extends Exception {
        SetupException(String message) {
            super(message);
        }
    }
}
