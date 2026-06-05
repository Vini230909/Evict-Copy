package vini.evictmap;

import arc.func.Cons;
import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.world.Tile;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Phase 1 of the Evict round system.
 *
 * Implemented:
 * - every generated neutral core belongs to Fallen team #18
 * - a first-time player receives a random unique team ID from #1..#128,
 *   excluding #18
 * - a safe unclaimed start hex is selected with two complete hexes between
 *   player starts
 * - edge / filled-wall protection is preferred
 * - reconnecting during the same round returns to the same team
 * - if no safe start hex exists, the player remains playable in Fallen team
 *
 * Implemented in the current phase:
 * - exact one-time starting resources on personal-core claim
 * - the Evict start schematic, anchored to the centered Nucleus
 *
 * Implemented in the current phase:
 * - destroyed registered cores leave an empty hex center for five seconds
 * - every synthetic building in the captured hex is removed
 * - the attacker receives a centered 3x3 Core Shard without bonus items
 * - existing attacker resources remain untouched because Mindustry cores
 *   intentionally share one team inventory
 * - personal-team elimination messages
 * - elimination and victory detection immediately after the successful
 *   destruction, before the delayed replacement Core Shard appears
 * - Fallen can win only after at least one personal start core was assigned
 * - one guarded automatic random-seed round reset
 */
final class TeamManager {

    static final int FALLEN_TEAM_ID = 18;
    static final Team FALLEN_TEAM = Team.get(FALLEN_TEAM_ID);

    private static final int FIRST_PERSONAL_TEAM_ID = 1;
    private static final int LAST_PERSONAL_TEAM_ID = 128;

    /**
     * Start A -> hex -> hex -> Start B
     *
     * This means graph distance 3 is the minimum allowed distance between
     * two claimed start hexes.
     */
    private static final int MINIMUM_START_DISTANCE = 3;

    private static final int SHORT_ROW_COLS = 7;
    private static final int LONG_ROW_COLS = 8;
    private static final int ROWS = 9;

    private static final long TEAM_RANDOM_XOR = 0x5445414d2d455649L;

    /**
     * Captures intentionally do not complete instantly. The empty center is
     * visible for a few seconds before the attacker's small Core Shard appears.
     */
    private static final float CAPTURE_DELAY_TICKS = 5f * 60f;

    /**
     * Capture cleanup follows the real Evict core range exactly.
     *
     * Every synthetic building whose center is within 40 tiles of the
     * destroyed core is removed, including buildings inside the overlap with
     * a neighbouring hex. This is intentionally core range, not build range.
     */
    private static final int CAPTURE_CLEAR_RADIUS = 40;
    private static final int CAPTURE_CLEAR_RADIUS_SQUARED =
        CAPTURE_CLEAR_RADIUS * CAPTURE_CLEAR_RADIUS;

    private final List<HexSlot> slots = new ArrayList<>();
    private final Map<String, Integer> teamIdByPlayerUuid = new HashMap<>();
    private final Map<Integer, String> playerNameByTeamId = new HashMap<>();
    private final Set<Integer> usedPersonalTeamIds = new HashSet<>();
    private final Set<Integer> eliminatedTeamIds = new HashSet<>();

    private final Cons<Team> victoryHandler;

    private Random random = new Random();
    private boolean roundActive = false;
    private boolean roundActivated = false;
    private boolean resetting = false;
    private long roundSerial = 0L;

    TeamManager(Cons<Team> victoryHandler) {
        this.victoryHandler = victoryHandler;
    }

    void beginRound(List<HexSlot> newSlots, long seed) {
        slots.clear();
        slots.addAll(newSlots);

        teamIdByPlayerUuid.clear();
        playerNameByTeamId.clear();
        usedPersonalTeamIds.clear();
        eliminatedTeamIds.clear();

        for (HexSlot slot : slots) {
            slot.ownerTeamId = FALLEN_TEAM_ID;
            slot.capturing = false;
            slot.pendingCaptureTeamId = FALLEN_TEAM_ID;
        }

        random = new Random(seed ^ TEAM_RANDOM_XOR);
        roundSerial++;
        roundActivated = false;
        resetting = false;
        roundActive = true;

        Log.info(
            "[EvictMapGenerator] Team round initialized. Fallen team=#@, neutralHexes=@, allowedPersonalTeams=#@..#@ except #@.",
            FALLEN_TEAM_ID,
            slots.size(),
            FIRST_PERSONAL_TEAM_ID,
            LAST_PERSONAL_TEAM_ID,
            FALLEN_TEAM_ID
        );
    }

