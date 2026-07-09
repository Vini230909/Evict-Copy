package vini.evictmap;

/**
 * Shared hex-grid geometry, measured from the editor reference map. The
 * single source of truth for the values that generation (EvictTerrainGenerator,
 * ResourceGenerator) and the round systems (TeamManager) must agree on - if
 * the grid ever changes, it changes here and nowhere else.
 */
final class HexGrid {

    /** Columns in the short (even) rows. */
    static final int SHORT_ROW_COLS = 7;

    /** Columns in the long (odd) rows. */
    static final int LONG_ROW_COLS = 8;

    /** Number of hex rows. */
    static final int ROWS = 9;

    /**
     * Radius of one playable hex circle, in tiles. Terrain carves this circle,
     * ore placement is bounded by it, and Extinction converts it to space.
     */
    static final int HEX_RADIUS = 39;

    private HexGrid() {
    }
}
