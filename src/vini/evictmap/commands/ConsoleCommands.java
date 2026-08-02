package vini.evictmap.commands;

import vini.evictmap.*;
import vini.evictmap.gen.*;
import vini.evictmap.data.*;
import vini.evictmap.round.*;
import vini.evictmap.core.cmd.Commands;
import vini.evictmap.discord.BanLogReporter;
import vini.evictmap.discord.ChatLogReporter;
import vini.evictmap.moderation.BanManager;
import vini.evictmap.moderation.WordFilter;
import vini.evictmap.moderation.WordMatcher;
import vini.evictmap.discord.DiscordStatusReporter;
import vini.evictmap.duel.DuelServerManager;

import arc.util.CommandHandler;
import arc.util.Log;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.Team;

import java.util.function.LongConsumer;

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

    /** Null on a duel worker: the hub relays worker chat into Discord. */
    private final ChatLogReporter chatLogReporter;

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
            ChatLogReporter chatLogReporter,
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
        this.chatLogReporter = chatLogReporter;
        this.generate = generate;
    }

    public void register(CommandHandler handler) {
        Commands commands = new Commands();

        commands.command("evictgen").console()
                .args("seed:string?")
                .description("Generate Evict terrain on the loaded map now.")
                .run(ctx -> generateTerrain(ctx.raw()));

        commands.command("evictauto").console()
                .args("on/off:bool")
                .description("Generate terrain whenever a map is hosted. Default on.")
                .run(ctx -> {
                    runtime.autoGenerate = ctx.getBool("on/off", true);
                    Log.info("[EvictMapGenerator] Automatic generation is now @.", runtime.autoGenerate ? "ON" : "OFF");
                });

        commands.command("evictseed").console()
                .args("seed:string?")
                .description("Seed for the next generated map.")
                .run(ctx -> setSeed(ctx.raw()));

        commands.command("evictstatus").console()
                .description("Generator settings and required base-map size.")
                .run(ctx -> showStatus());

        commands.command("evictteamstatus").console()
                .description("Fallen-team spawn assignment for this round.")
                .run(ctx -> teamManager.logStatus());

        commands.command("evictbuildspeed").console()
                .args("multiplier:string?")
                .description("Unit factory build-speed multiplier. Applies next match.")
                .run(ctx -> setBuildSpeed(ctx.raw()));

        commands.command("evictwater").console()
                .args("tries-per-hex:string?", "normal-patch-tiles:string?", "large-patch-percent:string?", "large-patch-tiles:string?")
                .description("Water patch tries per hex, sizes and large-patch chance.")
                .run(ctx -> configureWater(ctx.raw()));

        registerOre(commands, "evictcopper", EvictSettings.OreKind.COPPER);
        registerOre(commands, "evictlead", EvictSettings.OreKind.LEAD);
        registerOre(commands, "evictcoal", EvictSettings.OreKind.COAL);
        registerOre(commands, "evicttitanium", EvictSettings.OreKind.TITANIUM);
        registerOre(commands, "evictthorium", EvictSettings.OreKind.THORIUM);
        registerOre(commands, "evictscrap", EvictSettings.OreKind.SCRAP);

        commands.command("evictorestatus").console()
                .description("All ore settings for the next generated match.")
                .run(ctx -> Log.info("[EvictMapGenerator] ores: @", settings.compactOreSettings()));

        commands.command("evictplayerinfo").console()
                .args("query:text?")
                .description("Look up a stored player by name or UUID; no argument lists all.")
                .run(ctx -> showStoredPlayerInfo(ctx.str("query", "").trim()));

        commands.command("evictban").console()
                .args("name/uuid:text")
                .description("Ban a stored player by name or UUID, online or not.")
                .run(ctx -> handleBanCommand(ctx.str("name/uuid", "").trim()));

        commands.command("evictelo").console()
                .args("name/uuid:string", "value:string")
                .description("Set a stored player's ranked ELO.")
                .run(ctx -> handleEloCommand(ctx.raw()));

        commands.command("evictwall").console()
                .args("full-wall:string?", "small-wall:string?", "open:string?", "passage:string?")
                .description("Wall-template percentages.")
                .run(ctx -> configureWalls(ctx.raw()));

        commands.command("evictcorecap").console()
                .args("additional-per-core:int")
                .description("Add unit-cap capacity to every core.")
                .run(ctx -> addCoreCap(ctx.raw()));

        commands.command("evictattritioncore").console()
                .args("t1-3:string?", "t4:string?", "t5:string?")
                .description("Capture attrition percentages per tier.")
                .run(ctx -> configureCoreAttrition(ctx.raw()));

        commands.command("evictattritionrange").console()
                .args("percent:string?")
                .description("Flat range attrition percentage.")
                .run(ctx -> configureRangeAttrition(ctx.raw()));

        commands.command("evictduelserver").console()
                .args("ip:string?", "basePort:string?", "maxWorkers:string?", "map:string?")
                .description("Worker pool /play uses: ip, first port, how many, map.")
                .run(ctx -> configureDuelServer(ctx.raw()));

        commands.command("evictdiscord").console()
                .args("url/off/test:string?")
                .description("Discord webhook for the live status message.")
                .run(ctx -> handleDiscordCommand(ctx.str("url/off/test", "").trim()));

        commands.command("evictbanlog").console()
                .args("url/off/test:string?")
                .description("Discord webhook for the ban log. Staff-only: it posts IPs.")
                .run(ctx -> handleBanLogCommand(ctx.str("url/off/test", "").trim()));

        commands.command("evictchatlog").console()
                .args("target:string?", "value:string?")
                .description("Discord chat mirror: status, setup <server-id>, hub/<port> + channel id, reload, off, test.")
                .run(ctx -> handleChatLogCommand(ctx.raw()));

        commands.command("evictbanimport").console()
                .args("force:string?")
                .description("Post every existing ban to the ban log. One-off; 'force' repeats it.")
                .run(ctx -> handleBanImportCommand(ctx.str("force", "").trim()));

        commands.command("evictwordfilter").console()
                .args("action:string?", "text:text?")
                .description("Banned-word filter: status, on/off, or test a line.")
                .run(ctx -> handleWordFilterCommand(
                        ctx.str("action", "").trim(),
                        ctx.str("text", "")
                ));

        commands.command("evictduelstatus").console()
                .description("List the active worker servers and who is in them.")
                .run(ctx -> duelServerManager.logStatus());

        // Registered on the hub for 'help' (the stdin interception answers it
        // first) and on a worker so a stray line there says where it is instead
        // of "Invalid command".
        commands.command("evictattach").console()
                .args("port:string?")
                .description("Send this console to a match server; no port comes back.")
                .run(ctx -> duelServerManager.console().command(ctx.str("port", "")));

        // The detach-only spelling the interception accepts, declared so it
        // shows up in 'help' as well - a way back that is not listed anywhere
        // is a way back nobody finds.
        commands.command("evictdetach").console()
                .description("Come back from a match server to this hub console.")
                .run(ctx -> duelServerManager.console().command(""));

        commands.command("evicttime").console()
                .args("time:string?")
                .description("Show or set the elapsed round time in seconds.")
                .run(ctx -> handleSetTimeCommand(ctx.raw()));

        commands.command("evictrestart").console()
                .args("action:string?")
                .description("Queue a graceful restart; 'cancel' drops it, 'now' exits.")
                .run(ctx -> handleRestartCommand(ctx.str("action", "").trim().toLowerCase()));

        commands.installConsole(handler);
    }

    private void registerOre(Commands commands, String name, EvictSettings.OreKind oreKind) {
        commands.command(name).console()
                .args("scale:string?", "threshold:string?", "octaves:string?", "falloff:string?")
                .description("Ore noise settings for the next generated match.")
                .run(ctx -> configureOre(ctx.raw(), name, oreKind));
    }

    /**
     * evictdiscord: no argument reports the current wiring, a URL adopts a new
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
     * evictbanlog: no argument reports the current wiring, a URL adopts a new
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
     * evictchatlog: the Discord chat mirror's wiring. No argument prints the
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
                    "[EvictMapGenerator] Set @ in @ (never typed into this console - it would end up in the log), run 'evictchatlog reload', then 'evictchatlog setup <server-id>' to have the bot create the channels. Staff-only channels - they mirror everything players say.",
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
                    Log.err("[EvictMapGenerator] Use: evictchatlog setup <server-id> (Discord Developer Mode > right-click the server > Copy Server ID). The bot needs the Manage Channels permission for this.");
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
                    Log.err("[EvictMapGenerator] No bot token is loaded. Set @ in @ and run 'evictchatlog reload'.", chatLogReporter.tokenKey(), chatLogReporter.tokenPath());
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
                    "[EvictMapGenerator] The token is never typed here - it would be written to the server log. Set @ in @ and run 'evictchatlog reload'.",
                    chatLogReporter.tokenKey(),
                    chatLogReporter.tokenPath()
            );
            case "hub" -> {
                if (value.isEmpty()) {
                    Log.err("[EvictMapGenerator] Use: evictchatlog hub <channel-id/off>");
                } else if (value.equalsIgnoreCase("off")) {
                    chatLogReporter.disableHub();
                    Log.info("[EvictMapGenerator] The hub's chat is no longer mirrored.");
                } else if (chatLogReporter.configureHub(value)) {
                    warnIfTokenMissing();
                    Log.info("[EvictMapGenerator] Hub chat mirror wired up. 'evictchatlog test' verifies every channel.");
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
            Log.err("[EvictMapGenerator] Use: evictchatlog [setup <server-id> | hub/<port> <channel-id/off> | reload | off | test]");
            return;
        }

        int basePort = settings.duelServerPort();
        int lastPort = basePort + settings.duelMaxWorkers() - 1;

        if (value.isEmpty()) {
            Log.err("[EvictMapGenerator] Use: evictchatlog @ <channel-id/off>", port);
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
            Log.info("[EvictMapGenerator] Port @ chat mirror wired up. 'evictchatlog test' verifies every channel.", port);
        }
    }

    private void warnIfTokenMissing() {
        if (!chatLogReporter.hasToken()) {
            Log.warn(
                    "[EvictMapGenerator] No bot token is loaded yet - nothing will be posted until @ is set in @ and 'evictchatlog reload' has run.",
                    chatLogReporter.tokenKey(),
                    chatLogReporter.tokenPath()
            );
        }
    }

    /**
     * evictwordfilter: show or switch the filter, or try a line against it. The
     * test matters - the filter bans by itself, so an entry that also fires on
     * an ordinary sentence hands out permanent bans for nothing.
     */
    private void handleWordFilterCommand(String action, String text) {
        switch (action.toLowerCase()) {
            case "" -> Log.info(
                    "[EvictMapGenerator] Word filter: @, watching @ word(s). Bans on chat and on names. Edit the list in BannedWords.java and rebuild; 'evictwordfilter test <text>' tries a line.",
                    settings.wordFilterEnabled() ? "on" : "off",
                    WordMatcher.wordCount()
            );
            case "on" -> {
                settings.setWordFilterEnabled(true);
                Log.info("[EvictMapGenerator] Word filter on: a filtered word now bans automatically.");
            }
            case "off" -> {
                settings.setWordFilterEnabled(false);
                Log.info("[EvictMapGenerator] Word filter off. Nothing is filtered until it is switched back on.");
            }
            case "test" -> {
                if (text.isBlank()) {
                    Log.err("[EvictMapGenerator] Give the text to try: evictwordfilter test <text>");
                    return;
                }

                String word = WordFilter.test(text);

                if (word == null) {
                    Log.info("[EvictMapGenerator] Clean - no ban.");
                } else {
                    Log.info(
                            "[EvictMapGenerator] Would ban: matches '@' from the word list.",
                            word
                    );
                }
            }
            default -> Log.err(
                    "[EvictMapGenerator] Usage: evictwordfilter [on/off/test <text>]"
            );
        }
    }

    /**
     * evictbanimport: re-runs the import of existing bans. The automatic one
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
            Log.err("[EvictMapGenerator] The existing bans have already been written up. Running this again would post the whole back catalogue a second time; use 'evictbanimport force' if that is really what you want.");
            return;
        }

        if (banLogReporter != null && !settings.discordBanLogWebhookUrl().isBlank()) {
            Log.info("[EvictMapGenerator] Importing existing bans; entries are posted to the ban log at about one every 1.5s.");
        } else {
            Log.info("[EvictMapGenerator] Importing existing bans. No ban-log webhook is set, so nothing will be posted to Discord - set one with 'evictbanlog <url>' first if you want the write-up.");
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
     * evictban: ban a stored player whether or not they are online - the path
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
            Log.err("[EvictMapGenerator] Use: evictban <name/uuid>");
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

            banManager.ban(vini.evictmap.moderation.BanRequest.admin(
                    target.uuid(),
                    vini.evictmap.moderation.BanOrigin.now(
                            "the console",
                            vini.evictmap.moderation.BanOrigin.HUB
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
                Log.err("[EvictMapGenerator] Use: evictrestart [cancel/now]");
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
        Log.info("[EvictMapGenerator] duel server: @", settings.compactDuelServerSettings());
        terrain.logStatus();
    }

    private void setBuildSpeed(String[] args) {
        if (args.length == 0) {
            Log.info("[EvictMapGenerator] unit build speed: @", settings.compactUnitBuildSpeedSettings());
            return;
        }

        try {
            settings.setUnitBuildSpeedMultiplier(parseDecimal(args[0]));
            Log.info("[EvictMapGenerator] Unit build speed saved as @. Applies to the next generated match and to spawned duel workers.", settings.compactUnitBuildSpeedSettings());
        } catch (NumberFormatException exception) {
            Log.err("[EvictMapGenerator] Build speed multiplier must be a number.");
        } catch (IllegalArgumentException exception) {
            Log.err("[EvictMapGenerator] @", exception.getMessage());
        }
    }

    private void configureWater(String[] args) {
        if (args.length == 0) {
            Log.info("[EvictMapGenerator] water: @", settings.compactWaterSettings());
            return;
        }

        if (args.length != 4) {
            Log.err("[EvictMapGenerator] Use: evictwater <tries-per-hex> <normal-patch-tiles> <large-patch-percent> <large-patch-tiles>");
            return;
        }

        try {
            settings.setWaterSettings(parseDecimal(args[0]), Integer.parseInt(args[1]), parseDecimal(args[2]), Integer.parseInt(args[3]));
            Log.info("[EvictMapGenerator] Saved evictwater. Applies to the next generated match: @", settings.compactWaterSettings());
        } catch (NumberFormatException exception) {
            Log.err("[EvictMapGenerator] Water tries and percents must be numbers; tile counts must be whole numbers.");
        } catch (IllegalArgumentException exception) {
            Log.err("[EvictMapGenerator] @", exception.getMessage());
        }
    }

    private void configureOre(String[] args, String command, EvictSettings.OreKind oreKind) {
        if (args.length == 0) {
            Log.info("[EvictMapGenerator] @: @", command, settings.compactOreSettings(oreKind));
            return;
        }

        if (args.length != 4) {
            Log.err("[EvictMapGenerator] Use: @ <scale> <threshold> <octaves> <falloff>", command);
            return;
        }

        try {
            settings.setOreSettings(oreKind, Double.parseDouble(args[0]), Double.parseDouble(args[1]), Double.parseDouble(args[2]), Double.parseDouble(args[3]));
            Log.info("[EvictMapGenerator] Saved @. Applies to the next generated match: @", command, settings.compactOreSettings(oreKind));
        } catch (NumberFormatException exception) {
            Log.err("[EvictMapGenerator] Ore settings must be numbers.");
        } catch (IllegalArgumentException exception) {
            Log.err("[EvictMapGenerator] @", exception.getMessage());
        }
    }

    private void configureDuelServer(String[] args) {
        if (args.length == 0) {
            Log.info("[EvictMapGenerator] Duel server: @", settings.compactDuelServerSettings());
            return;
        }

        try {
            int basePort = args.length >= 2 ? Integer.parseInt(args[1]) : settings.duelServerPort();
            int maxWorkers = args.length >= 3 ? Integer.parseInt(args[2]) : settings.duelMaxWorkers();
            String map = args.length >= 4 ? args[3] : settings.duelWorkerMap();

            settings.setDuelServer(args[0], basePort, maxWorkers, map);
            Log.info("[EvictMapGenerator] Duel server saved as @. This applies immediately and after restart.", settings.compactDuelServerSettings());
        } catch (NumberFormatException exception) {
            Log.err("[EvictMapGenerator] basePort and maxWorkers must be whole numbers.");
        } catch (IllegalArgumentException exception) {
            Log.err("[EvictMapGenerator] @", exception.getMessage());
        }
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

    private void configureWalls(String[] args) {
        if (args.length == 0) {
            Log.info("Walls: " + settings.compactWallSettings());
            return;
        }

        if (args.length != 4) {
            Log.info("Use: /wall <full-wall> <small-wall> <open> <passage>");
            return;
        }

        try {
            double fullWall = Double.parseDouble(args[0]);
            double smallWall = Double.parseDouble(args[1]);
            double open = Double.parseDouble(args[2]);
            double passage = Double.parseDouble(args[3]);

            settings.setWallPercentages(fullWall, smallWall, open, passage);
            Log.info("Wall settings saved: " + settings.compactWallSettings() + ". Applies to the next generated map.");
        } catch (NumberFormatException exception) {
            Log.err("Wall values must be numbers.");
        } catch (IllegalArgumentException exception) {
            Log.err(exception.getMessage());
        }
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

    private void configureCoreAttrition(String[] args) {
        if (args.length == 0) {
            Log.info("Core attrition: " + settings.compactCoreAttritionSettings());
            return;
        }

        if (args.length != 3) {
            Log.err("Use: /attritioncore <t1-3> <t4> <t5>");
            return;
        }

        try {
            double tier1To3 = Double.parseDouble(args[0]);
            double tier4 = Double.parseDouble(args[1]);
            double tier5 = Double.parseDouble(args[2]);

            settings.setCoreAttritionPercentages(tier1To3, tier4, tier5);
            Log.info("Core attrition saved: " + settings.compactCoreAttritionSettings());
        } catch (NumberFormatException exception) {
            Log.err("Core attrition values must be numbers.");
        } catch (IllegalArgumentException exception) {
            Log.err(exception.getMessage());
        }
    }

    private void configureRangeAttrition(String[] args) {
        if (args.length == 0) {
            Log.info("Range attrition: " + settings.compactRangeAttritionSettings());
            return;
        }

        try {
            settings.setRangeAttritionPercent(Double.parseDouble(args[0]));
            Log.info("Range attrition saved: " + settings.compactRangeAttritionSettings());
        } catch (NumberFormatException exception) {
            Log.err("Range attrition value must be a number.");
        } catch (IllegalArgumentException exception) {
            Log.err(exception.getMessage());
        }
    }

    private void handleEloCommand(String[] args) {
        if (args.length < 2) {
            Log.err("[EvictMapGenerator] Use: evictelo <name/uuid> <value>");
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

    private double parseDecimal(String value) {
        return Double.parseDouble(value.replace(',', '.'));
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
