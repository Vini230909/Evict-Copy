package vini.evictmap.discord;

import arc.Core;
import arc.util.serialization.Jval;
import vini.evictmap.core.io.Secrets;
import vini.evictmap.core.util.PluginLog;
import vini.evictmap.gen.EvictSettings;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BinaryOperator;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * {@code /ban} and {@code /unban} as Discord slash commands, for staff who are
 * not at the console.
 *
 * <p>Hub only, like every other moderation path: the hub is the single writer
 * of bans, and the commands go through exactly the same {@code BanRequest} and
 * {@code banPlayerIP} an admin's own ban does. Nothing is decided here - the
 * ban is widened, kicked, synced to the match servers, announced in chat and
 * written up in the ban log because it took the ordinary route, not because
 * this class arranged any of it.
 *
 * <p>Two permission layers, and the second is the one that matters. Discord's
 * own {@code default_member_permissions} hides the commands from members
 * without BAN_MEMBERS, but a Discord server admin can re-open them to everyone
 * in Server Settings without the game server ever hearing about it. So every
 * interaction is checked here as well, against the server id and the role in
 * the settings file.
 *
 * <p>Threading: the gateway hands interactions over on its own thread, this
 * class does the HTTP on a worker thread, and the ban itself is posted to the
 * main thread and waited for - the admin store and Mindustry's events belong to
 * the game loop.
 */
public final class DiscordModCommands {

    /** How long the game gets to answer before the reply gives up on it. */
    private static final long MAIN_THREAD_TIMEOUT_SECONDS = 10L;

    private static final String COMMAND_BAN = "ban";
    private static final String COMMAND_UNBAN = "unban";

    private final EvictSettings settings;

    /** (target, actor) - the reply line. Run on the main thread. */
    private final BinaryOperator<String> ban;
    private final BinaryOperator<String> unban;

    /**
     * Separate clients on purpose: the gateway holds one connection open for
     * the life of the server, and a blocking REST call must never end up
     * queued behind it on the same executor.
     */
    private final HttpClient socketClient = DiscordWebhook.newClient();
    private final HttpClient restClient = DiscordWebhook.newClient();

    private final DiscordCommandApi api = new DiscordCommandApi(restClient);
    private final DiscordGateway gateway =
            new DiscordGateway(socketClient, this::onInteraction);

