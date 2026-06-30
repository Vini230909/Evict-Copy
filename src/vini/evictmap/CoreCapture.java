package vini.evictmap;

import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.Team;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;
import vini.evictmap.TeamManager.HexSlot;
import vini.evictmap.gameplay.AttritionManager;

import java.util.ArrayList;
import java.util.List;

/**
 * EVERYTHING about taking over a core lives here: the capture pipeline AND the
 * actual core placement.
 * Why it is more than one method: a capture happens over real time. The old
 * core dies, the hex sits empty for 5 seconds, then the attacker's Core Shard
 * appears. Time.run(delay, ...) runs code LATER on the main thread; we must
 * never block the thread to "wait". So the flow is cut into steps ONLY at the
 * points where it genuinely has to wait:
 * handleCoreChange     now      a core changed - is it really a death, and
 * who captured it?
 * startCapture         now      mark the hex captured, schedule the wipe
 * captureClearHex      +1 tick  wipe the hex, attrition, hand over ownership,
 * decide elimination + victory
 * replaceCapturedCore  +5 s     wipe again (anti-abuse), place the Core Shard;
 * retries every 60 ticks if the center is blocked
 * Every step does its own visible work and then schedules the next one at the
 * very end - no method just calls another. captureStepValid() is the single
 * shared "is this still the live capture?" guard.
 * This class only asks TeamManager for shared round/team state (slots, victory,
 * elimination) through a handful of clearly named calls. TeamManager calls back
 * for exactly two things it reuses elsewhere: placeCore() (surrender restores
 * Fallen cores) and clearBuildingsInHex() (a new start hex is wiped first).
 */
final class CoreCapture {

    /**
     * Captures intentionally do not complete instantly. The empty center is
     * visible for a few seconds before the attacker's small Core Shard appears.
     */
    private static final float CAPTURE_DELAY_TICKS = 5f * 60f;

    /**
     * Capture cleanup follows the real Evict core range exactly: every synthetic
     * building whose center is within 40 tiles of the destroyed core is removed,
     * including the overlap with a neighboring hex. This is core range, not
     * build range.
     */
    private static final int CAPTURE_CLEAR_RADIUS = 40;
    private static final int CAPTURE_CLEAR_RADIUS_SQUARED =
            CAPTURE_CLEAR_RADIUS * CAPTURE_CLEAR_RADIUS;

    /**
     * Replacement-core placement can transiently fail (a unit/building reappears
     * on the center tile, a floor change still settling, etc.). Retry instead of
     * abandoning the hex: an abandoned hex stays coreless, so the attacker's
     * units standing there immediately start taking range attrition. Retries
     * keep the hex logically the attacker's and attrition-protected until a real
     * core exists.
     */
    private static final int MAX_REPLACEMENT_RETRIES = 10;
    private static final float REPLACEMENT_RETRY_DELAY_TICKS = 60f;

    private final TeamManager team;

    CoreCapture(TeamManager team) {
        this.team = team;
    }

    /**
     * Everything one capture needs, carried as a single value through the
     * delayed steps instead of repeating five parameters on every method.
     * hex = the hex being captured, defender = who owned the core,
     * attacker = who destroyed it, serial = which round this belongs to.
     */
    private record Capture(HexSlot hex, Team defender, Team attacker, long serial, AttritionManager attrition) {
    }

    void handleCoreChange(CoreBuild core, AttritionManager attrition) {
        if (
                !team.isRoundActiveForSystems()
                        || team.isCaptureSuppressed()
                        || core == null
                        || core.tile == null
        ) {
            return;
        }

        // Find the hex by the core's footprint, not its origin tile: the 4x4
        // Foundation anchors one tile off-center, so a plain tile match would
        // miss an upgraded core. A core covering no hex center is ignored.
        HexSlot hex = team.slotForCore(core);
        if (hex == null) {
            return;
        }

        Team defender = core.team;
        Team attacker = validCaptureAttacker(core.lastDamage, defender);
        long serial = team.roundSerial();

        if (core.health <= 0f) {
            // The core is already gone: start the capture now.
            startCapture(new Capture(hex, defender, attacker, serial, attrition));
            return;
        }

        // The event can fire one tick before the core is actually removed. Wait
        // a tick, re-read the center tile, and only then decide whether it was
        // really a death (and who the final attacker was).
        Time.run(0f, () -> {
            if (!team.isRoundActiveForSystems() || serial != team.roundSerial()) {
                return;
            }

            Tile centre = Vars.world.tile(hex.x, hex.y);

            if (centre != null && centre.build instanceof CoreBuild live) {
                if (live.health > 0f) {
                    return; // the core survived: this was not a capture
                }
                startCapture(new Capture(
                        hex, live.team, validCaptureAttacker(live.lastDamage, live.team), serial, attrition));
            } else {
                startCapture(new Capture(hex, defender, attacker, serial, attrition));
            }
        });
    }

