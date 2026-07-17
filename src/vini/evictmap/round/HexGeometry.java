package vini.evictmap.round;

import vini.evictmap.gen.HexGrid;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure offset-grid geometry over {@link HexSlot}s: tile distance, grid distance
 * (BFS on the obstacle-free odd-row offset grid), adjacency, and nearest-slot
 * lookup. Stateless — every method is a function of its arguments and the shared
 * {@link HexGrid} constants — so it is easy to read and to unit-test in
 * isolation, and it keeps this math out of {@link TeamManager}.
 */
final class HexGeometry {

    private static final int SHORT_ROW_COLS = HexGrid.SHORT_ROW_COLS;
    private static final int LONG_ROW_COLS = HexGrid.LONG_ROW_COLS;
    private static final int ROWS = HexGrid.ROWS;
    private static final int CENTER_ROW = ROWS / 2;
    private static final int CENTER_COL = SHORT_ROW_COLS / 2;

    private HexGeometry() {
    }

    /** Squared tile distance from a point to a slot's center tile. */
    static long squaredDistance(int tileX, int tileY, HexSlot slot) {
        long dx = tileX - slot.x;
        long dy = tileY - slot.y;

        return dx * dx + dy * dy;
    }

    /** Grid steps from the center hex to {@code slot}; used to number Extinction rings. */
    static int gridDistanceFromCenter(HexSlot slot) {
        return gridDistance(new HexSlot(CENTER_COL, CENTER_ROW, 0, 0, 0), slot);
    }

    /**
     * Shortest number of hex steps between two slots, found by BFS over the
     * offset-grid neighbours. Returns {@link Integer#MAX_VALUE} if unreachable.
     */
    static int gridDistance(HexSlot first, HexSlot second) {
        GridPos start = new GridPos(first.col, first.row);
        GridPos target = new GridPos(second.col, second.row);

        if (start.equals(target)) {
            return 0;
        }

        ArrayDeque<GridStep> queue = new ArrayDeque<>();
        Set<GridPos> visited = new HashSet<>();

        queue.add(new GridStep(start, 0));
        visited.add(start);

        while (!queue.isEmpty()) {
            GridStep step = queue.removeFirst();

            for (GridPos neighbour : neighbourSlots(step.position)) {
                if (!validGridPos(neighbour) || !visited.add(neighbour)) {
                    continue;
                }

                int distance = step.distance + 1;

                if (neighbour.equals(target)) {
                    return distance;
                }

                queue.addLast(new GridStep(neighbour, distance));
            }
        }

        return Integer.MAX_VALUE;
    }

    /**
     * True when two hex slots are the same hex or share an edge. This matches
     * {@code gridDistance(a, b) <= 1} on the obstacle-free offset grid without
     * the BFS allocations, which matters because range attrition runs this for
     * every unit on a fixed interval.
     */
    static boolean isSameOrAdjacentHex(HexSlot a, HexSlot b) {
        int colDelta = b.col - a.col;
        int rowDelta = b.row - a.row;

        if (rowDelta == 0) {
            return colDelta >= -1 && colDelta <= 1;
        }

        if (rowDelta == 1 || rowDelta == -1) {
            return a.row % 2 == 0
                    ? colDelta == 0 || colDelta == 1
                    : colDelta == 0 || colDelta == -1;
        }

        return false;
    }

    /** Nearest non-extinct slot to a tile, or null if none. */
    static HexSlot closestHexSlot(List<HexSlot> slots, int tileX, int tileY) {
        HexSlot closest = null;
        long closestDistanceSquared = Long.MAX_VALUE;

        for (HexSlot slot : slots) {
            if (slot.extinct) {
                continue;
            }

            long distanceSquared = squaredDistance(tileX, tileY, slot);

            if (distanceSquared < closestDistanceSquared) {
                closest = slot;
                closestDistanceSquared = distanceSquared;
            }
        }

        return closest;
    }

    /** Nearest slot to a tile, extinct slots included, or null if none. */
    static HexSlot closestHexSlotIncludingExtinct(List<HexSlot> slots, int tileX, int tileY) {
        HexSlot closest = null;
        long closestDistanceSquared = Long.MAX_VALUE;

        for (HexSlot slot : slots) {
            long distanceSquared = squaredDistance(tileX, tileY, slot);

            if (distanceSquared < closestDistanceSquared) {
                closest = slot;
                closestDistanceSquared = distanceSquared;
            }
        }

        return closest;
    }

    private static List<GridPos> neighbourSlots(GridPos position) {
        List<GridPos> result = new ArrayList<>();

        result.add(new GridPos(position.col - 1, position.row));
        result.add(new GridPos(position.col + 1, position.row));

        if (position.row % 2 == 0) {
            result.add(new GridPos(position.col, position.row - 1));
            result.add(new GridPos(position.col + 1, position.row - 1));
            result.add(new GridPos(position.col, position.row + 1));
            result.add(new GridPos(position.col + 1, position.row + 1));
        } else {
            result.add(new GridPos(position.col - 1, position.row - 1));
            result.add(new GridPos(position.col, position.row - 1));
            result.add(new GridPos(position.col - 1, position.row + 1));
            result.add(new GridPos(position.col, position.row + 1));
        }

        return result;
    }

    private static boolean validGridPos(GridPos position) {
        return position.row >= 0
                && position.row < ROWS
                && position.col >= 0
                && position.col < colsForRow(position.row);
    }

    private static int colsForRow(int row) {
        return row % 2 == 0 ? SHORT_ROW_COLS : LONG_ROW_COLS;
    }

    private record GridPos(int col, int row) {
    }

    private record GridStep(GridPos position, int distance) {
    }
}
