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
 * - every generated neutral core belongs to Fallen team #14
 * - a first-time player receives a random unique team ID from #1..#128,
 *   excluding #14
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

    static final int FALLEN_TEAM_ID = 14;
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

    /**
     * The generated playable hex circles use radius 39. Extinction converts
     * each collapsed logical hex circle into space without touching the
     * surviving neighbour selected by the nearest-center check.
     */
    private static final int EXTINCTION_HEX_RADIUS = 39;
    private static final int EXTINCTION_HEX_RADIUS_SQUARED =
        EXTINCTION_HEX_RADIUS * EXTINCTION_HEX_RADIUS;

    /**
     * Floor changes are network-synchronised. Sending thousands of floor
     * packets in one tick can disconnect clients, so collapsed terrain is
     * streamed gradually.
     */
    private static final int DEFAULT_EXTINCTION_TERRAIN_CHANGES_PER_TICK = 120;
    private static final int MAX_EXTINCTION_TERRAIN_CHANGES_PER_TICK = 4096;

    private static final int CENTER_ROW = ROWS / 2;
    private static final int CENTER_COL = SHORT_ROW_COLS / 2;

    private final List<HexSlot> slots = new ArrayList<>();
    private final Map<String, Integer> teamIdByPlayerUuid = new HashMap<>();
    private final Map<Integer, String> playerNameByTeamId = new HashMap<>();
    private final Map<Integer, String> leaderUuidByTeamId = new HashMap<>();
    private final List<Integer> personalTeamCreationOrder = new ArrayList<>();
    private final Map<String, Integer> claimTeamIdByPlayerUuid = new HashMap<>();
    private final Map<Integer, Map<Integer, Integer>>
        capturesByDefenderTeamId = new HashMap<>();
    private final Map<Integer, Integer> maximumOwnedHexesByTeamId =
        new HashMap<>();
    private final Set<Integer> usedPersonalTeamIds = new HashSet<>();
    private final Set<Integer> eliminatedTeamIds = new HashSet<>();
    private final ArrayDeque<Tile> extinctionTerrainQueue = new ArrayDeque<>();

    private final Cons<Team> victoryHandler;
    private InviteManager inviteManager;
    private final CaptureManager captureManager = new CaptureManager(this);

    private Random random = new Random();
    private boolean roundActive = false;
    private boolean roundActivated = false;
    private boolean resetting = false;
    private boolean suppressCoreChangeEvents = false;
    private boolean extinctionActive = false;
    private int extinctionTerrainChangesPerTick =
        DEFAULT_EXTINCTION_TERRAIN_CHANGES_PER_TICK;
    private long roundSerial = 0L;

    TeamManager(Cons<Team> victoryHandler) {
        this.victoryHandler = victoryHandler;
    }

    void setInviteManager(InviteManager inviteManager) {
        this.inviteManager = inviteManager;
    }

    void beginRound(List<HexSlot> newSlots, long seed) {
        slots.clear();
        slots.addAll(newSlots);

        teamIdByPlayerUuid.clear();
        playerNameByTeamId.clear();
        leaderUuidByTeamId.clear();
        personalTeamCreationOrder.clear();
        claimTeamIdByPlayerUuid.clear();
        capturesByDefenderTeamId.clear();
        maximumOwnedHexesByTeamId.clear();
        usedPersonalTeamIds.clear();
        eliminatedTeamIds.clear();
        extinctionTerrainQueue.clear();

        for (HexSlot slot : slots) {
            slot.ownerTeamId = FALLEN_TEAM_ID;
            slot.capturing = false;
            slot.pendingCaptureTeamId = FALLEN_TEAM_ID;
            slot.extinct = false;
        }

        random = new Random(seed ^ TEAM_RANDOM_XOR);
        roundSerial++;
        roundActivated = false;
        resetting = false;
        extinctionActive = false;
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
            if (
                existingTeamId != FALLEN_TEAM_ID
                    && uuid.equals(leaderUuidByTeamId.get(existingTeamId))
            ) {
                playerNameByTeamId.put(existingTeamId, player.plainName());
            }

            assignPlayerToTeam(player, Team.get(existingTeamId));

            if (existingTeamId == FALLEN_TEAM_ID) {
                Integer claimantTeamId = claimTeamIdByPlayerUuid.get(uuid);

                if (claimantTeamId == null) {
                    player.sendMessage(
                        "[accent]Reconnected as Fallen. Use /invite to view available teams.[]"
                    );
                } else {
                    player.sendMessage(
                        "[accent]Reconnected as Fallen. You were claimed by "
                            + displayTeam(Team.get(claimantTeamId))
                            + "'s team.[]"
                    );
                }
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
        leaderUuidByTeamId.put(teamId, uuid);
        personalTeamCreationOrder.add(teamId);
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
                !slot.extinct
                    && slot.ownerTeamId == FALLEN_TEAM_ID
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
        updateMaximumOwnedHexes(personalTeam.id);
    }

    void handleCoreChange(
        CoreBuild core,
        AttritionManager attritionManager
    ) {
        captureManager.handleCoreChange(core, attritionManager);
    }

    void announceEliminationIfNeeded(
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

        Team claimantTeam = determineClaimantTeam(defenderTeam, attackerTeam);
        List<String> newlyEliminatedPlayerUuids =
            moveEliminatedTeamPlayersToFallen(defenderTeam, claimantTeam);

        transferExistingClaims(defenderTeam, claimantTeam);

        if (inviteManager != null) {
            inviteManager.handleTeamEliminated(
                defenderTeam,
                claimantTeam,
                newlyEliminatedPlayerUuids
            );
        }

        String message =
            "[accent]"
                + displayTeam(defenderTeam)
                + "[] has been eliminated by [scarlet]"
                + displayTeam(attackerTeam)
                + "[].";

        Call.sendMessage(message);

        Log.info(
            "[EvictMapGenerator] Elimination: @ was eliminated by @. claimant=@.",
            displayTeam(defenderTeam),
            displayTeam(attackerTeam),
            claimantTeam == null ? "none" : displayTeam(claimantTeam)
        );
    }

    void recordCoreDestruction(
        Team defenderTeam,
        Team attackerTeam
    ) {
        if (
            defenderTeam == null
                || attackerTeam == null
                || defenderTeam == FALLEN_TEAM
                || attackerTeam == FALLEN_TEAM
                || defenderTeam == attackerTeam
                || attackerTeam == Team.derelict
        ) {
            return;
        }

        Map<Integer, Integer> counts =
            capturesByDefenderTeamId.computeIfAbsent(
                defenderTeam.id,
                ignored -> new HashMap<>()
            );

        counts.put(
            attackerTeam.id,
            counts.getOrDefault(attackerTeam.id, 0) + 1
        );
    }

    private Team determineClaimantTeam(
        Team defenderTeam,
        Team lastCoreAttacker
    ) {
        Map<Integer, Integer> counts =
            capturesByDefenderTeamId.get(defenderTeam.id);

        if (counts == null || counts.isEmpty()) {
            return validClaimant(lastCoreAttacker) ? lastCoreAttacker : null;
        }

        int bestCount = Integer.MIN_VALUE;
        List<Integer> tiedTeamIds = new ArrayList<>();

        for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
            Team candidate = Team.get(entry.getKey());

            if (!validClaimant(candidate)) {
                continue;
            }

            if (entry.getValue() > bestCount) {
                bestCount = entry.getValue();
                tiedTeamIds.clear();
                tiedTeamIds.add(entry.getKey());
            } else if (entry.getValue() == bestCount) {
                tiedTeamIds.add(entry.getKey());
            }
        }

        if (tiedTeamIds.isEmpty()) {
            return null;
        }

        if (
            validClaimant(lastCoreAttacker)
                && tiedTeamIds.contains(lastCoreAttacker.id)
        ) {
            return lastCoreAttacker;
        }

        return Team.get(tiedTeamIds.get(0));
    }

    private boolean validClaimant(Team team) {
        return team != null
            && team != FALLEN_TEAM
            && team != Team.derelict
            && isActivePersonalTeam(team.id);
    }

    private List<String> moveEliminatedTeamPlayersToFallen(
        Team defenderTeam,
        Team claimantTeam
    ) {
        return moveTeamPlayersToFallen(
            defenderTeam,
            claimantTeam,
            "[scarlet]Your team was eliminated. You are now Fallen.[]"
        );
    }

    private List<String> moveTeamPlayersToFallen(
        Team previousTeam,
        Team claimantTeam,
        String playerMessage
    ) {
        List<String> affectedUuids = new ArrayList<>();

        for (Map.Entry<String, Integer> entry : teamIdByPlayerUuid.entrySet()) {
            if (entry.getValue() != previousTeam.id) {
                continue;
            }

            String uuid = entry.getKey();
            affectedUuids.add(uuid);
            entry.setValue(FALLEN_TEAM_ID);

            if (claimantTeam == null) {
                claimTeamIdByPlayerUuid.remove(uuid);
            } else {
                claimTeamIdByPlayerUuid.put(uuid, claimantTeam.id);
            }

            Player player = onlinePlayer(uuid);

            if (player != null) {
                assignPlayerToTeam(player, FALLEN_TEAM);
                player.sendMessage(playerMessage);

                if (claimantTeam != null) {
                    player.sendMessage(
                        "[accent]You were claimed by "
                            + displayTeam(claimantTeam)
                            + "'s team.[]"
                    );
                }
            }
        }

        return affectedUuids;
    }

    private void transferExistingClaims(
        Team eliminatedClaimant,
        Team replacementClaimant
    ) {
        List<String> affectedUuids = new ArrayList<>();

        for (Map.Entry<String, Integer> entry : claimTeamIdByPlayerUuid.entrySet()) {
            if (entry.getValue() == eliminatedClaimant.id) {
                affectedUuids.add(entry.getKey());
            }
        }

        for (String uuid : affectedUuids) {
            if (replacementClaimant == null) {
                claimTeamIdByPlayerUuid.remove(uuid);
            } else {
                claimTeamIdByPlayerUuid.put(uuid, replacementClaimant.id);
            }
        }
    }

    private boolean ownsAnyHex(int teamId) {
        for (HexSlot slot : slots) {
            if (effectiveOwnerTeamId(slot) == teamId) {
                return true;
            }
        }

        return false;
    }

    private int countOwnedHexes(int teamId) {
        int count = 0;

        for (HexSlot slot : slots) {
            if (effectiveOwnerTeamId(slot) == teamId) {
                count++;
            }
        }

        return count;
    }

    void updateMaximumOwnedHexes(int teamId) {
        if (teamId == FALLEN_TEAM_ID || teamId == Team.derelict.id) {
            return;
        }

        int current = countOwnedHexes(teamId);

        maximumOwnedHexesByTeamId.put(
            teamId,
            Math.max(
                current,
                maximumOwnedHexesByTeamId.getOrDefault(teamId, 0)
            )
        );
    }

    boolean surrenderTeam(Team team) {
        if (
            !roundActive
                || resetting
                || team == null
                || team == FALLEN_TEAM
                || team == Team.derelict
                || !isActivePersonalTeam(team.id)
        ) {
            return false;
        }

        String surrenderName = displayTeam(team);

        /**
         * Logical ownership changes before building destruction so CoreChange
         * events triggered by the surrender cannot create captures.
         *
         * Keep the surrendered slots so each one receives a Fallen Core Shard
         * immediately after the surrendered team's buildings are removed.
         */
        List<HexSlot> surrenderedSlots = new ArrayList<>();

        for (HexSlot slot : slots) {
            if (effectiveOwnerTeamId(slot) == team.id) {
                surrenderedSlots.add(slot);
                slot.ownerTeamId = FALLEN_TEAM_ID;
                slot.pendingCaptureTeamId = FALLEN_TEAM_ID;
                slot.capturing = false;
            }
        }

        clearSurrenderedTeamAssets(team);
        placeFallenCoresAfterSurrender(surrenderedSlots);
        eliminatedTeamIds.add(team.id);

        List<String> surrenderedPlayerUuids =
            moveTeamPlayersToFallen(
                team,
                null,
                "[scarlet]Your team surrendered. You are now Fallen.[]"
            );

        /**
         * Surrender has no conqueror. Existing claims held by this team become
         * free Fallen spectators rather than transferring to another team.
         */
        transferExistingClaims(team, null);

        if (inviteManager != null) {
            inviteManager.handleTeamEliminated(
                team,
                null,
                surrenderedPlayerUuids
            );
        }

        Call.sendMessage(
            "[scarlet]"
                + surrenderName
                + "[] has surrendered."
        );

        Log.info(
            "[EvictMapGenerator] Surrender: @ gave up. Buildings and units removed.",
            surrenderName
        );

        checkVictory();
        return true;
    }

    private void placeFallenCoresAfterSurrender(
        List<HexSlot> surrenderedSlots
    ) {
        for (HexSlot slot : surrenderedSlots) {
            Tile centerTile = Vars.world.tile(slot.x, slot.y);

            if (centerTile == null) {
                Log.err(
                    "[EvictMapGenerator] Cannot place Fallen surrender core: missing center tile for hex (@,@).",
                    slot.col,
                    slot.row
                );
                continue;
            }

            /**
             * All surrendered buildings were already removed. Clear any
             * unexpected synthetic remnant and restore the surrendered hex as
             * a normal Fallen-owned Nucleus immediately.
             */
            if (centerTile.synthetic()) {
                centerTile.removeNet();
            }

            centerTile.setNet(Blocks.coreNucleus, FALLEN_TEAM, 0);
        }

        Log.info(
            "[EvictMapGenerator] Restored @ surrendered hexes with Fallen Nucleus cores.",
            surrenderedSlots.size()
        );
    }

    private void clearSurrenderedTeamAssets(Team team) {
        List<Tile> buildingTiles = new ArrayList<>();
        List<Unit> unitsToKill = new ArrayList<>();

        for (Tile tile : Vars.world.tiles) {
            if (
                tile != null
                    && tile.build != null
                    && tile.isCenter()
                    && tile.build.team == team
            ) {
                buildingTiles.add(tile);
            }
        }

        Groups.unit.each(unit -> {
            if (unit != null && unit.team == team) {
                unitsToKill.add(unit);
            }
        });

        suppressCoreChangeEvents = true;

        try {
            for (Tile tile : buildingTiles) {
                if (
                    tile.build != null
                        && tile.isCenter()
                        && tile.build.team == team
                ) {
                    tile.build.kill();
                }
            }

            for (Unit unit : unitsToKill) {
                if (unit.isAdded()) {
                    unit.kill();
                }
            }
        } finally {
            suppressCoreChangeEvents = false;
        }
    }

    EarlyEndStatus earlyEndStatus(Team candidate) {
        if (
            candidate == null
                || candidate == FALLEN_TEAM
                || candidate == Team.derelict
                || !isActivePersonalTeam(candidate.id)
                || slots.isEmpty()
        ) {
            return new EarlyEndStatus(false, 0, 0, List.of());
        }

        int owned = countOwnedHexes(candidate.id);
        int requiredForHalf = (slots.size() + 1) / 2;
        int additionalNeeded = Math.max(0, requiredForHalf - owned);
        List<EarlyEndBlocker> blockers = new ArrayList<>();

        /**
         * A one-core team is ignored only when it never expanded beyond its
         * original starting core. A previously established enemy must be
         * eliminated completely, even if it has already been reduced back to
         * one remaining core.
         */
        for (int teamId : personalTeamCreationOrder) {
            if (
                teamId == candidate.id
                    || !isActivePersonalTeam(teamId)
            ) {
                continue;
            }

            int remainingCores = countOwnedHexes(teamId);
            int maximumCores =
                maximumOwnedHexesByTeamId.getOrDefault(teamId, remainingCores);

            if (maximumCores > 1) {
                blockers.add(
                    new EarlyEndBlocker(
                        Team.get(teamId),
                        remainingCores
                    )
                );
            }
        }

        return new EarlyEndStatus(
            additionalNeeded == 0 && blockers.isEmpty(),
            owned,
            additionalNeeded,
            blockers
        );
    }

    boolean endRoundEarly(Team winner) {
        EarlyEndStatus status = earlyEndStatus(winner);

        if (!roundActive || resetting || !status.eligible()) {
            return false;
        }

        resetting = true;
        roundActive = false;

        Call.sendMessage(
            "[accent]"
                + displayTeam(winner)
                + "'s team[] ended the round early after securing at least "
                + "50% of all cores with no remaining established enemy team "
                + "to conquer."
        );

        Log.info(
            "[EvictMapGenerator] Early round end: @ owns @/@ cores and has no remaining established enemy team to conquer.",
            displayTeam(winner),
            status.ownedCores(),
            slots.size()
        );

        victoryHandler.get(winner);
        return true;
    }

    void checkVictory() {
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
        Integer winnerTeamId = singleSurvivingOwnerTeamId();

        if (winnerTeamId == null) {
            return;
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

    private Integer singleSurvivingOwnerTeamId() {
        Integer winnerTeamId = null;

        for (HexSlot slot : slots) {
            /*
             * Extinction removes hexes logically before their terrain finishes
             * streaming to space. Those removed hexes no longer block victory.
             */
            if (slot.extinct) {
                continue;
            }

            int ownerTeamId = effectiveOwnerTeamId(slot);

            if (ownerTeamId == Team.derelict.id) {
                continue;
            }

            if (winnerTeamId == null) {
                winnerTeamId = ownerTeamId;
            } else if (winnerTeamId != ownerTeamId) {
                return null;
            }
        }

        return winnerTeamId;
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

        String victoryReason = extinctionActive
            ? " has secured every surviving hex during EXTINCTION and won "
                + "the round."
            : " has conquered every hex and won the round.";

        Call.sendMessage(
            "[accent]" + displayTeam(winner) + "[]" + victoryReason
        );

        Log.info(
            "[EvictMapGenerator] Victory: @ won the round@ Starting guarded post-game reset.",
            displayTeam(winner),
            forced ? " through /forceend." : "."
        );

        victoryHandler.get(winner);
    }

    boolean isFallenPlayer(Player player) {
        return player != null
            && teamIdByPlayerUuid.getOrDefault(
                player.uuid(),
                FALLEN_TEAM_ID
            ) == FALLEN_TEAM_ID;
    }

    boolean isPersonalRoundPlayer(Player player) {
        if (player == null) {
            return false;
        }

        Integer teamId = teamIdByPlayerUuid.get(player.uuid());

        return teamId != null
            && teamId != FALLEN_TEAM_ID
            && teamId != Team.derelict.id;
    }

    List<String> playerUuidsForTeam(Team team) {
        List<String> result = new ArrayList<>();

        if (team == null) {
            return result;
        }

        for (Map.Entry<String, Integer> entry : teamIdByPlayerUuid.entrySet()) {
            if (entry.getValue() == team.id) {
                result.add(entry.getKey());
            }
        }

        return result;
    }

    boolean isLeader(Player player) {
        if (player == null || player.team() == FALLEN_TEAM) {
            return false;
        }

        return player.uuid().equals(
            leaderUuidByTeamId.get(player.team().id)
        );
    }

    boolean isActivePersonalTeam(int teamId) {
        return teamId != FALLEN_TEAM_ID
            && !eliminatedTeamIds.contains(teamId)
            && ownsAnyHex(teamId);
    }

    Player onlineLeader(Team team) {
        if (team == null) {
            return null;
        }

        String leaderUuid = leaderUuidByTeamId.get(team.id);

        return leaderUuid == null ? null : onlinePlayer(leaderUuid);
    }

    List<Team> activeTeamsWithOnlineLeader() {
        List<Team> result = new ArrayList<>();

        for (int teamId : personalTeamCreationOrder) {
            Team team = Team.get(teamId);

            if (
                isActivePersonalTeam(teamId)
                    && onlineLeader(team) != null
            ) {
                result.add(team);
            }
        }

        return result;
    }

    Integer claimTeamId(String playerUuid) {
        return claimTeamIdByPlayerUuid.get(playerUuid);
    }

    boolean joinFallenPlayerToTeam(Player player, Team targetTeam) {
        if (
            player == null
                || targetTeam == null
                || !isFallenPlayer(player)
                || !isActivePersonalTeam(targetTeam.id)
        ) {
            return false;
        }

        Integer claimantTeamId =
            claimTeamIdByPlayerUuid.get(player.uuid());

        if (
            claimantTeamId != null
                && claimantTeamId != targetTeam.id
        ) {
            return false;
        }

        teamIdByPlayerUuid.put(player.uuid(), targetTeam.id);
        claimTeamIdByPlayerUuid.remove(player.uuid());
        assignPlayerToTeam(player, targetTeam);

        player.sendMessage(
            "[green]You joined "
                + displayTeam(targetTeam)
                + "'s team.[]"
        );

        return true;
    }

    private Player onlinePlayer(String uuid) {
        for (Player player : Groups.player) {
            if (player != null && player.uuid().equals(uuid)) {
                return player;
            }
        }

        return null;
    }

    String displayTeam(Team team) {
        if (team == FALLEN_TEAM) {
            return "Fallen";
        }

        String playerName = playerNameByTeamId.get(team.id);

        return playerName == null || playerName.isBlank()
            ? "Team #" + team.id
            : playerName;
    }

    private long squaredDistance(int tileX, int tileY, HexSlot slot) {
        long dx = tileX - slot.x;
        long dy = tileY - slot.y;

        return dx * dx + dy * dy;
    }

    private int effectiveOwnerTeamId(HexSlot slot) {
        if (slot.extinct) {
            return Team.derelict.id;
        }

        return slot.capturing ? slot.pendingCaptureTeamId : slot.ownerTeamId;
    }

    boolean isRoundActivated() {
        return roundActivated;
    }

    void setExtinctionActive(boolean active) {
        extinctionActive = active;
    }

    int gridDistanceFromCenter(HexSlot slot) {
        return gridDistance(
            new HexSlot(CENTER_COL, CENTER_ROW, 0, 0, 0),
            slot
        );
    }

    Team centerHexOwner() {
        for (HexSlot slot : slots) {
            if (slot.col == CENTER_COL && slot.row == CENTER_ROW) {
                return Team.get(effectiveOwnerTeamId(slot));
            }
        }

        return Team.derelict;
    }

    void collapseHexesForExtinction(List<HexSlot> collapsingSlots) {
        if (
            !roundActive
                || resetting
                || collapsingSlots == null
                || collapsingSlots.isEmpty()
        ) {
            return;
        }

        Set<HexSlot> collapsing = new HashSet<>(collapsingSlots);

        /**
         * Logical ownership is removed first. CoreChangeEvents emitted while
         * deleting blocks therefore cannot start normal capture timers.
         */
        for (HexSlot slot : collapsing) {
            slot.extinct = true;
            slot.capturing = false;
            slot.ownerTeamId = Team.derelict.id;
            slot.pendingCaptureTeamId = Team.derelict.id;
        }

        List<Tile> buildingCenters = new ArrayList<>();
        List<Unit> unitsToKill = new ArrayList<>();
        List<Tile> terrainTiles = new ArrayList<>();

        for (Tile tile : Vars.world.tiles) {
            if (
                tile != null
                    && belongsToCollapsedHex(tile.x, tile.y, collapsing)
            ) {
                terrainTiles.add(tile);

                if (
                    tile.build != null
                        && tile.isCenter()
                ) {
                    buildingCenters.add(tile);
                }
            }
        }

        Groups.unit.each(unit -> {
            if (
                unit != null
                    && unit.isAdded()
                    && belongsToCollapsedHex(
                        unit.tileX(),
                        unit.tileY(),
                        collapsing
                    )
            ) {
                unitsToKill.add(unit);
            }
        });

        suppressCoreChangeEvents = true;

        try {
            for (Tile tile : buildingCenters) {
                if (tile.build != null && tile.isCenter()) {
                    tile.removeNet();
                }
            }

            for (Unit unit : unitsToKill) {
                if (unit.isAdded()) {
                    unit.kill();
                }
            }

            /**
             * Queue floor removal instead of synchronising every tile in one
             * tick. Buildings and cores are already gone immediately, so the
             * logical collapse still happens at once while visual terrain
             * removal is streamed safely across later ticks.
             */
            extinctionTerrainQueue.addAll(terrainTiles);
        } finally {
            suppressCoreChangeEvents = false;
        }

        eliminateCorelessTeamsThroughExtinction();
        checkVictory();

        Log.info(
            "[EvictMapGenerator] Extinction collapsed @ hexes, removed @ building centers, killed @ units and queued @ terrain tiles for throttled removal.",
            collapsing.size(),
            buildingCenters.size(),
            unitsToKill.size(),
            terrainTiles.size()
        );
    }

    void updateExtinctionTerrainQueue() {
        if (extinctionTerrainQueue.isEmpty()) {
            return;
        }

        int changed = 0;

        suppressCoreChangeEvents = true;

        try {
            while (
                changed < extinctionTerrainChangesPerTick
                    && !extinctionTerrainQueue.isEmpty()
            ) {
                Tile tile = extinctionTerrainQueue.removeFirst();

                if (tile == null) {
                    continue;
                }

                if (tile.block() != Blocks.air) {
                    tile.removeNet();
                }

                tile.setFloorNet(Blocks.space);
                changed++;
            }
        } finally {
            suppressCoreChangeEvents = false;
        }
    }

    boolean hasPendingExtinctionTerrainChanges() {
        return !extinctionTerrainQueue.isEmpty();
    }

    int extinctionTerrainChangesPerTick() {
        return extinctionTerrainChangesPerTick;
    }

    void setExtinctionTerrainChangesPerTick(int amount) {
        if (
            amount < 1
                || amount > MAX_EXTINCTION_TERRAIN_CHANGES_PER_TICK
        ) {
            throw new IllegalArgumentException(
                "Extinction terrain changes per tick must be between 1 and "
                    + MAX_EXTINCTION_TERRAIN_CHANGES_PER_TICK
                    + "."
            );
        }

        extinctionTerrainChangesPerTick = amount;
    }

    void finishExtinction(Team winner, boolean overtime) {
        if (
            !roundActive
                || resetting
                || winner == null
                || winner == Team.derelict
        ) {
            return;
        }

        resetting = true;
        roundActive = false;

        if (winner == FALLEN_TEAM) {
            Call.sendMessage(
                "[scarlet]Fallen[] won EXTINCTION because no active personal team survived."
            );

            Log.info(
                "[EvictMapGenerator] Extinction winner: Fallen. No active personal team survived. Starting guarded post-game reset."
            );
        } else {
            Call.sendMessage(
                "[accent]"
                    + displayTeam(winner)
                    + "[] won EXTINCTION by controlling the center core"
                    + (overtime
                        ? " during overtime."
                        : " after the 4-minute final hold.")
            );

            Log.info(
                "[EvictMapGenerator] Extinction winner: @. Starting guarded post-game reset.",
                displayTeam(winner)
            );
        }

        victoryHandler.get(winner);
    }

    private void eliminateCorelessTeamsThroughExtinction() {
        List<Integer> teamIds = new ArrayList<>(personalTeamCreationOrder);

        for (int teamId : teamIds) {
            if (
                eliminatedTeamIds.contains(teamId)
                    || ownsAnyHex(teamId)
            ) {
                continue;
            }

            eliminateTeamThroughExtinction(Team.get(teamId));
        }

        if (!hasActivePersonalTeam()) {
            finishExtinction(FALLEN_TEAM, false);
        }
    }

    private void eliminateTeamThroughExtinction(Team team) {
        if (
            team == null
                || team == FALLEN_TEAM
                || team == Team.derelict
                || eliminatedTeamIds.contains(team.id)
        ) {
            return;
        }

        eliminatedTeamIds.add(team.id);

        List<String> eliminatedPlayerUuids =
            moveTeamPlayersToFallen(
                team,
                null,
                "[scarlet]Your team was consumed by EXTINCTION. You are now Fallen.[]"
            );

        /**
         * Extinction has no conquering team. Existing claims held by the
         * eliminated team are released instead of transferred.
         */
        transferExistingClaims(team, null);

        if (inviteManager != null) {
            inviteManager.handleTeamEliminated(
                team,
                null,
                eliminatedPlayerUuids
            );
        }

        Call.sendMessage(
            "[scarlet]"
                + displayTeam(team)
                + "[] was eliminated by EXTINCTION."
        );

        Log.info(
            "[EvictMapGenerator] Extinction elimination: @ lost every surviving core.",
            displayTeam(team)
        );
    }

    private boolean hasActivePersonalTeam() {
        for (int teamId : personalTeamCreationOrder) {
            if (
                !eliminatedTeamIds.contains(teamId)
                    && ownsAnyHex(teamId)
            ) {
                return true;
            }
        }

        return false;
    }

    private boolean belongsToCollapsedHex(
        int tileX,
        int tileY,
        Set<HexSlot> collapsing
    ) {
        HexSlot closest = closestHexSlotIncludingExtinct(tileX, tileY);

        return closest != null
            && collapsing.contains(closest)
            && squaredDistance(tileX, tileY, closest)
                <= EXTINCTION_HEX_RADIUS_SQUARED;
    }

    private HexSlot closestHexSlotIncludingExtinct(int tileX, int tileY) {
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

    List<HexSlot> slots() {
        return slots;
    }

    long roundSerial() {
        return roundSerial;
    }

    boolean isCaptureSuppressed() {
        return suppressCoreChangeEvents;
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
            if (slot.extinct) {
                continue;
            }

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

    record EarlyEndStatus(
        boolean eligible,
        int ownedCores,
        int additionalCoresNeededForHalf,
        List<EarlyEndBlocker> blockers
    ) {
    }

    record EarlyEndBlocker(
        Team team,
        int remainingCores
    ) {
    }

    static final class HexSlot {
        final int col;
        final int row;
        final int x;
        final int y;
        final int protectedSides;

        int ownerTeamId = FALLEN_TEAM_ID;
        boolean capturing = false;
        int pendingCaptureTeamId = FALLEN_TEAM_ID;
        boolean extinct = false;

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