    // step 1 (now): mark the hex as being captured, then schedule the cleanup.
    private void startCapture(Capture c) {
        if (c.hex.extinct || c.hex.capturing) {
            return;
        }

        c.hex.capturing = true;
        c.hex.pendingCaptureTeamId = c.attacker.id;

        if (c.attacker != c.defender) {
            team.updateMaximumOwnedHexes(c.attacker.id);
        }

        Log.info(
                "[EvictMapGenerator] Core destroyed at hex (@,@). defender=#@ attacker=#@. Clearing buildings and placing a Core Shard in 5 seconds.",
                c.hex.col, c.hex.row, c.defender.id, c.attacker.id
        );

        // Wait one tick so the vanilla removal of the old core finishes first.
        Time.run(0f, () -> captureClearHex(c));
    }

    // step 2 (+1 tick): wipe the hex, apply attrition, hand over ownership, judge the round.
    private void captureClearHex(Capture c) {
        if (!captureStepValid(c)) {
            return;
        }

        if (c.attacker != c.defender) {
            team.recordCoreDestruction(c.defender, c.attacker);
        }

        int removed = clearBuildingsInHex(c.hex);
        c.attrition.handleCoreExplosionAttrition(c.hex.x, c.hex.y);

        Log.info(
                "[EvictMapGenerator] Cleared @ synthetic buildings from hex (@,@).",
                removed, c.hex.col, c.hex.row
        );

        // Ownership is already the attacker's now, so elimination and victory
        // are decided HERE and must not wait the 5 seconds for the shard.
        if (c.attacker != c.defender) {
            team.announceEliminationIfNeeded(c.defender, c.attacker);
        }

        team.checkVictory();

        if (!team.isRoundActiveForSystems()) {
            Log.info("[EvictMapGenerator] Final capture resolved the round before replacement Core Shard placement.");
            return;
        }

        Log.info(
                "[EvictMapGenerator] Waiting 5 seconds for team #@ Core Shard at captured hex (@,@).",
                c.attacker.id, c.hex.col, c.hex.row
        );

        Time.run(CAPTURE_DELAY_TICKS, () -> replaceCapturedCore(c, 0));
    }

    // step 3 (+5 s): wipe again (anti-abuse), place the shard, retry if blocked.
    // Reschedules itself every 60 ticks while the center tile stays blocked,
    // keeping the hex the attacker's and attrition-protected, until it works or gives up.
    private void replaceCapturedCore(Capture c, int attempt) {
        if (!captureStepValid(c)) {
            return;
        }

        if (attempt == 0) {
            if (Vars.world.tile(c.hex.x, c.hex.y) == null) {
                Log.err("[EvictMapGenerator] Cannot finish capture: missing center tile for hex (@,@).", c.hex.col, c.hex.row);
                c.hex.capturing = false;
                return;
            }

            // Second wipe: remove anything built during the 5-second empty-core
            // window so the delay cannot be abused.
            int wiped = clearBuildingsInHex(c.hex);
            Log.info(
                    "[EvictMapGenerator] Removed @ synthetic buildings built or remaining during the 5-second capture window at hex (@,@).",
                    wiped, c.hex.col, c.hex.row
            );
        }

        if (placeCore(c.hex, Blocks.coreShard, c.attacker, "capture")) {
            // Do not clear the shard's items: a team's cores share one inventory,
            // so clearing it would wipe the attacker's resources from every core.
            c.hex.ownerTeamId = c.attacker.id;
            c.hex.pendingCaptureTeamId = c.attacker.id;
            c.hex.capturing = false;

            Log.info(
                    "[EvictMapGenerator] Capture complete at hex (@,@): team #@ -> team #@ with a Core Shard and no bonus items.@",
                    c.hex.col, c.hex.row, c.defender.id, c.attacker.id,
                    attempt > 0 ? " (after " + attempt + " retr(y/ies))" : ""
            );
            return;
        }

        // Center tile still blocked: keep the hex the attacker's and retry.
        if (attempt < MAX_REPLACEMENT_RETRIES) {
            Log.warn(
                    "[EvictMapGenerator] Capture at hex (@,@): replacement Core Shard for team #@ not verified on attempt @/@; retrying in @ ticks.",
                    c.hex.col, c.hex.row, c.attacker.id, attempt + 1, MAX_REPLACEMENT_RETRIES, (int) REPLACEMENT_RETRY_DELAY_TICKS
            );
            Time.run(REPLACEMENT_RETRY_DELAY_TICKS, () -> replaceCapturedCore(c, attempt + 1));
            return;
        }

        // Gave up after all retries: leave the hex unowned so it can't block victory.
        c.hex.ownerTeamId = Team.derelict.id;
        c.hex.pendingCaptureTeamId = Team.derelict.id;
        c.hex.capturing = false;

        Log.err(
                "[EvictMapGenerator] Capture at hex (@,@) could not place a verified Core Shard for team #@ after @ attempts. The hex is now unowned so it cannot block victory as a phantom core. See the placement diagnostics above for what blocked the center tile.",
                c.hex.col, c.hex.row, c.attacker.id, MAX_REPLACEMENT_RETRIES + 1
        );
    }

