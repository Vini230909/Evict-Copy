// Discord's /ban, /unban and /free, for staff who are not at the console: the bot answers them over the gateway.
package Extinction;

import Extinction.core.io.Secrets;
import Extinction.core.util.PluginLog;
import Extinction.discord.DiscordFormat;
import Extinction.discord.DiscordWebhook;

import arc.Core;
import arc.util.serialization.Jval;

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

// Hub only, and nothing is decided here: a ban takes the ordinary route, so it is widened, synced and logged like any other.
// Discord's gating decides who sees the commands; allowed() decides who may use them, since a Discord admin can undo the first.
public final class DiscordModeration {

    // How long the game gets to answer before the reply gives up on it.
    private static final long MAIN_THREAD_TIMEOUT_SECONDS = 10L;

    private static final String COMMAND_BAN = "ban";
    private static final String COMMAND_UNBAN = "unban";
    private static final String COMMAND_FREE = "free";

    // What /ban runs: the target, who asked, and why - the reason is required.
    public interface BanAction {
        String apply(String target, String actor, String reason);
    }

    // One slash command as Discord delivered it, reduced to what the plugin acts on. Crosses from the gateway's thread.
    private record Interaction(
            String id,
            String token,
            String guildId,
            String command,
            String argument,
            String reason,
            List<String> roles,
            boolean administrator,
            String actor
    ) {

        // Interaction type 2 - an application (slash) command was used.
        private static final int APPLICATION_COMMAND = 2;

        // Discord's ADMINISTRATOR permission bit.
        private static final long ADMINISTRATOR = 1L << 3;

        // The option every command takes, and the one /ban takes on top.
        private static final String ARGUMENT = "target";
        private static final String REASON = "reason";

        // Shown when Discord sends no usable name for the member.
        private static final String UNKNOWN_ACTOR = "someone on Discord";

        // Null when it is not a slash command run by a member of a Discord server: those have no roles to check.
        static Interaction parse(Jval payload) {
            if (payload == null
                    || !payload.isObject()
                    || payload.getInt("type", -1) != APPLICATION_COMMAND) {
                return null;
            }

            Jval data = payload.get("data");
            Jval member = payload.get("member");

            if (data == null || !data.isObject() || member == null || !member.isObject()) {
                return null;
            }

            String id = payload.getString("id", "");
            String token = payload.getString("token", "");
            String command = data.getString("name", "");

            if (id.isEmpty() || token.isEmpty() || command.isEmpty()) {
                return null;
            }

            return new Interaction(
                    id,
                    token,
                    payload.getString("guild_id", ""),
                    command,
                    option(data, ARGUMENT),
                    option(data, REASON),
                    roles(member),
                    (permissions(member.getString("permissions", "0")) & ADMINISTRATOR) != 0L,
                    actor(member)
            );
        }

        private static String option(Jval data, String name) {
            Jval options = data.get("options");

            if (options == null || !options.isArray()) {
                return "";
            }

            for (Jval option : options.asArray()) {
                if (option != null
                        && option.isObject()
                        && name.equals(option.getString("name", ""))) {
                    return option.getString("value", "");
                }
            }

            return "";
        }

        private static List<String> roles(Jval member) {
            Jval list = member.get("roles");

            if (list == null || !list.isArray()) {
                return List.of();
            }

            List<String> roles = new ArrayList<>();

            for (Jval role : list.asArray()) {
                if (role != null && role.isString()) {
                    roles.add(role.asString());
                }
            }

            return List.copyOf(roles);
        }

        // The name the ban log shows, so a ban made from Discord traces back to a person like any other.
        private static String actor(Jval member) {
            Jval user = member.get("user");

            if (user == null || !user.isObject()) {
                return UNKNOWN_ACTOR;
            }

            String name = user.getString("global_name", "");

            if (name == null || name.isBlank()) {
                name = user.getString("username", "");
            }

            return name == null || name.isBlank() ? UNKNOWN_ACTOR : name;
        }

        // Discord sends the permission bitfield as a decimal string.
        private static long permissions(String raw) {
            try {
                return Long.parseLong(raw == null ? "0" : raw.trim());
            } catch (NumberFormatException exception) {
                return 0L;
            }
        }
    }

    // (target, actor[, reason]) - the reply line. Run on the main thread.
    private final BanAction ban;
    private final BinaryOperator<String> unban;
    private final BinaryOperator<String> free;

