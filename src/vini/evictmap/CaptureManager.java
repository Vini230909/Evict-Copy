package vini.evictmap;

import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.Team;
import mindustry.world.Tile;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns the complete core-capture lifecycle:
 *
 * old core destruction -> immediate cleanup -> five-second delay ->
 * second anti-abuse cleanup -> replacement Core Shard.
 */
final class CaptureManager {

    private static final float CAPTURE_DELAY_TICKS = 5f * 60f;

    /**
     * Replacement-core placement can transiently fail (a unit or building that
     * reappears on the center tile, a floor change still settling, etc.). When
     * it does, retry instead of abandoning the hex: an abandoned hex stays
     * coreless, so the attacker's units standing there immediately start taking
     * range attrition. Retries keep {@code slot.capturing} set, which keeps the
     * hex logically the attacker's and attrition-protected until a real core
     * exists.
     */
    private static final int MAX_REPLACEMENT_RETRIES = 10;
    private static final float REPLACEMENT_RETRY_DELAY_TICKS = 60f;

    private static final int CAPTURE_CLEAR_RADIUS = 40;
    private static final int CAPTURE_CLEAR_RADIUS_SQUARED =
        CAPTURE_CLEAR_RADIUS * CAPTURE_CLEAR_RADIUS;

    private final TeamManager teamManager;

    CaptureManager(TeamManager teamManager) {
        this.teamManager = teamManager;
    }

    void handleCoreChange(
        CoreBuild core,
        AttritionManager attritionManager
    ) {
        if (
            !teamManager.isRoundActiveForSystems()
                || teamManager.isCaptureSuppressed()
                || core == null
                || core.tile == null
        ) {
            return;
        }

        /**
         * Resolve the hex by core footprint, then drive the rest of the capture
         * pipeline from the hex centre tile. An even-sized Foundation (4x4)
         * anchors its {@code tile} one off-centre, so using the raw core tile
         * here would miss the slot and the destroyed core would never be
         * captured or replaced. A core not covering any hex centre (a secondary
         * off-centre core) is intentionally ignored.
         */
        TeamManager.HexSlot slot = teamManager.slotForCore(core);

        if (slot == null) {
            return;
        }

        int coreTileX = slot.x;
        int coreTileY = slot.y;
        Team defenderTeam = core.team;
        Team attackerTeam = validCaptureAttacker(core.lastDamage, defenderTeam);

        if (core.health > 0f) {
            retryCoreChangeAfterVanillaUpdate(
                coreTileX,
                coreTileY,
                defenderTeam,
                attackerTeam,
                teamManager.roundSerial(),
                attritionManager
            );
            return;
        }

        beginCaptureForDestroyedCore(
            coreTileX,
            coreTileY,
            defenderTeam,
            attackerTeam,
            attritionManager
        );
    }

    private void retryCoreChangeAfterVanillaUpdate(
        int coreTileX,
        int coreTileY,
        Team originalDefenderTeam,
        Team originalAttackerTeam,
        long scheduledRoundSerial,
        AttritionManager attritionManager
    ) {
        Time.run(
            0f,
            () -> retryCoreChangeAfterVanillaUpdate(
                coreTileX,
                coreTileY,
                originalDefenderTeam,
                originalAttackerTeam,
                scheduledRoundSerial,
                attritionManager,
                Vars.world.tile(coreTileX, coreTileY)
            )
        );
    }

    private void retryCoreChangeAfterVanillaUpdate(
        int coreTileX,
        int coreTileY,
        Team originalDefenderTeam,
        Team originalAttackerTeam,
        long scheduledRoundSerial,
        AttritionManager attritionManager,
        Tile centerTile
    ) {
        if (
            !teamManager.isRoundActiveForSystems()
                || scheduledRoundSerial != teamManager.roundSerial()
        ) {
            return;
        }

        if (centerTile != null && centerTile.build instanceof CoreBuild core) {
            if (core.health > 0f) {
                return;
            }

            beginCaptureForDestroyedCore(
                coreTileX,
                coreTileY,
                core.team,
                validCaptureAttacker(core.lastDamage, core.team),
                attritionManager
            );
            return;
        }

        beginCaptureForDestroyedCore(
            coreTileX,
            coreTileY,
            originalDefenderTeam,
            originalAttackerTeam,
            attritionManager
        );
    }

