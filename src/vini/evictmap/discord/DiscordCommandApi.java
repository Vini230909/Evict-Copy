package vini.evictmap.discord;

import arc.util.serialization.Jval;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The REST half of the slash commands: registering them, and answering one.
 *
 * <p>Answering is a two-step exchange, and deliberately so. Discord drops an
 * interaction that is not acknowledged within three seconds, and the work here
 * has to be handed to the game's main thread and waited for - a hitching server
 * would lose the command and leave the admin looking at "the application did
 * not respond". So the acknowledgement goes out first, on its own, and the real
 * answer edits it afterwards.
 *
 * <p>Every reply is <em>ephemeral</em> ({@code flags: 64}): only the admin who
 * ran the command sees it. A ban is announced in the game and written up in the
 * ban log; the channel does not need a second copy, and an ephemeral reply
 * leaves no record for anyone who should not be reading one.
 *
 * <p>Blocking, so the caller runs it off the main thread.
 */
final class DiscordCommandApi {

    private static final String API = "https://discord.com/api/v10";

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    /** Interaction callback type 5: "thinking", to be edited into the answer. */
    private static final int DEFERRED_REPLY = 5;

    /** Only the caller sees the reply. */
    private static final int EPHEMERAL = 64;

    /** Application command type 1 (CHAT_INPUT) with one string option (3). */
    private static final int CHAT_INPUT = 1;
    private static final int STRING_OPTION = 3;

    /**
     * BAN_MEMBERS. Hides the commands from members without it, which is a
     * convenience rather than a guarantee - the plugin checks the role itself
     * (see {@link SlashInteraction}).
     */
    private static final long BAN_MEMBERS = 1L << 2;

    private static final String ARGUMENT_DESCRIPTION =
            "The player's UUID, or an IP address.";

    /** A Discord server or role the bot can see: the id, and a readable name. */
    record Named(String id, String name) {
    }

    /** What one listing found, or why it found nothing. */
    record Listing(String error, List<Named> items) {
    }

    private final HttpClient client;

    private volatile String token = "";
    private volatile String applicationId = "";

    DiscordCommandApi(HttpClient client) {
        this.client = client;
    }

    /**
     * A <em>changed</em> token invalidates the cached application id, which was
     * resolved with the old one. Re-setting the same token leaves the cache
     * alone, so re-reading the secrets file costs nothing.
     */
    void setToken(String newToken) {
        String cleaned = newToken == null ? "" : newToken.trim();

        if (cleaned.equals(token)) {
            return;
        }

        token = cleaned;
        applicationId = "";
    }

    boolean hasApplicationId() {
        return !applicationId.isEmpty();
    }

