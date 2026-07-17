package vini.evictmap.round;

/**
 * One hex cell of the generated map and its live ownership state.
 *
 * <p>The immutable geometry ({@link #col}/{@link #row} grid position,
 * {@link #x}/{@link #y} center tile, {@link #protectedSides}) is set at
 * generation time; the mutable fields track capture and Extinction state during
 * the round. Owned jointly by {@link TeamManager} (assignment, victory),
 * {@link CoreCapture} (capture lifecycle), the terrain generator (creation) and
 * the Extinction system (collapse), so it lives as its own top-level type
 * instead of nested in {@code TeamManager}.
 */
public final class HexSlot {

    public final int col;
    public final int row;
    public final int x;
    public final int y;
    public final int protectedSides;

    public int ownerTeamId = TeamManager.FALLEN_TEAM_ID;
    public boolean capturing = false;
    public int pendingCaptureTeamId = TeamManager.FALLEN_TEAM_ID;
    public boolean extinct = false;

    public HexSlot(int col, int row, int x, int y, int protectedSides) {
        this.col = col;
        this.row = row;
        this.x = x;
        this.y = y;
        this.protectedSides = protectedSides;
    }
}
