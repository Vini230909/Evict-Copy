package vini.evictmap;

import arc.math.Mathf;
import arc.util.CommandHandler;
import arc.util.Time;
import mindustry.Vars;
import mindustry.ai.UnitCommand;
import mindustry.ai.types.CommandAI;
import mindustry.content.Blocks;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.type.UnitType;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

import java.util.HashSet;
import java.util.Set;

/**
 * In-game player commands for the Evict server.
 *
 * /fullassault is toggled separately for each team and can only command that
 * team's own eligible units. It is never a global server-wide assault switch.
 *
 * Development commands are deliberately admin-only.
 *
 * Kept separate from the generator so additional commands can be added later
 * without turning EvictMapPlugin into a command monolith.
 */
final class EvictCommands {

    private static final float FULL_ASSAULT_REFRESH_INTERVAL_TICKS = 5f * 60f;
    private static final int MAX_SPAWNUNIT_AMOUNT = 1000;
    private static final int MAX_CORECAP_INCREMENT = 10000;

    private final TeamManager teamManager;
    private final AttritionManager attritionManager;
    private final EvictSettings settings;
    private final Set<Integer> fullAssaultTeamIds = new HashSet<>();

    private float fullAssaultRefreshTimer = 0f;
    private int extraCoreCapPerCore = 0;

    EvictCommands(
        TeamManager teamManager,
        AttritionManager attritionManager,
        EvictSettings settings
    ) {
        this.teamManager = teamManager;
        this.attritionManager = attritionManager;
        this.settings = settings;
    }

