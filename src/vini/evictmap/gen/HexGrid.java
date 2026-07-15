package vini.evictmap.gen;

/**
 * Shared hex-grid geometry, measured from the editor reference map. The
 * single source of truth for the values that generation (EvictTerrainGenerator,
 * ResourceGenerator) and the round systems (TeamManager) must agree on - if
 * the grid ever changes, it changes here and nowhere else.
 */
public final class HexGrid {

    /** Columns in the short (even) rows. */
    public static final int SHORT_ROW_COLS = 7;

    /** Columns in the long (odd) rows. */
    public static final int LONG_ROW_COLS = 8;

    /** Number of hex rows. */
    public static final int ROWS = 9;

    /**
     * Radius of one playable hex circle, in tiles. Terrain carves this circle,
     * ore placement is bounded by it, and Extinction converts it to space.
     */
    public static final int HEX_RADIUS = 39;

    private HexGrid() {
    }
}
