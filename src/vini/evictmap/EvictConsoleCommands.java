package vini.evictmap;

import arc.util.CommandHandler;
import arc.util.Log;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.Team;
import mindustry.gen.Groups;

import java.util.function.LongConsumer;

/**
 * All dedicated-server console commands in one place.
 */
final class EvictConsoleCommands {

    private final EvictRuntimeState runtime;
    private final EvictSettings settings;
    private final EvictTerrainGenerator terrain;
    private final TeamManager teamManager;
    private final PlayerDataManager playerDataManager;
    private final DuelServerManager duelServerManager;
    private final LongConsumer generate;
    private final AttritionManager attritionManager;

    private static final int MAX_CORECAP_INCREMENT = 10000;

    private int extraCoreCapPerCore = 0;

    EvictConsoleCommands(
        EvictRuntimeState runtime,
        EvictSettings settings,
        EvictTerrainGenerator terrain,
        TeamManager teamManager,
        PlayerDataManager playerDataManager,
        DuelServerManager duelServerManager,
        LongConsumer generate,
        AttritionManager attritionManager
    ) {
        this.runtime = runtime;
        this.settings = settings;
        this.terrain = terrain;
        this.teamManager = teamManager;
        this.playerDataManager = playerDataManager;
        this.duelServerManager = duelServerManager;
        this.generate = generate;
        this.attritionManager = attritionManager;
    }

