package vini.evictmap.gameplay;

import arc.util.Time;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.world.Tile;
import mindustry.world.blocks.storage.CoreBlock;
import vini.evictmap.core.util.PluginLog;
import vini.evictmap.gen.HexGrid;
import vini.evictmap.round.HexSlot;
import vini.evictmap.round.TeamManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Extinction as a continuous wave that eats the map from the outside in, tile
 * by tile, instead of collapsing whole hex rings in bursts.
 *
 * <p>This exists next to {@link ExtinctionManager}, not inside it: it is
 * driven only by {@code /extinction} for now and is meant to replace the ring
 * collapse once it has been tested in a live round. Nothing else in the plugin
 * starts it.
 *
 * <h2>Why a wave</h2>
 * The ring collapse queues a whole ring at once and drains it at a fixed 128
 * tiles per <em>tick</em>. Each tile costs two reliable packets to every
 * connected client ({@code removeNet} + {@code setFloorNet}, ~18 bytes per
 * client), so at 60 TPS that is ~135 KB/s per client - more than four times
 * what a 32 KB per-client write buffer holds per second. A client whose buffer
 * overflows is dropped by {@code ArcNetProvider} on the spot, which is why a
 * ring collapse on a laggy server disconnects nearly everyone at once, and why
 * a player connecting during one never finishes receiving the world.
 *
 * <p>The total work is the same either way - every hex tile has to become
 * space. The ring collapse just does it in five bursts inside a ten-minute
 * window it otherwise spends idle (~6% duty cycle). Spreading the identical
 * work evenly across that window costs no extra wall-clock time and drops the
 * peak rate by a factor of ~17, to ~445 tiles/s (~7.8 KB/s per client).
 *
 * <h2>Rate</h2>
 * The budget is derived from elapsed <em>time</em>, not from a per-tick
 * constant, so a TPS drop cannot stretch the collapse: at 60 TPS the wave eats
 * ~7 tiles per tick, at 10 TPS ~44, and the packets per second - the number
 * that actually matters to a client - stay the same. {@link #MAX_TILES_PER_TICK}
 * caps a single catch-up burst well below the write buffer.
 *
 * <p>The radius shrinks by equal <em>area</em> per second rather than equal
 * distance: tiles are simply consumed farthest-first from a precomputed
 * distance-ordered list, which keeps the tile rate flat regardless of how much
 * hex area happens to sit at a given radius.
 *
 * <h2>What it touches</h2>
 * Only the hex circles themselves, exactly like the ring collapse - the gaps
 * between hexes and the filled wall hexes stay as they are. The center hex is
 * excluded entirely: it is the prize, and whoever holds its core when the wave
 * finishes has won.
 */
public final class WaveExtinction implements GameplayManagerInterface {

    /** Full collapse duration when {@code /extinction} is called without one. */
    public static final int DEFAULT_DURATION_SECONDS = 600;

    public static final int MIN_DURATION_SECONDS = 10;
    public static final int MAX_DURATION_SECONDS = 3600;

    /**
     * Hard ceiling on one tick's batch, ~8 KB of packets per client - a quarter
     * of the 32 KB per-client write buffer {@code ArcNetProvider} gives out
     * ({@code new Server(32768, 16384, ...)}). Filling that buffer in one tick
     * is what disconnects clients, so a stall must never be caught up in a
     * single burst. At the default duration this only engages below ~1 TPS.
     */
    private static final int MAX_TILES_PER_TICK = 455;

    /**
     * Tiles of slack between the wave front and a hex center at which the hex
     * is declared extinct. A core is up to 5 tiles wide, so its footprint is
     * reached slightly before its center tile; marking the slot first stops
     * {@code CoreCapture} from treating the loss as a capture.
     */
    private static final int CORE_CLEARANCE = 5;

    private static final long UNIT_SWEEP_INTERVAL_MILLIS = 1000L;

    private static final int HEX_RADIUS_SQUARED =
            HexGrid.HEX_RADIUS * HexGrid.HEX_RADIUS;

    private static final int CENTER_COL = HexGrid.SHORT_ROW_COLS / 2;
    private static final int CENTER_ROW = HexGrid.ROWS / 2;

    private final TeamManager teamManager;

    private boolean running;
    private long startedAtMillis;
    private long lastUnitSweepMillis;
    private int durationSeconds = DEFAULT_DURATION_SECONDS;

    /** Every tile the wave will convert, farthest from the center hex first. */
    private Tile[] tiles = new Tile[0];

    /** {@link #tiles} distances from the center hex, same order (descending). */
    private int[] tileDistances = new int[0];

    private int nextTile;

    private HexSlot centerSlot;

    /** Every collapsing slot, farthest first, so ownership dies in wave order. */
    private HexSlot[] slotsByDistance = new HexSlot[0];

    private int[] slotDistances = new int[0];

    private int nextSlot;

    public WaveExtinction(TeamManager teamManager) {
        this.teamManager = teamManager;
    }

    /**
     * Arms the wave over {@code seconds}.
     *
     * @return {@code null} once running, or the reason it could not start.
     */
    public String start(int seconds) {
        if (running) {
            return "The wave is already running - /extinction stop first.";
        }

        List<HexSlot> slots = teamManager.slots();

        if (slots.isEmpty()) {
            return "No hex slots on this map - nothing to collapse.";
        }

        centerSlot = findCenterSlot(slots);

        if (centerSlot == null) {
            return "No center hex found.";
        }

        collectTiles(slots);

        if (tiles.length == 0) {
            return "Every hex tile is already space.";
        }

        collectSlots(slots);

        durationSeconds = seconds;
        nextTile = 0;
        nextSlot = 0;
        startedAtMillis = Time.millis();
        lastUnitSweepMillis = startedAtMillis;
        running = true;

        PluginLog.info(
                "Wave extinction armed: @ tiles over @ s (@ tiles/s, ~@ B/s per client).",
                tiles.length,
                seconds,
                tiles.length / seconds,
                tiles.length / seconds * 18
        );

        return null;
    }

    /** Stops the wave where it is; already-converted terrain stays space. */
    public void stop() {
        running = false;
        tiles = new Tile[0];
        tileDistances = new int[0];
        slotsByDistance = new HexSlot[0];
        slotDistances = new int[0];
        nextTile = 0;
        nextSlot = 0;
    }

    public boolean isRunning() {
        return running;
    }

    /** One line for {@code /extinction status}. */
    public String status() {
        if (!running) {
            return "Wave extinction is not running.";
        }

        int done = nextTile;
        int total = tiles.length;
        int percent = total == 0 ? 100 : done * 100 / total;
        float elapsed = elapsedSeconds();

        return "Wave extinction: " + percent + "% (" + done + "/" + total
                + " tiles), radius " + currentRadius()
                + ", " + Math.max(0, Math.round(durationSeconds - elapsed)) + " s left, "
                + (total - done) + " tiles to go.";
    }

    @Override
    public void beginRound() {
        stop();
    }

    @Override
    public void endRound() {
        stop();
    }

    @Override
    public void update() {
        if (!running) {
            return;
        }

        int target = Math.round(tiles.length * Math.min(1f, elapsedSeconds() / durationSeconds));
        int budget = Math.min(target - nextTile, MAX_TILES_PER_TICK);

        if (budget > 0) {
            // The radius the front will have reached once this batch is done,
            // so a hex is declared extinct before its core is touched.
            int radiusAfter =
                    tileDistances[Math.min(nextTile + budget, tiles.length - 1)];

            markReachedSlotsExtinct(radiusAfter);
            convert(budget);
        }

        sweepUnits();

        if (nextTile >= tiles.length) {
            finish();
        }
    }

    /**
     * Converts one batch. Captures stay suppressed for the duration: a core
     * dying to the wave is an extinction, not a capture, and without this
     * {@code CoreCapture} would schedule a replacement Core Shard on ground
     * that is about to be space.
     */
    private void convert(int budget) {
        boolean wasSuppressed = teamManager.isCaptureSuppressed();
        teamManager.setCaptureSuppressed(true);

        try {
            for (int i = 0; i < budget && nextTile < tiles.length; i++) {
                Tile tile = tiles[nextTile++];

                // Skipped for the ~90% of tiles that carry nothing, halving the
                // packets - removeNet() sends unconditionally.
                if (tile.block() != Blocks.air) {
                    tile.removeNet();
                }

                tile.setFloorNet(Blocks.space);
            }
        } finally {
            teamManager.setCaptureSuppressed(wasSuppressed);
        }
    }

    private void markReachedSlotsExtinct(int radiusAfter) {
        while (
                nextSlot < slotsByDistance.length
                        && radiusAfter <= slotDistances[nextSlot] + CORE_CLEARANCE
        ) {
            HexSlot slot = slotsByDistance[nextSlot++];

            slot.extinct = true;
            slot.capturing = false;
            slot.ownerTeamId = Team.derelict.id;
            slot.pendingCaptureTeamId = Team.derelict.id;
        }
    }

    /**
     * Kills whatever is standing in the void. Run once a second rather than per
     * tick - a unit a second late into the vacuum is invisible, a per-tick
     * sweep over every unit is not.
     */
    private void sweepUnits() {
        long now = Time.millis();

        if (now - lastUnitSweepMillis < UNIT_SWEEP_INTERVAL_MILLIS) {
            return;
        }

        lastUnitSweepMillis = now;

        long radius = currentRadius();
        long radiusSquared = radius * radius;
        List<Unit> doomed = new ArrayList<>();

        Groups.unit.each(unit -> {
            if (!unit.isAdded()) {
                return;
            }

            long dx = unit.tileX() - centerSlot.x;
            long dy = unit.tileY() - centerSlot.y;

            if (dx * dx + dy * dy > radiusSquared) {
                doomed.add(unit);
            }
        });

        for (Unit unit : doomed) {
            unit.kill();
        }
    }

    private void finish() {
        running = false;

        Team holder = centerCoreTeam();

        if (holder == null) {
            Call.sendMessage(
                    "[scarlet]Extinction complete.[] The center core did not survive - nobody won."
            );
            PluginLog.info("Wave extinction finished with no surviving center core.");
        } else {
            Call.sendMessage(
                    "[scarlet]Extinction complete.[] [accent]"
                            + teamManager.displayTeam(holder)
                            + "[] held the center core."
            );
            PluginLog.info(
                    "Wave extinction finished: team #@ held the center core.",
                    holder.id
            );
        }

        // TODO: when this replaces ExtinctionManager, hand the holder to
        // TeamManager.finishExtinction(holder) instead of only announcing it.
        // Left out while the wave is under test so a trial run cannot end a
        // live round.
    }

    private Team centerCoreTeam() {
        Tile tile = Vars.world.tile(centerSlot.x, centerSlot.y);

        if (tile == null) {
            return null;
        }

        Building build = tile.build;

        return build == null || !(build.block instanceof CoreBlock)
                ? null
                : build.team;
    }

    private float elapsedSeconds() {
        return (Time.millis() - startedAtMillis) / 1000f;
    }

    /**
     * How far out the front still is. Once the queue is empty the wave has come
     * to rest on the center hex's edge, which is what keeps its units alive.
     */
    private int currentRadius() {
        return nextTile < tiles.length
                ? tileDistances[nextTile]
                : HexGrid.HEX_RADIUS;
    }

    /**
     * The middle hex by grid position. It is protected from procedural filling,
     * so it is always a real slot; the nearest-to-the-others fallback only
     * covers a hand-edited map.
     */
    private HexSlot findCenterSlot(List<HexSlot> slots) {
        for (HexSlot slot : slots) {
            if (slot.col == CENTER_COL && slot.row == CENTER_ROW) {
                return slot;
            }
        }

        long sumX = 0;
        long sumY = 0;

        for (HexSlot slot : slots) {
            sumX += slot.x;
            sumY += slot.y;
        }

        int averageX = (int) (sumX / slots.size());
        int averageY = (int) (sumY / slots.size());
        HexSlot closest = null;
        long closestDistance = Long.MAX_VALUE;

        for (HexSlot slot : slots) {
            long dx = slot.x - averageX;
            long dy = slot.y - averageY;
            long distance = dx * dx + dy * dy;

            if (distance < closestDistance) {
                closestDistance = distance;
                closest = slot;
            }
        }

        return closest;
    }

    /**
     * Collects every tile the wave will eat and orders it farthest-first.
     *
     * <p>Membership is the same test the ring collapse uses: the tile's nearest
     * slot owns it, and only within the hex radius - so the gaps between hexes
     * and the filled wall hexes (which are not slots) are never touched.
     *
     * <p>Ordering is a counting sort over the integer distance rather than a
     * comparison sort: the key range is a few hundred, the input a few hundred
     * thousand, and this runs on the main thread.
     */
    private void collectTiles(List<HexSlot> slots) {
        Tile[] buffer = new Tile[4096];
        int[] distances = new int[4096];
        int count = 0;
        int maxDistance = 0;

        for (Tile tile : Vars.world.tiles) {
            HexSlot nearest = nearestSlot(slots, tile.x, tile.y);

            if (
                    nearest == null
                            || nearest == centerSlot
                            || squaredDistance(tile.x, tile.y, nearest) > HEX_RADIUS_SQUARED
                            || tile.floor() == Blocks.space
            ) {
                continue;
            }

            if (count == buffer.length) {
                Tile[] grownTiles = new Tile[count * 2];
                int[] grownDistances = new int[count * 2];
                System.arraycopy(buffer, 0, grownTiles, 0, count);
                System.arraycopy(distances, 0, grownDistances, 0, count);
                buffer = grownTiles;
                distances = grownDistances;
            }

            int distance = (int) Math.round(
                    Math.sqrt(squaredDistance(tile.x, tile.y, centerSlot))
            );

            buffer[count] = tile;
            distances[count] = distance;
            count++;
            maxDistance = Math.max(maxDistance, distance);
        }

        int[] cursor = new int[maxDistance + 1];

        for (int i = 0; i < count; i++) {
            cursor[distances[i]]++;
        }

        int placed = 0;

        for (int distance = maxDistance; distance >= 0; distance--) {
            int amount = cursor[distance];
            cursor[distance] = placed;
            placed += amount;
        }

        Tile[] sorted = new Tile[count];
        int[] sortedDistances = new int[count];

        for (int i = 0; i < count; i++) {
            int at = cursor[distances[i]]++;
            sorted[at] = buffer[i];
            sortedDistances[at] = distances[i];
        }

        tiles = sorted;
        tileDistances = sortedDistances;
    }

    private void collectSlots(List<HexSlot> slots) {
        List<HexSlot> collapsing = new ArrayList<>();

        for (HexSlot slot : slots) {
            if (slot != centerSlot) {
                collapsing.add(slot);
            }
        }

        collapsing.sort((first, second) -> Long.compare(
                squaredDistance(second.x, second.y, centerSlot),
                squaredDistance(first.x, first.y, centerSlot)
        ));

        slotsByDistance = collapsing.toArray(new HexSlot[0]);
        slotDistances = new int[slotsByDistance.length];

        for (int i = 0; i < slotsByDistance.length; i++) {
            slotDistances[i] = (int) Math.round(Math.sqrt(
                    squaredDistance(slotsByDistance[i].x, slotsByDistance[i].y, centerSlot)
            ));
        }
    }

    private static HexSlot nearestSlot(List<HexSlot> slots, int tileX, int tileY) {
        HexSlot closest = null;
        long closestDistance = Long.MAX_VALUE;

        for (HexSlot slot : slots) {
            long distance = squaredDistance(tileX, tileY, slot);

            if (distance < closestDistance) {
                closestDistance = distance;
                closest = slot;
            }
        }

        return closest;
    }

    private static long squaredDistance(int tileX, int tileY, HexSlot slot) {
        long dx = tileX - slot.x;
        long dy = tileY - slot.y;

        return dx * dx + dy * dy;
    }
}
