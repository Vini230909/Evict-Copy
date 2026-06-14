package vini.evictmap;

import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.world.Tile;

final class CoreMarkerFloor {

    private static final int MARKER_RADIUS = 1;

    private CoreMarkerFloor() {
    }

    static void place(int centerX, int centerY) {
        for (
            int y = centerY - MARKER_RADIUS;
            y <= centerY + MARKER_RADIUS;
            y++
        ) {
            for (
                int x = centerX - MARKER_RADIUS;
                x <= centerX + MARKER_RADIUS;
                x++
            ) {
                Tile tile = Vars.world.tile(x, y);

                if (tile != null) {
                    tile.setFloorNet(Blocks.snow, Blocks.air);
                }
            }
        }
    }
}
