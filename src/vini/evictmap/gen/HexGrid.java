package vini.evictmap.gen;

/**
 * Shared hex-grid geometry, measured from the editor reference map. Generation
 * and the round systems must agree on these; if the grid changes, it changes
 * here and nowhere else.
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