    // The shared guard for every delayed step: the round is still live, this
    // callback belongs to the round it was scheduled in (not a regenerated round
    // reusing the same hex), and the hex is still mid-capture.
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean captureStepValid(Capture c) {
        return team.isRoundActiveForSystems()
                && c.serial == team.roundSerial()
                && c.hex.capturing;
    }

    private Team validCaptureAttacker(Team lastDamage, Team defender) {
        if (
                lastDamage == null
                        || lastDamage == Team.derelict
                        || lastDamage == defender
        ) {
            return defender;
        }

        return lastDamage;
    }

    // ----- hex building cleanup (shared with start-hex setup) -----

    /**
     * Removes every player-built structure inside a hex. Used by the capture
     * cleanup passes and by TeamManager when a new personal team is dropped onto
     * a Fallen hex, so anything raised there is wiped before the start schematic
     * and its core land.
     */
    int clearBuildingsInHex(HexSlot hex) {
        List<Tile> centersToRemove = new ArrayList<>();

        for (Tile tile : Vars.world.tiles) {
            if (
                    tile != null
                            && tile.build != null
                            && tile.isCenter()
                            && tile.synthetic()
                            && belongsToCaptureHex(tile.x, tile.y, hex)
            ) {
                centersToRemove.add(tile);
            }
        }

        for (Tile tile : centersToRemove) {
            if (
                    tile.build != null
                            && tile.isCenter()
                            && tile.synthetic()
            ) {
                tile.removeNet();
            }
        }

        return centersToRemove.size();
    }

    private boolean belongsToCaptureHex(int tileX, int tileY, HexSlot candidate) {
        long dx = tileX - candidate.x;
        long dy = tileY - candidate.y;
        return dx * dx + dy * dy <= CAPTURE_CLEAR_RADIUS_SQUARED;
    }

    // ----- core placement (shared with surrender's Fallen-core restore) -----

    /**
     * Places a core block centred on a hex and verifies it registered. Tries
     * twice (a networked setNet does not always "stick" on the first attempt)
     * and suppresses capture events while doing so (this placement is not itself
     * a capture). Returns false if the core could not be verified.
     * Assumes the center tile is already clear - the callers wipe the hex first
     * (capture: clearBuildingsInHex; surrender: clearSurrenderedTeamAssets), and
     * the engine removes any unit standing under a freshly placed core.
     */
    boolean placeCore(HexSlot slot, Block coreBlock, Team coreTeam, String reason) {
        if (
                slot == null
                        || coreBlock == null
                        || coreTeam == null
                        || coreTeam == Team.derelict
        ) {
            return false;
        }

        Tile centerTile = Vars.world.tile(slot.x, slot.y);

        if (centerTile == null) {
            Log.err(
                    "[EvictMapGenerator] Cannot place @ core: missing center tile for hex (@,@).",
                    reason, slot.col, slot.row
            );
            return false;
        }

        boolean previousSuppression = team.isCaptureSuppressed();
        team.setCaptureSuppressed(true);

        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                CoreMarkerFloor.place(centerTile.x, centerTile.y);
                centerTile.setNet(coreBlock, coreTeam, 0);

                if (hasExpectedCore(slot, coreBlock, coreTeam)) {
                    return true;
                }
            }
        } finally {
            team.setCaptureSuppressed(previousSuppression);
        }

        Log.err(
                "[EvictMapGenerator] Failed to verify @ core at hex (@,@), tile (@,@), expected @ for team #@. The hex will not count as owned. Center tile now holds block=@ floor=@ build=@ buildTeam=@.",
                reason,
                slot.col,
                slot.row,
                slot.x,
                slot.y,
                coreBlock.name,
                coreTeam.id,
                centerTile.block() == null ? "null" : centerTile.block().name,
                centerTile.floor() == null ? "null" : centerTile.floor().name,
                centerTile.build == null || centerTile.build.block == null
                        ? "none"
                        : centerTile.build.block.name,
                centerTile.build == null || centerTile.build.team == null
                        ? "none"
                        : "#" + centerTile.build.team.id
        );

        return false;
    }

    private boolean hasExpectedCore(HexSlot slot, Block coreBlock, Team coreTeam) {
        Tile centerTile = Vars.world.tile(slot.x, slot.y);

        return centerTile != null
                && centerTile.block() == coreBlock
                && centerTile.build instanceof CoreBuild core
                && core.team == coreTeam;
    }
}