    // Separate clients: the gateway holds one connection open for good, and a REST call must never queue behind it.
    private final HttpClient socketClient = DiscordWebhook.newClient();
    private final HttpClient restClient = DiscordWebhook.newClient();

    private final DiscordApi api = new DiscordApi(restClient);
    private final DiscordGateway gateway =
            new DiscordGateway(socketClient, this::onInteraction);

    // One command at a time; two admins banning at once is not a race here.
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "evict-discord-command");
                thread.setDaemon(true);
                return thread;
            });

    private volatile String token = "";
    private volatile String lastRegistration = "";

    public DiscordModeration(
            BanAction ban,
            BinaryOperator<String> unban,
            BinaryOperator<String> free
    ) {
        this.ban = ban;
        this.unban = unban;
        this.free = free;
    }

    // Hub-only: reads the token even when nothing is wired yet, so the checklist never calls a present token missing.
    public void start() {
        loadToken();

        if (Config.discordCommandGuild.isBlank()) {
            return;
        }

        connect(true);
    }

    // Sets up what needs no admin: the Discord server, the commands, the connection, the role names. Off the main thread.
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

        String guild = Config.discordCommandGuild;

        if (guild.isBlank()) {
            guild = detectGuild(lines);

            if (guild.isEmpty()) {
                return;
            }
        }

        String found = guild;
        String role = Config.discordCommandRole;

        Core.app.post(() -> {
            wire(found, role);
            gateway.connect(token);
        });

        String error = api.registerCommands(guild);
        lastRegistration = error;

        if (error.isBlank()) {
            lines.add("/ban, /unban and /free registered. They are usable in Discord now.");
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

    // Asks Discord which server the bot is in instead of the admin; empty when that could not be settled.
    private String detectGuild(List<String> lines) {
        DiscordApi.Listing found = api.guilds();

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

            for (DiscordApi.Named guild : found.items()) {
                lines.add("  " + guild.name() + " - 'discordcommands "
                        + guild.id() + "'");
            }

            return "";
        }

        DiscordApi.Named only = found.items().get(0);
        lines.add("Discord server: " + only.name() + " (" + only.id() + ").");

        return only.id();
    }

    // Prints the roles by name, so one can be chosen without an id.
    private void listRoles(List<String> lines) {
        DiscordApi.Listing roles = api.roles(Config.discordCommandGuild);

        if (!roles.error().isBlank() || roles.items().isEmpty()) {
            lines.add("Set one with 'discordcommands role <role name or id>'.");
            return;
        }

        lines.add("Pick one with 'discordcommands role <name>':");

        for (DiscordApi.Named role : roles.items()) {
            lines.add("  " + role.name());
        }
    }

    // Sets the role allowed to use the commands, by name or id - a name, so nobody needs Developer Mode for one setting.
    public void setRole(String nameOrId, Consumer<List<String>> report) {
        worker.execute(() -> {
            List<String> lines = new ArrayList<>();
            String resolved = resolveRole(nameOrId, lines);

            if (!resolved.isEmpty()) {
                Core.app.post(() -> wire(Config.discordCommandGuild, resolved));
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

        String guild = Config.discordCommandGuild;

        if (guild.isBlank()) {
            lines.add("No Discord server is set yet. Run 'discordcommands setup' first.");
            return "";
        }

        api.setToken(token);

        DiscordApi.Listing roles = api.roles(guild);

        if (roles.error().isBlank()) {
            for (DiscordApi.Named role : roles.items()) {
                if (role.id().equals(wanted)
                        || role.name().equalsIgnoreCase(wanted)) {
                    lines.add("Only " + role.name()
                            + " may use /ban, /unban and /free from now on.");
                    return role.id();
                }
            }

            lines.add("No role called '" + wanted + "' in that Discord server.");
            return "";
        }

        // The roles could not be listed (a missing permission, a network hiccup); a plain id still works on its own.
        if (wanted.chars().allMatch(Character::isDigit)) {
            lines.add("Role set to " + wanted + " (Discord's role list was "
                    + "unavailable: " + roles.error() + ").");
            return wanted;
        }

        lines.add("Could not look the roles up: " + roles.error());
        return "";
    }

    // Points the commands at a Discord server and optionally a role; no role means Discord's Administrator permission.
    public void configure(String guildId, String roleId) {
        wire(guildId, roleId);
        connect(true);
    }

    // Stops answering commands. The registered commands stay in Discord.
    public void disable() {
        wire("", "");
        gateway.disconnect();
        token = "";
        lastRegistration = "";
    }

    // Re-reads the secrets file and reconnects - how a rotated token heals without a restart. True when a token is there.
    public boolean reload() {
        connect(false);
        return !token.isBlank();
    }

    public boolean isConfigured() {
        return !Config.discordCommandGuild.isBlank();
    }

    // The wiring checklist for the console.
    public List<String> statusLines() {
        List<String> lines = new ArrayList<>();

        lines.add("bot token: " + (token.isBlank()
                ? "NOT SET - set " + Secrets.DISCORD_CHAT_BOT_TOKEN + " in "
                + Secrets.path() + ", then 'discordcommands reload'"
                : "loaded from " + Secrets.path()));

        String guild = Config.discordCommandGuild;

        lines.add("Discord server: " + (guild.isBlank()
                ? "NOT SET - 'discordcommands <server-id> [role-id]'"
                : guild));

        String role = Config.discordCommandRole;

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

    // The Discord server and role, stored at once.
    private static void wire(String guildId, String roleId) {
        Config.discordCommandGuild = guildId == null ? "" : guildId.trim();
        Config.discordCommandRole = roleId == null ? "" : roleId.trim();
        Config.save();
    }

    // Reads the token, registers the commands and opens the gateway; quiet on startup, where not set up is no complaint.
    private void connect(boolean quiet) {
        loadToken();

        if (token.isBlank()) {
            if (!quiet) {
                PluginLog.err(
                        "Discord commands: @ is not set in @. Add it there and "
                                + "run 'discordcommands reload'.",
                        Secrets.DISCORD_CHAT_BOT_TOKEN,
                        Secrets.path()
                );
            }

            gateway.disconnect();
            return;
        }

        api.setToken(token);

        String guild = Config.discordCommandGuild;

        if (guild.isBlank()) {
            gateway.disconnect();
            return;
        }

        worker.execute(() -> register(guild));
        gateway.connect(token);
    }

    // Re-reads the shared bot token, apart from connecting, so the console can report a token long before it is used.
    private void loadToken() {
        Secrets.reload();
        token = Secrets.get(Secrets.DISCORD_CHAT_BOT_TOKEN);
        api.setToken(token);
    }

    private void register(String guildId) {
        String error = api.registerCommands(guildId);
        lastRegistration = error;

        if (error.isBlank()) {
            PluginLog.info("Discord commands /ban, /unban and /free registered.");
        } else {
            PluginLog.err("Discord commands could not be registered: @", error);
        }
    }

    // Gateway thread: hand the work on and get out of the way.
    private void onInteraction(Jval payload) {
        Interaction interaction = Interaction.parse(payload);

        if (interaction == null) {
            return;
        }

        worker.execute(() -> run(interaction));
    }

    private void run(Interaction interaction) {
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
        String reason = interaction.reason().trim();
        String actor = interaction.actor() + " (Discord)";

        String reply = switch (interaction.command()) {
            case COMMAND_BAN -> onMainThread(() -> ban.apply(target, actor, reason));
            case COMMAND_UNBAN -> onMainThread(() -> unban.apply(target, actor));
            case COMMAND_FREE -> onMainThread(() -> free.apply(target, actor));
            default -> "That command is not handled by this server.";
        };

        answer(interaction, reply);
    }

    // Escaped, not trusted: the reply carries a player's name, and a stray ** would bold the rest of the line.
    private void answer(Interaction interaction, String reply) {
        api.reply(interaction.token(), DiscordFormat.escapeMarkdown(reply));
    }

    // The configured Discord server, and its role - or Discord's Administrator permission when no role is set.
    private boolean allowed(Interaction interaction) {
        String guild = Config.discordCommandGuild;

        if (guild.isBlank() || !guild.equals(interaction.guildId())) {
            return false;
        }

        String role = Config.discordCommandRole;

        if (role.isBlank()) {
            return interaction.administrator();
        }

        return interaction.administrator() || interaction.roles().contains(role);
    }

    // Runs the action on the game loop and waits: the admin store, the ban events and the kicks belong to it.
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
