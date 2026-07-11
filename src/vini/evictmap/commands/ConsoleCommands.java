package vini.evictmap.commands;

import vini.evictmap.*;
import vini.evictmap.duel.DuelServerManager;

import arc.util.CommandHandler;
import arc.util.Log;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.gen.Player;

import java.util.Map;
import java.util.function.LongConsumer;

/**
 * All dedicated-server console commands in one place.
 */
public final class ConsoleCommands {

    private final EvictRuntimeState runtime;
    private final EvictSettings settings;
    private final EvictTerrainGenerator terrain;
    private final TeamManager teamManager;
    private final PlayerDataManager playerDataManager;
    private final DuelServerManager duelServerManager;
    private final RankManager rankManager;
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
            LongConsumer generate
    ) {
        this.runtime = runtime;
        this.settings = settings;
        this.terrain = terrain;
        this.teamManager = teamManager;
        this.playerDataManager = playerDataManager;
        this.duelServerManager = duelServerManager;
        this.rankManager = rankManager;
        this.generate = generate;
    }

    public void register(CommandHandler handler) {
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

                    if (!Groups.player.isEmpty()) {
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
                            "[EvictMapGenerator] unit build speed: @",
                            settings.compactUnitBuildSpeedSettings()
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
                "evictbuildspeed",
                "[multiplier]",
                "Show or persist the unit factory build-speed multiplier applied each round. Defaults to 1.4 and is synced to spawned duel workers. Applies to the next generated match.",
                args -> {
                    if (args.length == 0) {
                        Log.info(
                                "[EvictMapGenerator] unit build speed: @",
                                settings.compactUnitBuildSpeedSettings()
                        );

                        return;
                    }

                    if (args.length != 1) {
                        Log.err(
                                "[EvictMapGenerator] Use: evictbuildspeed <multiplier>"
                        );

                        return;
                    }

                    try {
                        settings.setUnitBuildSpeedMultiplier(parseDecimal(args[0]));

                        Log.info(
                                "[EvictMapGenerator] Unit build speed saved as @. Applies to the next generated match and to spawned duel workers.",
                                settings.compactUnitBuildSpeedSettings()
                        );
                    } catch (NumberFormatException exception) {
                        Log.err(
                                "[EvictMapGenerator] Build speed multiplier must be a number."
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
                "evictelo",
                "<name/uuid> <value>",
                "Set a stored player's ranked ELO. The player is matched like evictplayerinfo (partial latest name first, then UUID); pass a UUID if a name is ambiguous. Peak ELO only rises, so a manual set never lowers it.",
                this::handleEloCommand
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
                "evictattritionrange",
                "[percent]",
                "Show or set the flat range attrition percentage",
                this::configureRangeAttrition
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

        handler.register(
                "evictrank",
                "[list/add/remove] [uuid] [rank]",
                "Manage tournament ranks by UUID. 'add <uuid> [commentator]' grants (default commentator), 'remove <uuid>' revokes, no args lists. Commentators get a [C] tag and may /restart 1v1s they spectate.",
                this::handleRankCommand
        );

        handler.register(
                "evicttime",
                "[time]",
                "Set the elapsed in-game time to a given number of seconds, or shows the elapsed time if used without arguments",
                this::handleSetTimeCommand
        );
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

        if (
                action.equals("remove")
                        || action.equals("revoke")
                        || action.equals("delete")
        ) {
            removeRank(args);
            return;
        }

        Log.err(
                "[EvictMapGenerator] Use: evictrank [list/add/remove] [uuid] [rank]"
        );
    }

    private void addRank(String[] args) {
        if (args.length < 2) {
            Log.err("[EvictMapGenerator] Use: evictrank add <uuid> [commentator]");
            return;
        }

        String uuid = args[1].trim();
        RankManager.Rank rank = args.length >= 3
                ? RankManager.Rank.parse(args[2])
                : RankManager.Rank.COMMENTATOR;

        if (rank == null) {
            Log.err(
                    "[EvictMapGenerator] Unknown rank '@'. Known ranks: commentator.",
                    args[2]
            );
            return;
        }

        if (!rankManager.grant(uuid, rank)) {
            Log.err(
                    "[EvictMapGenerator] Could not grant the rank; check the UUID."
            );
            return;
        }

        applyTagToOnline(uuid);

        Log.info(
                "[EvictMapGenerator] Granted @ to @. Applies to 1v1 matches started from now on.",
                rank.title,
                uuid
        );
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
            Log.info(
                    "[EvictMapGenerator]   @ = @",
                    entry.getKey(),
                    entry.getValue().title
            );
        }
    }

    private void applyTagToOnline(String uuid) {
        Player player =
                Groups.player.find(target -> target != null && target.uuid().equals(uuid));

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
            Log.err(exception.getMessage());
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
                            + settings.compactCoreAttritionSettings()
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

            settings.setCoreAttritionPercentages(
                    tier1To3,
                    tier4,
                    tier5
            );

            Log.info(
                    "Core attrition saved: "
                            + settings.compactCoreAttritionSettings()
            );
        } catch (NumberFormatException exception) {
            Log.err(
                    "Core attrition values must be numbers."
            );
        } catch (IllegalArgumentException exception) {
            Log.err(exception.getMessage());
        }
    }

    private void configureRangeAttrition(String[] args) {
        if (args.length == 0) {
            Log.info(
                    "Range attrition: "
                            + settings.compactRangeAttritionSettings()
            );
            return;
        }

        if (args.length != 1) {
            Log.err(
                    "Use: /attritionrange <percent>"
            );
            return;
        }

        try {
            settings.setRangeAttritionPercent(
                    Double.parseDouble(args[0])
            );

            Log.info(
                    "Range attrition saved: "
                            + settings.compactRangeAttritionSettings()
            );
        } catch (NumberFormatException exception) {
            Log.err(
                    "Range attrition value must be a number."
            );
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
                Log.err(
                        "[EvictMapGenerator] No stored players match '@'.",
                        query
                );
                return;
            }

            if (matches.size() > 1) {
                Log.err(
                        "[EvictMapGenerator] '@' matches @ players; be more specific or use a UUID:",
                        query,
                        matches.size()
                );

                for (PlayerDataManager.PlayerInfo info : matches) {
                    Log.info("[EvictMapGenerator] @", compactPlayerInfo(info));
                }

                return;
            }

            PlayerDataManager.PlayerInfo target = matches.get(0);
            int previousElo = target.elo();

            playerDataManager.setElo(target.uuid(), newElo, updated -> {
                if (updated) {
                    Log.info(
                            "[EvictMapGenerator] Set @'s ELO to @ (was @).",
                            target.lastName(),
                            newElo,
                            previousElo
                    );
                } else {
                    Log.err(
                            "[EvictMapGenerator] Could not update ELO for @.",
                            target.lastName()
                    );
                }
            });
        });
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
                + formatDuration(info.totalPlaytimeMillis());
    }

    private String plainPlayerInfo(PlayerDataManager.PlayerInfo info) {
        return info.lastName()
                + " | uuid=" + info.uuid()
                + " | names=" + String.join(", ", info.knownNames())
                + " | totalPlaytime="
                + formatDuration(info.totalPlaytimeMillis())
                + " | ffaPlaytime="
                + formatDuration(info.ffaPlaytimeMillis())
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