    /** One command at a time; two admins banning at once is not a race here. */
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "evict-discord-command");
                thread.setDaemon(true);
                return thread;
            });

    private volatile String token = "";
    private volatile String lastRegistration = "";

    public DiscordModCommands(
            EvictSettings settings,
            BinaryOperator<String> ban,
            BinaryOperator<String> unban
    ) {
        this.settings = settings;
        this.ban = ban;
        this.unban = unban;
    }

    /**
     * Hub-only: reads the token and connects if a Discord server has been
     * wired up.
     *
     * <p>The token is read even when nothing else is configured, so the
     * console's checklist tells the truth on a server that has not been set up
     * yet - reporting a token that is sitting in the file as missing sends an
     * admin looking for the wrong problem.
     */
    public void start() {
        loadToken();

        if (settings.discordCommandGuild().isBlank()) {
            return;
        }

        connect(true);
    }

    /**
     * Sets up everything that can be worked out without the admin: which
     * Discord server the bot is in, registering the commands, connecting, and
     * listing the roles by name so a role can be chosen without Developer Mode.
     *
     * <p>Runs off the main thread and reports in one go.
     */
    public void setup(Consumer<List<String>> report) {
        worker.execute(() -> {
            List<String> lines = new ArrayList<>();

            try {
                runSetup(lines);
            } catch (Exception exception) {
                lines.add("Setup failed: " + exception);
            }

            Core.app.post(() -> report.accept(lines));
        });
    }

    private void runSetup(List<String> lines) {
        loadToken();

        if (token.isBlank()) {
            lines.add("No bot token is loaded. Set "
                    + Secrets.DISCORD_CHAT_BOT_TOKEN + " in " + Secrets.path()
                    + " and run this again.");
            return;
        }

        api.setToken(token);

        String guild = settings.discordCommandGuild();

        if (guild.isBlank()) {
            guild = detectGuild(lines);

            if (guild.isEmpty()) {
                return;
            }
        }

        String found = guild;
        String role = settings.discordCommandRole();

        Core.app.post(() -> {
            settings.setDiscordCommands(found, role);
            gateway.connect(token);
        });

        String error = api.registerCommands(guild);
        lastRegistration = error;

        if (error.isBlank()) {
            lines.add("/ban and /unban registered. They are usable in Discord now.");
        } else {
            lines.add("The commands could not be registered: " + error);
            return;
        }

        if (!role.isBlank()) {
            lines.add("Allowed role is already set to " + role + ".");
            return;
        }

        lines.add("No role is set yet, so only members with Discord's "
                + "Administrator permission may use the commands.");

        listRoles(lines);
    }

    /**
     * Asks Discord which server the bot is in, instead of asking the admin for
     * an id they would have to go and copy.
     *
     * @return the server id, or an empty string when it could not be settled
     */
    private String detectGuild(List<String> lines) {
        DiscordCommandApi.Listing found = api.guilds();

        if (!found.error().isBlank()) {
            lines.add("Could not ask Discord which servers the bot is in: "
                    + found.error());
            return "";
        }

        if (found.items().isEmpty()) {
            lines.add("The bot is not in any Discord server yet. Invite it "
                    + "with the 'bot' and 'applications.commands' scopes first.");
            return "";
        }

        if (found.items().size() > 1) {
            lines.add("The bot is in several Discord servers, so pick the one "
                    + "the commands belong in:");

            for (DiscordCommandApi.Named guild : found.items()) {
                lines.add("  " + guild.name() + " - 'evictdiscordcmd "
                        + guild.id() + "'");
            }

            return "";
        }

        DiscordCommandApi.Named only = found.items().get(0);
        lines.add("Discord server: " + only.name() + " (" + only.id() + ").");

        return only.id();
    }

    /** Prints the roles by name, so one can be chosen without an id. */
    private void listRoles(List<String> lines) {
        DiscordCommandApi.Listing roles =
                api.roles(settings.discordCommandGuild());

        if (!roles.error().isBlank() || roles.items().isEmpty()) {
            lines.add("Set one with 'evictdiscordcmd role <role name or id>'.");
            return;
        }

        lines.add("Pick one with 'evictdiscordcmd role <name>':");

        for (DiscordCommandApi.Named role : roles.items()) {
            lines.add("  " + role.name());
        }
    }

    /**
     * Sets the role allowed to use the commands, by name or by id - a name so
     * that nobody has to turn on Developer Mode for one setting.
     */
    public void setRole(String nameOrId, Consumer<List<String>> report) {
        worker.execute(() -> {
            List<String> lines = new ArrayList<>();
            String resolved = resolveRole(nameOrId, lines);

            if (!resolved.isEmpty()) {
                Core.app.post(() -> settings.setDiscordCommands(
                        settings.discordCommandGuild(),
                        resolved
                ));
            }

            Core.app.post(() -> report.accept(lines));
        });
    }

    private String resolveRole(String nameOrId, List<String> lines) {
        String wanted = nameOrId == null ? "" : nameOrId.trim();

        if (wanted.isEmpty()) {
            lines.add("Give a role name or id.");
            return "";
        }

        String guild = settings.discordCommandGuild();

        if (guild.isBlank()) {
            lines.add("No Discord server is set yet. Run 'evictdiscordcmd setup' first.");
            return "";
        }

        api.setToken(token);

        DiscordCommandApi.Listing roles = api.roles(guild);

        if (roles.error().isBlank()) {
            for (DiscordCommandApi.Named role : roles.items()) {
                if (role.id().equals(wanted)
                        || role.name().equalsIgnoreCase(wanted)) {
                    lines.add("Only " + role.name()
                            + " may use /ban and /unban from now on.");
                    return role.id();
                }
            }

            lines.add("No role called '" + wanted + "' in that Discord server.");
            return "";
        }

        // The roles could not be listed (a missing permission, a network
        // hiccup); a plain id is still usable on its own.
        if (wanted.chars().allMatch(Character::isDigit)) {
            lines.add("Role set to " + wanted + " (Discord's role list was "
                    + "unavailable: " + roles.error() + ").");
            return wanted;
        }

        lines.add("Could not look the roles up: " + roles.error());
        return "";
    }

    /**
     * Points the commands at a Discord server and, optionally, the role allowed
     * to use them. A blank role falls back to Discord's own Administrator
     * permission, so the commands are never open to everyone by accident.
     */
    public void configure(String guildId, String roleId) {
        settings.setDiscordCommands(guildId, roleId);
        connect(true);
    }

    /** Stops answering commands. The registered commands stay in Discord. */
    public void disable() {
        settings.setDiscordCommands("", "");
        gateway.disconnect();
        token = "";
        lastRegistration = "";
    }

    /**
     * Re-reads the secrets file and reconnects - how a rotated token heals a
     * connection Discord rejected, without a restart.
     *
     * @return true when the file now holds a token
     */
    public boolean reload() {
        connect(false);
        return !token.isBlank();
    }

    public boolean isConfigured() {
        return !settings.discordCommandGuild().isBlank();
    }

    /** The wiring checklist for the console. */
    public List<String> statusLines() {
        List<String> lines = new ArrayList<>();

        lines.add("bot token: " + (token.isBlank()
                ? "NOT SET - set " + Secrets.DISCORD_CHAT_BOT_TOKEN + " in "
                + Secrets.path() + ", then 'evictdiscordcmd reload'"
                : "loaded from " + Secrets.path()));

        String guild = settings.discordCommandGuild();

        lines.add("Discord server: " + (guild.isBlank()
                ? "NOT SET - 'evictdiscordcmd <server-id> [role-id]'"
                : guild));

        String role = settings.discordCommandRole();

        lines.add("allowed role: " + (role.isBlank()
                ? "none set - only members with Discord's Administrator "
                + "permission may use the commands"
                : role));

        lines.add("connection: " + connectionState());

        lines.add("commands registered: " + (lastRegistration.isBlank()
                ? (api.hasApplicationId() ? "yes" : "not yet")
                : "FAILED - " + lastRegistration));

        return lines;
    }

    private String connectionState() {
        if (!gateway.isRunning()) {
            return "off";
        }

        if (gateway.isConnected()) {
            return "connected";
        }

        return "connecting" + (gateway.lastError().isBlank()
                ? ""
                : " - last error: " + gateway.lastError());
    }

    /**
     * Reads the token, registers the commands and opens the gateway.
     *
     * @param quiet true on startup, where a server that has simply not been set
     *              up should not complain about it every boot
     */
    private void connect(boolean quiet) {
        loadToken();

        if (token.isBlank()) {
            if (!quiet) {
                PluginLog.err(
                        "Discord commands: @ is not set in @. Add it there and "
                                + "run 'evictdiscordcmd reload'.",
                        Secrets.DISCORD_CHAT_BOT_TOKEN,
                        Secrets.path()
                );
            }

            gateway.disconnect();
            return;
        }

        api.setToken(token);

        String guild = settings.discordCommandGuild();

        if (guild.isBlank()) {
            gateway.disconnect();
            return;
        }

        worker.execute(() -> register(guild));
        gateway.connect(token);
    }

    /**
     * Re-reads the shared bot token from the secrets file. Kept apart from
     * connecting so the console can report on a token that is present long
     * before anything is wired up to use it.
     */
    private void loadToken() {
        Secrets.reload();
        token = Secrets.get(Secrets.DISCORD_CHAT_BOT_TOKEN);
        api.setToken(token);
    }

    private void register(String guildId) {
        String error = api.registerCommands(guildId);
        lastRegistration = error;

        if (error.isBlank()) {
            PluginLog.info("Discord commands /ban and /unban registered.");
        } else {
            PluginLog.err("Discord commands could not be registered: @", error);
        }
    }

    /** Gateway thread: hand the work on and get out of the way. */
    private void onInteraction(Jval payload) {
        SlashInteraction interaction = SlashInteraction.parse(payload);

        if (interaction == null) {
            return;
        }

        worker.execute(() -> run(interaction));
    }

    private void run(SlashInteraction interaction) {
        api.acknowledge(interaction.id(), interaction.token());

        if (!allowed(interaction)) {
            PluginLog.info(
                    "Discord: @ tried to use /@ without permission.",
                    interaction.actor(),
                    interaction.command()
            );

            answer(interaction, "You may not use this command.");
            return;
        }

        String target = interaction.argument().trim();
        String actor = interaction.actor() + " (Discord)";

        String reply = switch (interaction.command()) {
            case COMMAND_BAN -> onMainThread(() -> ban.apply(target, actor));
            case COMMAND_UNBAN -> onMainThread(() -> unban.apply(target, actor));
            default -> "That command is not handled by this server.";
        };

        answer(interaction, reply);
    }

    /**
     * The reply is escaped rather than trusted: it carries a player name, and
     * a name is whatever the player typed - a stray {@code **} would otherwise
     * bold the rest of the line.
     */
    private void answer(SlashInteraction interaction, String reply) {
        api.reply(interaction.token(), DiscordFormat.escapeMarkdown(reply));
    }

    /**
     * Discord's own gating decides who sees the commands; this decides who may
     * actually use them. Both, because the first can be undone in Discord's UI
     * by anyone who administers that server.
     */
    private boolean allowed(SlashInteraction interaction) {
        String guild = settings.discordCommandGuild();

        if (guild.isBlank() || !guild.equals(interaction.guildId())) {
            return false;
        }

        String role = settings.discordCommandRole();

        if (role.isBlank()) {
            return interaction.administrator();
        }

        return interaction.administrator() || interaction.roles().contains(role);
    }

    /**
     * Runs the ban on the game loop and waits for its answer. Everything it
     * touches - the admin store, the ban events, the players it kicks - belongs
     * to the main thread.
     */
    private String onMainThread(Supplier<String> action) {
        CompletableFuture<String> result = new CompletableFuture<>();

        Core.app.post(() -> {
            try {
                result.complete(action.get());
            } catch (Throwable error) {
                PluginLog.err("Discord command failed: @", error.toString());
                result.complete("That did not work; check the server console.");
            }
        });

        try {
            return result.get(MAIN_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception exception) {
            return "The server did not answer in time; check the console.";
        }
    }
}
