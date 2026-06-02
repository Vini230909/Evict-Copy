package vini.evictmap;

import arc.Events;
import arc.util.CommandHandler;
import arc.util.Log;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.gen.Groups;
import mindustry.mod.Plugin;
import mindustry.world.Tile;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * First functional Evict-style map generator prototype.
 *
 * Current scope:
 * - Dark Sand floor
 * - Dirt Wall terrain
 * - 6x6 staggered hex grid
 * - rare completely filled hexes, biased toward map edges
 * - no separated playable sectors
 * - four wall / connection templates
 *
 * Deliberately not included yet:
 * - cores
 * - team assignment
 * - ores
 * - water
 * - balancing rules beyond connectivity
 */
public class EvictMapPlugin extends Plugin {

    // ---------------------------------------------------------------------
    // Geometry measured from the editor reference map
    // ---------------------------------------------------------------------

    private static final int COLS = 6;
    private static final int ROWS = 6;

    private static final int OUTER_RADIUS = 38;
    private static final int HORIZONTAL_DX = 75;
    private static final int DIAGONAL_DX = 37;
    private static final int DIAGONAL_DY = 64;

    private static final int PASSAGE_WIDTH = 7;
    private static final double THIN_WALL_HALF_WIDTH = 0.5;
    private static final int OUTER_BUFFER = 10;

    /**
     * Mirrored inner guaranteed-floor polygon.
     *
     * Reference center: (739, 168)
     * Supplied points:
     * - middle left:          (706, 168) -> (-33,  0)
     * - upper-left side:      (706, 187) -> (-33, 19)
     * - upper-left top:       (736, 205) -> ( -3, 37)
     * - middle top:           (739, 205) -> (  0, 37)
     */
    private static final Point[] INNER_POLYGON = new Point[]{
        new Point(-33,   0),
        new Point(-33,  19),
        new Point( -3,  37),
        new Point(  0,  37),
        new Point(  3,  37),
        new Point( 33,  19),
        new Point( 33,   0),
        new Point( 33, -19),
        new Point(  3, -37),
        new Point(  0, -37),
        new Point( -3, -37),
        new Point(-33, -19)
    };

    // ---------------------------------------------------------------------
    // Adjustable probabilities
    // ---------------------------------------------------------------------

    private static final double FULL_WEIGHT = 0.10;
    private static final double THIN_WEIGHT = 0.2333333333;
    private static final double OPEN_WEIGHT = 0.4333333333;
    private static final double PASSAGE_WEIGHT = 0.2333333334;

    private static final double FILLED_HEX_BORDER_CHANCE = 0.11;
    private static final double FILLED_HEX_SECOND_RING_CHANCE = 0.035;
    private static final double FILLED_HEX_INNER_CHANCE = 0.010;

    private static final double CHAIN_START_CHANCE = 0.22;
    private static final double CHAIN_CONTINUE_CHANCE = 0.48;
    private static final int CHAIN_MAX_LENGTH = 3;

    // ---------------------------------------------------------------------
    // Runtime state
    // ---------------------------------------------------------------------

    private boolean autoGenerate = false;
    private Long nextSeed = null;
    private Long lastSeed = null;

