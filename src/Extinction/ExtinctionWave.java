// Extinction: the late-game wave that eats the map from the outside in, tile by tile, until only the center hex is left.
package Extinction;

import Extinction.core.util.PluginLog;
import Extinction.gen.HexGrid;
import Extinction.round.HexSlot;
import Extinction.round.TeamManager;

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

import java.util.ArrayList;
import java.util.List;

// Why a wave, the rate and the ordering are explained in docs/GAMEPLAY.md, "Extinction".
public final class ExtinctionWave {

    // Round seconds at which the collapse starts; the warnings are offsets before it.
    public static final float EXTINCTION_BEGINS_SECONDS = 90 * 60;

    // How long the collapse takes: minute 90 to 100 of round time, the old five-ring window.
    private static final int DURATION_SECONDS = 600;

    // Round seconds at which each warning goes out, and what it says.
    private static final float[] WARNING_SECONDS = {80 * 60, 85 * 60, 89 * 60};
    private static final String[] WARNING_MESSAGES = {
            "Extinction begins in 10 minutes.",
            "Extinction begins in 5 minutes.",
            "Extinction begins in 1 minute."
    };

    // Ceiling on one tick's batch: ~8 KB of packets per client, a quarter of the 32 KB write buffer.
    private static final int MAX_TILES_PER_TICK = 455;

    // Tiles of slack before the front reaches a hex center at which the hex is declared extinct
    // (a core is up to 5 tiles wide), so CoreCapture never treats the loss as a capture.
    private static final int CORE_CLEARANCE = 5;

    private static final long UNIT_SWEEP_INTERVAL_MILLIS = 1000L;

    private static final int HEX_RADIUS_SQUARED = HexGrid.HEX_RADIUS * HexGrid.HEX_RADIUS;
    private static final int CENTER_COL = HexGrid.SHORT_ROW_COLS / 2;
    private static final int CENTER_ROW = HexGrid.ROWS / 2;

    private final TeamManager teamManager;

    private boolean running;
    private long startedAtMillis;
    private long lastUnitSweepMillis;

    // Which warning is next; also how far through the schedule the round is.
    private int nextWarning;

    // Set once the schedule has armed the wave, so it is armed only once.
    private boolean triggered;

    // Every tile the wave will convert, farthest from the center hex first, with its distance.
    private Tile[] tiles = new Tile[0];
    private int[] tileDistances = new int[0];
    private int nextTile;

    private HexSlot centerSlot;

    // Every collapsing slot, farthest first, so ownership dies in wave order.
    private HexSlot[] slotsByDistance = new HexSlot[0];
    private int[] slotDistances = new int[0];
    private int nextSlot;

    public ExtinctionWave(TeamManager teamManager) {
        this.teamManager = teamManager;
    }

    // Seconds left before the collapse starts, 0 once it has. Read off the round clock, so it
    // stays right whether the wave is armed, running or already finished.
    public float secondsUntilExtinction() {
        return Math.max(0f, EXTINCTION_BEGINS_SECONDS - roundSeconds());
    }

    public boolean hasBegun() {
        return secondsUntilExtinction() <= 0f;
    }

    public void beginRound() {
        stop();
        nextWarning = 0;
        triggered = false;
    }

    public void update() {
        if (!teamManager.isRoundActiveForSystems()) return;

        if (running) {
            advance();
        } else if (!triggered) {
            followSchedule();
        }
    }

    // Warns the round, then arms the wave when the clock reaches Extinction. One step per tick,
    // so a server that comes up mid-round announces the warnings it missed in quick succession.
    private void followSchedule() {
        float elapsed = roundSeconds();

        if (nextWarning < WARNING_SECONDS.length && elapsed >= WARNING_SECONDS[nextWarning]) {
            Call.sendMessage(WARNING_MESSAGES[nextWarning]);
            nextWarning++;
            return;
        }

        if (elapsed < EXTINCTION_BEGINS_SECONDS) return;

        // Set before arming: a map the wave cannot collapse must not be retried every tick.
        triggered = true;

        String failure = arm();
        if (failure != null) {
            PluginLog.err("Extinction could not start: @", failure);
            return;
        }

        Call.sendMessage("[scarlet]Extinction has begun.[]");
    }

    // Works out what the wave will eat and starts it; null once running, else why it could not.
    private String arm() {
        List<HexSlot> slots = teamManager.slots();
        if (slots.isEmpty()) return "no hex slots on this map - nothing to collapse";

        centerSlot = findCenterSlot(slots);
        if (centerSlot == null) return "no center hex found";

        collectTiles(slots);
        if (tiles.length == 0) return "every hex tile is already space";

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

    // Stops the wave where it is; already-converted terrain stays space.
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
        int target = Math.round(tiles.length * Math.min(1f, elapsedSeconds() / DURATION_SECONDS));
        int budget = Math.min(target - nextTile, MAX_TILES_PER_TICK);

        if (budget > 0) {
            // The radius the front will have reached once this batch is done, so a hex is
            // declared extinct before its core is touched.
            int radiusAfter = tileDistances[Math.min(nextTile + budget, tiles.length - 1)];

            if (markReachedSlotsExtinct(radiusAfter) > 0) {
                settleOwnership();

                // Settling can end the round outright (one owner left); nothing more to convert then.
                if (!teamManager.isRoundActiveForSystems()) {
                    stop();
                    return;
                }
            }

            convert(budget);
        }

        sweepUnits();

        if (nextTile >= tiles.length) finish();
    }

