// Full assault: the team-scoped /fullassault toggle (alias /fa) that sends idle combat units at the nearest enemy core.
package Extinction;

import Extinction.round.TeamManager;

import mindustry.ai.UnitCommand;
import mindustry.ai.types.CommandAI;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class FullAssault {

    // Seconds between two pulses that move units.
    private static final float INTERVAL_SECONDS = 5f;

    private final TeamManager teamManager;
    private final Set<Integer> enabledTeamIds = new HashSet<>();
    private long lastPulseMillis = 0;

    public FullAssault(TeamManager teamManager) {
        this.teamManager = teamManager;
    }

    public void beginRound() {
        enabledTeamIds.clear();
        lastPulseMillis = 0;
    }

    public void update() {
        if (!teamManager.isRoundActiveForSystems()) return;

        // Pulses land slightly later than every 5 s; close enough.
        float elapsedSeconds = (teamManager.roundRuntimeMillis() - lastPulseMillis) / 1000f;
        if (elapsedSeconds < INTERVAL_SECONDS) return;
        lastPulseMillis = teamManager.roundRuntimeMillis();

        if (enabledTeamIds.isEmpty()) return;

        List<CoreBuild> coreSnapshot = teamManager.snapshotSlotCores();
        Groups.unit.each(unit -> {
            if (enabledTeamIds.contains(unit.team.id)) {
                attackWithUnit(unit, coreSnapshot);
            }
        });
    }

    // /fullassault: flips the toggle for the sender's team.
    public void toggle(String[] args, Player player) {
        if (args.length != 0) {
            player.sendMessage("Too many arguments. Usage: /fullassault");
            return;
        }

        if (enabledTeamIds.remove(player.team().id)) {
            player.sendMessage("Full assault disabled.");
            return;
        }

        enabledTeamIds.add(player.team().id);
        player.sendMessage("Full assault enabled.");
    }

    // Only unattended, command-able combat units on plain move are sent; player-controlled and busy ones stay.
    private void attackWithUnit(Unit unit, List<CoreBuild> coreSnapshot) {
        if (unit == null || !unit.isAdded() || unit.spawnedByCore || unit.isPlayer() || !unit.type.canAttack
                || !unit.type.hasWeapons() || !(unit.controller() instanceof CommandAI commandAI))
            return;

        UnitCommand currentCommand = commandAI.currentCommand();
        if (currentCommand != UnitCommand.moveCommand) return;

        CoreBuild targetCore = teamManager.closestEnemyCore(unit, coreSnapshot);
        if (targetCore == null) return;

        commandAI.command(UnitCommand.moveCommand);
        commandAI.clearCommands();
        commandAI.attackTarget = targetCore;
    }
}