    void registerClientCommands(CommandHandler handler) {
        handler.<Player>register(
            "fullassault",
            "Toggle automatic attacks against the closest enemy core for your team's unattended combat units.",
            (args, player) -> toggleFullAssault(player)
        );

        handler.<Player>register(
            "forceend",
            "Admin only: force-end the current round with your current team as winner.",
            (args, player) -> forceEnd(player)
        );

        handler.<Player>register(
            "attritioncore",
            "[t1-3] [t4] [t5]",
            "Admin only: show or set capture attrition percentages, e.g. /attritioncore 40 18 9.",
            (args, player) -> configureCoreAttrition(args, player)
        );

        handler.<Player>register(
            "attritionrange",
            "[percent]",
            "Admin only: show or set the flat range attrition percentage, e.g. /attritionrange 20.",
            (args, player) -> configureRangeAttrition(args, player)
        );

        handler.<Player>register(
            "wall",
            "[full-wall] [small-wall] [open] [passage]",
            "Admin only: show or set persistent wall-template percentages, e.g. /wall 25 20 15 40.",
            (args, player) -> configureWalls(args, player)
        );

        handler.<Player>register(
            "corecap",
            "<additional-per-core>",
            "Admin only: add unit-cap capacity to every core, e.g. /corecap 10.",
            (args, player) -> addCoreCap(args, player)
        );

        handler.<Player>register(
            "spawnunit",
            "<unit> <amount> [team]",
            "Admin only: spawn test units near you. Team defaults to your current team.",
            (args, player) -> spawnUnits(args, player)
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

    private void forceEnd(Player player) {
        if (!requireAdmin(player)) {
            return;
        }

        if (teamManager.forceEnd(player.team())) {
            player.sendMessage("[green]Round end triggered.[]");
        } else {
            player.sendMessage("[scarlet]No active round can be ended right now.[]");
        }
    }

    private void configureCoreAttrition(String[] args, Player player) {
        if (!requireAdmin(player)) {
            return;
        }

        if (args.length == 0) {
            player.sendMessage(
                "[accent]Core attrition: []"
                    + attritionManager.compactCoreSettings()
            );
            return;
        }

        if (args.length != 3) {
            player.sendMessage(
                "[scarlet]Use: /attritioncore <t1-3> <t4> <t5>[]"
            );
            return;
        }

        try {
            double tier1To3 = Double.parseDouble(args[0]);
            double tier4 = Double.parseDouble(args[1]);
            double tier5 = Double.parseDouble(args[2]);

            attritionManager.setCoreDeathChancesPercent(
                tier1To3,
                tier4,
                tier5
            );

            player.sendMessage(
                "[green]Core attrition saved: []"
                    + attritionManager.compactCoreSettings()
            );
        } catch (NumberFormatException exception) {
            player.sendMessage(
                "[scarlet]Core attrition values must be numbers.[]"
            );
        } catch (IllegalArgumentException exception) {
            player.sendMessage("[scarlet]" + exception.getMessage() + "[]");
        }
    }

    private void configureRangeAttrition(String[] args, Player player) {
        if (!requireAdmin(player)) {
            return;
        }

        if (args.length == 0) {
            player.sendMessage(
                "[accent]Range attrition: []"
                    + attritionManager.compactRangeSettings()
            );
            return;
        }

        if (args.length != 1) {
            player.sendMessage(
                "[scarlet]Use: /attritionrange <percent>[]"
            );
            return;
        }

        try {
            attritionManager.setRangeDeathChancePercent(
                Double.parseDouble(args[0])
            );

            player.sendMessage(
                "[green]Range attrition saved: []"
                    + attritionManager.compactRangeSettings()
            );
        } catch (NumberFormatException exception) {
            player.sendMessage(
                "[scarlet]Range attrition value must be a number.[]"
            );
        } catch (IllegalArgumentException exception) {
            player.sendMessage("[scarlet]" + exception.getMessage() + "[]");
        }
    }

    private void configureWalls(String[] args, Player player) {
        if (!requireAdmin(player)) {
            return;
        }

        if (args.length == 0) {
            player.sendMessage(
                "[accent]Walls: []" + settings.compactWallSettings()
            );
            return;
        }

        if (args.length != 4) {
            player.sendMessage(
                "[scarlet]Use: /wall <full-wall> <small-wall> <open> <passage>[]"
            );
            return;
        }

        try {
            double fullWall = Double.parseDouble(args[0]);
            double smallWall = Double.parseDouble(args[1]);
            double open = Double.parseDouble(args[2]);
            double passage = Double.parseDouble(args[3]);

            settings.setWallPercentages(
                fullWall,
                smallWall,
                open,
                passage
            );

            player.sendMessage(
                "[green]Wall settings saved: []"
                    + settings.compactWallSettings()
                    + "[green]. Applies to the next generated map.[]"
            );
        } catch (NumberFormatException exception) {
            player.sendMessage("[scarlet]Wall values must be numbers.[]");
        } catch (IllegalArgumentException exception) {
            player.sendMessage("[scarlet]" + exception.getMessage() + "[]");
        }
    }

    private void addCoreCap(String[] args, Player player) {
        if (!requireAdmin(player)) {
            return;
        }

        if (args.length != 1) {
            player.sendMessage("[scarlet]Use: /corecap <additional-per-core>[]");
            return;
        }

        final int additional;

        try {
            additional = Integer.parseInt(args[0]);
        } catch (NumberFormatException exception) {
            player.sendMessage("[scarlet]Core-cap increment must be a whole number.[]");
            return;
        }

        if (additional <= 0 || additional > MAX_CORECAP_INCREMENT) {
            player.sendMessage(
                "[scarlet]Core-cap increment must be between 1 and "
                    + MAX_CORECAP_INCREMENT
                    + ".[]"
            );
            return;
        }

        /**
         * Vanilla calculates the final cap from the base rule plus the team's
         * accumulated per-building modifiers. Increase all three vanilla core
         * blocks for future captures and adjust already existing cores once.
         */
        Blocks.coreShard.unitCapModifier += additional;
        Blocks.coreFoundation.unitCapModifier += additional;
        Blocks.coreNucleus.unitCapModifier += additional;

        for (Team team : Team.all) {
            int existingCoreCount = team.data().cores.size;

            if (existingCoreCount > 0) {
                team.data().unitCap += existingCoreCount * additional;
            }
        }

        Vars.state.rules.unitCapVariable = true;
        extraCoreCapPerCore += additional;

        player.sendMessage(
            "[green]Added "
                + additional
                + " unit cap per core. Total added bonus per core: "
                + extraCoreCapPerCore
                + ".[]"
        );
    }

    private void spawnUnits(String[] args, Player player) {
        if (!requireAdmin(player)) {
            return;
        }

        if (args.length < 2 || args.length > 3) {
            player.sendMessage("[scarlet]Use: /spawnunit <unit> <amount> [team][]");
            return;
        }

        UnitType unitType = Vars.content.units().find(
            type -> type.name.equalsIgnoreCase(args[0])
        );

        if (unitType == null) {
            player.sendMessage("[scarlet]Unknown unit: " + args[0] + "[]");
            return;
        }

        final int amount;

        try {
            amount = Integer.parseInt(args[1]);
        } catch (NumberFormatException exception) {
            player.sendMessage("[scarlet]Unit amount must be a whole number.[]");
            return;
        }

        if (amount <= 0 || amount > MAX_SPAWNUNIT_AMOUNT) {
            player.sendMessage(
                "[scarlet]Unit amount must be between 1 and "
                    + MAX_SPAWNUNIT_AMOUNT
                    + ".[]"
            );
            return;
        }

        Team targetTeam = player.team();

        if (args.length == 3) {
            final int teamId;

            try {
                teamId = Integer.parseInt(args[2]);
            } catch (NumberFormatException exception) {
                player.sendMessage("[scarlet]Team must be a numeric team ID.[]");
                return;
            }

            if (teamId < 0 || teamId > 255) {
                player.sendMessage("[scarlet]Team ID must be between 0 and 255.[]");
                return;
            }

            targetTeam = Team.get(teamId);
        }

        for (int index = 0; index < amount; index++) {
            unitType.spawn(
                targetTeam,
                player.x + Mathf.range(80f),
                player.y + Mathf.range(80f)
            );
        }

        player.sendMessage(
            "[green]Spawned "
                + amount
                + " "
                + unitType.name
                + " for team #"
                + targetTeam.id
                + ".[]"
        );
    }

    private boolean requireAdmin(Player player) {
        if (player != null && player.admin) {
            return true;
        }

        if (player != null) {
            player.sendMessage("[scarlet]Admin only.[]");
        }

        return false;
    }
}