    @Override
    public void init() {
        Events.on(WorldLoadEvent.class, event -> {
            if (!autoGenerate) {
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

        Log.info("[EvictMapGenerator] Loaded. Use 'evictstatus' for commands and current settings.");
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
                Log.info("[EvictMapGenerator] grid: @x@", COLS, ROWS);
                Log.info("[EvictMapGenerator] required map size: at least @x@ tiles", minimumWorldWidth(), minimumWorldHeight());
                Log.info("[EvictMapGenerator] edge weights: full=@%, thin=@%, open=@%, passage=@%",
                    percent(FULL_WEIGHT),
                    percent(THIN_WEIGHT),
                    percent(OPEN_WEIGHT),
                    percent(PASSAGE_WEIGHT)
                );
            }
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

        Random random = new Random(seed);

        List<Cell> cells = allCells();
        Set<Cell> filledCells = chooseFilledCells(random, cells);

        List<Cell> normalCells = new ArrayList<>();
        for (Cell cell : cells) {
            if (!filledCells.contains(cell)) {
                normalCells.add(cell);
            }
        }

        Set<Edge> backbone = buildRandomSpanningTree(normalCells, random);

        Map<Edge, EdgeType> edgeTypes = new LinkedHashMap<>();
        for (Edge edge : uniqueEdges(normalCells)) {
            if (backbone.contains(edge)) {
                edgeTypes.put(edge, chooseTraversableEdgeType(random));
            } else {
                edgeTypes.put(edge, chooseEdgeType(random));
            }
        }

        Map<Cell, Point> centers = translatedCenters(worldWidth, worldHeight);
        byte[][] zones = createZoneMap(worldWidth, worldHeight, centers, normalCells);
        boolean[][] walls = createWallMap(worldWidth, worldHeight, zones);

        carveRoundedOuterCaps(walls, zones, centers, normalCells);
        applyConnectionTemplates(walls, zones, centers, edgeTypes);
        applyTerrainToWorld(walls);

        lastSeed = seed;

        Log.info(
            "[EvictMapGenerator] Done. seed=@ normalHexes=@ filledHexes=@ guaranteedNetworkEdges=@",
            seed,
            normalCells.size(),
            filledCells.size(),
            backbone.size()
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
                    if (pointInsideTranslatedInnerPolygon(x, y, center)) {
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
                // Everything starts as wall except the guaranteed inner polygons.
                walls[y][x] = zones[y][x] != 2;
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
        int height = zones.length;
        int width = zones[0].length;

        for (Map.Entry<Edge, EdgeType> entry : edgeTypes.entrySet()) {
            Edge edge = entry.getKey();
            EdgeType edgeType = entry.getValue();

            Point a = centers.get(edge.a);
            Point b = centers.get(edge.b);

            double dx = b.x - a.x;
            double dy = b.y - a.y;
            double distance = Math.hypot(dx, dy);

            double ux = dx / distance;
            double uy = dy / distance;
            double vx = -uy;
            double vy = ux;

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
                    double v = relX * vx + relY * vy;

                    if (Math.abs(u) > gapHalf + 0.75) {
                        continue;
                    }

                    boolean insideA = squaredDistance(x, y, a.x, a.y) <= OUTER_RADIUS * OUTER_RADIUS;
                    boolean insideB = squaredDistance(x, y, b.x, b.y) <= OUTER_RADIUS * OUTER_RADIUS;

                    if (!insideA && !insideB) {
                        continue;
                    }

                    switch (edgeType) {
                        case FULL -> walls[y][x] = true;
                        case OPEN -> walls[y][x] = false;
                        case THIN -> walls[y][x] = Math.abs(u) <= THIN_WALL_HALF_WIDTH;
                        case PASSAGE -> walls[y][x] = !(Math.abs(v) < PASSAGE_WIDTH / 2.0);
                    }
                }
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

                // Preserve temporary cores and other synthetic editor blocks.
                // Terrain walls and air are replaced.
                if (!tile.block().synthetic()) {
                    tile.setBlock(walls[y][x] ? Blocks.dirtWall : Blocks.air);
                }
            }
        }
    }

    private Set<Cell> chooseFilledCells(Random random, List<Cell> cells) {
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

    private Set<Edge> buildRandomSpanningTree(List<Cell> normalCells, Random random) {
        Set<Cell> cellSet = new HashSet<>(normalCells);
        Set<Cell> visited = new HashSet<>();
        Set<Edge> tree = new HashSet<>();
        Deque<Cell> stack = new ArrayDeque<>();

        Cell start = normalCells.get(random.nextInt(normalCells.size()));
        visited.add(start);
        stack.push(start);

        while (!stack.isEmpty()) {
            Cell current = stack.peek();

            List<Cell> options = new ArrayList<>();
            for (Cell neighbour : neighbors(current)) {
                if (cellSet.contains(neighbour) && !visited.contains(neighbour)) {
                    options.add(neighbour);
                }
            }

            Collections.shuffle(options, random);

            if (options.isEmpty()) {
                stack.pop();
                continue;
            }

            Cell neighbour = options.get(0);
            visited.add(neighbour);
            stack.push(neighbour);
            tree.add(Edge.of(current, neighbour));
        }

        if (visited.size() != cellSet.size()) {
            throw new IllegalStateException("Remaining normal hex graph is unexpectedly disconnected.");
        }

        return tree;
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
        double openShare = OPEN_WEIGHT / (OPEN_WEIGHT + PASSAGE_WEIGHT);
        return random.nextDouble() < openShare ? EdgeType.OPEN : EdgeType.PASSAGE;
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

    private List<Cell> allCells() {
        List<Cell> cells = new ArrayList<>();

        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                cells.add(new Cell(col, row));
            }
        }

        return cells;
    }

    private List<Cell> neighbourSlots(Cell cell) {
        List<Cell> slots = new ArrayList<>();

        slots.add(new Cell(cell.col - 1, cell.row));
        slots.add(new Cell(cell.col + 1, cell.row));

        if (cell.row % 2 == 0) {
            slots.add(new Cell(cell.col - 1, cell.row - 1));
            slots.add(new Cell(cell.col,     cell.row - 1));
            slots.add(new Cell(cell.col - 1, cell.row + 1));
            slots.add(new Cell(cell.col,     cell.row + 1));
        } else {
            slots.add(new Cell(cell.col,     cell.row - 1));
            slots.add(new Cell(cell.col + 1, cell.row - 1));
            slots.add(new Cell(cell.col,     cell.row + 1));
            slots.add(new Cell(cell.col + 1, cell.row + 1));
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
        return cell.col >= 0 && cell.col < COLS && cell.row >= 0 && cell.row < ROWS;
    }

    private int borderDistance(Cell cell) {
        return Math.min(
            Math.min(cell.col, COLS - 1 - cell.col),
            Math.min(cell.row, ROWS - 1 - cell.row)
        );
    }

    private Point rawCenter(Cell cell) {
        return new Point(
            cell.col * HORIZONTAL_DX + (cell.row % 2 == 1 ? DIAGONAL_DX : 0),
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
