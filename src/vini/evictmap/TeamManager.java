package vini.evictmap;

import arc.util.Log;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.world.Tile;

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
 * Not implemented yet:
 * - captures, eliminations and round victory
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

    private final List<HexSlot> slots = new ArrayList<>();
    private final Map<String, Integer> teamIdByPlayerUuid = new HashMap<>();
    private final Set<Integer> usedPersonalTeamIds = new HashSet<>();

    private Random random = new Random();
    private boolean roundActive = false;

    void beginRound(List<HexSlot> newSlots, long seed) {
        slots.clear();
        slots.addAll(newSlots);

        teamIdByPlayerUuid.clear();
        usedPersonalTeamIds.clear();

        for (HexSlot slot : slots) {
            slot.ownerTeamId = FALLEN_TEAM_ID;
        }

        random = new Random(seed ^ TEAM_RANDOM_XOR);
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
        if (!roundActive) {
            return;
        }

        Groups.player.each(this::handlePlayerJoin);
    }

    void handlePlayerJoin(Player player) {
        if (!roundActive || player == null) {
            return;
        }

        String uuid = player.uuid();
        Integer existingTeamId = teamIdByPlayerUuid.get(uuid);

        if (existingTeamId != null) {
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

        for (HexSlot slot : slots) {
            if (slot.ownerTeamId != FALLEN_TEAM_ID) {
                claimed++;
            }
        }

        return "Fallen=#" + FALLEN_TEAM_ID
            + ", neutralHexes=" + (slots.size() - claimed)
            + ", claimedHexes=" + claimed
            + ", rememberedPlayers=" + teamIdByPlayerUuid.size();
    }

    private HexSlot chooseSafeStartHex() {
        List<HexSlot> candidates = new ArrayList<>();

        for (HexSlot slot : slots) {
            if (
                slot.ownerTeamId == FALLEN_TEAM_ID
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
                occupied.ownerTeamId != FALLEN_TEAM_ID
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