    // The two checks the ring collapse ran after every collapsed hex: coreless teams are
    // eliminated, and a single remaining owner wins. TeamManager skips extinct slots when counting.
    private void settleOwnership() {
        teamManager.eliminateCorelessTeamsThroughExtinction();
        teamManager.checkVictory();
    }

    // Converts one batch. Captures stay suppressed: a core dying to the wave is an extinction,
    // not a capture, and CoreCapture must not schedule a replacement shard on ground about to be space.
    private void convert(int budget) {
        boolean wasSuppressed = teamManager.isCaptureSuppressed();
        teamManager.setCaptureSuppressed(true);

        try {
            for (int i = 0; i < budget && nextTile < tiles.length; i++) {
                Tile tile = tiles[nextTile++];

                // Skipped for the ~90% of tiles that carry nothing, halving the packets.
                if (tile.block() != Blocks.air) tile.removeNet();

                tile.setFloorNet(Blocks.space);
            }
        } finally {
            teamManager.setCaptureSuppressed(wasSuppressed);
        }
    }

    // Returns how many hexes this batch killed, so ownership is only re-checked when one did.
    private int markReachedSlotsExtinct(int radiusAfter) {
        int marked = 0;

        while (nextSlot < slotsByDistance.length && radiusAfter <= slotDistances[nextSlot] + CORE_CLEARANCE) {
            HexSlot slot = slotsByDistance[nextSlot++];

            slot.extinct = true;
            slot.capturing = false;
            slot.ownerTeamId = Team.derelict.id;
            slot.pendingCaptureTeamId = Team.derelict.id;
            marked++;
        }

        return marked;
    }

    // Kills whatever is standing in the void, once a second rather than per tick.
    private void sweepUnits() {
        long now = Time.millis();
        if (now - lastUnitSweepMillis < UNIT_SWEEP_INTERVAL_MILLIS) return;
        lastUnitSweepMillis = now;

        long radius = currentRadius();
        long radiusSquared = radius * radius;
        List<Unit> doomed = new ArrayList<>();

        Groups.unit.each(unit -> {
            if (!unit.isAdded()) return;

            long dx = unit.tileX() - centerSlot.x;
            long dy = unit.tileY() - centerSlot.y;

            if (dx * dx + dy * dy > radiusSquared) doomed.add(unit);
        });

        for (Unit unit : doomed) {
            unit.kill();
        }
    }

    // The wave has eaten everything but the center hex. Usually settleOwnership() already ended the
    // round; this covers the rest, above all a center core held by Fallen, or none at all.
    private void finish() {
        running = false;

        Team holder = centerCoreTeam();

        if (holder == null) {
            PluginLog.info("Extinction finished with no surviving center core.");
        } else {
            PluginLog.info("Extinction finished: team #@ held the center core.", holder.id);
        }

        // No core left means nobody held the middle, which is Fallen's win: no personal team survived.
        teamManager.finishExtinction(holder == null ? TeamManager.FALLEN_TEAM : holder);
    }

    private Team centerCoreTeam() {
        Tile tile = Vars.world.tile(centerSlot.x, centerSlot.y);
        if (tile == null) return null;

        Building build = tile.build;

        return build == null || !(build.block instanceof CoreBlock) ? null : build.team;
    }

    private float elapsedSeconds() {
        return (Time.millis() - startedAtMillis) / 1000f;
    }

    // Round time, which excludes whatever the round spent paused.
    private float roundSeconds() {
        return teamManager.roundRuntimeMillis() / 1000f;
    }

    // How far out the front still is. Once the queue is empty the wave rests on the center hex's edge.
    private int currentRadius() {
        return nextTile < tiles.length ? tileDistances[nextTile] : HexGrid.HEX_RADIUS;
    }

    // The middle hex by grid position; it is protected from filling, so it is always a real slot.
    // The nearest-to-the-average fallback only covers a hand-edited map.
    private HexSlot findCenterSlot(List<HexSlot> slots) {
        for (HexSlot slot : slots) {
            if (slot.col == CENTER_COL && slot.row == CENTER_ROW) return slot;
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

    // Collects every tile the wave will eat (nearest slot owns it, within the hex radius, center hex
    // excluded) and orders it farthest-first with a counting sort over the integer distance.
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

            int distance = (int) Math.round(Math.sqrt(squaredDistance(tile.x, tile.y, centerSlot)));

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
            if (slot != centerSlot) collapsing.add(slot);
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
