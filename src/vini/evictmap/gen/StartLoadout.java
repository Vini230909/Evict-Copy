package vini.evictmap.gen;

import arc.struct.Seq;
import arc.util.Log;
import mindustry.Vars;
import mindustry.content.Items;
import mindustry.game.Schematic;
import mindustry.game.Schematic.Stile;
import mindustry.game.Schematics;
import mindustry.game.Team;
import mindustry.world.Tile;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

/**
 * Places the one-time Evict starting package when a personal team claims its
 * first hex.
 * The uploaded .msch file is embedded as Base64 so the plugin remains a single
 * self-contained JAR. The schematic is 15x15, contains one centered Nucleus at
 * local coordinate 7,7 and preserves rotations plus block configurations.
 */
public final class StartLoadout {

    private static final String START_SCHEMATIC_BASE64 =
            "bXNjaAF4nDWTbW7UMBCGJ3Y+vHG85Qi9wEr8RL0DJ0D8cFMjVgpJlE1bqqr34K4Uyrzz0o02T5zMvPPhsVzJVS31nH8U+VAezuN+"
                    + "fdnztl9/Lz+vpR+XeS/z/jmvkp4/3jxfxi2vN59eXiTeFV2c1/28zCLSTvm2TBdxX772EsdlXct2eszTJD5vowQVeihPyybxskx5"
                    + "O615LpN0lzHve9mkuzU+Sb8uj+o5L3dFwv08LflOPw/jspXTfD9O5f4iMZ+307c87os6iPzSv1R66c8Rnohiv4FIeFmZLc1xc3zw"
                    + "RC0VXjQUaomO+oEmB6InolRQGbhKtDxi5fgMgcorHLJy+vdALR6vLJBjIIdA+BZociB6IhKDeGgmRjiyVnkv2XkTgDrWiGYRPCN4"
                    + "RvCI0CgO4mDZI0EPaXxNFDPpmpo1NJFzQ7SEirWKEDT3t1dYVZ3eD2oVPLxMsaZiQ6kGaTSC6K5T1DDWq4Ma+tGjMQ3KBQYiQail"
                    + "QgsFQHNukUBwb381CsKjkybQUqClQAsBtLdCTzrsCeCJmuhpEomBML9Av0C/QL8Av1bRa/xXzUBLqtBZ26jA2gNqdzYyFeAIT6hA"
                    + "p+jRkIP6OeyI+R3YM5sx7I9WXMMODr001jNU2oYKsStMiZYFBEJjQTLauHtIpfcD4QATjtyMiF3BqkW1kUqRStGyVvx3P76fJ8eh"
                    + "rzA3tiEDR3vA8AENYZoDNQdqDtCEu03xQM3Ec4lXDgfJcYXBVNREQ5hmomaiZmKeybqkiDgnCSli85P26ffbH1O0w3m0k6lwXHki"
                    + "wvnIPTgil3+LrVgo";

    private static final Schematic START_SCHEMATIC =
            Schematics.readBase64(START_SCHEMATIC_BASE64);

    private StartLoadout() {
    }

    public static void place(int coreX, int coreY, Team team) {
        Stile schematicCore = START_SCHEMATIC.tiles.find(
                stile -> stile.block instanceof mindustry.world.blocks.storage.CoreBlock
        );

        if (schematicCore == null) {
            throw new IllegalStateException(
                    "Embedded Evict start schematic does not contain a core."
            );
        }

        int offsetX = coreX - schematicCore.x;
        int offsetY = coreY - schematicCore.y;

        /*
         * Sort by schematic priority like Mindustry's own loadout placer.
         * tile.setNet() is used instead of plain setBlock() because this package
         * is placed after a player joins an already-running server.
         */
        Seq<Stile> sortedTiles = START_SCHEMATIC.tiles.copy();
        sortedTiles.sort(stile -> -stile.block.schematicPriority);

        CoreMarkerFloor.place(coreX, coreY);

        for (Stile stile : sortedTiles) {
            Tile tile = Vars.world.tile(
                    stile.x + offsetX,
                    stile.y + offsetY
            );

            if (tile == null) {
                throw new IllegalStateException(
                        "Start schematic reaches outside the map at "
                                + (stile.x + offsetX)
                                + ","
                                + (stile.y + offsetY)
                                + "."
                );
            }

            tile.setNet(stile.block, team, stile.rotation);

            if (tile.build != null) {
                tile.build.configureAny(stile.config);
            }
        }

        Tile coreTile = Vars.world.tile(coreX, coreY);

        if (
                coreTile == null
                        || !(coreTile.build instanceof CoreBuild core)
        ) {
            throw new IllegalStateException(
                    "Start schematic did not place a Nucleus at "
                            + coreX + "," + coreY + "."
            );
        }

        fillStartingItems(core);

        Log.info(
                "[EvictMapGenerator] Placed 15x15 start schematic and starting resources for team #@ at @,@.",
                team.id,
                coreX,
                coreY
        );
    }

    private static void fillStartingItems(CoreBuild core) {
        core.items.clear();

        core.items.add(Items.copper, 900);
        core.items.add(Items.lead, 900);
        core.items.add(Items.metaglass, 100);
        core.items.add(Items.graphite, 200);
        core.items.add(Items.titanium, 50);
        core.items.add(Items.scrap, 1000);
        core.items.add(Items.silicon, 400);
    }
}
