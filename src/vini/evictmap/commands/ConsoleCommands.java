package vini.evictmap.commands;

import vini.evictmap.*;
import vini.evictmap.gen.*;
import vini.evictmap.data.*;
import vini.evictmap.round.*;
import vini.evictmap.core.cmd.Commands;
import vini.evictmap.core.util.Players;
import vini.evictmap.duel.DuelServerManager;

import arc.util.CommandHandler;
import arc.util.Log;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.Team;
import mindustry.gen.Player;

import java.util.Map;
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
    private final RankManager rankManager;
    private final RestartManager restartManager;
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
            RankManager rankManager,
            RestartManager restartManager,
            LongConsumer generate
    ) {
        this.runtime = runtime;
        this.settings = settings;
        this.terrain = terrain;
        this.teamManager = teamManager;
        this.playerDataManager = playerDataManager;
        this.duelServerManager = duelServerManager;
        this.rankManager = rankManager;
        this.restartManager = restartManager;
        this.generate = generate;
    }

    public void register(CommandHandler handler) {
        Commands commands = new Commands();

        commands.command("evictgen").console()
                .args("seed:string?")
                .description("Generate Evict terrain immediately on the currently loaded map. Prefer evictauto before hosting a map.")
                .run(ctx -> generateTerrain(ctx.raw()));

        commands.command("evictauto").console()
                .args("on/off:bool")
                .description("Enable or disable terrain generation whenever a map is hosted or loaded. Defaults to ON.")
                .run(ctx -> {
                    runtime.autoGenerate = ctx.getBool("on/off", true);
                    Log.info("[EvictMapGenerator] Automatic generation is now @.", runtime.autoGenerate ? "ON" : "OFF");
                });

        commands.command("evictseed").console()
                .args("seed:string?")
                .description("Set the seed used for the next automatically generated map.")
                .run(ctx -> setSeed(ctx.raw()));

        commands.command("evictstatus").console()
                .description("Show generator settings and required base-map size.")
                .run(ctx -> showStatus());

        commands.command("evictteamstatus").console()
                .description("Show Fallen-team spawn assignment status for the current round.")
                .run(ctx -> teamManager.logStatus());

        commands.command("evictbuildspeed").console()
                .args("multiplier:string?")
                .description("Show or persist the unit factory build-speed multiplier applied each round. Defaults to 1.4 and is synced to spawned duel workers. Applies to the next generated match.")
                .run(ctx -> setBuildSpeed(ctx.raw()));

        commands.command("evictwater").console()
                .args("tries-per-hex:string?", "normal-patch-tiles:string?", "large-patch-percent:string?", "large-patch-tiles:string?")
                .description("Show or persist water patch tries per hex, normal size and large-patch chance for the next generated match.")
                .run(ctx -> configureWater(ctx.raw()));

        registerOre(commands, "evictcopper", EvictSettings.OreKind.COPPER);
        registerOre(commands, "evictlead", EvictSettings.OreKind.LEAD);
        registerOre(commands, "evictcoal", EvictSettings.OreKind.COAL);
        registerOre(commands, "evicttitanium", EvictSettings.OreKind.TITANIUM);
        registerOre(commands, "evictthorium", EvictSettings.OreKind.THORIUM);
        registerOre(commands, "evictscrap", EvictSettings.OreKind.SCRAP);

        commands.command("evictorestatus").console()
                .description("Show persistent ore settings used for the next generated match.")
                .run(ctx -> Log.info("[EvictMapGenerator] ores: @", settings.compactOreSettings()));

        commands.command("evictplayerinfo").console()
                .args("query:text?")
                .description("Search stored player data by partial name or UUID. With no argument, list all stored players.")
                .run(ctx -> showStoredPlayerInfo(ctx.str("query", "").trim()));

        commands.command("evictelo").console()
                .args("name/uuid:string", "value:string")
                .description("Set a stored player's ranked ELO. Matched like evictplayerinfo (partial latest name first, then UUID); pass a UUID if a name is ambiguous. Peak ELO only rises.")
                .run(ctx -> handleEloCommand(ctx.raw()));

        commands.command("evictwall").console()
                .args("full-wall:string?", "small-wall:string?", "open:string?", "passage:string?")
                .description("Show or set persistent wall-template percentages")
                .run(ctx -> configureWalls(ctx.raw()));

        commands.command("evictcorecap").console()
                .args("additional-per-core:int")
                .description("Add unit-cap capacity to every core")
                .run(ctx -> addCoreCap(ctx.raw()));

        commands.command("evictattritioncore").console()
                .args("t1-3:string?", "t4:string?", "t5:string?")
                .description("Show or set capture attrition percentages")
                .run(ctx -> configureCoreAttrition(ctx.raw()));

        commands.command("evictattritionrange").console()
                .args("percent:string?")
                .description("Show or set the flat range attrition percentage")
                .run(ctx -> configureRangeAttrition(ctx.raw()));

        commands.command("evictduelserver").console()
                .args("ip:string?", "basePort:string?", "maxWorkers:string?", "map:string?")
                .description("Show or set the on-demand worker pool that /play uses. ip is the address clients reach workers at; basePort the first worker port; maxWorkers how many duels run at once (1-10); map the map workers host. Omitted values keep current.")
                .run(ctx -> configureDuelServer(ctx.raw()));

        commands.command("evictduelstatus").console()
                .description("List the active worker servers and who is in them.")
                .run(ctx -> duelServerManager.logStatus());

        commands.command("evictrank").console()
                .args("action:string?", "uuid:string?", "rank:string?")
                .description("Manage tournament ranks by UUID. 'add <uuid> [commentator]' grants, 'remove <uuid>' revokes, no args lists. Commentators get a [C] tag and may /restart matches they spectate.")
                .run(ctx -> handleRankCommand(ctx.raw()));

        commands.command("evicttime").console()
                .args("time:string?")
                .description("Set the elapsed in-game time to a given number of seconds, or show the elapsed time with no argument.")
                .run(ctx -> handleSetTimeCommand(ctx.raw()));

        commands.command("evictrestart").console()
                .args("action:string?")
                .description("Queue a graceful restart for updates (fires at the next safe moment). 'cancel' drops it; 'now' exits immediately. Needs the start-script loop - see docs/RESTART_LOOP.md.")
                .run(ctx -> handleRestartCommand(ctx.str("action", "").trim().toLowerCase()));

        commands.installConsole(handler);
    }

    private void registerOre(Commands commands, String name, EvictSettings.OreKind oreKind) {
        commands.command(name).console()
                .args("scale:string?", "threshold:string?", "octaves:string?", "falloff:string?")
                .description("Show or persist editor-style ore noise settings for the next generated match.")
                .run(ctx -> configureOre(ctx.raw(), name, oreKind));
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

    private void handleRankCommand(String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            listRanks();
            return;
        }

        String action = args[0].trim().toLowerCase();

        if (action.equals("add") || action.equals("grant") || action.equals("set")) {
            addRank(args);
            return;
        }

        if (action.equals("remove") || action.equals("revoke") || action.equals("delete")) {
            removeRank(args);
            return;
        }

        Log.err("[EvictMapGenerator] Use: evictrank [list/add/remove] [uuid] [rank]");
    }

    private void addRank(String[] args) {
        if (args.length < 2) {
            Log.err("[EvictMapGenerator] Use: evictrank add <uuid> [commentator]");
            return;
        }

        String uuid = args[1].trim();
        RankManager.Rank rank = args.length >= 3 ? RankManager.Rank.parse(args[2]) : RankManager.Rank.COMMENTATOR;

        if (rank == null) {
            Log.err("[EvictMapGenerator] Unknown rank '@'. Known ranks: commentator.", args[2]);
            return;
        }

        if (!rankManager.grant(uuid, rank)) {
            Log.err("[EvictMapGenerator] Could not grant the rank; check the UUID.");
            return;
        }

        applyTagToOnline(uuid);
        Log.info("[EvictMapGenerator] Granted @ to @. Applies to matches started from now on.", rank.title, uuid);
    }

    private void removeRank(String[] args) {
        if (args.length < 2) {
            Log.err("[EvictMapGenerator] Use: evictrank remove <uuid>");
            return;
        }

        String uuid = args[1].trim();

        if (!rankManager.revoke(uuid)) {
            Log.info("[EvictMapGenerator] @ had no rank to remove.", uuid);
            return;
        }

        applyTagToOnline(uuid);
        Log.info("[EvictMapGenerator] Removed the rank from @.", uuid);
    }

    private void listRanks() {
        Map<String, RankManager.Rank> ranks = rankManager.snapshot();

        if (ranks.isEmpty()) {
            Log.info("[EvictMapGenerator] No tournament ranks are granted.");
            return;
        }

        Log.info("[EvictMapGenerator] Tournament ranks (@):", ranks.size());
        for (Map.Entry<String, RankManager.Rank> entry : ranks.entrySet()) {
            Log.info("[EvictMapGenerator]   @ = @", entry.getKey(), entry.getValue().title);
        }
    }

    private void applyTagToOnline(String uuid) {
        Player player = Players.byUuid(uuid);
        if (player != null) {
            rankManager.applyNameTag(player);
        }
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
                + " | FFA=" + info.ffaWon() + "/" + info.ffaPlayed()
                + " | playtime=" + formatDuration(info.totalPlaytimeMillis());
    }

    private String plainPlayerInfo(PlayerDataManager.PlayerInfo info) {
        return info.lastName()
                + " | uuid=" + info.uuid()
                + " | names=" + String.join(", ", info.knownNames())
                + " | totalPlaytime=" + formatDuration(info.totalPlaytimeMillis())
                + " | ffaPlaytime=" + formatDuration(info.ffaPlaytimeMillis())
                + " | ffaWon=" + info.ffaWon()
                + " | ffaPlayed=" + info.ffaPlayed()
                + " | rankedWins=" + info.rankedWins()
                + " | rankedLosses=" + info.rankedLosses()
                + " | rankedPlayed=" + info.rankedMatchesPlayed()
                + " | elo=" + info.elo()
                + " | peakElo=" + info.peakElo();
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
