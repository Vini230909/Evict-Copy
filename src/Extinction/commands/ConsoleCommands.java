package Extinction.commands;

import Extinction.*;
import Extinction.gen.*;
import Extinction.data.*;
import Extinction.round.*;
import Extinction.core.cmd.Commands;
import Extinction.core.io.Secrets;
import Extinction.discord.BanLogReporter;
import Extinction.discord.ChatLogReporter;
import Extinction.discord.DiscordModCommands;
import Extinction.moderation.ban.BanManager;
import Extinction.moderation.lock.PlayerLock;
import Extinction.moderation.vpn.VpnScan;
import Extinction.discord.DiscordStatusReporter;
import Extinction.discord.PerfReporter;
import Extinction.metrics.PerfSampler;
import Extinction.metrics.PerfSnapshot;
import Extinction.duel.DuelServerManager;

import arc.util.CommandHandler;
import arc.util.Log;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.Team;

import java.util.List;
import java.util.Locale;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

/**
 * All dedicated-server console commands, declared on the shared command
 * framework.
 *
 * <p>{@link #register} used to be one ~319-line method of inline lambdas; it is
 * now a flat list of {@code command(...).run(...)} declarations, with each
 * handler in its own method. The framework does registration, argument shaping
 * and error catching centrally.
 */
public final class ConsoleCommands {

    private final EvictRuntimeState runtime;
    private final EvictSettings settings;
    private final EvictTerrainGenerator terrain;
    private final TeamManager teamManager;
    private final PlayerDataManager playerDataManager;
    private final DuelServerManager duelServerManager;
    private final RestartManager restartManager;

    /** Null on a duel worker, which never reports to Discord. */
    private final DiscordStatusReporter discordStatusReporter;

    /** Null on a duel worker: the hub owns the ban log. */
    private final BanLogReporter banLogReporter;

    /** Null on a duel worker: only the hub decides who is banned. */
    private final BanManager banManager;

    /** Null on a duel worker: the hub looks every join up, log only. */
    private final VpnScan vpnScan;

    /** Null on a duel worker: the hub owns the lock list. */
    private final PlayerLock playerLock;

    /** Null on a duel worker: the hub relays worker chat into Discord. */
    private final ChatLogReporter chatLogReporter;

    /** Null on a duel worker: only the hub answers Discord's /ban. */
    private final DiscordModCommands discordModCommands;

    /** Null on a duel worker: the hub draws the performance table for all of them. */
    private final PerfReporter perfReporter;

    /**
     * This process's own performance sampler - hub or worker, since a match
     * server's console is exactly where you look when that match server is the
     * slow one. A supplier because the sampler is created in bootstrap(), after
     * the commands are constructed.
     */
    private final Supplier<PerfSampler> perfSampler;

    private final LongConsumer generate;

    private static final int MAX_CORECAP_INCREMENT = 10000;

    private int extraCoreCapPerCore = 0;

    public ConsoleCommands(
            EvictRuntimeState runtime,
            EvictSettings settings,
            EvictTerrainGenerator terrain,
            TeamManager teamManager,
            PlayerDataManager playerDataManager,
            DuelServerManager duelServerManager,
            RestartManager restartManager,
            DiscordStatusReporter discordStatusReporter,
            BanLogReporter banLogReporter,
            BanManager banManager,
            VpnScan vpnScan,
            PlayerLock playerLock,
            ChatLogReporter chatLogReporter,
            DiscordModCommands discordModCommands,
            PerfReporter perfReporter,
            Supplier<PerfSampler> perfSampler,
            LongConsumer generate
    ) {
        this.runtime = runtime;
        this.settings = settings;
        this.terrain = terrain;
        this.teamManager = teamManager;
        this.playerDataManager = playerDataManager;
        this.duelServerManager = duelServerManager;
        this.restartManager = restartManager;
        this.discordStatusReporter = discordStatusReporter;
        this.banLogReporter = banLogReporter;
        this.banManager = banManager;
        this.vpnScan = vpnScan;
        this.playerLock = playerLock;
        this.chatLogReporter = chatLogReporter;
        this.discordModCommands = discordModCommands;
        this.perfReporter = perfReporter;
        this.perfSampler = perfSampler;
        this.generate = generate;
    }