    private void beginCaptureForDestroyedCore(
        int coreTileX,
        int coreTileY,
        Team defenderTeam,
        Team attackerTeam,
        AttritionManager attritionManager
    ) {
        TeamManager.HexSlot slot =
            teamManager.slotAtCoreTile(coreTileX, coreTileY);

        if (slot == null || slot.extinct || slot.capturing) {
            return;
        }

        slot.capturing = true;
        slot.pendingCaptureTeamId = attackerTeam.id;

        if (attackerTeam != defenderTeam) {
            teamManager.updateMaximumOwnedHexes(attackerTeam.id);
        }

        long scheduledRoundSerial = teamManager.roundSerial();

        Log.info(
            "[EvictMapGenerator] Core destroyed at hex (@,@). defender=#@ attacker=#@. Clearing buildings and placing a Core Shard in 5 seconds.",
            slot.col,
            slot.row,
            defenderTeam.id,
            attackerTeam.id
        );

        /**
         * CoreChangeEvent fires from the core destruction lifecycle itself.
         * Delay the wipe until the next update so the vanilla removal can
         * finish before additional networked tile removals are sent.
         */
        Time.run(
            0f,
            () -> beginDelayedCapture(
                slot,
                defenderTeam,
                attackerTeam,
                scheduledRoundSerial,
                attritionManager
            )
        );
    }

    private void beginDelayedCapture(
        TeamManager.HexSlot slot,
        Team defenderTeam,
        Team attackerTeam,
        long scheduledRoundSerial,
        AttritionManager attritionManager
    ) {
        if (
            !teamManager.isRoundActiveForSystems()
                || scheduledRoundSerial != teamManager.roundSerial()
                || !slot.capturing
        ) {
            return;
        }

        if (attackerTeam != defenderTeam) {
            teamManager.recordCoreDestruction(defenderTeam, attackerTeam);
        }

        int removedBuildings = clearSyntheticBuildingsInsideHex(slot);
        int attritionDeaths =
            attritionManager.applyCaptureAttrition(slot.x, slot.y);

        Log.info(
            "[EvictMapGenerator] Cleared @ synthetic buildings and removed @ units through capture attrition from hex (@,@).",
            removedBuildings,
            attritionDeaths,
            slot.col,
            slot.row
        );

        /**
         * Ownership changes logically as soon as the old core is destroyed.
         * The replacement Core Shard remains delayed for visual/gameplay
         * parity, but elimination and victory messages must not wait five
         * seconds for that replacement block.
         */
        if (attackerTeam != defenderTeam) {
            teamManager.announceEliminationIfNeeded(defenderTeam, attackerTeam);
        }

        teamManager.checkVictory();

        if (!teamManager.isRoundActiveForSystems()) {
            Log.info(
                "[EvictMapGenerator] Final capture resolved the round before replacement Core Shard placement."
            );
            return;
        }

        Log.info(
            "[EvictMapGenerator] Waiting 5 seconds for team #@ Core Shard at captured hex (@,@).",
            attackerTeam.id,
            slot.col,
            slot.row
        );

        Time.run(
            CAPTURE_DELAY_TICKS,
            () -> finishDelayedCapture(
                slot,
                defenderTeam,
                attackerTeam,
                scheduledRoundSerial
            )
        );
    }

    private void finishDelayedCapture(
        TeamManager.HexSlot slot,
        Team defenderTeam,
        Team attackerTeam,
        long scheduledRoundSerial
    ) {
        if (
            !teamManager.isRoundActiveForSystems()
                || scheduledRoundSerial != teamManager.roundSerial()
                || !slot.capturing
        ) {
            return;
        }

        Tile centerTile = Vars.world.tile(slot.x, slot.y);

        if (centerTile == null) {
            Log.err(
                "[EvictMapGenerator] Cannot finish capture: missing center tile for hex (@,@).",
                slot.col,
                slot.row
            );
            slot.capturing = false;
            return;
        }

        /**
         * Wipe the complete captured hex a second time immediately before the
         * replacement core appears.
         *
         * This intentionally removes anything built during the five-second
         * empty-core window. Without the second wipe, players could abuse that
         * delay to preserve or quickly establish buildings in the captured
         * hex before the replacement Core Shard appears.
         */
        int delayedWindowRemovedBuildings =
            clearSyntheticBuildingsInsideHex(slot);

        Log.info(
            "[EvictMapGenerator] Removed @ synthetic buildings built or remaining during the 5-second capture window at hex (@,@).",
            delayedWindowRemovedBuildings,
            slot.col,
            slot.row
        );

        placeReplacementCoreWithRetry(
            slot,
            defenderTeam,
            attackerTeam,
            scheduledRoundSerial,
            0
        );
    }

