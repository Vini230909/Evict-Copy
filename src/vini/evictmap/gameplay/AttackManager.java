package vini.evictmap.gameplay;

import arc.util.CommandHandler;
import mindustry.ai.UnitCommand;
import mindustry.ai.types.CommandAI;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;
import vini.evictmap.TeamManager;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Implements the team-scoped /fullassault toggle (alias /fa).
 */
public final class AttackManager implements GameplayManagerInterface {
    /**
     * Interval (in seconds) between when /fullassault should move units.
     */
    private static final float FULL_ASSAULT_INTERVAL_SECONDS = 5f;

    private final TeamManager teamManager;
    /**
     * Which teams currently have /fullassault toggle on.
     */
    private final Set<Integer> fullAssaultTeamIds = new HashSet<>();
    /**
     * When /fullassault last moved units.
     */
    private long lastFullassaultMillis = 0;

    /**
     * Constructor
     *
     * @param teamManager {@link TeamManager} to be used for future operations.
     */
    public AttackManager(TeamManager teamManager) {
        this.teamManager = teamManager;
    }

    /**
     * Register commands to the handler (/fullassault and its /fa alias).
     *
     * @param handler Where to register the commands to.
     */
    public void registerClientCommands(CommandHandler handler) {
        handler.register(
                "fullassault",
                "Send your team's idle combat units at the nearest enemy core every 5 seconds.",
                this::handleFullassaultCommand
        );

        handler.register(
                "fa",
                "Alias for /fullassault.",
                this::handleFullassaultCommand
        );
    }

    @Override
    public void beginRound() {
        fullAssaultTeamIds.clear();
        lastFullassaultMillis = 0;
    }

    @Override
    public void update() {
        if (!teamManager.isRoundActiveForSystems()) return;

        /*
         * This isn't quite right; there will be a slightly larger interval than 5000 ms between
         * assault pulses when fullassault is enabled. But it is close enough for now.
         */
        float elapsedSeconds = (teamManager.roundRuntimeMillis() - lastFullassaultMillis) / 1000f;
        if (elapsedSeconds < FULL_ASSAULT_INTERVAL_SECONDS) return;
        lastFullassaultMillis = teamManager.roundRuntimeMillis();

        if (fullAssaultTeamIds.isEmpty()) return;

        List<CoreBuild> coreSnapshot = teamManager.snapshotSlotCores();
        Groups.unit.each(unit -> {
            if (fullAssaultTeamIds.contains(unit.team.id)) {
                attackWithUnit(unit, coreSnapshot);
            }
        });
    }

    @Override
    public void endRound() {
    }

    /**
     * {@code /fullassault} command handler.
     */
    private void handleFullassaultCommand(String[] args, Player player) {
        if (args.length != 0) {
            player.sendMessage("Too many arguments. Usage: /fullassault");
            return;
        }

        if (fullAssaultTeamIds.remove(player.team().id)) {
            player.sendMessage("Full assault disabled.");
            return;
        }

        fullAssaultTeamIds.add(player.team().id);
        player.sendMessage("Full assault enabled.");
    }

    /**
     * Helper function to attack with a specified unit.
     *
     * @param unit         Unit to attack with
     * @param coreSnapshot Core snapshot, used to determine which cores are close to which units.
     */
    private void attackWithUnit(Unit unit, List<CoreBuild> coreSnapshot) {
        // Test if it is ever ok to attack with this unit
        if (unit == null || !unit.isAdded() || unit.spawnedByCore || unit.isPlayer() || !unit.type.canAttack
                || !unit.type.hasWeapons() || !(unit.controller() instanceof CommandAI commandAI))
            return;

        // Determine if the unit is on a move command (return otherwise, we don't want to attack with it if not)
        UnitCommand currentCommand = commandAI.currentCommand();
        if (currentCommand != UnitCommand.moveCommand) return;

        // Find target core
        CoreBuild targetCore = teamManager.closestEnemyCore(unit, coreSnapshot);
        if (targetCore == null) return;

        // And then attack
        commandAI.command(UnitCommand.moveCommand);
        commandAI.clearCommands();
        commandAI.attackTarget = targetCore;
    }
}
