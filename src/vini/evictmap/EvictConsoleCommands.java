package vini.evictmap;

import arc.util.CommandHandler;
import arc.util.Log;
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
    private final LongConsumer generate;

    EvictConsoleCommands(
        EvictRuntimeState runtime,
        EvictSettings settings,
        EvictTerrainGenerator terrain,
        TeamManager teamManager,
        PlayerDataManager playerDataManager,
        LongConsumer generate
    ) {
        this.runtime = runtime;
        this.settings = settings;
        this.terrain = terrain;
        this.teamManager = teamManager;
        this.playerDataManager = playerDataManager;
        this.generate = generate;
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
            "Enable or disable terrain generation whenever a map is hosted or loaded.",
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
    }

    private void showStoredPlayerInfo(String query) {
        playerDataManager.searchPlayerInfo(
            query,
            matches -> {
                if (matches.isEmpty()) {
                    Log.info(
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
}