    private void placeReplacementCoreWithRetry(
        TeamManager.HexSlot slot,
        Team defenderTeam,
        Team attackerTeam,
        long scheduledRoundSerial,
        int attempt
    ) {
        if (
            !teamManager.isRoundActiveForSystems()
                || scheduledRoundSerial != teamManager.roundSerial()
                || !slot.capturing
        ) {
            return;
        }

        boolean corePlaced =
            teamManager.placeCoreAndVerify(
                slot,
                Blocks.coreShard,
                attackerTeam,
                "capture"
            );

        if (corePlaced) {
            /**
             * Do not clear capturedCore.items here.
             *
             * Mindustry intentionally shares one ItemModule between every core
             * of the same team. Clearing the new Core Shard would therefore
             * erase the attacker's resources from all existing cores as well.
             *
             * A captured shard adds no bonus resources; it simply joins the
             * attacker's already shared core inventory.
             */
            slot.ownerTeamId = attackerTeam.id;
            slot.pendingCaptureTeamId = attackerTeam.id;
            slot.capturing = false;

            Log.info(
                "[EvictMapGenerator] Capture complete at hex (@,@): team #@ -> team #@ with a Core Shard and no bonus items.@",
                slot.col,
                slot.row,
                defenderTeam.id,
                attackerTeam.id,
                attempt > 0 ? " (after " + attempt + " retr(y/ies))" : ""
            );
            return;
        }

        /**
         * Keep slot.capturing set so the hex stays logically the attacker's and
         * its units remain attrition-protected while we retry. Abandoning here
         * would leave the hex coreless, and any attacker units standing in it
         * would start taking range attrition every five seconds.
         */
        if (attempt < MAX_REPLACEMENT_RETRIES) {
            Log.warn(
                "[EvictMapGenerator] Capture at hex (@,@): replacement Core Shard for team #@ not verified on attempt @/@; retrying in @ ticks.",
                slot.col,
                slot.row,
                attackerTeam.id,
                attempt + 1,
                MAX_REPLACEMENT_RETRIES,
                (int) REPLACEMENT_RETRY_DELAY_TICKS
            );

            Time.run(
                REPLACEMENT_RETRY_DELAY_TICKS,
                () -> placeReplacementCoreWithRetry(
                    slot,
                    defenderTeam,
                    attackerTeam,
                    scheduledRoundSerial,
                    attempt + 1
                )
            );
            return;
        }

        slot.ownerTeamId = Team.derelict.id;
        slot.pendingCaptureTeamId = Team.derelict.id;
        slot.capturing = false;

        Log.err(
            "[EvictMapGenerator] Capture at hex (@,@) could not place a verified Core Shard for team #@ after @ attempts. The hex is now unowned so it cannot block victory as a phantom core. See the placement diagnostics above for what blocked the center tile.",
            slot.col,
            slot.row,
            attackerTeam.id,
            MAX_REPLACEMENT_RETRIES + 1
        );
    }


    private Team validCaptureAttacker(
        Team lastDamage,
        Team defenderTeam
    ) {
        if (
            lastDamage == null
                || lastDamage == Team.derelict
                || lastDamage == defenderTeam
        ) {
            return defenderTeam;
        }

        return lastDamage;
    }

    /**
     * Removes every player-built structure inside a hex. Reused when a new
     * personal team is dropped onto a Fallen hex so that anything the Fallen
     * team (or a previous owner) raised there is wiped before the start
     * schematic and its core land.
     */
    int clearBuildingsInsideHex(TeamManager.HexSlot slot) {
        return clearSyntheticBuildingsInsideHex(slot);
    }

    private int clearSyntheticBuildingsInsideHex(
        TeamManager.HexSlot capturedSlot
    ) {
        List<Tile> centersToRemove = new ArrayList<>();

        for (Tile tile : Vars.world.tiles) {
            if (
                tile != null
                    && tile.build != null
                    && tile.isCenter()
                    && tile.synthetic()
                    && belongsToHex(tile.x, tile.y, capturedSlot)
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

    private boolean belongsToHex(
        int tileX,
        int tileY,
        TeamManager.HexSlot candidate
    ) {
        return squaredDistance(tileX, tileY, candidate)
            <= CAPTURE_CLEAR_RADIUS_SQUARED;
    }

    private long squaredDistance(
        int tileX,
        int tileY,
        TeamManager.HexSlot slot
    ) {
        long dx = tileX - slot.x;
        long dy = tileY - slot.y;

        return dx * dx + dy * dy;
    }
}