    void assignConnectedPlayers() {
        if (!roundActive || resetting) {
            return;
        }

        /**
         * This method may run both during generation and one tick after
         * PlayEvent. Only process players not yet registered in this round so
         * an already assigned player is never spawned or messaged twice.
         */
        Groups.player.each(player -> {
            if (
                player != null
                    && !teamIdByPlayerUuid.containsKey(player.uuid())
            ) {
                handlePlayerJoin(player);
            }
        });
    }

    void handlePlayerJoin(Player player) {
        if (!roundActive || resetting || player == null) {
            return;
        }

        String uuid = player.uuid();
        Integer existingTeamId = teamIdByPlayerUuid.get(uuid);

        if (existingTeamId != null) {
            if (existingTeamId != FALLEN_TEAM_ID) {
                playerNameByTeamId.put(existingTeamId, player.plainName());
            }

            assignPlayerToTeam(player, Team.get(existingTeamId));

            if (existingTeamId == FALLEN_TEAM_ID) {
                player.sendMessage(
                    "[scarlet]No safe starting hex is available. "
                        + "You remain in the Fallen team without a starting bonus."
                );
            } else {
                player.sendMessage(
                    "[accent]Reconnected to your previous team: #"
                        + existingTeamId
                        + "."
                );
            }

            return;
        }

        HexSlot startHex = chooseSafeStartHex();
        Integer teamId = chooseUnusedPersonalTeamId();

        if (startHex == null || teamId == null) {
            teamIdByPlayerUuid.put(uuid, FALLEN_TEAM_ID);
            assignPlayerToTeam(player, FALLEN_TEAM);

            player.sendMessage(
                "[scarlet]No safe starting hex is available. "
                    + "You joined the Fallen team without a starting bonus."
            );

            Log.info(
                "[EvictMapGenerator] Player '@' joined Fallen team #@ because no safe personal start was available.",
                player.name,
                FALLEN_TEAM_ID
            );

            return;
        }

        Team personalTeam = Team.get(teamId);

        claimCore(startHex, personalTeam);
        usedPersonalTeamIds.add(teamId);
        teamIdByPlayerUuid.put(uuid, teamId);
        playerNameByTeamId.put(teamId, player.plainName());
        roundActivated = true;

        assignPlayerToTeam(player, personalTeam);

        player.sendMessage(
            "[accent]You claimed a protected starting hex as team #"
                + teamId
                + "."
        );

        Log.info(
            "[EvictMapGenerator] Player '@' claimed hex (@,@) as team #@. protectionScore=@.",
            player.name,
            startHex.col,
            startHex.row,
            teamId,
            startHex.protectedSides
        );
    }

    void logStatus() {
        Log.info("[EvictMapGenerator] Team assignment status: @", compactStatus());

        for (HexSlot slot : slots) {
            if (slot.ownerTeamId != FALLEN_TEAM_ID) {
                Log.info(
                    "[EvictMapGenerator] claimed hex (@,@) -> team #@, protectionScore=@",
                    slot.col,
                    slot.row,
                    slot.ownerTeamId,
                    slot.protectedSides
                );
            }
        }
    }

    String compactStatus() {
        int claimed = 0;
        int neutral = 0;
        int capturing = 0;

        for (HexSlot slot : slots) {
            if (slot.capturing) {
                capturing++;
            } else if (slot.ownerTeamId == FALLEN_TEAM_ID) {
                neutral++;
            } else {
                claimed++;
            }
        }

        return "Fallen=#" + FALLEN_TEAM_ID
            + ", neutralHexes=" + neutral
            + ", claimedHexes=" + claimed
            + ", capturingHexes=" + capturing
            + ", rememberedPlayers=" + teamIdByPlayerUuid.size()
            + ", roundActivated=" + roundActivated
            + ", resetting=" + resetting;
    }

    private HexSlot chooseSafeStartHex() {
        List<HexSlot> candidates = new ArrayList<>();

        for (HexSlot slot : slots) {
            if (
                slot.ownerTeamId == FALLEN_TEAM_ID
                    && !slot.capturing
                    && farEnoughFromClaimedTeamHexes(slot)
            ) {
                candidates.add(slot);
            }
        }

        if (candidates.isEmpty()) {
            return null;
        }

        int bestProtectionScore = Integer.MIN_VALUE;

        for (HexSlot slot : candidates) {
            bestProtectionScore = Math.max(
                bestProtectionScore,
                slot.protectedSides
            );
        }

        List<HexSlot> bestCandidates = new ArrayList<>();

        for (HexSlot slot : candidates) {
            if (slot.protectedSides == bestProtectionScore) {
                bestCandidates.add(slot);
            }
        }

        Collections.shuffle(bestCandidates, random);
        return bestCandidates.get(0);
    }