    void register(CommandHandler handler) {
        handler.register(
            "evictgen",
            "[seed]",
            "Generate Evict terrain immediately on the currently loaded map. Prefer evictauto before hosting a map.",
            args -> {
                Long seed = runtime.parseSeedOrRandom(args);

                if (seed == null) {
                    Log.err("[EvictMapGenerator] Seed must be a whole number or 'random'.");
                    return;
                }

                if (Groups.player.size() > 0) {
                    Log.warn(
                        "[EvictMapGenerator] Players are connected. Immediate generation is intended for testing. Reconnect clients afterwards if terrain is not refreshed."
                    );
                }

                try {
                    generate.accept(seed);
                } catch (Exception exception) {
                    Log.err("[EvictMapGenerator] Generation failed.", exception);
                }
            }
        );

        handler.register(
            "evictauto",
            "<on/off>",
            "Enable or disable terrain generation whenever a map is hosted or loaded. Defaults to ON.",
            args -> {
                String value = args[0].trim().toLowerCase();

                if (
                    value.equals("on")
                        || value.equals("true")
                        || value.equals("yes")
                ) {
                    runtime.autoGenerate = true;
                } else if (
                    value.equals("off")
                        || value.equals("false")
                        || value.equals("no")
                ) {
                    runtime.autoGenerate = false;
                } else {
                    Log.err("[EvictMapGenerator] Use: evictauto <on/off>");
                    return;
                }

                Log.info(
                    "[EvictMapGenerator] Automatic generation is now @.",
                    runtime.autoGenerate ? "ON" : "OFF"
                );
            }
        );

        handler.register(
            "evictseed",
            "[seed/random]",
            "Set the seed used for the next automatically generated map.",
            args -> {
                if (
                    args.length == 0
                        || args[0].equalsIgnoreCase("random")
                ) {
                    runtime.nextSeed = runtime.randomSeed();
                    Log.info(
                        "[EvictMapGenerator] Next seed: @",
                        runtime.nextSeed
                    );
                    return;
                }

                try {
                    runtime.nextSeed = Long.parseLong(args[0]);
                    Log.info(
                        "[EvictMapGenerator] Next seed: @",
                        runtime.nextSeed
                    );
                } catch (NumberFormatException exception) {
                    Log.err(
                        "[EvictMapGenerator] Seed must be a whole number or 'random'."
                    );
                }
            }
        );

        handler.register(
            "evictstatus",
            "Show generator settings and required base-map size.",
            args -> {
                Log.info(
                    "[EvictMapGenerator] autoGenerate: @",
                    runtime.autoGenerate
                );

                Log.info(
                    "[EvictMapGenerator] nextSeed: @",
                    runtime.nextSeed == null ? "random" : runtime.nextSeed
                );

                Log.info(
                    "[EvictMapGenerator] lastSeed: @",
                    runtime.lastSeed == null ? "none" : runtime.lastSeed
                );

                Log.info(
                    "[EvictMapGenerator] extinction terrain changes per tick: @",
                    teamManager.extinctionTerrainChangesPerTick()
                );

                Log.info(
                    "[EvictMapGenerator] duel server: @",
                    settings.compactDuelServerSettings()
                );

                terrain.logStatus();
            }
        );

        handler.register(
            "evictteamstatus",
            "Show Fallen-team spawn assignment status for the current round.",
            args -> teamManager.logStatus()
        );

        handler.register(
            "evictextinctiontiles",
            "[amount]",
            "Show or persist how many collapsed terrain tiles are converted to space per tick.",
            args -> {
                if (args.length == 0) {
                    Log.info(
                        "[EvictMapGenerator] Extinction terrain changes per tick: @",
                        teamManager.extinctionTerrainChangesPerTick()
                    );

                    return;
                }

                if (args.length != 1) {
                    Log.err(
                        "[EvictMapGenerator] Use: evictextinctiontiles <amount>"
                    );

                    return;
                }

                try {
                    int amount = Integer.parseInt(args[0]);

                    settings.setExtinctionTerrainChangesPerTick(amount);
                    teamManager.setExtinctionTerrainChangesPerTick(
                        settings.extinctionTerrainChangesPerTick()
                    );

                    Log.info(
                        "[EvictMapGenerator] Extinction terrain changes per tick saved as @. This applies immediately and after restart.",
                        teamManager.extinctionTerrainChangesPerTick()
                    );
                } catch (NumberFormatException exception) {
                    Log.err(
                        "[EvictMapGenerator] Extinction terrain changes per tick must be a whole number."
                    );
                } catch (IllegalArgumentException exception) {
                    Log.err(
                        "[EvictMapGenerator] @",
                        exception.getMessage()
                    );
                }
            }
        );

        registerWaterSettingsCommand(handler);

        registerOrePresetCommand(
            handler,
            "evictcopper",
            EvictSettings.OreKind.COPPER
        );

        registerOrePresetCommand(
            handler,
            "evictlead",
            EvictSettings.OreKind.LEAD
        );

        registerOrePresetCommand(
            handler,
            "evictcoal",
            EvictSettings.OreKind.COAL
        );

        registerOrePresetCommand(
            handler,
            "evicttitanium",
            EvictSettings.OreKind.TITANIUM
        );

        registerOrePresetCommand(
            handler,
            "evictthorium",
            EvictSettings.OreKind.THORIUM
        );

        registerOrePresetCommand(
            handler,
            "evictscrap",
            EvictSettings.OreKind.SCRAP
        );

        handler.register(
            "evictorestatus",
            "Show persistent ore settings used for the next generated match.",
            args -> Log.info(
                "[EvictMapGenerator] ores: @",
                settings.compactOreSettings()
            )
        );

        handler.register(
            "evictplayerinfo",
            "[name/uuid]",
            "Search stored player data by partial name or UUID. With no argument, list all stored players.",
            args -> showStoredPlayerInfo(String.join(" ", args).trim())
        );

        handler.register(
                "evictwall",
                "[full-wall] [small-wall] [open] [passage]",
                "Show or set persistent wall-template percentages",
                this::configureWalls
        );

        handler.register(
                "evictcorecap",
                "<additional-per-core>",
                "Add unit-cap capacity to every core",
                this::addCoreCap
        );

        handler.register(
                "evictattritioncore",
                "[t1-3] [t4] [t5]",
                "Show or set capture attrition percentages",
                this::configureCoreAttrition
        );

        handler.register(
            "evictduelserver",
            "[ip] [basePort] [maxWorkers] [map]",
            "Show or set the on-demand 1v1 worker pool that /play uses. ip is the address clients reach the workers at; basePort is the first worker port; maxWorkers is how many duels may run at once (1-10); map is the map workers host. Omitted values keep their current setting.",
            args -> {
                if (args.length == 0) {
                    Log.info(
                        "[EvictMapGenerator] Duel server: @",
                        settings.compactDuelServerSettings()
                    );
                    return;
                }

                try {
                    int basePort = args.length >= 2
                        ? Integer.parseInt(args[1])
                        : settings.duelServerPort();
                    int maxWorkers = args.length >= 3
                        ? Integer.parseInt(args[2])
                        : settings.duelMaxWorkers();
                    String map = args.length >= 4
                        ? args[3]
                        : settings.duelWorkerMap();

                    settings.setDuelServer(args[0], basePort, maxWorkers, map);

                    Log.info(
                        "[EvictMapGenerator] Duel server saved as @. This applies immediately and after restart.",
                        settings.compactDuelServerSettings()
                    );
                } catch (NumberFormatException exception) {
                    Log.err(
                        "[EvictMapGenerator] basePort and maxWorkers must be whole numbers."
                    );
                } catch (IllegalArgumentException exception) {
                    Log.err("[EvictMapGenerator] @", exception.getMessage());
                }
            }
        );

        handler.register(
            "evictduelstatus",
            "List the active 1v1 worker servers and who is in them.",
            args -> duelServerManager.logStatus()
        );
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

            // TODO: is this bugged? `evictwall 100 0 0 0`
            //  doesn't seem to work as expected
            settings.setWallPercentages(
                    fullWall,
                    smallWall,
                    open,
                    passage
            );

            Log.info(
                    "Wall settings saved: "
                            + settings.compactWallSettings()
                            + ". Applies to the next generated map."
            );
        } catch (NumberFormatException exception) {
            Log.err("Wall values must be numbers.");
        } catch (IllegalArgumentException exception) {
            Log.err("" + exception.getMessage() + "");
        }
    }

    private void addCoreCap(String[] args) {
        if (args.length != 1) {
            Log.err("Use: /corecap <additional-per-core>");
            return;
        }

        final int additional;

        try {
            additional = Integer.parseInt(args[0]);
        } catch (NumberFormatException exception) {
            Log.err("Core-cap increment must be a whole number.");
            return;
        }

        if (additional <= 0 || additional > MAX_CORECAP_INCREMENT) {
            Log.info(
                    "Core-cap increment must be between 1 and "
                            + MAX_CORECAP_INCREMENT
                            + "."
            );
            return;
        }

        /**
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

        Log.info(
                "Added "
                        + additional
                        + " unit cap per core. Total added bonus per core: "
                        + extraCoreCapPerCore
                        + "."
        );
    }
    private void configureCoreAttrition(String[] args) {

        if (args.length == 0) {
            Log.info(
                    "Core attrition: "
                            + attritionManager.compactCoreSettings()
            );
            return;
        }

        if (args.length != 3) {
            Log.err(
                    "Use: /attritioncore <t1-3> <t4> <t5>"
            );
            return;
        }

        try {
            double tier1To3 = Double.parseDouble(args[0]);
            double tier4 = Double.parseDouble(args[1]);
            double tier5 = Double.parseDouble(args[2]);

            attritionManager.setCoreDeathChancesPercent(
                    tier1To3,
                    tier4,
                    tier5
            );

            Log.info(
                    "Core attrition saved: "
                            + attritionManager.compactCoreSettings()
            );
        } catch (NumberFormatException exception) {
            Log.err(
                    "Core attrition values must be numbers."
            );
        } catch (IllegalArgumentException exception) {
            Log.err(exception.getMessage());
        }
    }

    private void showStoredPlayerInfo(String query) {
        playerDataManager.searchPlayerInfo(
            query,
            matches -> {
                if (matches.isEmpty()) {
                    Log.err(
                        "[EvictMapGenerator] No stored players match '@'.",
                        query
                    );
                    return;
                }

                if (matches.size() == 1) {
                    Log.info(
                        "[EvictMapGenerator] @",
                        plainPlayerInfo(matches.get(0))
                    );
                    return;
                }

                Log.info(
                    "[EvictMapGenerator] Stored player matches (@):",
                    matches.size()
                );

                for (PlayerDataManager.PlayerInfo info : matches) {
                    Log.info(
                        "[EvictMapGenerator] @",
                        compactPlayerInfo(info)
                    );
                }
            }
        );
    }

    private String compactPlayerInfo(PlayerDataManager.PlayerInfo info) {
        return info.lastName()
            + " | uuid=" + info.uuid()
            + " | names=" + String.join(", ", info.knownNames())
            + " | FFA=" + info.ffaWon() + "/" + info.ffaPlayed()
            + " | playtime="
            + EvictCommands.formatDuration(info.totalPlaytimeMillis());
    }

    private String plainPlayerInfo(PlayerDataManager.PlayerInfo info) {
        return info.lastName()
            + " | uuid=" + info.uuid()
            + " | names=" + String.join(", ", info.knownNames())
            + " | totalPlaytime="
            + EvictCommands.formatDuration(info.totalPlaytimeMillis())
            + " | ffaPlaytime="
            + EvictCommands.formatDuration(info.ffaPlaytimeMillis())
            + " | ffaWon=" + info.ffaWon()
            + " | ffaPlayed=" + info.ffaPlayed()
            + " | rankedWins=" + info.rankedWins()
            + " | rankedLosses=" + info.rankedLosses()
            + " | rankedPlayed=" + info.rankedMatchesPlayed()
            + " | elo=" + info.elo()
            + " | peakElo=" + info.peakElo();
    }

    private void registerWaterSettingsCommand(CommandHandler handler) {
        handler.register(
            "evictwater",
            "[tries-per-hex] [normal-patch-tiles] [large-patch-percent] [large-patch-tiles]",
            "Show or persist water patch tries per hex, normal size and large-patch chance for the next generated match.",
            args -> {
                if (args.length == 0) {
                    Log.info(
                        "[EvictMapGenerator] water: @",
                        settings.compactWaterSettings()
                    );

                    return;
                }

                if (args.length != 4) {
                    Log.err(
                        "[EvictMapGenerator] Use: evictwater <tries-per-hex> <normal-patch-tiles> <large-patch-percent> <large-patch-tiles>"
                    );

                    return;
                }

                try {
                    settings.setWaterSettings(
                        parseDecimal(args[0]),
                        Integer.parseInt(args[1]),
                        parseDecimal(args[2]),
                        Integer.parseInt(args[3])
                    );

                    Log.info(
                        "[EvictMapGenerator] Saved evictwater. Applies to the next generated match: @",
                        settings.compactWaterSettings()
                    );
                } catch (NumberFormatException exception) {
                    Log.err(
                        "[EvictMapGenerator] Water tries and percents must be numbers; tile counts must be whole numbers."
                    );
                } catch (IllegalArgumentException exception) {
                    Log.err(
                        "[EvictMapGenerator] @",
                        exception.getMessage()
                    );
                }
            }
        );
    }

    private void registerOrePresetCommand(
        CommandHandler handler,
        String command,
        EvictSettings.OreKind oreKind
    ) {
        handler.register(
            command,
            "[scale] [threshold] [octaves] [falloff]",
            "Show or persist editor-style ore noise settings for the next generated match.",
            args -> {
                if (args.length == 0) {
                    Log.info(
                        "[EvictMapGenerator] @: @",
                        command,
                        settings.compactOreSettings(oreKind)
                    );

                    return;
                }

                if (args.length != 4) {
                    Log.err(
                        "[EvictMapGenerator] Use: @ <scale> <threshold> <octaves> <falloff>",
                        command
                    );

                    return;
                }

                try {
                    settings.setOreSettings(
                        oreKind,
                        Double.parseDouble(args[0]),
                        Double.parseDouble(args[1]),
                        Double.parseDouble(args[2]),
                        Double.parseDouble(args[3])
                    );

                    Log.info(
                        "[EvictMapGenerator] Saved @. Applies to the next generated match: @",
                        command,
                        settings.compactOreSettings(oreKind)
                    );
                } catch (NumberFormatException exception) {
                    Log.err(
                        "[EvictMapGenerator] Ore settings must be numbers."
                    );
                } catch (IllegalArgumentException exception) {
                    Log.err(
                        "[EvictMapGenerator] @",
                        exception.getMessage()
                    );
                }
            }
        );
    }

    private double parseDecimal(String value) {
        return Double.parseDouble(value.replace(',', '.'));
    }
}