    public void register(CommandHandler handler) {
        Commands commands = new Commands();

        commands.command("oregen").console()
                .args("action:string?", "value:string?")
                .description("Terrain generator: status, gen [seed], seed <n/random>, auto on/off.")
                .run(ctx -> handleOreGenCommand(
                        ctx.str("action", "").trim().toLowerCase(),
                        ctx.str("value", "").trim()
                ));

        commands.command("round").console()
                .args("action:string?", "value:string?")
                .description("This round: team assignment and elapsed time; 'time <seconds>' sets the time.")
                .run(ctx -> handleRoundCommand(
                        ctx.str("action", "").trim().toLowerCase(),
                        ctx.str("value", "").trim()
                ));

        commands.command("matchstatus").console()
                .description("The worker pool: its settings, the active match servers and who is in them.")
                .run(ctx -> {
                    Log.info("[EvictMapGenerator] duel server: @", settings.compactDuelServerSettings());
                    duelServerManager.logStatus();
                });

        commands.command("playerinfo").console()
                .args("query:text?")
                .description("Look up a stored player by name or UUID; no argument lists all.")
                .run(ctx -> showStoredPlayerInfo(ctx.str("query", "").trim()));

        commands.command("banplayer").console()
                .args("name/uuid:text")
                .description("Ban a stored player by name or UUID, online or not.")
                .run(ctx -> handleBanCommand(ctx.str("name/uuid", "").trim()));

        commands.command("elo").console()
                .args("name/uuid:string", "value:string")
                .description("Set a stored player's ranked ELO.")
                .run(ctx -> handleEloCommand(ctx.raw()));

        commands.command("corecap").console()
                .args("additional-per-core:int")
                .description("Add unit-cap capacity to every core.")
                .run(ctx -> addCoreCap(ctx.raw()));

        commands.command("discordstatus").console()
                .args("url/off/test:string?")
                .description("Discord webhook for the live status message.")
                .run(ctx -> handleDiscordCommand(ctx.str("url/off/test", "").trim()));

        commands.command("banlog").console()
                .args("action:string?", "force:string?")
                .description("Discord ban log: status, <webhook-url>, off, test, import [force]. Staff-only: it posts IPs.")
                .run(ctx -> {
                    String action = ctx.str("action", "").trim();

                    if (action.equalsIgnoreCase("import")) {
                        handleBanImportCommand(ctx.str("force", "").trim());
                    } else {
                        handleBanLogCommand(action);
                    }
                });

        commands.command("discordcommands").console()
                .args("action:string?", "value:text?")
                .description("Discord /ban and /unban: setup, role <name>, reload, off.")
                .run(ctx -> handleDiscordCommandsCommand(
                        ctx.str("action", "").trim(),
                        ctx.str("value", "").trim()
                ));

        commands.command("chatlog").console()
                .args("target:string?", "value:string?")
                .description("Discord chat mirror: status, setup <server-id>, hub/<port> + channel id, reload, off, test.")
                .run(ctx -> handleChatLogCommand(ctx.raw()));

        commands.command("perf").console()
                .args("action:string?", "value:string?", "extra:string?")
                .description("Live performance reports in Discord: setup, rate, off, reload, test.")
                .run(ctx -> handlePerfCommand(
                        ctx.str("action", "").trim(),
                        ctx.str("value", "").trim(),
                        ctx.str("extra", "").trim()
                ));

        commands.command("profile").console()
                .args("on/off:string?")
                .description("What this server's tick is spending itself on, last minute; 'on'/'off' switch the profiler.")
                .run(ctx -> {
                    String value = ctx.str("on/off", "").trim();

                    if (value.isEmpty()) {
                        handleProfileCommand();
                    } else {
                        handleProfileToggle(value);
                    }
                });

        commands.command("vpn").console()
                .args("action:string?", "value:string?")
                .description("VPN scan: status, on/off, lock on/off, reload the key, test <ip>.")
                .run(ctx -> handleVpnScanCommand(
                        ctx.str("action", "").trim(),
                        ctx.str("value", "").trim()
                ));

        commands.command("free").console()
                .args("target:text?")
                .description("Free a locked account by name or UUID; no argument lists the locked ones.")
                .run(ctx -> handleFreeCommand(ctx.str("target", "").trim()));

        commands.command("restart").console()
                .args("action:string?")
                .description("Queue a graceful restart; 'cancel' drops it, 'now' exits.")
                .run(ctx -> handleRestartCommand(ctx.str("action", "").trim().toLowerCase()));

        commands.installConsole(handler);
    }

    /** oregen: no argument is the status; gen, seed and auto are the old separate commands. */
    private void handleOreGenCommand(String action, String value) {
        String[] args = value.isEmpty() ? new String[0] : new String[]{value};

        switch (action) {
            case "" -> showStatus();
            case "gen" -> generateTerrain(args);
            case "seed" -> setSeed(args);
            case "auto" -> {
                switch (value.toLowerCase()) {
                    case "on", "true" -> runtime.autoGenerate = true;
                    case "off", "false" -> runtime.autoGenerate = false;
                    default -> {
                        Log.err("[EvictMapGenerator] Use: oregen auto on/off");
                        return;
                    }
                }

                Log.info("[EvictMapGenerator] Automatic generation is now @.", runtime.autoGenerate ? "ON" : "OFF");
            }
            default -> Log.err("[EvictMapGenerator] Use: oregen [gen [seed] | seed <n/random> | auto on/off]");
        }
    }

    /** round: no argument prints the team assignment and the elapsed time; 'time <seconds>' sets it. */
    private void handleRoundCommand(String action, String value) {
        switch (action) {
            case "" -> {
                teamManager.logStatus();
                handleSetTimeCommand(new String[0]);
            }
            case "time" -> handleSetTimeCommand(value.isEmpty() ? new String[0] : new String[]{value});
            default -> Log.err("[EvictMapGenerator] Use: round [time <seconds>]");
        }
    }

    /**
     * discordstatus: no argument reports the current wiring, a URL adopts a new
     * webhook, 'off' takes the message offline and stops, 'test' forces an
     * immediate refresh.
     */
    private void handleDiscordCommand(String argument) {
        if (discordStatusReporter == null) {
            Log.err("[EvictMapGenerator] Discord status reporting only runs on the hub.");
            return;
        }

        switch (argument.toLowerCase()) {
            case "" -> Log.info(
                    "[EvictMapGenerator] Discord status: @",
                    discordStatusReporter.statusLine()
            );
            case "off" -> {
                discordStatusReporter.disable();
                Log.info("[EvictMapGenerator] Discord status reporting is off; the message now reads Offline.");
            }
            case "test" -> {
                discordStatusReporter.publishNow();
                Log.info("[EvictMapGenerator] Discord status update requested.");
            }
            default -> {
                if (discordStatusReporter.configure(argument)) {
                    Log.info("[EvictMapGenerator] Discord webhook set. A fresh status message is being posted.");
                } else {
                    Log.err("[EvictMapGenerator] That is not a Discord webhook URL. Copy it from Channel Settings > Integrations > Webhooks.");
                }
            }
        }
    }

    /**
     * banlog: no argument reports the current wiring, a URL adopts a new
     * webhook, 'off' stops logging, 'test' posts a sample entry.
     */
    private void handleBanLogCommand(String argument) {
        if (banLogReporter == null) {
            Log.err("[EvictMapGenerator] The ban log only runs on the hub.");
            return;
        }

        switch (argument.toLowerCase()) {
            case "" -> Log.info(
                    "[EvictMapGenerator] Discord ban log: @",
                    banLogReporter.statusLine()
            );
            case "off" -> {
                banLogReporter.disable();
                Log.info("[EvictMapGenerator] Discord ban logging is off.");
            }
            case "test" -> {
                if (banLogReporter.publishTest()) {
                    Log.info("[EvictMapGenerator] Test entry queued.");
                } else {
                    Log.err("[EvictMapGenerator] No ban-log webhook is set.");
                }
            }
            default -> {
                if (banLogReporter.configure(argument)) {
                    Log.info("[EvictMapGenerator] Ban-log webhook set. Bans will be posted there from now on.");
                } else {
                    Log.err("[EvictMapGenerator] That is not a Discord webhook URL. Copy it from Channel Settings > Integrations > Webhooks.");
                }
            }
        }
    }