    private boolean farEnoughFromClaimedTeamHexes(HexSlot candidate) {
        for (HexSlot occupied : slots) {
            if (
                effectiveOwnerTeamId(occupied) != FALLEN_TEAM_ID
                    && gridDistance(candidate, occupied)
                        < MINIMUM_START_DISTANCE
            ) {
                return false;
            }
        }

        return true;
    }

    private Integer chooseUnusedPersonalTeamId() {
        List<Integer> available = new ArrayList<>();

        for (
            int teamId = FIRST_PERSONAL_TEAM_ID;
            teamId <= LAST_PERSONAL_TEAM_ID;
            teamId++
        ) {
            if (
                teamId != FALLEN_TEAM_ID
                    && !usedPersonalTeamIds.contains(teamId)
            ) {
                available.add(teamId);
            }
        }

        if (available.isEmpty()) {
            return null;
        }

        return available.get(random.nextInt(available.size()));
    }

    private void claimCore(HexSlot slot, Team personalTeam) {
        Tile tile = Vars.world.tile(slot.x, slot.y);

        if (tile == null) {
            throw new IllegalStateException(
                "Cannot claim missing core tile at "
                    + slot.x + "," + slot.y + "."
            );
        }

        /**
         * The schematic includes its own centered Nucleus. StartLoadout
         * anchors that Nucleus exactly onto the neutral core tile, places all
         * buildings as the new personal team and fills the core once.
         *
         * Reconnects never reach this method, so the package cannot be claimed
         * twice by the same player.
         */
        StartLoadout.place(slot.x, slot.y, personalTeam);
        slot.ownerTeamId = personalTeam.id;
    }