    /**
     * Declares {@code /ban} and {@code /unban} in one Discord server, replacing
     * whatever was there before.
     *
     * <p>Guild commands rather than global ones: a guild command is usable the
     * moment this returns, where a global one takes up to an hour to appear.
     *
     * @return an empty string on success, otherwise what went wrong
     */
    String registerCommands(String guildId) {
        if (token.isEmpty()) {
            return "no bot token is loaded";
        }

        if (guildId == null || guildId.isBlank()) {
            return "no Discord server id is set";
        }

        String application = resolveApplicationId();

        if (application.isEmpty()) {
            return "Discord would not name the bot (check the token)";
        }

        try {
            HttpResponse<String> response = send(
                    "PUT",
                    API + "/applications/" + application
                            + "/guilds/" + guildId.trim() + "/commands",
                    definitions(),
                    true
            );

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return "";
            }

            return explain(response.statusCode(), response.body());
        } catch (Exception exception) {
            return "could not reach Discord: " + exception;
        }
    }

    /**
     * The Discord servers the bot is a member of.
     *
     * <p>Setup asks Discord rather than the admin: the bot is already in the
     * server (it mirrors chat there), so the id it needs is something it can
     * look up. One server means nothing to type at all.
     */
    Listing guilds() {
        return list(API + "/users/@me/guilds", false, "");
    }

    /**
     * The roles of one Discord server, so an admin can pick one by name
     * instead of turning on Developer Mode to copy an id.
     *
     * <p>{@code @everyone} (its id is the server's own) and bot-managed roles
     * are left out: neither can be handed to a member of staff.
     */
    Listing roles(String guildId) {
        return list(API + "/guilds/" + guildId + "/roles", true, guildId);
    }

    private Listing list(String url, boolean skipManaged, String guildId) {
        if (token.isEmpty()) {
            return new Listing("no bot token is loaded", List.of());
        }

        try {
            HttpResponse<String> response = send("GET", url, null, true);

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new Listing(
                        explain(response.statusCode(), response.body()),
                        List.of()
                );
            }

            Jval body = Jval.read(response.body());

            if (body == null || !body.isArray()) {
                return new Listing("Discord sent an unreadable reply", List.of());
            }

            List<Named> items = new ArrayList<>();

            for (Jval entry : body.asArray()) {
                if (entry == null || !entry.isObject()) {
                    continue;
                }

                String id = entry.getString("id", "");

                if (id.isEmpty() || id.equals(guildId)) {
                    continue;
                }

                if (skipManaged && entry.getBool("managed", false)) {
                    continue;
                }

                items.add(new Named(id, entry.getString("name", id)));
            }

            return new Listing("", List.copyOf(items));
        } catch (Exception exception) {
            return new Listing("could not reach Discord: " + exception, List.of());
        }
    }

    /**
     * Tells Discord the command was received, before doing anything with it.
     * Ephemeral from the start: the flag is fixed when the reply is created,
     * not when it is edited.
     */
    void acknowledge(String interactionId, String interactionToken) {
        String body = new DiscordJson.Obj()
                .num("type", DEFERRED_REPLY)
                .raw("data", new DiscordJson.Obj()
                        .num("flags", EPHEMERAL)
                        .toString())
                .toString();

        try {
            // Interaction endpoints authenticate through the token in the URL,
            // so the bot token stays out of this request entirely.
            send(
                    "POST",
                    API + "/interactions/" + interactionId + "/"
                            + interactionToken + "/callback",
                    body,
                    false
            );
        } catch (Exception exception) {
            // Nothing to do: the admin sees Discord's own "did not respond".
        }
    }

    /** Fills in the acknowledged reply with the answer. */
    void reply(String interactionToken, String content) {
        String application = resolveApplicationId();

        if (application.isEmpty()) {
            return;
        }

        String body = new DiscordJson.Obj()
                .str("content", content)
                .raw("allowed_mentions", "{\"parse\":[]}")
                .toString();

        try {
            send(
                    "PATCH",
                    API + "/webhooks/" + application + "/" + interactionToken
                            + "/messages/@original",
                    body,
                    false
            );
        } catch (Exception exception) {
            // The command already ran; a lost reply is not worth a retry loop.
        }
    }

    /**
     * A bot's application id is its own user id, so it comes free from the
     * token rather than having to be copied out of the developer portal.
     */
    private String resolveApplicationId() {
        String cached = applicationId;

        if (!cached.isEmpty() || token.isEmpty()) {
            return cached;
        }

        try {
            HttpResponse<String> response =
                    send("GET", API + "/users/@me", null, true);

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return "";
            }

            String id = Jval.read(response.body()).getString("id", "");
            applicationId = id == null ? "" : id;
        } catch (Exception exception) {
            return "";
        }

        return applicationId;
    }

    private HttpResponse<String> send(
            String method,
            String url,
            String body,
            boolean authenticated
    ) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header(
                        "User-Agent",
                        "DiscordBot (EvictMapGenerator moderation commands)"
                );

        if (authenticated) {
            request.header("Authorization", "Bot " + token);
        }

        return client.send(
                request.method(
                        method,
                        body == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(body)
                ).build(),
                HttpResponse.BodyHandlers.ofString()
        );
    }

    /** The two commands, exactly as Discord should show them. */
    private static String definitions() {
        return new DiscordJson.Arr()
                .add(definition(
                        "ban",
                        "Ban a player UUID or an IP address from the server."
                ))
                .add(definition(
                        "unban",
                        "Lift a ban on a player UUID or an IP address."
                ))
                .toString();
    }

    private static DiscordJson.Obj definition(String name, String description) {
        return new DiscordJson.Obj()
                .str("name", name)
                .str("description", description)
                .num("type", CHAT_INPUT)
                .raw("dm_permission", "false")
                .str("default_member_permissions", Long.toString(BAN_MEMBERS))
                .raw("options", new DiscordJson.Arr()
                        .add(new DiscordJson.Obj()
                                .str("name", "target")
                                .str("description", ARGUMENT_DESCRIPTION)
                                .num("type", STRING_OPTION)
                                .raw("required", "true"))
                        .toString());
    }

    private static String explain(int status, String body) {
        return switch (status) {
            case 401 -> "the bot token was rejected (HTTP 401) - check "
                    + "DISCORD_CHAT_BOT_TOKEN and run 'evictdiscordcmd reload'";
            case 403 -> "the bot may not add commands here (HTTP 403) - "
                    + "re-invite it with the applications.commands scope";
            case 404 -> "no such Discord server (HTTP 404) - check the server "
                    + "id, and that the bot is a member of it";
            case 429 -> "Discord is rate-limiting command registration; try "
                    + "again in a few minutes";
            default -> "Discord refused with HTTP " + status + ": "
                    + DiscordFormat.truncate(body == null ? "" : body, 200);
        };
    }
}
