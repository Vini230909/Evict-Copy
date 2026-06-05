package vini.evictmap;

import arc.Events;
import arc.util.CommandHandler;
import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.EventType.CoreChangeEvent;
import mindustry.game.EventType.GameOverEvent;
import mindustry.game.EventType.PlayEvent;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.PlayerJoin;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.mod.Plugin;
import mindustry.world.Tile;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * First functional Evict-style map generator prototype.
 *
 * Current scope:
 * - Dark Sand floor
 * - Dirt Wall terrain
 * - 9 staggered rows: 7 / 8 / 7 / 8 / 7 / 8 / 7 / 8 / 7 hexes
 * - rare completely filled hexes, biased toward map edges
 * - no separated playable sectors
 * - equal 25% initial chance for each connection template
 * - only the minimum number of edges is repaired if a seed would split the map
 * - four wall / connection templates
 * - first-test resource generator: ores, shallow water and tar/oil
 * - Fallen team (#18) neutral cores
 * - first-join personal team assignment with safe spawn spacing
 * - reconnect to the same personal team during the current round
 * - one-time starting items and starting schematic on personal-core claim
 *
 * - delayed Core Shard capture after a core is destroyed
 * - captured-hex building wipe before the new Core Shard appears
 *
 * - elimination announcements
 * - custom victory detection
 * - automatic random-seed round reset through the dedicated server's
 *   normal post-game transition
 * - capture and long-range unit attrition
 * - /fullassault toggle for unattended combat units
 *
 * Deliberately not included yet:
 * - final resource balancing
 */
public class EvictMapPlugin extends Plugin {

    // ---------------------------------------------------------------------
    // Geometry measured from the editor reference map
    // ---------------------------------------------------------------------

    private static final int SHORT_ROW_COLS = 7;
    private static final int LONG_ROW_COLS = 8;
    private static final int ROWS = 9;

    private static final int OUTER_RADIUS = 39;

    // Important: "75 tiles from center to center" was counted inclusively
    // in the editor. Tile coordinates differ by 74. Using 75 puts the
    // shared middle exactly between two tile columns and creates a 2-tile
    // center everywhere.
    private static final int HORIZONTAL_DX = 74;

    // With 74 horizontal distance, 37 is the exact half-row shift.
    private static final int DIAGONAL_DX = 37;
    private static final int DIAGONAL_DY = 64;

    // Width of the open doorway cut across PASSAGE walls.
    // This is independent from the wall thickness. The wall thickness itself
    // is derived automatically from circle radius, core spacing and polygon.
    private static final int PASSAGE_WIDTH = 7;

    private static final double THIN_WALL_HALF_WIDTH = 0.5;
    private static final int OUTER_BUFFER = 10;

    /**
     * Mirrored inner guaranteed-floor polygon.
     *
     * Reference center: (739, 168)
     * Updated points:
     * - middle left:          (-34,  0)
     * - upper-left side:      (-34, 20)
     * - upper-left top:       ( -4, 38)
     * - middle top:           (  0, 38)
     *
     * These are the former polygon points shifted one tile farther
     * away from the center on each applicable axis.
     */
    private static final Point[] INNER_POLYGON = new Point[]{
        new Point(-34,   0),
        new Point(-34,  20),
        new Point( -4,  38),
        new Point(  0,  38),
        new Point(  4,  38),
        new Point( 34,  20),
        new Point( 34,   0),
        new Point( 34, -20),
        new Point(  4, -38),
        new Point(  0, -38),
        new Point( -4, -38),
        new Point(-34, -20)
    };

    // ---------------------------------------------------------------------
    // Adjustable probabilities
    // ---------------------------------------------------------------------

    private static final double FULL_WEIGHT = 0.25;
    private static final double THIN_WEIGHT = 0.25;
    private static final double OPEN_WEIGHT = 0.25;
    private static final double PASSAGE_WEIGHT = 0.25;

    private static final double FILLED_HEX_BORDER_CHANCE = 0.11;
    private static final double FILLED_HEX_SECOND_RING_CHANCE = 0.035;
    private static final double FILLED_HEX_INNER_CHANCE = 0.010;

    // Small extra chance near the map centre.
    // It fades smoothly to zero before reaching the outer rows.
    // Squared distance is used so no square root is needed.
    private static final double CENTER_FILLED_HEX_BONUS = 0.08;
    private static final int CENTER_BONUS_RADIUS = 180;
    private static final int CENTER_BONUS_RADIUS_SQUARED =
        CENTER_BONUS_RADIUS * CENTER_BONUS_RADIUS;

    // Prevent unusually unlucky seeds from removing too many rooms.
    private static final int MAX_FILLED_HEXES = 8;

    private static final double CHAIN_START_CHANCE = 0.22;
    private static final double CHAIN_CONTINUE_CHANCE = 0.48;
    private static final int CHAIN_MAX_LENGTH = 3;

    // ---------------------------------------------------------------------
    // Runtime state
    // ---------------------------------------------------------------------

    private boolean autoGenerate = false;
    private Long nextSeed = null;
    private Long lastSeed = null;

    /**
     * After runtime terrain generation, Mindustry's ore and pathfinding
     * indexes still describe the originally loaded blank map. We deliberately
     * fire a second WorldLoadEvent after generation so vanilla systems rebuild
     * those indexes. This guard prevents that event from generating a second
     * map recursively.
     */
    private boolean refreshingWorldIndexes = false;

    /**
     * Connected players can temporarily disappear from Groups.player while a
     * dedicated server transitions into the next map. A single next-tick
     * retry was not sufficient on real round resets, so scan repeatedly for a
     * short bounded period after every PlayEvent.
     */
    private static final float CONNECTED_PLAYER_SCAN_INITIAL_DELAY_TICKS = 1f;
    private static final float CONNECTED_PLAYER_SCAN_INTERVAL_TICKS = 15f;
    private static final int CONNECTED_PLAYER_SCAN_ATTEMPTS = 120;

    private long connectedPlayerScanSerial = 0L;

    private final TeamManager teamManager = new TeamManager(this::handleRoundVictory);
    private final AttritionManager attritionManager =
        new AttritionManager(teamManager);
    private final EvictCommands evictCommands =
        new EvictCommands(teamManager, attritionManager);

    @Override
    public void init() {
        Events.on(WorldLoadEvent.class, event -> {
            if (!autoGenerate || refreshingWorldIndexes) {
                return;
            }

            long seed = consumeNextSeed();
            Log.info("[EvictMapGenerator] World loaded. Generating Evict terrain with seed @.", seed);

            try {
                generate(seed);
            } catch (Exception exception) {
                Log.err("[EvictMapGenerator] Generation failed.", exception);
            }
        });

        Events.on(PlayEvent.class, event -> {
            if (!autoGenerate) {
                return;
            }

            /**
             * Dedicated-server hosting applies the selected map / gamemode
             * rules again after WorldLoadEvent. Re-apply the Evict rules here,
             * after that overwrite and before the first game-state check.
             */
            applyEvictRules();

            /**
             * During an automatic post-game map reload, connected players are
             * temporarily absent from Groups.player. On a real server they may
             * still be missing one tick after PlayEvent, so keep checking for a
             * bounded period. TeamManager safely ignores players already
             * assigned during this round.
             */
            scheduleConnectedPlayerAssignmentScan();

            Log.info("[EvictMapGenerator] Re-applied Evict rules after host-mode initialization.");
        });

        Events.on(PlayerJoin.class, event -> teamManager.handlePlayerJoin(event.player));
        Events.on(
            CoreChangeEvent.class,
            event -> teamManager.handleCoreChange(event.core, attritionManager)
        );

        Events.run(Trigger.update, () -> {
            attritionManager.update();
            evictCommands.update();
        });

        Log.info("[EvictMapGenerator] Loaded. Code revision 0.9.2. Use 'evictstatus' for commands and current settings.");
    }

    @Override
    public void registerClientCommands(CommandHandler handler) {
        evictCommands.registerClientCommands(handler);
    }

    @Override
    public void registerServerCommands(CommandHandler handler) {
        handler.register(
            "evictgen",
            "[seed]",
            "Generate Evict terrain immediately on the currently loaded map. Prefer evictauto before hosting a map.",
            args -> {
                Long seed = parseSeedOrRandom(args);

                if (seed == null) {
                    return;
                }

                if (Groups.player.size() > 0) {
                    Log.warn("[EvictMapGenerator] Players are connected. Immediate generation is intended for testing. Reconnect clients afterwards if terrain is not refreshed.");
                }

                try {
                    generate(seed);
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
                if (args.length == 0) {
                    Log.info("[EvictMapGenerator] evictauto is currently @.", autoGenerate ? "ON" : "OFF");
                    return;
                }

                String value = args[0].trim().toLowerCase();

                if (value.equals("on") || value.equals("true") || value.equals("yes")) {
                    autoGenerate = true;
                } else if (value.equals("off") || value.equals("false") || value.equals("no")) {
                    autoGenerate = false;
                } else {
                    Log.err("[EvictMapGenerator] Use: evictauto <on/off>");
                    return;
                }

                Log.info("[EvictMapGenerator] Automatic generation is now @.", autoGenerate ? "ON" : "OFF");
            }
        );

        handler.register(
            "evictseed",
            "[seed/random]",
            "Set the seed used for the next automatically generated map.",
            args -> {
                if (args.length == 0 || args[0].equalsIgnoreCase("random")) {
                    nextSeed = randomSeed();
                    Log.info("[EvictMapGenerator] Next seed: @", nextSeed);
                    return;
                }

                try {
                    nextSeed = Long.parseLong(args[0]);
                    Log.info("[EvictMapGenerator] Next seed: @", nextSeed);
                } catch (NumberFormatException exception) {
                    Log.err("[EvictMapGenerator] Seed must be a whole number or 'random'.");
                }
            }
        );

        handler.register(
            "evictstatus",
            "Show generator settings and required base-map size.",
            args -> {
                Log.info("[EvictMapGenerator] autoGenerate: @", autoGenerate);
                Log.info("[EvictMapGenerator] nextSeed: @", nextSeed == null ? "random" : nextSeed);
                Log.info("[EvictMapGenerator] lastSeed: @", lastSeed == null ? "none" : lastSeed);
                Log.info(
                    "[EvictMapGenerator] grid: @ staggered rows, alternating @ / @ hexes",
                    ROWS,
                    SHORT_ROW_COLS,
                    LONG_ROW_COLS
                );
                Log.info("[EvictMapGenerator] required map size: at least @x@ tiles", minimumWorldWidth(), minimumWorldHeight());
                Log.info(
                    "[EvictMapGenerator] geometry: outerRadius=@, outerDiameter=@, horizontalCenterDistance=@ coordinates / @ tiles counted inclusively",
                    OUTER_RADIUS,
                    OUTER_RADIUS * 2 + 1,
                    HORIZONTAL_DX,
                    HORIZONTAL_DX + 1
                );
                Log.info(
                    "[EvictMapGenerator] geometry: horizontal grey connection band derives to @ tiles, passage opening width=@",
                    horizontalGreyBandWidth(),
                    PASSAGE_WIDTH
                );
                Log.info(
                    "[EvictMapGenerator] filled hexes: max=@, centre bonus up to @% within @ tiles",
                    MAX_FILLED_HEXES,
                    percent(CENTER_FILLED_HEX_BONUS),
                    CENTER_BONUS_RADIUS
                );
                Log.info("[EvictMapGenerator] resources: @", ResourceGenerator.presetDescription());
                Log.info("[EvictMapGenerator] edge weights: full=@%, thin=@%, open=@%, passage=@%",
                    percent(FULL_WEIGHT),
                    percent(THIN_WEIGHT),
                    percent(OPEN_WEIGHT),
                    percent(PASSAGE_WEIGHT)
                );
            }
        );
        handler.register(
            "evictteamstatus",
            "Show Fallen-team spawn assignment status for the current round.",
            args -> teamManager.logStatus()
        );

    }

    private void generate(long seed) {
        if (Vars.world == null || Vars.world.width() <= 0 || Vars.world.height() <= 0) {
            throw new IllegalStateException("No map is loaded. Host a sufficiently large blank test map first.");
        }

        int worldWidth = Vars.world.width();
        int worldHeight = Vars.world.height();

        if (worldWidth < minimumWorldWidth() || worldHeight < minimumWorldHeight()) {
            throw new IllegalStateException(
                "Base map is too small. Required: at least "
                    + minimumWorldWidth() + "x" + minimumWorldHeight()
                    + " tiles. Loaded: " + worldWidth + "x" + worldHeight + "."
            );
        }

        applyEvictRules();

        Random random = new Random(seed);

        List<Cell> cells = allCells();
        Set<Cell> filledCells = chooseFilledCells(random, cells, rawGridCenter(cells));

        List<Cell> normalCells = new ArrayList<>();
        for (Cell cell : cells) {
            if (!filledCells.contains(cell)) {
                normalCells.add(cell);
            }
        }

        Map<Edge, EdgeType> edgeTypes = new LinkedHashMap<>();
        for (Edge edge : uniqueEdges(normalCells)) {
            edgeTypes.put(edge, chooseEdgeType(random));
        }

        Set<Edge> repairedConnectivityEdges =
            ensureTraversableConnectivity(normalCells, edgeTypes, random);

        Map<Cell, Point> centers = translatedCenters(worldWidth, worldHeight);
        byte[][] zones = createZoneMap(worldWidth, worldHeight, centers, normalCells);
        boolean[][] walls = createWallMap(worldWidth, worldHeight, zones);

        applyConnectionTemplates(walls, zones, centers, edgeTypes);
        applyTerrainToWorld(walls);

        ResourceGenerator.Summary resourceSummary =
            ResourceGenerator.generate(seed, resourceCenters(centers, normalCells));

        placeNucleusCores(centers, normalCells);

        refreshWorldIndexes();

        teamManager.beginRound(
            startHexSlots(centers, normalCells, filledCells),
            seed
        );
        attritionManager.beginRound();
        evictCommands.beginRound();
        teamManager.assignConnectedPlayers();

        lastSeed = seed;

        Log.info(
            "[EvictMapGenerator] Done. seed=@ normalHexes=@ filledHexes=@ nucleusCores=@ repairedConnectivityEdges=@ resources=@ teams=@",
            seed,
            normalCells.size(),
            filledCells.size(),
            normalCells.size(),
            repairedConnectivityEdges.size(),
            resourceSummary.compact(),
            teamManager.compactStatus()
        );
    }

    private void scheduleConnectedPlayerAssignmentScan() {
        long scanSerial = ++connectedPlayerScanSerial;

        scheduleConnectedPlayerAssignmentScan(
            scanSerial,
            CONNECTED_PLAYER_SCAN_ATTEMPTS,
            CONNECTED_PLAYER_SCAN_INITIAL_DELAY_TICKS
        );

        Log.info(
            "[EvictMapGenerator] Scheduled connected-player start assignment scan for up to 30 seconds."
        );
    }

    private void scheduleConnectedPlayerAssignmentScan(
        long scanSerial,
        int attemptsRemaining,
        float delayTicks
    ) {
        Time.run(
            delayTicks,
            () -> {
                if (
                    !autoGenerate
                        || scanSerial != connectedPlayerScanSerial
                        || attemptsRemaining <= 0
                ) {
                    return;
                }

                teamManager.assignConnectedPlayers();

                if (attemptsRemaining > 1) {
                    scheduleConnectedPlayerAssignmentScan(
                        scanSerial,
                        attemptsRemaining - 1,
                        CONNECTED_PLAYER_SCAN_INTERVAL_TICKS
                    );
                }
            }
        );
    }

    private void handleRoundVictory(Team winner) {
        /**
         * Reuse the dedicated server's normal GameOverEvent transition.
         *
         * ServerControl will announce the selected next map, wait its normal
         * post-game delay and load a fresh base map. WorldLoadEvent then runs
         * this generator again and consumes the random seed prepared here.
         */
        nextSeed = randomSeed();

        Log.info(
            "[EvictMapGenerator] Round winner: team #@. Prepared random seed @ for the next generated round.",
            winner.id,
            nextSeed
        );

        Events.fire(new GameOverEvent(winner));
    }

    private void refreshWorldIndexes() {
        /**
         * The blank base map has already emitted its normal WorldLoadEvent
         * before this plugin replaces floors, overlays, walls and cores.
         *
         * Vanilla systems such as BlockIndexer and Pathfinder listen for that
         * event to build their caches. Fire the event once more after the
         * finished Evict world exists so Monos mine the real ore tiles and
         * pathfinding sees the generated walls.
         *
         * Mindustry's own BlockIndexer explicitly supports duplicate
         * WorldLoadEvents, so this uses the same normal rebuild path instead
         * of modifying internal cache fields directly.
         */
        refreshingWorldIndexes = true;

        try {
            Events.fire(new WorldLoadEvent());
            Log.info("[EvictMapGenerator] Rebuilt vanilla world indexes after runtime terrain generation.");
        } finally {
            refreshingWorldIndexes = false;
        }
    }

    private void applyEvictRules() {
        /**
         * The generated map is a custom always-running PvP mode.
         *
         * Vanilla game-over must stay disabled because all cores initially
         * belong to Fallen team #18 and the plugin will later implement its
         * own capture, victory and reset logic.
         */
        Vars.state.rules.pvp = true;
        Vars.state.rules.pvpAutoPause = false;

        Vars.state.rules.waves = false;
        Vars.state.rules.waveTimer = false;
        Vars.state.rules.waveSending = false;
        Vars.state.rules.winWave = 0;

        Vars.state.rules.infiniteResources = false;
        Vars.state.rules.attackMode = false;

        /**
         * Hosting with the vanilla PvP preset forces unit factories to 2x.
         * Evict uses normal 1x unit-factory speed, so override that preset here
         * after host-mode initialization.
         */
        Vars.state.rules.unitBuildSpeedMultiplier = 1f;

        /**
         * Unit-cap bonuses are intentionally core-based in Evict. This also
         * keeps the admin /corecap tuning command aligned with vanilla cap
         * calculation.
         */
        Vars.state.rules.unitCapVariable = true;

        Vars.state.rules.canGameOver = false;
        Vars.state.rules.cleanupDeadTeams = false;
        Vars.state.rules.coreCapture = false;

        Vars.state.rules.defaultTeam = TeamManager.FALLEN_TEAM;

        /**
         * The server's normal logic.play() fills one core per active team from
         * rules.loadout. Fallen owns every neutral core at round start, so that
         * vanilla loadout must be disabled. Personal start resources are added
         * exactly once by StartLoadout when a player claims a hex.
         */
        Vars.state.rules.loadout.clear();

        Log.info(
            "[EvictMapGenerator] Applied Evict rules: pvp=ON, pvpAutoPause=OFF, waves=OFF, vanillaGameOver=OFF, unitFactorySpeed=1x, defaultTeam=Fallen #@.",
            TeamManager.FALLEN_TEAM_ID
        );
    }

    private byte[][] createZoneMap(
        int width,
        int height,
        Map<Cell, Point> centers,
        List<Cell> normalCells
    ) {
        // 0 = outside all active circles: fixed Dirt Wall
        // 1 = inside a circle, outside all inner polygons: variable grey zone
        // 2 = inside an inner polygon: guaranteed Dark Sand
        byte[][] zones = new byte[height][width];

        for (Cell cell : normalCells) {
            Point center = centers.get(cell);

            int minX = Math.max(0, center.x - OUTER_RADIUS);
            int maxX = Math.min(width - 1, center.x + OUTER_RADIUS);
            int minY = Math.max(0, center.y - OUTER_RADIUS);
            int maxY = Math.min(height - 1, center.y + OUTER_RADIUS);

            for (int y = minY; y <= maxY; y++) {
                for (int x = minX; x <= maxX; x++) {
                    int dx = x - center.x;
                    int dy = y - center.y;

                    if (dx * dx + dy * dy <= OUTER_RADIUS * OUTER_RADIUS && zones[y][x] == 0) {
                        zones[y][x] = 1;
                    }
                }
            }

            for (int y = minY; y <= maxY; y++) {
                for (int x = minX; x <= maxX; x++) {
                    // Red zone is hard-locked:
                    // tiles outside every outer circle stay Dirt Wall forever.
                    // The inner polygon may only turn a tile into guaranteed
                    // floor when that tile was already reached by a circle.
                    if (
                        zones[y][x] != 0
                            && pointInsideTranslatedInnerPolygon(x, y, center)
                    ) {
                        zones[y][x] = 2;
                    }
                }
            }
        }

        return zones;
    }

    private boolean[][] createWallMap(int width, int height, byte[][] zones) {
        boolean[][] walls = new boolean[height][width];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // Outside every active outer circle is fixed Dirt Wall.
                // Everything reached by a circle starts as floor. Connection
                // templates add walls only between two normal neighbouring hexes.
                // This makes all map-border and full-hex-facing sides perfectly round.
                walls[y][x] = zones[y][x] == 0;
            }
        }

        return walls;
    }

    private void carveRoundedOuterCaps(
        boolean[][] walls,
        byte[][] zones,
        Map<Cell, Point> centers,
        List<Cell> normalCells
    ) {
        int height = zones.length;
        int width = zones[0].length;

        Set<Cell> normalSet = new HashSet<>(normalCells);

        for (Cell cell : normalCells) {
            Point center = centers.get(cell);

            for (Cell neighbour : neighbourSlots(cell)) {
                boolean neighbourIsNormal = validCell(neighbour) && normalSet.contains(neighbour);

                if (neighbourIsNormal) {
                    continue;
                }

                Point currentRaw = rawCenter(cell);
                Point neighbourRaw = rawCenter(neighbour);

                double dx = neighbourRaw.x - currentRaw.x;
                double dy = neighbourRaw.y - currentRaw.y;
                double distance = Math.hypot(dx, dy);

                double ux = dx / distance;
                double uy = dy / distance;
                double support = supportDistance(ux, uy);

                int minX = Math.max(0, center.x - OUTER_RADIUS);
                int maxX = Math.min(width - 1, center.x + OUTER_RADIUS);
                int minY = Math.max(0, center.y - OUTER_RADIUS);
                int maxY = Math.min(height - 1, center.y + OUTER_RADIUS);

                for (int y = minY; y <= maxY; y++) {
                    for (int x = minX; x <= maxX; x++) {
                        if (zones[y][x] != 1) {
                            continue;
                        }

                        double relX = x - center.x;
                        double relY = y - center.y;

                        boolean insideCircle = relX * relX + relY * relY <= OUTER_RADIUS * OUTER_RADIUS;
                        boolean facingCap = relX * ux + relY * uy >= support - 0.75;

                        if (insideCircle && facingCap) {
                            walls[y][x] = false;
                        }
                    }
                }
            }
        }
    }

    private void applyConnectionTemplates(
        boolean[][] walls,
        byte[][] zones,
        Map<Cell, Point> centers,
        Map<Edge, EdgeType> edgeTypes
    ) {
        /**
         * Important raster rule:
         *
         * Mindustry uses square tiles. Several grey edge regions touch each other
         * near the red triangle tips. The previous prototype let multiple edges
         * edit the same tile. The edge processed last could therefore slightly
         * distort a neighbouring triangle.
         *
         * Now every editable grey tile is assigned to exactly one edge first.
         * Afterwards each template edits only its own fixed tile mask.
         */
        Map<Edge, Set<TilePoint>> tilesByEdge =
            buildOwnedEdgeMasks(zones, centers, edgeTypes.keySet());

        for (Map.Entry<Edge, EdgeType> entry : edgeTypes.entrySet()) {
            Edge edge = entry.getKey();
            EdgeType edgeType = entry.getValue();
            Set<TilePoint> mask = tilesByEdge.getOrDefault(edge, Collections.emptySet());

            switch (edgeType) {
                case FULL -> {
                    for (TilePoint point : mask) {
                        walls[point.y][point.x] = true;
                    }
                }

                case OPEN -> {
                    for (TilePoint point : mask) {
                        walls[point.y][point.x] = false;
                    }
                }

                case PASSAGE -> applyPassageTemplate(walls, centers, edge, mask);

                case THIN -> applyOneTileThinWallTemplate(walls, centers, edge, mask);
            }
        }
    }

    private Map<Edge, Set<TilePoint>> buildOwnedEdgeMasks(
        byte[][] zones,
        Map<Cell, Point> centers,
        Set<Edge> edges
    ) {
        int height = zones.length;
        int width = zones[0].length;

        List<Edge> orderedEdges = new ArrayList<>(edges);
        orderedEdges.sort(edgeComparator());

        Map<Edge, Set<TilePoint>> tilesByEdge = new LinkedHashMap<>();
        Map<TilePoint, EdgeOwnership> ownerByTile = new HashMap<>();

        for (Edge edge : orderedEdges) {
            tilesByEdge.put(edge, new LinkedHashSet<>());

            Point a = centers.get(edge.a);
            Point b = centers.get(edge.b);

            double dx = b.x - a.x;
            double dy = b.y - a.y;
            double distance = Math.hypot(dx, dy);

            double ux = dx / distance;
            double uy = dy / distance;

            double gapHalf = Math.max(0.0, distance / 2.0 - supportDistance(ux, uy));

            double middleX = (a.x + b.x) / 2.0;
            double middleY = (a.y + b.y) / 2.0;

            int reach = OUTER_RADIUS + 2;
            int minX = Math.max(0, (int)Math.floor(middleX - reach));
            int maxX = Math.min(width - 1, (int)Math.ceil(middleX + reach));
            int minY = Math.max(0, (int)Math.floor(middleY - reach));
            int maxY = Math.min(height - 1, (int)Math.ceil(middleY + reach));

            for (int y = minY; y <= maxY; y++) {
                for (int x = minX; x <= maxX; x++) {
                    if (zones[y][x] != 1) {
                        continue;
                    }

                    double relX = x - middleX;
                    double relY = y - middleY;
                    double u = relX * ux + relY * uy;

                    if (Math.abs(u) > gapHalf + 0.75) {
                        continue;
                    }

                    boolean insideA =
                        squaredDistance(x, y, a.x, a.y) <= OUTER_RADIUS * OUTER_RADIUS;

                    boolean insideB =
                        squaredDistance(x, y, b.x, b.y) <= OUTER_RADIUS * OUTER_RADIUS;

                    if (!insideA && !insideB) {
                        continue;
                    }

                    TilePoint tile = new TilePoint(x, y);
                    double score = relX * relX + relY * relY;

                    EdgeOwnership current = ownerByTile.get(tile);

                    if (
                        current == null
                            || score < current.score - 1e-9
                            || (
                                Math.abs(score - current.score) <= 1e-9
                                    && edgeComparator().compare(edge, current.edge) < 0
                            )
                    ) {
                        ownerByTile.put(tile, new EdgeOwnership(edge, score));
                    }
                }
            }
        }

        for (Map.Entry<TilePoint, EdgeOwnership> entry : ownerByTile.entrySet()) {
            tilesByEdge.get(entry.getValue().edge).add(entry.getKey());
        }

        return tilesByEdge;
    }

    private void applyOneTileThinWallTemplate(
        boolean[][] walls,
        Map<Cell, Point> centers,
        Edge edge,
        Set<TilePoint> mask
    ) {
        /**
         * A mathematically centered line can lie exactly between two tile rows.
         * Selecting every tile with distance <= 0.5 would then create a two-tile
         * wall. Instead, rasterize one digital line with Bresenham's algorithm.
         * This always produces a line exactly one tile thick.
         */
        for (TilePoint point : mask) {
            walls[point.y][point.x] = false;
        }

        if (mask.isEmpty()) {
            return;
        }

        Point a = centers.get(edge.a);
        Point b = centers.get(edge.b);

        double dx = b.x - a.x;
        double dy = b.y - a.y;
        double distance = Math.hypot(dx, dy);

        double vx = -dy / distance;
        double vy = dx / distance;

        double middleX = (a.x + b.x) / 2.0;
        double middleY = (a.y + b.y) / 2.0;

        TilePoint first = null;
        TilePoint last = null;
        double minimumV = Double.POSITIVE_INFINITY;
        double maximumV = Double.NEGATIVE_INFINITY;

        for (TilePoint tile : mask) {
            double v = (tile.x - middleX) * vx + (tile.y - middleY) * vy;

            if (v < minimumV) {
                minimumV = v;
                first = tile;
            }

            if (v > maximumV) {
                maximumV = v;
                last = tile;
            }
        }

        if (first == null || last == null) {
            return;
        }

        int startX = deterministicRound(middleX + minimumV * vx);
        int startY = deterministicRound(middleY + minimumV * vy);
        int endX = deterministicRound(middleX + maximumV * vx);
        int endY = deterministicRound(middleY + maximumV * vy);

        Set<TilePoint> line = rasterizeOneTileLine(startX, startY, endX, endY);

        for (TilePoint tile : line) {
            if (mask.contains(tile)) {
                walls[tile.y][tile.x] = true;
            }
        }
    }

    private void applyPassageTemplate(
        boolean[][] walls,
        Map<Cell, Point> centers,
        Edge edge,
        Set<TilePoint> mask
    ) {
        Point a = centers.get(edge.a);
        Point b = centers.get(edge.b);

        double dx = b.x - a.x;
        double dy = b.y - a.y;
        double distance = Math.hypot(dx, dy);

        double vx = -dy / distance;
        double vy = dx / distance;

        double middleX = (a.x + b.x) / 2.0;
        double middleY = (a.y + b.y) / 2.0;

        for (TilePoint tile : mask) {
            double v = (tile.x - middleX) * vx + (tile.y - middleY) * vy;

            walls[tile.y][tile.x] = !(Math.abs(v) < PASSAGE_WIDTH / 2.0);
        }
    }

    private Set<TilePoint> rasterizeOneTileLine(
        int startX,
        int startY,
        int endX,
        int endY
    ) {
        Set<TilePoint> line = new LinkedHashSet<>();

        int x = startX;
        int y = startY;

        int deltaX = Math.abs(endX - startX);
        int deltaY = Math.abs(endY - startY);

        int stepX = startX < endX ? 1 : -1;
        int stepY = startY < endY ? 1 : -1;

        int error = deltaX - deltaY;

        while (true) {
            line.add(new TilePoint(x, y));

            if (x == endX && y == endY) {
                break;
            }

            int doubledError = error * 2;

            if (doubledError > -deltaY) {
                error -= deltaY;
                x += stepX;
            }

            if (doubledError < deltaX) {
                error += deltaX;
                y += stepY;
            }
        }

        return line;
    }

    private int deterministicRound(double value) {
        /**
         * Java's normal round() would also work most of the time, but this
         * explicitly resolves exact half-tile positions in one consistent way.
         */
        return (int)Math.floor(value + 0.5);
    }

    private Comparator<Edge> edgeComparator() {
        return Comparator
            .comparingInt((Edge edge) -> edge.a.row)
            .thenComparingInt(edge -> edge.a.col)
            .thenComparingInt(edge -> edge.b.row)
            .thenComparingInt(edge -> edge.b.col);
    }


    private void enforceThinWallsLast(
        boolean[][] walls,
        byte[][] zones,
        Map<Cell, Point> centers,
        Map<Edge, EdgeType> edgeTypes
    ) {
        /**
         * A thin wall must be exactly one tile thick.
         *
         * Even after assigning edge ownership, another nearby template can touch
         * the same visual corridor close to the triangle tips. Therefore thin
         * walls get a final cleanup pass after every other template:
         *
         * 1. clear the complete grey corridor for this edge
         * 2. draw one single digital center line
         *
         * For a half-tile midpoint, one side is selected consistently.
         */
        for (Map.Entry<Edge, EdgeType> entry : edgeTypes.entrySet()) {
            if (entry.getValue() != EdgeType.THIN) {
                continue;
            }

            Edge edge = entry.getKey();
            Set<TilePoint> corridor = collectRawBridgeMask(zones, centers, edge);

            for (TilePoint tile : corridor) {
                walls[tile.y][tile.x] = false;
            }

            drawSingleCenteredLine(walls, centers, edge, corridor);
        }
    }

    private Set<TilePoint> collectRawBridgeMask(
        byte[][] zones,
        Map<Cell, Point> centers,
        Edge edge
    ) {
        int height = zones.length;
        int width = zones[0].length;

        Set<TilePoint> corridor = new LinkedHashSet<>();

        Point a = centers.get(edge.a);
        Point b = centers.get(edge.b);

        double dx = b.x - a.x;
        double dy = b.y - a.y;
        double distance = Math.hypot(dx, dy);

        double ux = dx / distance;
        double uy = dy / distance;

        double gapHalf = Math.max(0.0, distance / 2.0 - supportDistance(ux, uy));

        double middleX = (a.x + b.x) / 2.0;
        double middleY = (a.y + b.y) / 2.0;

        int reach = OUTER_RADIUS + 2;
        int minX = Math.max(0, (int)Math.floor(middleX - reach));
        int maxX = Math.min(width - 1, (int)Math.ceil(middleX + reach));
        int minY = Math.max(0, (int)Math.floor(middleY - reach));
        int maxY = Math.min(height - 1, (int)Math.ceil(middleY + reach));

        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                if (zones[y][x] != 1) {
                    continue;
                }

                double relX = x - middleX;
                double relY = y - middleY;
                double u = relX * ux + relY * uy;

                if (Math.abs(u) > gapHalf + 0.75) {
                    continue;
                }

                boolean insideA =
                    squaredDistance(x, y, a.x, a.y) <= OUTER_RADIUS * OUTER_RADIUS;

                boolean insideB =
                    squaredDistance(x, y, b.x, b.y) <= OUTER_RADIUS * OUTER_RADIUS;

                if (insideA || insideB) {
                    corridor.add(new TilePoint(x, y));
                }
            }
        }

        return corridor;
    }

    private void drawSingleCenteredLine(
        boolean[][] walls,
        Map<Cell, Point> centers,
        Edge edge,
        Set<TilePoint> corridor
    ) {
        if (corridor.isEmpty()) {
            return;
        }

        Point a = centers.get(edge.a);
        Point b = centers.get(edge.b);

        double dx = b.x - a.x;
        double dy = b.y - a.y;
        double distance = Math.hypot(dx, dy);

        // v points along the wall line, perpendicular to the line between cores.
        double vx = -dy / distance;
        double vy = dx / distance;

        double middleX = (a.x + b.x) / 2.0;
        double middleY = (a.y + b.y) / 2.0;

        double minimumV = Double.POSITIVE_INFINITY;
        double maximumV = Double.NEGATIVE_INFINITY;

        for (TilePoint tile : corridor) {
            double v = (tile.x - middleX) * vx + (tile.y - middleY) * vy;
            minimumV = Math.min(minimumV, v);
            maximumV = Math.max(maximumV, v);
        }

        int startX = deterministicRound(middleX + minimumV * vx);
        int startY = deterministicRound(middleY + minimumV * vy);
        int endX = deterministicRound(middleX + maximumV * vx);
        int endY = deterministicRound(middleY + maximumV * vy);

        for (TilePoint tile : rasterizeOneTileLine(startX, startY, endX, endY)) {
            if (corridor.contains(tile)) {
                walls[tile.y][tile.x] = true;
            }
        }
    }


    private void applyTerrainToWorld(boolean[][] walls) {
        int width = Vars.world.width();
        int height = Vars.world.height();

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                Tile tile = Vars.world.tile(x, y);

                // Clear ores / overlays and make all floors Dark Sand.
                Tile.setFloor(tile, Blocks.darksand, Blocks.air);

                // Replace every previous editor block too, including the one
                // temporary shard core used only to make the base map hostable.
                tile.setBlock(walls[y][x] ? Blocks.dirtWall : Blocks.air);
            }
        }
    }

    private List<ResourceGenerator.HexCenter> resourceCenters(
        Map<Cell, Point> centers,
        List<Cell> normalCells
    ) {
        List<ResourceGenerator.HexCenter> result = new ArrayList<>();

        for (Cell cell : normalCells) {
            Point center = centers.get(cell);
            result.add(new ResourceGenerator.HexCenter(center.x, center.y));
        }

        return result;
    }

    private void placeNucleusCores(
        Map<Cell, Point> centers,
        List<Cell> normalCells
    ) {
        /**
         * Every unclaimed Nucleus starts as Fallen team #18.
         * A first-time player later claims one safe random start hex and
         * receives a unique personal team.
         */
        for (Cell cell : normalCells) {
            Point center = centers.get(cell);
            Tile tile = Vars.world.tile(center.x, center.y);

            if (tile == null) {
                throw new IllegalStateException(
                    "Core center is outside the loaded world at "
                        + center.x + "," + center.y + "."
                );
            }

            tile.setBlock(Blocks.coreNucleus, TeamManager.FALLEN_TEAM);
        }
    }

    private List<TeamManager.HexSlot> startHexSlots(
        Map<Cell, Point> centers,
        List<Cell> normalCells,
        Set<Cell> filledCells
    ) {
        List<TeamManager.HexSlot> slots = new ArrayList<>();

        for (Cell cell : normalCells) {
            Point center = centers.get(cell);
            int protectedSides = protectedStartSides(cell, filledCells);

            slots.add(
                new TeamManager.HexSlot(
                    cell.col,
                    cell.row,
                    center.x,
                    center.y,
                    protectedSides
                )
            );
        }

        return slots;
    }

    private int protectedStartSides(Cell cell, Set<Cell> filledCells) {
        int protectedSides = 0;

        for (Cell neighbour : neighbourSlots(cell)) {
            if (!validCell(neighbour) || filledCells.contains(neighbour)) {
                protectedSides++;
            }
        }

        return protectedSides;
    }

    private Point rawGridCenter(List<Cell> cells) {
        int sumX = 0;
        int sumY = 0;

        for (Cell cell : cells) {
            Point point = rawCenter(cell);
            sumX += point.x;
            sumY += point.y;
        }

        return new Point(sumX / cells.size(), sumY / cells.size());
    }

    private double centreFilledHexBonus(Cell cell, Point gridCenter) {
        Point point = rawCenter(cell);

        int deltaX = point.x - gridCenter.x;
        int deltaY = point.y - gridCenter.y;
        int distanceSquared = deltaX * deltaX + deltaY * deltaY;

        if (distanceSquared >= CENTER_BONUS_RADIUS_SQUARED) {
            return 0.0;
        }

        double centreFactor =
            1.0 - distanceSquared / (double)CENTER_BONUS_RADIUS_SQUARED;

        return CENTER_FILLED_HEX_BONUS * centreFactor;
    }

    private Set<Cell> chooseFilledCells(
        Random random,
        List<Cell> cells,
        Point gridCenter
    ) {
        Set<Cell> filled = new HashSet<>();

        List<Cell> candidates = new ArrayList<>(cells);
        Collections.shuffle(candidates, random);

        for (Cell cell : candidates) {
            int ring = borderDistance(cell);

            double chance = ring == 0
                ? FILLED_HEX_BORDER_CHANCE
                : ring == 1
                    ? FILLED_HEX_SECOND_RING_CHANCE
                    : FILLED_HEX_INNER_CHANCE;

            chance += centreFilledHexBonus(cell, gridCenter);

            if (random.nextDouble() < chance) {
                tryAddFilledCell(filled, cell, cells);
            }
        }

        List<Cell> borderStarts = new ArrayList<>();
        for (Cell cell : filled) {
            if (borderDistance(cell) == 0) {
                borderStarts.add(cell);
            }
        }

        Collections.shuffle(borderStarts, random);

        for (Cell start : borderStarts) {
            if (random.nextDouble() >= CHAIN_START_CHANCE) {
                continue;
            }

            Cell current = start;

            for (int step = 0; step < CHAIN_MAX_LENGTH - 1; step++) {
                List<Cell> inward = new ArrayList<>();

                for (Cell neighbour : neighbors(current)) {
                    if (
                        borderDistance(neighbour) > borderDistance(current)
                            && !filled.contains(neighbour)
                    ) {
                        inward.add(neighbour);
                    }
                }

                Collections.shuffle(inward, random);

                if (inward.isEmpty() || random.nextDouble() >= CHAIN_CONTINUE_CHANCE) {
                    break;
                }

                boolean accepted = false;

                for (Cell candidate : inward) {
                    if (tryAddFilledCell(filled, candidate, cells)) {
                        current = candidate;
                        accepted = true;
                        break;
                    }
                }

                if (!accepted) {
                    break;
                }
            }
        }

        return filled;
    }

    private boolean tryAddFilledCell(Set<Cell> filled, Cell candidate, List<Cell> cells) {
        if (filled.contains(candidate)) {
            return true;
        }

        if (filled.size() >= MAX_FILLED_HEXES) {
            return false;
        }

        Set<Cell> proposed = new HashSet<>(filled);
        proposed.add(candidate);

        List<Cell> normal = new ArrayList<>();
        for (Cell cell : cells) {
            if (!proposed.contains(cell)) {
                normal.add(cell);
            }
        }

        if (graphIsConnected(normal)) {
            filled.add(candidate);
            return true;
        }

        return false;
    }

    private boolean graphIsConnected(List<Cell> cells) {
        if (cells.isEmpty()) {
            return false;
        }

        Set<Cell> cellSet = new HashSet<>(cells);
        Set<Cell> visited = new HashSet<>();
        Deque<Cell> stack = new ArrayDeque<>();

        Cell start = cells.get(0);
        visited.add(start);
        stack.push(start);

        while (!stack.isEmpty()) {
            Cell current = stack.pop();

            for (Cell neighbour : neighbors(current)) {
                if (cellSet.contains(neighbour) && visited.add(neighbour)) {
                    stack.push(neighbour);
                }
            }
        }

        return visited.size() == cellSet.size();
    }

    private Set<Edge> ensureTraversableConnectivity(
        List<Cell> normalCells,
        Map<Edge, EdgeType> edgeTypes,
        Random random
    ) {
        /**
         * First, every normal-normal edge is rolled with the normal 25/25/25/25
         * probabilities. Only if this creates separated sectors do we repair the
         * minimum number of crossing edges by converting them to OPEN or PASSAGE.
         *
         * This keeps the visible result much closer to the requested equal
         * distribution than forcing an entire spanning tree up front.
         */
        Set<Edge> repairedEdges = new HashSet<>();

        while (true) {
            Map<Cell, Integer> componentByCell =
                traversableComponents(normalCells, edgeTypes);

            int componentCount = 0;
            for (int component : componentByCell.values()) {
                componentCount = Math.max(componentCount, component + 1);
            }

            if (componentCount <= 1) {
                return repairedEdges;
            }

            List<Edge> crossingEdges = new ArrayList<>();

            for (Edge edge : edgeTypes.keySet()) {
                int firstComponent = componentByCell.get(edge.a);
                int secondComponent = componentByCell.get(edge.b);

                if (firstComponent != secondComponent) {
                    crossingEdges.add(edge);
                }
            }

            if (crossingEdges.isEmpty()) {
                throw new IllegalStateException(
                    "Could not repair map connectivity although normal hexes should be connected."
                );
            }

            Edge chosen = crossingEdges.get(random.nextInt(crossingEdges.size()));
            edgeTypes.put(chosen, chooseTraversableEdgeType(random));
            repairedEdges.add(chosen);
        }
    }

    private Map<Cell, Integer> traversableComponents(
        List<Cell> normalCells,
        Map<Edge, EdgeType> edgeTypes
    ) {
        Map<Cell, List<Cell>> traversableNeighbours = new HashMap<>();

        for (Cell cell : normalCells) {
            traversableNeighbours.put(cell, new ArrayList<>());
        }

        for (Map.Entry<Edge, EdgeType> entry : edgeTypes.entrySet()) {
            if (!isTraversable(entry.getValue())) {
                continue;
            }

            Edge edge = entry.getKey();
            traversableNeighbours.get(edge.a).add(edge.b);
            traversableNeighbours.get(edge.b).add(edge.a);
        }

        Map<Cell, Integer> componentByCell = new HashMap<>();
        int nextComponent = 0;

        for (Cell start : normalCells) {
            if (componentByCell.containsKey(start)) {
                continue;
            }

            Deque<Cell> stack = new ArrayDeque<>();
            stack.push(start);
            componentByCell.put(start, nextComponent);

            while (!stack.isEmpty()) {
                Cell current = stack.pop();

                for (Cell neighbour : traversableNeighbours.get(current)) {
                    if (!componentByCell.containsKey(neighbour)) {
                        componentByCell.put(neighbour, nextComponent);
                        stack.push(neighbour);
                    }
                }
            }

            nextComponent++;
        }

        return componentByCell;
    }

    private boolean isTraversable(EdgeType edgeType) {
        return edgeType == EdgeType.OPEN || edgeType == EdgeType.PASSAGE;
    }

    private Set<Edge> uniqueEdges(List<Cell> cells) {
        Set<Cell> cellSet = new HashSet<>(cells);
        Set<Edge> edges = new HashSet<>();

        for (Cell cell : cells) {
            for (Cell neighbour : neighbors(cell)) {
                if (cellSet.contains(neighbour)) {
                    edges.add(Edge.of(cell, neighbour));
                }
            }
        }

        return edges;
    }

    private EdgeType chooseTraversableEdgeType(Random random) {
        return random.nextBoolean() ? EdgeType.OPEN : EdgeType.PASSAGE;
    }

    private EdgeType chooseEdgeType(Random random) {
        double value = random.nextDouble();

        if (value < FULL_WEIGHT) {
            return EdgeType.FULL;
        }

        value -= FULL_WEIGHT;

        if (value < THIN_WEIGHT) {
            return EdgeType.THIN;
        }

        value -= THIN_WEIGHT;

        if (value < OPEN_WEIGHT) {
            return EdgeType.OPEN;
        }

        return EdgeType.PASSAGE;
    }

    private Map<Cell, Point> translatedCenters(int worldWidth, int worldHeight) {
        int minRawX = Integer.MAX_VALUE;
        int maxRawX = Integer.MIN_VALUE;
        int minRawY = Integer.MAX_VALUE;
        int maxRawY = Integer.MIN_VALUE;

        for (Cell cell : allCells()) {
            Point raw = rawCenter(cell);
            minRawX = Math.min(minRawX, raw.x);
            maxRawX = Math.max(maxRawX, raw.x);
            minRawY = Math.min(minRawY, raw.y);
            maxRawY = Math.max(maxRawY, raw.y);
        }

        int gridWidth = maxRawX - minRawX + OUTER_RADIUS * 2 + OUTER_BUFFER * 2 + 1;
        int gridHeight = maxRawY - minRawY + OUTER_RADIUS * 2 + OUTER_BUFFER * 2 + 1;

        int originX = (worldWidth - gridWidth) / 2 + OUTER_BUFFER + OUTER_RADIUS - minRawX;
        int originY = (worldHeight - gridHeight) / 2 + OUTER_BUFFER + OUTER_RADIUS - minRawY;

        Map<Cell, Point> centers = new HashMap<>();

        for (Cell cell : allCells()) {
            Point raw = rawCenter(cell);
            centers.put(cell, new Point(originX + raw.x, originY + raw.y));
        }

        return centers;
    }

    private int minimumWorldWidth() {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;

        for (Cell cell : allCells()) {
            int x = rawCenter(cell).x;
            min = Math.min(min, x);
            max = Math.max(max, x);
        }

        return max - min + OUTER_RADIUS * 2 + OUTER_BUFFER * 2 + 1;
    }

    private int minimumWorldHeight() {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;

        for (Cell cell : allCells()) {
            int y = rawCenter(cell).y;
            min = Math.min(min, y);
            max = Math.max(max, y);
        }

        return max - min + OUTER_RADIUS * 2 + OUTER_BUFFER * 2 + 1;
    }

    private int colsForRow(int row) {
        return row % 2 == 0 ? SHORT_ROW_COLS : LONG_ROW_COLS;
    }

    private List<Cell> allCells() {
        List<Cell> cells = new ArrayList<>();

        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < colsForRow(row); col++) {
                cells.add(new Cell(col, row));
            }
        }

        return cells;
    }

    private List<Cell> neighbourSlots(Cell cell) {
        List<Cell> slots = new ArrayList<>();

        slots.add(new Cell(cell.col - 1, cell.row));
        slots.add(new Cell(cell.col + 1, cell.row));

        /**
         * Short rows contain 7 cores and are shifted 37 tiles to the right.
         * Long rows contain 8 cores and start 37 tiles further left.
         *
         * This centers the pattern:
         *   7
         *  8
         *   7
         *  8
         */
        if (cell.row % 2 == 0) {
            // Short shifted row -> adjacent long rows.
            slots.add(new Cell(cell.col,     cell.row - 1));
            slots.add(new Cell(cell.col + 1, cell.row - 1));
            slots.add(new Cell(cell.col,     cell.row + 1));
            slots.add(new Cell(cell.col + 1, cell.row + 1));
        } else {
            // Long unshifted row -> adjacent short rows.
            slots.add(new Cell(cell.col - 1, cell.row - 1));
            slots.add(new Cell(cell.col,     cell.row - 1));
            slots.add(new Cell(cell.col - 1, cell.row + 1));
            slots.add(new Cell(cell.col,     cell.row + 1));
        }

        return slots;
    }

    private List<Cell> neighbors(Cell cell) {
        List<Cell> neighbors = new ArrayList<>();

        for (Cell candidate : neighbourSlots(cell)) {
            if (validCell(candidate)) {
                neighbors.add(candidate);
            }
        }

        return neighbors;
    }

    private boolean validCell(Cell cell) {
        return cell.row >= 0
            && cell.row < ROWS
            && cell.col >= 0
            && cell.col < colsForRow(cell.row);
    }

    private int borderDistance(Cell cell) {
        return Math.min(
            Math.min(cell.col, colsForRow(cell.row) - 1 - cell.col),
            Math.min(cell.row, ROWS - 1 - cell.row)
        );
    }

    private Point rawCenter(Cell cell) {
        return new Point(
            cell.col * HORIZONTAL_DX + (cell.row % 2 == 0 ? DIAGONAL_DX : 0),
            cell.row * DIAGONAL_DY
        );
    }

    private boolean pointInsideTranslatedInnerPolygon(double x, double y, Point center) {
        double localX = x - center.x;
        double localY = y - center.y;

        boolean inside = false;

        for (int i = 0, j = INNER_POLYGON.length - 1; i < INNER_POLYGON.length; j = i++) {
            Point current = INNER_POLYGON[i];
            Point previous = INNER_POLYGON[j];

            if (pointOnSegment(localX, localY, previous.x, previous.y, current.x, current.y)) {
                return true;
            }

            boolean intersects = (current.y > localY) != (previous.y > localY);

            if (intersects) {
                double intersectionX =
                    (previous.x - current.x) * (localY - current.y)
                        / (double)(previous.y - current.y)
                        + current.x;

                if (localX <= intersectionX) {
                    inside = !inside;
                }
            }
        }

        return inside;
    }

    private boolean pointOnSegment(
        double x,
        double y,
        double x1,
        double y1,
        double x2,
        double y2
    ) {
        double cross = (x - x1) * (y2 - y1) - (y - y1) * (x2 - x1);

        if (Math.abs(cross) > 1e-9) {
            return false;
        }

        return x >= Math.min(x1, x2) - 1e-9
            && x <= Math.max(x1, x2) + 1e-9
            && y >= Math.min(y1, y2) - 1e-9
            && y <= Math.max(y1, y2) + 1e-9;
    }

    private double supportDistance(double directionX, double directionY) {
        double max = Double.NEGATIVE_INFINITY;

        for (Point point : INNER_POLYGON) {
            max = Math.max(max, point.x * directionX + point.y * directionY);
        }

        return max;
    }

    private int squaredDistance(int x1, int y1, int x2, int y2) {
        int dx = x1 - x2;
        int dy = y1 - y2;
        return dx * dx + dy * dy;
    }

    private Long parseSeedOrRandom(String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("random")) {
            return randomSeed();
        }

        try {
            return Long.parseLong(args[0]);
        } catch (NumberFormatException exception) {
            Log.err("[EvictMapGenerator] Seed must be a whole number or 'random'.");
            return null;
        }
    }

    private long consumeNextSeed() {
        if (nextSeed == null) {
            return randomSeed();
        }

        long seed = nextSeed;
        nextSeed = null;
        return seed;
    }

    private long randomSeed() {
        return new Random().nextLong();
    }

    private int horizontalGreyBandWidth() {
        /**
         * The horizontal variable zone is everything strictly between the
         * guaranteed-floor polygons of two neighbouring cores.
         *
         * With the current values:
         *   coordinate distance = 74
         *   inner polygon reaches 34 tiles toward its neighbour on each side
         *   grey band = 74 - 34 - 34 - 1 = 5 tiles
         *
         * The -1 accounts for inclusive tile coordinates.
         */
        return HORIZONTAL_DX - 2 * supportDistance(1.0, 0.0) < 1.0
            ? 0
            : (int)Math.round(HORIZONTAL_DX - 2 * supportDistance(1.0, 0.0) - 1.0);
    }

    private String percent(double value) {
        return String.format("%.2f", value * 100.0);
    }

    private enum EdgeType {
        FULL,
        THIN,
        OPEN,
        PASSAGE
    }

    private record Cell(int col, int row) {
    }

    private record Point(int x, int y) {
    }

    private record TilePoint(int x, int y) {
    }

    private record EdgeOwnership(Edge edge, double score) {
    }

    private record Edge(Cell a, Cell b) {
        private static Edge of(Cell first, Cell second) {
            if (
                first.row < second.row
                    || (first.row == second.row && first.col <= second.col)
            ) {
                return new Edge(first, second);
            }

            return new Edge(second, first);
        }
    }
}