    void handleCoreChange(
        CoreBuild core,
        AttritionManager attritionManager
    ) {
        if (
            !roundActive
                || resetting
                || core == null
                || core.health > 0f
                || core.tile == null
        ) {
            return;
        }

        HexSlot slot = findSlotByCoreTile(core.tile.x, core.tile.y);

        if (slot == null || slot.capturing) {
            return;
        }

        Team defenderTeam = core.team;
        Team attackerTeam = validCaptureAttacker(core.lastDamage, defenderTeam);

        slot.capturing = true;
        slot.pendingCaptureTeamId = attackerTeam.id;

        long scheduledRoundSerial = roundSerial;

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
        HexSlot slot,
        Team defenderTeam,
        Team attackerTeam,
        long scheduledRoundSerial,
        AttritionManager attritionManager
    ) {
        if (
            !roundActive
                || resetting
                || scheduledRoundSerial != roundSerial
                || !slot.capturing
        ) {
            return;
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
            announceEliminationIfNeeded(defenderTeam, attackerTeam);
        }

        checkVictory();

        if (!roundActive || resetting) {
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
        HexSlot slot,
        Team defenderTeam,
        Team attackerTeam,
        long scheduledRoundSerial
    ) {
        if (
            !roundActive
                || resetting
                || scheduledRoundSerial != roundSerial
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
         * The center should already be empty. Clear any unexpected synthetic
         * block that appeared during the delay and place the attacker's small
         * 3x3 Core Shard without adding any bonus items.
         */
        if (centerTile.synthetic()) {
            centerTile.removeNet();
        }

        centerTile.setNet(Blocks.coreShard, attackerTeam, 0);

        /**
         * Do not clear capturedCore.items here.
         *
         * Mindustry intentionally shares one ItemModule between every core of
         * the same team. Clearing the new Core Shard would therefore erase the
         * attacker's resources from all existing cores as well.
         *
         * A captured shard adds no bonus resources; it simply joins the
         * attacker's already shared core inventory.
         */
        slot.ownerTeamId = attackerTeam.id;
        slot.pendingCaptureTeamId = attackerTeam.id;
        slot.capturing = false;

        Log.info(
            "[EvictMapGenerator] Capture complete at hex (@,@): team #@ -> team #@ with a Core Shard and no bonus items.",
            slot.col,
            slot.row,
            defenderTeam.id,
            attackerTeam.id
        );

    }

    private void announceEliminationIfNeeded(
        Team defenderTeam,
        Team attackerTeam
    ) {
        if (
            defenderTeam == FALLEN_TEAM
                || defenderTeam == attackerTeam
                || eliminatedTeamIds.contains(defenderTeam.id)
                || ownsAnyHex(defenderTeam.id)
        ) {
            return;
        }

        eliminatedTeamIds.add(defenderTeam.id);

        String message =
            "[accent]"
                + displayTeam(defenderTeam)
                + "[] has been eliminated by [scarlet]"
                + displayTeam(attackerTeam)
                + "[].";

        Call.sendMessage(message);

        Log.info(
            "[EvictMapGenerator] Elimination: @ was eliminated by @.",
            displayTeam(defenderTeam),
            displayTeam(attackerTeam)
        );
    }

    private boolean ownsAnyHex(int teamId) {
        for (HexSlot slot : slots) {
            if (effectiveOwnerTeamId(slot) == teamId) {
                return true;
            }
        }

        return false;
    }

    private void checkVictory() {
        if (
            !roundActive
                || resetting
                || slots.isEmpty()
        ) {
            return;
        }

        /**
         * Pending captures count as ownership immediately. The delayed Core
         * Shard is only the visible replacement block, not the moment at which
         * the round result is decided.
         */
        int winnerTeamId = effectiveOwnerTeamId(slots.get(0));

        for (HexSlot slot : slots) {
            if (effectiveOwnerTeamId(slot) != winnerTeamId) {
                return;
            }
        }

        /**
         * Fallen owns every neutral core immediately after generation. It may
         * win only after the round has actually started through at least one
         * personal start-core assignment.
         */
        if (winnerTeamId == FALLEN_TEAM_ID && !roundActivated) {
            return;
        }

        finishRound(Team.get(winnerTeamId), false);
    }

    boolean forceEnd(Team winner) {
        if (
            !roundActive
                || resetting
                || winner == null
                || winner == Team.derelict
        ) {
            return false;
        }

        finishRound(winner, true);
        return true;
    }

    private void finishRound(Team winner, boolean forced) {
        resetting = true;
        roundActive = false;

        if (forced) {
            Call.sendMessage("[scarlet]The round was force-ended by an admin.[]");
        }

        Call.sendMessage(
            "[accent]"
                + displayTeam(winner)
                + "[] has conquered every hex and won the round."
        );

        Log.info(
            "[EvictMapGenerator] Victory: @ won the round@ Starting guarded post-game reset.",
            displayTeam(winner),
            forced ? " through /forceend." : "."
        );

        victoryHandler.get(winner);
    }

    private String displayTeam(Team team) {
        if (team == FALLEN_TEAM) {
            return "Fallen";
        }

        String playerName = playerNameByTeamId.get(team.id);

        return playerName == null || playerName.isBlank()
            ? "Team #" + team.id
            : playerName;
    }

    private Team validCaptureAttacker(Team lastDamage, Team defenderTeam) {
        /**
         * Normal PvP damage stores the real attacking team in CoreBuild.
         * If an admin command, environment effect or unusual edge case removes
         * a core without a valid enemy source, restore the hex to its previous
         * owner instead of creating a derelict or permanently dead hex.
         */
        if (
            lastDamage == null
                || lastDamage == Team.derelict
                || lastDamage == defenderTeam
        ) {
            return defenderTeam;
        }

        return lastDamage;
    }

    private int clearSyntheticBuildingsInsideHex(HexSlot capturedSlot) {
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

    private boolean belongsToHex(int tileX, int tileY, HexSlot candidate) {
        return squaredDistance(tileX, tileY, candidate)
            <= CAPTURE_CLEAR_RADIUS_SQUARED;
    }

    private long squaredDistance(int tileX, int tileY, HexSlot slot) {
        long dx = tileX - slot.x;
        long dy = tileY - slot.y;

        return dx * dx + dy * dy;
    }

    private HexSlot findSlotByCoreTile(int x, int y) {
        for (HexSlot slot : slots) {
            if (slot.x == x && slot.y == y) {
                return slot;
            }
        }

        return null;
    }

    private int effectiveOwnerTeamId(HexSlot slot) {
        return slot.capturing ? slot.pendingCaptureTeamId : slot.ownerTeamId;
    }

    boolean isRoundActiveForSystems() {
        return roundActive && !resetting;
    }

    /**
     * A unit is protected from recurring range attrition while it remains in
     * an owned core hex or one directly neighbouring hex. Entering a hex two
     * graph steps away is the first point at which recurring attrition applies.
     */
    boolean isWithinOneHexOfOwnedCore(Unit unit) {
        if (unit == null || slots.isEmpty()) {
            return false;
        }

        HexSlot unitHex = closestHexSlot(unit.tileX(), unit.tileY());

        if (unitHex == null) {
            return false;
        }

        for (HexSlot coreHex : slots) {
            if (
                effectiveOwnerTeamId(coreHex) == unit.team.id
                    && gridDistance(unitHex, coreHex) <= 1
            ) {
                return true;
            }
        }

        return false;
    }

    /**
     * /fullassault targets the globally closest currently existing enemy core
     * for each eligible unit. Pending captures are deliberately skipped until
     * their delayed Core Shard actually exists.
     */
    CoreBuild closestEnemyCore(Unit unit) {
        if (unit == null) {
            return null;
        }

        CoreBuild closest = null;
        float closestDistanceSquared = Float.POSITIVE_INFINITY;

        for (HexSlot slot : slots) {
            Tile tile = Vars.world.tile(slot.x, slot.y);

            if (
                tile == null
                    || !(tile.build instanceof CoreBuild core)
                    || core.team == unit.team
            ) {
                continue;
            }

            float distanceSquared = unit.dst2(core);

            if (distanceSquared < closestDistanceSquared) {
                closest = core;
                closestDistanceSquared = distanceSquared;
            }
        }

        return closest;
    }

    private HexSlot closestHexSlot(int tileX, int tileY) {
        HexSlot closest = null;
        long closestDistanceSquared = Long.MAX_VALUE;

        for (HexSlot slot : slots) {
            long distanceSquared = squaredDistance(tileX, tileY, slot);

            if (distanceSquared < closestDistanceSquared) {
                closest = slot;
                closestDistanceSquared = distanceSquared;
            }
        }

        return closest;
    }

    private void assignPlayerToTeam(Player player, Team team) {
        player.team(team);

        /**
         * Force a clean spawn request at the assigned team's core.
         * This avoids keeping a unit that was briefly created for a previous
         * default team during the connection process.
         */
        player.clearUnit();
        player.checkSpawn();
    }

    private int gridDistance(HexSlot first, HexSlot second) {
        GridPos start = new GridPos(first.col, first.row);
        GridPos target = new GridPos(second.col, second.row);

        if (start.equals(target)) {
            return 0;
        }

        ArrayDeque<GridStep> queue = new ArrayDeque<>();
        Set<GridPos> visited = new HashSet<>();

        queue.add(new GridStep(start, 0));
        visited.add(start);

        while (!queue.isEmpty()) {
            GridStep step = queue.removeFirst();

            for (GridPos neighbour : neighbourSlots(step.position)) {
                if (!validGridPos(neighbour) || !visited.add(neighbour)) {
                    continue;
                }

                int distance = step.distance + 1;

                if (neighbour.equals(target)) {
                    return distance;
                }

                queue.addLast(new GridStep(neighbour, distance));
            }
        }

        return Integer.MAX_VALUE;
    }

    private List<GridPos> neighbourSlots(GridPos position) {
        List<GridPos> result = new ArrayList<>();

        result.add(new GridPos(position.col - 1, position.row));
        result.add(new GridPos(position.col + 1, position.row));

        if (position.row % 2 == 0) {
            result.add(new GridPos(position.col,     position.row - 1));
            result.add(new GridPos(position.col + 1, position.row - 1));
            result.add(new GridPos(position.col,     position.row + 1));
            result.add(new GridPos(position.col + 1, position.row + 1));
        } else {
            result.add(new GridPos(position.col - 1, position.row - 1));
            result.add(new GridPos(position.col,     position.row - 1));
            result.add(new GridPos(position.col - 1, position.row + 1));
            result.add(new GridPos(position.col,     position.row + 1));
        }

        return result;
    }

    private boolean validGridPos(GridPos position) {
        return position.row >= 0
            && position.row < ROWS
            && position.col >= 0
            && position.col < colsForRow(position.row);
    }

    private int colsForRow(int row) {
        return row % 2 == 0 ? SHORT_ROW_COLS : LONG_ROW_COLS;
    }

    static final class HexSlot {
        private final int col;
        private final int row;
        private final int x;
        private final int y;
        private final int protectedSides;

        private int ownerTeamId = FALLEN_TEAM_ID;
        private boolean capturing = false;
        private int pendingCaptureTeamId = FALLEN_TEAM_ID;

        HexSlot(
            int col,
            int row,
            int x,
            int y,
            int protectedSides
        ) {
            this.col = col;
            this.row = row;
            this.x = x;
            this.y = y;
            this.protectedSides = protectedSides;
        }
    }

    private record GridPos(int col, int row) {
    }

    private record GridStep(GridPos position, int distance) {
    }
}
