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
 * Extinction: the late-game collapse that decides a round nobody has won on
 * the map.
 *
 * <p>It runs as a continuous wave that eats the map from the outside in, tile
 * by tile. The ring collapse it replaced (1.9.3) did the same total work in
 * five bursts, and those bursts disconnected players.
 *
 * <h2>The schedule</h2>
 * Driven by the round clock, exactly as the ring collapse was: warnings at 80,
 * 85 and 89 minutes, and at {@link #EXTINCTION_BEGINS_SECONDS} (90 minutes) the
 * wave arms itself over {@link #DURATION_SECONDS}, so the map is gone by minute
 * 100. Nothing else starts it.
 *
 * <h2>Why a wave</h2>
 * The ring collapse queued a whole ring at once and drained it at a fixed 128
 * tiles per <em>tick</em>. Each tile costs two reliable packets to every
 * connected client ({@code removeNet} + {@code setFloorNet}, ~18 bytes per
 * client), so at 60 TPS that is ~135 KB/s per client - more than four times
 * what a 32 KB per-client write buffer holds per second. A client whose buffer
 * overflows is dropped by {@code ArcNetProvider} on the spot, which is why a
 * ring collapse on a laggy server disconnected nearly everyone at once, and why
 * a player connecting during one never finished receiving the world.
 *
 * <p>The total work is the same either way - every hex tile has to become
 * space. The rings just did it in five bursts inside a ten-minute window they
 * otherwise spent idle (~6% duty cycle). Spreading the identical work evenly
 * across that window costs no extra wall-clock time and drops the peak rate by
 * a factor of ~17, to ~445 tiles/s (~7.8 KB/s per client).
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
 * excluded entirely: it is the prize, and whoever holds its core when the rest
 * of the map is gone has won.
 *
 * <h2>How the round ends</h2>
 * A hex is marked extinct just before the wave reaches its core, which is what
 * takes it out of the ownership counts - {@code TeamManager} skips extinct
 * slots. Each time that happens the same two checks the ring collapse ran are
 * run: teams that have lost every surviving core are eliminated, and a single
 * remaining owner wins on the spot. So the round is decided the moment the last
 * hex besides the center goes, whoever holds the center core then - including
 * Fallen, which resets the round normally.
 */
public final class WaveExtinction implements GameplayManagerInterface {

    /**
     * Round time, in seconds, at which the collapse starts. The warnings before
     * it are offsets from this moment.
     */
    public static final float EXTINCTION_BEGINS_SECONDS = 90 * 60;

    /**
     * How long the collapse takes. 90 to 100 minutes of round time - the same
     * window the five ring collapses used to be spread across.
     */
    private static final int DURATION_SECONDS = 600;

    /** Round seconds at which each warning goes out, and what it says. */
    private static final float[] WARNING_SECONDS = {80 * 60, 85 * 60, 89 * 60};

    private static final String[] WARNING_MESSAGES = {
            "Extinction begins in 10 minutes.",
            "Extinction begins in 5 minutes.",
            "Extinction begins in 1 minute."
    };

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

    /** Which warning is next; also how far through the schedule the round is. */
    private int nextWarning;

    /** Set once the schedule has armed the wave, so it is armed only once. */
    private boolean triggered;

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
     * Seconds left before the collapse starts, 0 once it has. Read off the
     * round clock rather than the wave's own state, so it stays right whether
     * the wave is armed, running or already finished.
     */
    public float secondsUntilExtinction() {
        return Math.max(0f, EXTINCTION_BEGINS_SECONDS - roundSeconds());
    }

    /** Whether the round has reached Extinction. */
    public boolean hasBegun() {
        return secondsUntilExtinction() <= 0f;
    }

    public boolean isRunning() {
        return running;
    }

    @Override
    public void beginRound() {
        stop();
        nextWarning = 0;
        triggered = false;
    }

    @Override
    public void endRound() {
        stop();
    }

    @Override
    public void update() {
        if (!teamManager.isRoundActiveForSystems()) {
            return;
        }

        if (running) {
            advance();
        } else if (!triggered) {
            followSchedule();
        }
    }

    /**
     * Warns the round, then arms the wave when the clock reaches Extinction.
     *
     * <p>One step per tick, like the state machine this replaced: a server that
     * comes up mid-round past several of these announces them in quick
     * succession rather than swallowing the ones it missed.
     */
    private void followSchedule() {
        float elapsed = roundSeconds();

        if (
                nextWarning < WARNING_SECONDS.length
                        && elapsed >= WARNING_SECONDS[nextWarning]
        ) {
            Call.sendMessage(WARNING_MESSAGES[nextWarning]);
            nextWarning++;
            return;
        }

        if (elapsed < EXTINCTION_BEGINS_SECONDS) {
            return;
        }

        // Set before arming: a map the wave cannot collapse must not be
        // retried every tick for the rest of the round.
        triggered = true;

        String failure = arm();

        if (failure != null) {
            PluginLog.err("Extinction could not start: @", failure);
            return;
        }

        Call.sendMessage("[scarlet]Extinction has begun.[]");
    }

    /**
     * Works out what the wave will eat and starts it.
     *
     * @return {@code null} once running, or the reason it could not start
     */
    private String arm() {
        List<HexSlot> slots = teamManager.slots();

        if (slots.isEmpty()) {
            return "no hex slots on this map - nothing to collapse";
        }

        centerSlot = findCenterSlot(slots);

        if (centerSlot == null) {
            return "no center hex found";
        }

        collectTiles(slots);

        if (tiles.length == 0) {
            return "every hex tile is already space";
        }

        collectSlots(slots);

        nextTile = 0;
        nextSlot = 0;
        startedAtMillis = Time.millis();
        lastUnitSweepMillis = startedAtMillis;
        running = true;

        PluginLog.info(
                "Extinction armed: @ tiles over @ s (@ tiles/s, ~@ B/s per client).",
                tiles.length,
                DURATION_SECONDS,
                tiles.length / DURATION_SECONDS,
                tiles.length / DURATION_SECONDS * 18
        );

        return null;
    }

    /** Stops the wave where it is; already-converted terrain stays space. */
    private void stop() {
        running = false;
        tiles = new Tile[0];
        tileDistances = new int[0];
        slotsByDistance = new HexSlot[0];
        slotDistances = new int[0];
        nextTile = 0;
        nextSlot = 0;
    }

    private void advance() {
        int target = Math.round(
                tiles.length * Math.min(1f, elapsedSeconds() / DURATION_SECONDS)
        );
        int budget = Math.min(target - nextTile, MAX_TILES_PER_TICK);

        if (budget > 0) {
            // The radius the front will have reached once this batch is done,
            // so a hex is declared extinct before its core is touched.
            int radiusAfter =
                    tileDistances[Math.min(nextTile + budget, tiles.length - 1)];

            if (markReachedSlotsExtinct(radiusAfter) > 0) {
                settleOwnership();

                // Settling can end the round outright - the last hex besides
                // the center leaves one owner standing. Nothing more to convert
                // then; the map is about to be replaced anyway.
                if (!teamManager.isRoundActiveForSystems()) {
                    stop();
                    return;
                }
            }

            convert(budget);
        }

        sweepUnits();

        if (nextTile >= tiles.length) {
            finish();
        }
    }

    /**
     * The two checks the ring collapse ran after every collapsed hex: teams
     * that have just lost their last surviving core are eliminated, and a
     * single remaining owner wins.
     *
     * <p>Marking the slot extinct is enough for both - {@code TeamManager}
     * skips extinct slots when it counts cores, so the core block standing
     * there for another second does not keep a dead team alive.
     */
    private void settleOwnership() {
        teamManager.eliminateCorelessTeamsThroughExtinction();
        teamManager.checkVictory();
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

    /** @return how many hexes this batch killed, so ownership is only re-checked when one did */
    private int markReachedSlotsExtinct(int radiusAfter) {
        int marked = 0;

        while (
                nextSlot < slotsByDistance.length
                        && radiusAfter <= slotDistances[nextSlot] + CORE_CLEARANCE
        ) {
            HexSlot slot = slotsByDistance[nextSlot++];

            slot.extinct = true;
            slot.capturing = false;
            slot.ownerTeamId = Team.derelict.id;
            slot.pendingCaptureTeamId = Team.derelict.id;
            marked++;
        }

        return marked;
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

    /**
     * The wave has eaten everything but the center hex.
     *
     * <p>Usually the round is already over by now: the hex before last going
     * extinct leaves a single owner, and {@link #settleOwnership()} ends it
     * there. This covers what is left - above all a center core held by Fallen,
     * or none at all, neither of which any team wins by holding.
     */
    private void finish() {
        running = false;

        Team holder = centerCoreTeam();

        if (holder == null) {
            PluginLog.info("Extinction finished with no surviving center core.");
        } else {
            PluginLog.info(
                    "Extinction finished: team #@ held the center core.",
                    holder.id
            );
        }

        // No core left means nobody held the middle, which is Fallen's win in
        // the same sense an empty map is: no personal team survived.
        teamManager.finishExtinction(
                holder == null ? TeamManager.FALLEN_TEAM : holder
        );
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

    /** Round time, which excludes whatever the round spent paused. */
    private float roundSeconds() {
        return teamManager.roundRuntimeMillis() / 1000f;
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
