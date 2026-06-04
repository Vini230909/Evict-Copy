package vini.evictmap;

import arc.util.CommandHandler;
import arc.util.Time;
import mindustry.ai.UnitCommand;
import mindustry.ai.types.CommandAI;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

import java.util.HashSet;
import java.util.Set;

/**
 * In-game player commands for the Evict server.
 *
 * /fullassault is toggled separately for each team and can only command that
 * team's own eligible units. It is never a global server-wide assault switch.
 *
 * Kept separate from the generator so additional commands can be added later
 * without turning EvictMapPlugin into a command monolith.
 */
final class EvictCommands {

    private static final float FULL_ASSAULT_REFRESH_INTERVAL_TICKS = 60f;

    private final TeamManager teamManager;
    private final Set<Integer> fullAssaultTeamIds = new HashSet<>();

    private float fullAssaultRefreshTimer = 0f;

    EvictCommands(TeamManager teamManager) {
        this.teamManager = teamManager;
    }

    void registerClientCommands(CommandHandler handler) {
        handler.<Player>register(
            "fullassault",
            "Toggle automatic attacks against the closest enemy core for unattended combat units.",
            (args, player) -> toggleFullAssault(player)
        );
    }

    void beginRound() {
        fullAssaultTeamIds.clear();
        fullAssaultRefreshTimer = 0f;
    }

    void update() {
        if (!teamManager.isRoundActiveForSystems()) {
            fullAssaultRefreshTimer = 0f;
            return;
        }

        fullAssaultRefreshTimer += Time.delta;

        if (fullAssaultRefreshTimer < FULL_ASSAULT_REFRESH_INTERVAL_TICKS) {
            return;
        }

        fullAssaultRefreshTimer %= FULL_ASSAULT_REFRESH_INTERVAL_TICKS;

        /**
         * Full assault is a team mode, not a global server mode and not a
         * per-player unit mode. Every active team updates only its own units.
         *
         * Copy the IDs before iterating so a command toggle during an update
         * cannot modify the set currently being traversed.
         */
        for (int teamId : new HashSet<>(fullAssaultTeamIds)) {
            updateFullAssaultForTeam(Team.get(teamId));
        }
    }

    private void toggleFullAssault(Player player) {
        if (player == null) {
            return;
        }

        int teamId = player.team().id;

        if (fullAssaultTeamIds.remove(teamId)) {
            player.sendMessage("[accent]Full assault: [red]INACTIVE[]");
            return;
        }

        fullAssaultTeamIds.add(teamId);
        player.sendMessage("[accent]Full assault: [green]ACTIVE[]");

        /**
         * Apply immediately as well as during the recurring refresh so the
         * command feels responsive when toggled.
         */
        updateFullAssaultForTeam(player.team());
    }

    private void updateFullAssaultForTeam(Team team) {
        Groups.unit.each(unit -> {
            if (!eligibleForFullAssault(unit, team)) {
                return;
            }

            CommandAI commandAI = (CommandAI)unit.controller();
            UnitCommand currentCommand = commandAI.currentCommand();

            if (ignoredCommand(currentCommand)) {
                return;
            }

            CoreBuild targetCore = teamManager.closestEnemyCore(unit);

            if (targetCore == null) {
                return;
            }

            if (
                currentCommand == UnitCommand.moveCommand
                    && commandAI.attackTarget == targetCore
            ) {
                return;
            }

            commandAI.command(UnitCommand.moveCommand);
            commandAI.clearCommands();
            commandAI.attackTarget = targetCore;
        });
    }

    private boolean eligibleForFullAssault(Unit unit, Team team) {
        return unit != null
            && unit.isAdded()
            && unit.team == team
            && !unit.spawnedByCore
            && !unit.isPlayer()
            && unit.type.canAttack
            && unit.type.hasWeapons()
            && unit.controller() instanceof CommandAI;
    }

    private boolean ignoredCommand(UnitCommand command) {
        return command == UnitCommand.mineCommand
            || command == UnitCommand.assistCommand
            || command == UnitCommand.rebuildCommand
            || command == UnitCommand.repairCommand;
    }
}