    /**
     * discordcommands: the wiring for Discord's /ban and /unban. No argument
     * prints the checklist; a server id (with an optional role id) wires them
     * up and registers the commands; 'reload' re-reads the token file after a
     * rotation; 'off' stops answering.
     *
     * <p>Like the chat mirror, this never takes the bot token: typing it here
     * would write it into the server log and the start script's screen log
     * permanently. It lives in the secrets file.
     */
    private void handleDiscordCommandsCommand(String action, String value) {
        if (discordModCommands == null) {
            Log.err("[EvictMapGenerator] The Discord commands only run on the hub.");
            return;
        }

        switch (action.toLowerCase()) {
            case "" -> {
                Log.info("[EvictMapGenerator] Discord /ban and /unban:");

                for (String line : discordModCommands.statusLines()) {
                    Log.info("[EvictMapGenerator]   @", line);
                }

                if (!discordModCommands.isConfigured()) {
                    Log.info("[EvictMapGenerator] Run 'discordcommands setup' - it finds the Discord server itself, no ids to copy. ('chatlog setup <server-id>' already does this too.)");
                }
            }
            case "setup" -> {
                Log.info("[EvictMapGenerator] Setting the Discord commands up; this takes a few seconds...");
                discordModCommands.setup(this::logDiscordCommandLines);
            }
            case "role" -> {
                if (value.isEmpty()) {
                    Log.err("[EvictMapGenerator] Use: discordcommands role <role name or id> ('discordcommands setup' lists the names).");
                } else {
                    discordModCommands.setRole(value, this::logDiscordCommandLines);
                }
            }
            case "off" -> {
                discordModCommands.disable();
                Log.info("[EvictMapGenerator] Discord /ban and /unban are off. The commands stay visible in Discord until Discord drops them; this server simply refuses them.");
            }
            case "reload" -> {
                if (discordModCommands.reload()) {
                    Log.info("[EvictMapGenerator] Bot token re-read; reconnecting to Discord.");
                } else {
                    Log.err("[EvictMapGenerator] @ is not set in @. Add it there, then run this again.", Secrets.DISCORD_CHAT_BOT_TOKEN, Secrets.path());
                }
            }
            case "token" -> Log.err(
                    "[EvictMapGenerator] The token is never typed here - it would be written to the server log. Set @ in @ and run 'discordcommands reload'.",
                    Secrets.DISCORD_CHAT_BOT_TOKEN,
                    Secrets.path()
            );
            default -> {
                // A bare server id still works, for the case setup cannot
                // settle by itself: several Discord servers with the same bot.
                if (!action.chars().allMatch(Character::isDigit)) {
                    Log.err("[EvictMapGenerator] Usage: discordcommands [setup | role <name> | reload | off]");
                    return;
                }

                discordModCommands.configure(action, value);
                Log.info("[EvictMapGenerator] Discord commands wired to server @. Run 'discordcommands' to check the connection.", action);
            }
        }
    }

    /** Setup and role changes answer asynchronously; print what they found. */
    private void logDiscordCommandLines(java.util.List<String> lines) {
        for (String line : lines) {
            Log.info("[EvictMapGenerator] @", line);
        }
    }

    /**
     * perf: the live performance table. No argument prints the checklist
     * and this server's current numbers; 'setup' has the bot create the channel
     * (reusing the Discord server the slash commands are already wired to, so
     * no id has to be found twice); a channel id wires one by hand; 'off' stops
     * it; 'reload' re-reads the bot token; 'test' refreshes right now. Hub only;
     * the stack profiler is 'profile', which also works on a match server.
     */
    private void handlePerfCommand(String action, String value, String extra) {
        if (perfReporter == null) {
            Log.err("[EvictMapGenerator] The performance table only runs on the hub. 'profile' works here.");
            return;
        }

        if (action.isEmpty()) {
            Log.info("[EvictMapGenerator] Discord performance table:");

            for (String line : perfReporter.statusLines()) {
                Log.info("[EvictMapGenerator]   @", line);
            }

            Log.info("[EvictMapGenerator]   @", profilerLine());
            Log.info("[EvictMapGenerator]   Now: @", currentPerfLine());
            Log.info("[EvictMapGenerator] 'perf setup' creates the channel with the bot (needs Manage Channels). Staff-only, like the other log channels.");
            return;
        }

        switch (action.toLowerCase(Locale.ROOT)) {
            case "off" -> {
                perfReporter.disable();
                Log.info("[EvictMapGenerator] Performance table off; the channel is left in Discord.");
            }
            case "setup" -> handlePerfSetup(value);
            case "rate" -> handlePerfRate(value, extra);
            case "reload" -> {
                if (perfReporter.reloadToken()) {
                    Log.info("[EvictMapGenerator] Bot token loaded. The table resumes on its next refresh.");
                } else {
                    Log.err("[EvictMapGenerator] No bot token is set. Add it to the secrets file and run this again.");
                }
            }
            case "test" -> {
                if (!perfReporter.isConfigured()) {
                    Log.err("[EvictMapGenerator] No channel is wired yet - run 'perf setup'.");
                    return;
                }

                perfReporter.refreshAll();
                Log.info("[EvictMapGenerator] Every report will be redrawn as the request budget allows.");
            }
            default -> {
                if (!isChannelId(action)) {
                    Log.err("[EvictMapGenerator] Use: perf [setup <server-id> | rate <requests> <seconds> | <channel-id> | off | reload | test]");
                    return;
                }

                perfReporter.configureChannel(action);
                Log.info("[EvictMapGenerator] Performance reports wired to channel @; one message per server will be posted there.", action);
            }
        }
    }

