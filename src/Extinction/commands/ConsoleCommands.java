package Extinction.commands;

import Extinction.*;
import Extinction.gen.*;
import Extinction.data.*;
import Extinction.round.*;
import Extinction.core.cmd.Commands;
import Extinction.discord.ChatLogReporter;
import Extinction.moderation.vpn.VpnScan;

import arc.util.CommandHandler;
import arc.util.Log;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.Team;

import java.util.Locale;
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

    /** Null on a duel worker: the hub looks every join up, log only. */
    private final VpnScan vpnScan;

    /** Null on a duel worker: the hub owns the lock list. */
    private final PlayerLock playerLock;

    /** Null on a duel worker: the hub relays worker chat into Discord. */
    private final ChatLogReporter chatLogReporter;

    /** Null on a duel worker: only the hub answers Discord's /ban. */
    private final DiscordModeration discordModCommands;

    private final LongConsumer generate;

    private static final int MAX_CORECAP_INCREMENT = 10000;

    private int extraCoreCapPerCore = 0;

    public ConsoleCommands(
            EvictRuntimeState runtime,
            EvictSettings settings,
            EvictTerrainGenerator terrain,
            TeamManager teamManager,
            PlayerDataManager playerDataManager,
            VpnScan vpnScan,
            PlayerLock playerLock,
            ChatLogReporter chatLogReporter,
            DiscordModeration discordModCommands,
            LongConsumer generate
    ) {
        this.runtime = runtime;
        this.settings = settings;
        this.terrain = terrain;
        this.teamManager = teamManager;
        this.playerDataManager = playerDataManager;
        this.vpnScan = vpnScan;
        this.playerLock = playerLock;
        this.chatLogReporter = chatLogReporter;
        this.discordModCommands = discordModCommands;
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

        commands.command("corecap").console()
                .args("additional-per-core:int")
                .description("Add unit-cap capacity to every core.")
                .run(ctx -> addCoreCap(ctx.raw()));

        commands.command("chatlog").console()
                .args("target:string?", "value:string?")
                .description("Discord chat mirror: status, setup <server-id>, hub/<port> + channel id, reload, off, test.")
                .run(ctx -> handleChatLogCommand(ctx.raw()));

        commands.command("vpn").console()
                .args("action:string?", "value:string?")
                .description("VPN scan: status, on/off, lock on/off, reload the key, test <ip>.")
                .run(ctx -> handleVpnScanCommand(
                        ctx.str("action", "").trim(),
                        ctx.str("value", "").trim()
                ));

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
                    Config.duelServerPort,
                    Config.duelMaxWorkers
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
                        Config.duelServerPort,
                        Config.duelMaxWorkers,
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

        int basePort = Config.duelServerPort;
        int lastPort = basePort + Config.duelMaxWorkers - 1;

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
                        Config.vpnLock = true;
                        Config.save();
                        Log.info("[EvictMapGenerator] Lock on: an account's first join through a VPN, proxy or hosting range is held on the Fallen team until an admin frees it ('free', /free, Discord /free).");
                    }
                    case "off" -> {
                        Config.vpnLock = false;
                        Config.save();
                        Log.info("[EvictMapGenerator] Lock off: new accounts are not looked up or locked. Accounts already locked stay locked until freed.");
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

        return "  Lock: " + (Config.vpnLock
                ? "on - a first join through a VPN is held for an admin"
                : "off - new accounts are not looked up")
                + "; " + playerLock.lockedCount() + " locked, "
                + playerLock.verifiedCount() + " verified ('free' lists and frees)";
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
}
