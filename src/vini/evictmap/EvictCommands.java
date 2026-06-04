package vini.evictmap;

import arc.util.CommandHandler;
import arc.util.Time;
import mindustry.ai.UnitCommand;
import mindustry.ai.types.CommandAI;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

import java.util.HashSet;
import java.util.Set;

/**
 * In-game player commands for the Evict server.
 *
 * Kept separate from the generator so additional commands can be added later
 * without turning EvictMapPlugin into a command monolith.
 */
final class EvictCommands {

    private static final float FULL_ASSAULT_REFRESH_INTERVAL_TICKS = 60f;

    private final TeamManager teamManager;
    private final Set<String> fullAssaultPlayerUuids = new HashSet<>();

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
        fullAssaultPlayerUuids.clear();
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

        Groups.player.each(player -> {
            if (
                player != null
                    && fullAssaultPlayerUuids.contains(player.uuid())
            ) {
                updateFullAssaultFor(player);
            }
        });
    }

    private void toggleFullAssault(Player player) {
        if (player == null) {
            return;
        }

        String uuid = player.uuid();

        if (fullAssaultPlayerUuids.remove(uuid)) {
            player.sendMessage("[accent]Full assault: [red]INACTIVE[]");
            return;
        }

        fullAssaultPlayerUuids.add(uuid);
        player.sendMessage("[accent]Full assault: [green]ACTIVE[]");

        /**
         * Apply immediately as well as during the recurring refresh so the
         * command feels responsive when toggled.
         */
        updateFullAssaultFor(player);
    }

    private void updateFullAssaultFor(Player player) {
        Groups.unit.each(unit -> {
            if (!eligibleForFullAssault(unit, player)) {
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

    private boolean eligibleForFullAssault(Unit unit, Player player) {
        return unit != null
            && unit.isAdded()
            && unit.team == player.team()
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