    /**
     * The bot creates the channel itself. The Discord server is the one the
     * slash commands already use unless another is named - the same bot in the
     * same place, so making an admin find the id a second time would be pure
     * friction.
     */
    private void handlePerfSetup(String value) {
        String guild = value.isEmpty() ? settings.discordCommandGuild() : value;

        if (guild.isBlank()) {
            Log.err("[EvictMapGenerator] No Discord server known yet. Run 'discordcommands setup' (it finds the server itself), or pass the id: perf setup <server-id>.");
            return;
        }

        Log.info("[EvictMapGenerator] Creating the performance channel in Discord; this takes a moment...");

        perfReporter.setupChannel(guild, lines -> {
            for (String line : lines) {
                Log.info("[EvictMapGenerator] @", line);
            }
        });
    }

    /**
     * perf rate: how many Discord requests the reports may spend, and over
     * how long. It is the one knob that matters, because the budget is shared
     * out between the servers that are running - raising it refreshes each of
     * them sooner, lowering it is how you stay clear of a rate limit.
     */
    private void handlePerfRate(String requests, String seconds) {
        if (requests.isEmpty() || seconds.isEmpty()) {
            Log.info(
                    "[EvictMapGenerator] Budget is @ request(s) per @s. Change it with 'perf rate <requests> <seconds>' (Discord allows about 5 per 5s in one channel).",
                    settings.perfRateRequests(),
                    settings.perfRateSeconds()
            );
            return;
        }

        int parsedRequests;
        int parsedSeconds;

        try {
            parsedRequests = Integer.parseInt(requests);
            parsedSeconds = Integer.parseInt(seconds);
        } catch (NumberFormatException exception) {
            Log.err("[EvictMapGenerator] Use: perf rate <requests> <seconds> - both whole numbers.");
            return;
        }

        perfReporter.setRate(parsedRequests, parsedSeconds);

        Log.info("[EvictMapGenerator] Budget is now @ request(s) per @s.",
                settings.perfRateRequests(),
                settings.perfRateSeconds());

        for (String line : perfReporter.statusLines()) {
            Log.info("[EvictMapGenerator]   @", line);
        }
    }

    /** profile on/off: the stack profiler of this very process, hub or match server alike. */
    private void handleProfileToggle(String value) {
        PerfSampler sampler = perfSampler.get();

        if (sampler == null) {
            Log.err("[EvictMapGenerator] The performance sampler is not up yet.");
            return;
        }

        if (value.isEmpty()) {
            Log.info("[EvictMapGenerator] @", profilerLine());
            return;
        }

        switch (value.toLowerCase(Locale.ROOT)) {
            case "on" -> {
                sampler.setProfiling(true);
                Log.info("[EvictMapGenerator] Stack profiler on. 'profile' shows what the tick is doing; give it a few seconds to fill.");
            }
            case "off" -> {
                sampler.setProfiling(false);
                Log.info("[EvictMapGenerator] Stack profiler off. Tick rate, load and memory keep being measured; only the hotspot names stop.");
            }
            default -> Log.err("[EvictMapGenerator] Use: profile [on/off]");
        }
    }

    /**
     * profile: the full hotspot table for this process - the question the
     * Discord row only has room to answer three entries deep.
     */
    private void handleProfileCommand() {
        PerfSampler sampler = perfSampler.get();

        if (sampler == null) {
            Log.err("[EvictMapGenerator] The performance sampler is not up yet.");
            return;
        }

        Log.info("[EvictMapGenerator] @", currentPerfLine());

        if (!sampler.isProfiling()) {
            Log.info("[EvictMapGenerator] The stack profiler is off - turn it on with 'profile on'.");
            return;
        }

        List<PerfSnapshot.Hotspot> hotspots = sampler.profile(25);

        if (hotspots.isEmpty()) {
            Log.info("[EvictMapGenerator] Nothing sampled yet - the server has been idle, or the profiler has only just started.");
            return;
        }

        Log.info(
                "[EvictMapGenerator] Where the last minute of tick time went (@ samples, @ working):",
                sampler.profileSamples(),
                String.format(Locale.ROOT, "%.0f%%", sampler.snapshot().busyPercent())
        );

        // The rollup first: it is the form you can act on, and the method list
        // below is what you read once the rollup has told you where to look.
        Log.info("[EvictMapGenerator] By subsystem:");

        for (PerfSnapshot.Hotspot subsystem : sampler.subsystemProfile(20)) {
            Log.info(
                    "[EvictMapGenerator]   @  @",
                    String.format(Locale.ROOT, "%5.1f%%", subsystem.percent()),
                    subsystem.label()
            );
        }

        Log.info("[EvictMapGenerator] By method:");

        for (PerfSnapshot.Hotspot hotspot : hotspots) {
            Log.info(
                    "[EvictMapGenerator]   @  @",
                    String.format(Locale.ROOT, "%5.1f%%", hotspot.percent()),
                    hotspot.label()
            );
        }
    }

    /** One line of this server's current numbers, for both perf commands. */
    private String currentPerfLine() {
        PerfSampler sampler = perfSampler.get();

        if (sampler == null) {
            return "no measurements yet.";
        }

        PerfSnapshot perf = sampler.snapshot();

        if (!perf.hasData()) {
            return "no measurements yet.";
        }

        return String.format(
                Locale.ROOT,
                "%.1f TPS (mean %.1fms, p95 %.1fms, worst %.0fms), busy %.0f%%, "
                        + "%d units, %d buildings, %d bullets, heap %.1f/%.1fG, "
                        + "GC %.0fms/min",
                perf.tps(),
                perf.tickMeanMs(),
                perf.tickP95Ms(),
                perf.tickWorstMs(),
                perf.busyPercent(),
                perf.units(),
                perf.buildings(),
                perf.bullets(),
                perf.heapUsedBytes() / (1024d * 1024d * 1024d),
                perf.heapMaxBytes() / (1024d * 1024d * 1024d),
                perf.gcMillisPerMinute()
        );
    }

    private String profilerLine() {
        PerfSampler sampler = perfSampler.get();

        if (sampler == null) {
            return "Stack profiler: not up yet.";
        }

        return "Stack profiler: " + (sampler.isProfiling()
                ? "on, " + sampler.profileSamples()
                + " samples in the window ('profile' prints them)"
                : "off ('profile on')");
    }

    /** A Discord channel id is a snowflake: digits only, 15-22 of them. */
    private static boolean isChannelId(String value) {
        if (value.length() < 15 || value.length() > 22) {
            return false;
        }

        for (int index = 0; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) {
                return false;
            }
        }

        return true;
    }

    /**
     * chatlog: the Discord chat mirror's wiring. No argument prints the
     * checklist (token file, hub and every port of the pool - eleven channel
     * ids are eleven chances to paste one wrong); 'hub'/a port plus a channel
     * id wires one feed, plus 'off' unwires it; 'reload' re-reads the token
     * file; bare 'off' drops the channels (never the token file); 'test'
     * posts a line into every configured channel so the mis-pasted one shows
     * up as the channel that stays quiet.
     *
     * <p>There is deliberately no command that takes the token itself:
     * anything typed here lands in the server log.
     */
    private void handleChatLogCommand(String[] args) {
        if (chatLogReporter == null) {
            Log.err("[EvictMapGenerator] The chat mirror only runs on the hub.");
            return;
        }

        if (args.length == 0) {
            Log.info("[EvictMapGenerator] Discord chat mirror:");

            for (String line : chatLogReporter.statusLines(
                    settings.duelServerPort(),
                    settings.duelMaxWorkers()
            )) {
                Log.info("[EvictMapGenerator]   @", line);
            }

            Log.info(
                    "[EvictMapGenerator] Set @ in @ (never typed into this console - it would end up in the log), run 'chatlog reload', then 'chatlog setup <server-id>' to have the bot create the channels. Staff-only channels - they mirror everything players say.",
                    chatLogReporter.tokenKey(),
                    chatLogReporter.tokenPath()
            );
            return;
        }

        String target = args[0].trim().toLowerCase();
        String value = args.length >= 2 ? args[1].trim() : "";

        switch (target) {
            case "off" -> {
                chatLogReporter.disableAll();
                Log.info(
                        "[EvictMapGenerator] Chat mirror off everywhere; all channels dropped. The secrets file (@) is left alone - remove the token yourself if the bot is being retired.",
                        chatLogReporter.tokenPath()
                );
            }
            case "setup" -> {
                if (value.isEmpty()) {
                    Log.err("[EvictMapGenerator] Use: chatlog setup <server-id> (Discord Developer Mode > right-click the server > Copy Server ID). The bot needs the Manage Channels permission for this.");
                    return;
                }

                Log.info("[EvictMapGenerator] Creating the mirror channels in Discord; this takes a few seconds...");

                chatLogReporter.setupChannels(
                        value,
                        settings.duelServerPort(),
                        settings.duelMaxWorkers(),
                        lines -> {
                            for (String line : lines) {
                                Log.info("[EvictMapGenerator] @", line);
                            }
                        }
                );

                // Same bot, same Discord server: there is no reason to make an
                // admin find the id a second time for the slash commands.
                if (discordModCommands != null
                        && !discordModCommands.isConfigured()) {
                    discordModCommands.configure(value, "");
                    Log.info("[EvictMapGenerator] Discord /ban and /unban wired to the same server. 'discordcommands role <name>' picks who may use them; until then it is Discord's Administrator permission.");
                }
            }
            case "reload" -> {
                if (chatLogReporter.reloadToken()) {
                    Log.info("[EvictMapGenerator] Bot token loaded from @. Invite the bot to the server with View Channel + Send Messages on the mirror channels.", chatLogReporter.tokenPath());
                } else {
                    Log.err("[EvictMapGenerator] @ is not set in @. Add it there, then run this again.", chatLogReporter.tokenKey(), chatLogReporter.tokenPath());
                }
            }
            case "test" -> {
                if (!chatLogReporter.hasToken()) {
                    Log.err("[EvictMapGenerator] No bot token is loaded. Set @ in @ and run 'chatlog reload'.", chatLogReporter.tokenKey(), chatLogReporter.tokenPath());
                    return;
                }

                int tested = chatLogReporter.publishTest();

                if (tested == 0) {
                    Log.err("[EvictMapGenerator] No chat-mirror channel is set.");
                } else {
                    Log.info("[EvictMapGenerator] Test line queued into @ channel(s). One that stays quiet is wired to the wrong channel id.", tested);
                }
            }
            case "token" -> Log.err(
                    "[EvictMapGenerator] The token is never typed here - it would be written to the server log. Set @ in @ and run 'chatlog reload'.",
                    chatLogReporter.tokenKey(),
                    chatLogReporter.tokenPath()
            );
            case "hub" -> {
                if (value.isEmpty()) {
                    Log.err("[EvictMapGenerator] Use: chatlog hub <channel-id/off>");
                } else if (value.equalsIgnoreCase("off")) {
                    chatLogReporter.disableHub();
                    Log.info("[EvictMapGenerator] The hub's chat is no longer mirrored.");
                } else if (chatLogReporter.configureHub(value)) {
                    warnIfTokenMissing();
                    Log.info("[EvictMapGenerator] Hub chat mirror wired up. 'chatlog test' verifies every channel.");
                } else {
                    Log.err("[EvictMapGenerator] That is not a channel id. Enable Developer Mode in Discord, right-click the channel, Copy Channel ID.");
                }
            }
            default -> handleChatLogPort(target, value);
        }
    }

    private void handleChatLogPort(String target, String value) {
        int port;

        try {
            port = Integer.parseInt(target);
        } catch (NumberFormatException exception) {
            Log.err("[EvictMapGenerator] Use: chatlog [setup <server-id> | hub/<port> <channel-id/off> | reload | off | test]");
            return;
        }

        int basePort = settings.duelServerPort();
        int lastPort = basePort + settings.duelMaxWorkers() - 1;

        if (value.isEmpty()) {
            Log.err("[EvictMapGenerator] Use: chatlog @ <channel-id/off>", port);
            return;
        }

        if (value.equalsIgnoreCase("off")) {
            chatLogReporter.disablePort(port);
            Log.info("[EvictMapGenerator] Port @ is no longer mirrored.", port);
            return;
        }

        if (!chatLogReporter.configurePort(port, value)) {
            Log.err("[EvictMapGenerator] That is not a channel id. Enable Developer Mode in Discord, right-click the channel, Copy Channel ID.");
            return;
        }

        warnIfTokenMissing();

        if (port < basePort || port > lastPort) {
            Log.warn(
                    "[EvictMapGenerator] Port @ mirror wired up - but the pool currently uses ports @-@, so it will not see a match until that changes.",
                    port,
                    basePort,
                    lastPort
            );
        } else {
            Log.info("[EvictMapGenerator] Port @ chat mirror wired up. 'chatlog test' verifies every channel.", port);
        }
    }

    private void warnIfTokenMissing() {
        if (!chatLogReporter.hasToken()) {
            Log.warn(
                    "[EvictMapGenerator] No bot token is loaded yet - nothing will be posted until @ is set in @ and 'chatlog reload' has run.",
                    chatLogReporter.tokenKey(),
                    chatLogReporter.tokenPath()
            );
        }
    }

    /**
     * vpn: the log-only VPN scan. Status is the whole checklist - key,
     * where hits go, today's spending against the daily allowance - because
     * "is it working" has four different answers and the console should give
     * the right one. 'test' spends one lookup, which is the point: it proves
     * the key.
     */
    private void handleVpnScanCommand(String action, String ip) {
        if (vpnScan == null) {
            Log.err("[EvictMapGenerator] The VPN scan runs on the hub only.");
            return;
        }

        switch (action.toLowerCase()) {
            case "" -> {
                for (String line : vpnScan.statusLines()) {
                    Log.info("[EvictMapGenerator] @", line);
                }

                Log.info("[EvictMapGenerator] @", lockStatusLine());
            }
            case "lock" -> {
                switch (ip.toLowerCase()) {
                    case "on" -> {
                        settings.setVpnLockEnabled(true);
                        Log.info("[EvictMapGenerator] Lock on: an account's first join through a VPN, proxy or hosting range is held on the Fallen team until an admin frees it ('free', /free, Discord /free).");
                    }
                    case "off" -> {
                        settings.setVpnLockEnabled(false);
                        Log.info("[EvictMapGenerator] Lock off: joins are only written down again. Accounts already locked stay locked until freed.");
                    }
                    default -> Log.info("[EvictMapGenerator] @ Usage: vpn lock on/off", lockStatusLine());
                }
            }
            case "on" -> {
                settings.setVpnScanEnabled(true);

                if (vpnScan.hasKey()) {
                    Log.info("[EvictMapGenerator] VPN scan on, log only (vpnapi + ip-api): a join through a VPN, proxy or hosting range is written to the console and the ban log. Nothing is blocked.");
                } else {
                    Log.info("[EvictMapGenerator] VPN scan on, log only, with ip-api only - add @=... to @ and run 'vpn reload' for vpnapi.io as the second opinion. Nothing is blocked.", Extinction.core.io.Secrets.VPNAPI_KEY, Extinction.core.io.Secrets.path());
                }
            }
            case "off" -> {
                settings.setVpnScanEnabled(false);
                Log.info("[EvictMapGenerator] VPN scan off. Joins are not looked up until it is switched back on.");
            }
            case "reload" -> {
                if (vpnScan.reloadKey()) {
                    Log.info("[EvictMapGenerator] VPN scan: API key loaded from @. 'vpn test <ip>' proves it.", Extinction.core.io.Secrets.path());
                } else {
                    Log.warn("[EvictMapGenerator] VPN scan: @ is still not set in @ - scanning with ip-api only.", Extinction.core.io.Secrets.VPNAPI_KEY, Extinction.core.io.Secrets.path());
                }
            }
            case "test" -> {
                if (ip.isBlank()) {
                    Log.err("[EvictMapGenerator] Give an address to try: vpn test <ip>");
                    return;
                }

                vpnScan.test(ip, line -> Log.info("[EvictMapGenerator] VPN scan test - @", line));
            }
            default -> Log.err(
                    "[EvictMapGenerator] Usage: vpn [on/off/lock on/off/reload/test <ip>]"
            );
        }
    }

    private String lockStatusLine() {
        if (playerLock == null) {
            return "  Lock: decided on the hub.";
        }

        return "  Lock: " + (settings.vpnLockEnabled()
                ? "on - a first join through a VPN is held for an admin"
                : "off - log only")
                + "; " + playerLock.lockedCount() + " locked, "
                + playerLock.verifiedCount() + " verified ('free' lists and frees)";
    }

    /**
     * free: frees a locked account from the console - by UUID, or by a
     * part of the name when it matches exactly one. No argument lists them.
     */
    private void handleFreeCommand(String target) {
        if (playerLock == null) {
            Log.err("[EvictMapGenerator] Locks are freed on the hub.");
            return;
        }

        java.util.List<Extinction.moderation.lock.LockList.Entry> entries = playerLock.lockedEntries();

        if (target.isEmpty()) {
            if (entries.isEmpty()) {
                Log.info("[EvictMapGenerator] No account is locked.");
                return;
            }

            Log.info("[EvictMapGenerator] @ locked account(s):", entries.size());

            for (Extinction.moderation.lock.LockList.Entry entry : entries) {
                Log.info(
                        "[EvictMapGenerator]   @ (@) from @ - @",
                        arc.util.Strings.stripColors(entry.name()),
                        entry.uuid(),
                        entry.ip(),
                        entry.reason()
                );
            }

            return;
        }

        java.util.List<Extinction.moderation.lock.LockList.Entry> matches = new java.util.ArrayList<>();
        String needle = target.toLowerCase(java.util.Locale.ROOT);

        for (Extinction.moderation.lock.LockList.Entry entry : entries) {
            if (
                    entry.uuid().equals(target)
                            || arc.util.Strings.stripColors(entry.name())
                            .toLowerCase(java.util.Locale.ROOT).contains(needle)
            ) {
                matches.add(entry);
            }
        }

        if (matches.isEmpty()) {
            Log.err("[EvictMapGenerator] No locked account matches '@'. 'free' lists them.", target);
            return;
        }

        if (matches.size() > 1) {
            Log.err("[EvictMapGenerator] '@' matches @ locked accounts - give the UUID:", target, matches.size());

            for (Extinction.moderation.lock.LockList.Entry entry : matches) {
                Log.info("[EvictMapGenerator]   @ (@)", arc.util.Strings.stripColors(entry.name()), entry.uuid());
            }

            return;
        }

        PlayerLock.FreeResult result = playerLock.free(matches.get(0).uuid(), "the console");

        if (result.freed()) {
            Log.info("[EvictMapGenerator] @", result.line());
        } else {
            Log.err("[EvictMapGenerator] @", result.line());
        }
    }

    /**
     * banlog import: re-runs the import of existing bans. The automatic one
     * fires on the first start after the upgrade, necessarily before a log
     * webhook could have been configured, so this is how those bans get their
     * write-up.
     */
    private void handleBanImportCommand(String argument) {
        if (banManager == null) {
            Log.err("[EvictMapGenerator] Bans are managed on the hub, not on a match server.");
            return;
        }

        boolean forced = "force".equalsIgnoreCase(argument);

        if (settings.banImportLogged() && !forced) {
            Log.err("[EvictMapGenerator] The existing bans have already been written up. Running this again would post the whole back catalogue a second time; use 'banlog import force' if that is really what you want.");
            return;
        }

        if (banLogReporter != null && !settings.discordBanLogWebhookUrl().isBlank()) {
            Log.info("[EvictMapGenerator] Importing existing bans; entries are posted to the ban log at about one every 1.5s.");
        } else {
            Log.info("[EvictMapGenerator] Importing existing bans. No ban-log webhook is set, so nothing will be posted to Discord - set one with 'banlog <url>' first if you want the write-up.");
        }

        int entries = banManager.importNow();
        settings.markBanImportLogged();

        Log.info(
                "[EvictMapGenerator] Import finished: @ entr@ reported. See the lines above for the totals.",
                entries,
                entries == 1 ? "y" : "ies"
        );
    }

    /**
     * banplayer: ban a stored player whether or not they are online - the path
     * for harassment found in the chat log after the offender left. Resolves
     * the name against the plugin's player DB (vanilla 'ban name' only works
     * on connected players), then seeds a normal ban through banPlayerID, so
     * BanManager widens, kicks, syncs and logs it like any other.
     */
    private void handleBanCommand(String query) {
        if (banManager == null) {
            Log.err("[EvictMapGenerator] Bans are managed on the hub, not on a match server.");
            return;
        }

        if (query.isEmpty()) {
            Log.err("[EvictMapGenerator] Use: banplayer <name/uuid>");
            return;
        }

        playerDataManager.searchPlayerInfo(query, matches -> {
            if (matches.isEmpty()) {
                Log.err("[EvictMapGenerator] No stored players match '@'.", query);
                return;
            }

            if (matches.size() > 1) {
                Log.err("[EvictMapGenerator] '@' matches @ players; be more specific or use a UUID:", query, matches.size());
                for (PlayerDataManager.PlayerInfo info : matches) {
                    Log.info("[EvictMapGenerator] @", compactPlayerInfo(info));
                }
                return;
            }

            PlayerDataManager.PlayerInfo target = matches.get(0);

            if (Vars.netServer.admins.isIDBanned(target.uuid())) {
                Log.info("[EvictMapGenerator] @ (@) is already banned.", target.lastName(), target.uuid());
                return;
            }

            banManager.ban(Extinction.moderation.ban.BanRequest.admin(
                    target.uuid(),
                    Extinction.moderation.ban.BanOrigin.now(
                            "the console",
                            Extinction.moderation.ban.BanOrigin.HUB
                    )
            ));

            Log.info("[EvictMapGenerator] Banned @ (@). The line above shows everything the ban covered.", target.lastName(), target.uuid());
        });
    }

    private void handleRestartCommand(String action) {
        switch (action) {
            case "":
                restartManager.requestRestart();
                break;
            case "cancel":
                restartManager.cancelRestart();
                break;
            case "now":
                restartManager.restartNow();
                break;
            default:
                Log.err("[EvictMapGenerator] Use: restart [cancel/now]");
        }
    }

    private void generateTerrain(String[] args) {
        Long seed = runtime.parseSeedOrRandom(args);

        if (seed == null) {
            Log.err("[EvictMapGenerator] Seed must be a whole number or 'random'.");
            return;
        }

        if (!mindustry.gen.Groups.player.isEmpty()) {
            Log.warn("[EvictMapGenerator] Players are connected. Immediate generation is intended for testing. Reconnect clients afterwards if terrain is not refreshed.");
        }

        try {
            generate.accept(seed);
        } catch (Exception exception) {
            Log.err("[EvictMapGenerator] Generation failed.", exception);
        }
    }

    private void setSeed(String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("random")) {
            runtime.nextSeed = runtime.randomSeed();
            Log.info("[EvictMapGenerator] Next seed: @", runtime.nextSeed);
            return;
        }

        try {
            runtime.nextSeed = Long.parseLong(args[0]);
            Log.info("[EvictMapGenerator] Next seed: @", runtime.nextSeed);
        } catch (NumberFormatException exception) {
            Log.err("[EvictMapGenerator] Seed must be a whole number or 'random'.");
        }
    }

    private void showStatus() {
        Log.info("[EvictMapGenerator] autoGenerate: @", runtime.autoGenerate);
        Log.info("[EvictMapGenerator] nextSeed: @", runtime.nextSeed == null ? "random" : runtime.nextSeed);
        Log.info("[EvictMapGenerator] lastSeed: @", runtime.lastSeed == null ? "none" : runtime.lastSeed);
        Log.info("[EvictMapGenerator] unit build speed: @", settings.compactUnitBuildSpeedSettings());
        terrain.logStatus();
    }

    private void handleSetTimeCommand(String[] args) {
        if (args.length == 0) {
            Log.info("[EvictMapGenerator] time = @", teamManager.roundRuntimeMillis() / 1000);
            return;
        }

        long parsedTime;
        try {
            parsedTime = Long.parseLong(args[0]);
        } catch (NumberFormatException e) {
            Log.err("[EvictMapGenerator] time must be a long");
            return;
        }

        Log.info("[EvictMapGenerator] setting time to @", parsedTime);
        teamManager.setElapsedTimeMillis(parsedTime * 1000);
    }

    private void addCoreCap(String[] args) {
        final int additional;

        try {
            additional = Integer.parseInt(args[0]);
        } catch (NumberFormatException exception) {
            Log.err("Core-cap increment must be a whole number.");
            return;
        }

        if (additional <= 0 || additional > MAX_CORECAP_INCREMENT) {
            Log.info("Core-cap increment must be between 1 and " + MAX_CORECAP_INCREMENT + ".");
            return;
        }

        /*
         * Vanilla calculates the final cap from the base rule plus the team's
         * accumulated per-building modifiers. Increase all three vanilla core
         * blocks for future captures and adjust already existing cores once.
         */
        Blocks.coreShard.unitCapModifier += additional;
        Blocks.coreFoundation.unitCapModifier += additional;
        Blocks.coreNucleus.unitCapModifier += additional;

        for (Team team : Team.all) {
            int existingCoreCount = team.data().cores.size;
            if (existingCoreCount > 0) {
                team.data().unitCap += existingCoreCount * additional;
            }
        }

        Vars.state.rules.unitCapVariable = true;
        extraCoreCapPerCore += additional;

        Log.info("Added " + additional + " unit cap per core. Total added bonus per core: " + extraCoreCapPerCore + ".");
    }

    private void handleEloCommand(String[] args) {
        if (args.length < 2) {
            Log.err("[EvictMapGenerator] Use: elo <name/uuid> <value>");
            return;
        }

        int newElo;
        try {
            newElo = Integer.parseInt(args[1].trim());
        } catch (NumberFormatException exception) {
            Log.err("[EvictMapGenerator] ELO must be a whole number.");
            return;
        }

        if (newElo < 0) {
            Log.err("[EvictMapGenerator] ELO cannot be negative.");
            return;
        }

        String query = args[0].trim();

        playerDataManager.searchPlayerInfo(query, matches -> {
            if (matches.isEmpty()) {
                Log.err("[EvictMapGenerator] No stored players match '@'.", query);
                return;
            }

            if (matches.size() > 1) {
                Log.err("[EvictMapGenerator] '@' matches @ players; be more specific or use a UUID:", query, matches.size());
                for (PlayerDataManager.PlayerInfo info : matches) {
                    Log.info("[EvictMapGenerator] @", compactPlayerInfo(info));
                }
                return;
            }

            PlayerDataManager.PlayerInfo target = matches.get(0);
            int previousElo = target.elo();

            playerDataManager.setElo(target.uuid(), newElo, updated -> {
                if (updated) {
                    Log.info("[EvictMapGenerator] Set @'s ELO to @ (was @).", target.lastName(), newElo, previousElo);
                } else {
                    Log.err("[EvictMapGenerator] Could not update ELO for @.", target.lastName());
                }
            });
        });
    }

    private void showStoredPlayerInfo(String query) {
        playerDataManager.searchPlayerInfo(query, matches -> {
            if (matches.isEmpty()) {
                Log.err("[EvictMapGenerator] No stored players match '@'.", query);
                return;
            }

            if (matches.size() == 1) {
                Log.info("[EvictMapGenerator] @", plainPlayerInfo(matches.get(0)));
                return;
            }

            Log.info("[EvictMapGenerator] Stored player matches (@):", matches.size());
            for (PlayerDataManager.PlayerInfo info : matches) {
                Log.info("[EvictMapGenerator] @", compactPlayerInfo(info));
            }
        });
    }

    private String compactPlayerInfo(PlayerDataManager.PlayerInfo info) {
        return info.lastName()
                + " | uuid=" + info.uuid()
                + " | names=" + String.join(", ", info.knownNames())
                + " | playtime=" + formatDuration(info.totalPlaytimeMillis());
    }

    private String plainPlayerInfo(PlayerDataManager.PlayerInfo info) {
        return info.lastName()
                + " | uuid=" + info.uuid()
                + " | names=" + String.join(", ", info.knownNames())
                + ipInfo(info.uuid())
                + " | totalPlaytime=" + formatDuration(info.totalPlaytimeMillis())
                + " | normalWins=" + info.normalWins()
                + " | normalLosses=" + info.normalLosses()
                + " | normalPlayed=" + info.normalMatchesPlayed()
                + " | rankedWins=" + info.rankedWins()
                + " | rankedLosses=" + info.rankedLosses()
                + " | rankedPlayed=" + info.rankedMatchesPlayed()
                + " | elo=" + info.elo()
                + " | peakElo=" + info.peakElo();
    }

    /**
     * The player's last and all known IPs for the console only (to feed
     * {@code ban ip <ip>}). Nothing IP-related lives in the plugin's own DB -
     * this reads Mindustry's built-in admin store, which already tracks every
     * IP a UUID ever connected with.
     */
    private String ipInfo(String uuid) {
        if (Vars.netServer == null) {
            return "";
        }

        mindustry.net.Administration.PlayerInfo vanilla =
                Vars.netServer.admins.getInfoOptional(uuid);

        if (vanilla == null) {
            return " | lastIP=never connected here";
        }

        return " | lastIP=" + vanilla.lastIP
                + " | knownIPs=" + vanilla.ips.toString(", ");
    }

    static String formatDuration(long durationMillis) {
        long totalSeconds = Math.max(0L, durationMillis / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;

        StringBuilder result = new StringBuilder();

        if (hours > 0L) {
            result.append(hours).append("h ");
        }

        if (hours > 0L || minutes > 0L) {
            result.append(minutes).append("m ");
        }

        result.append(seconds).append("s");
        return result.toString();
    }
}
