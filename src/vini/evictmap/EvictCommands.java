package vini.evictmap;

import arc.math.Mathf;
import arc.util.CommandHandler;
import arc.util.Time;
import mindustry.Vars;
import mindustry.ai.UnitCommand;
import mindustry.ai.types.CommandAI;
import mindustry.content.Blocks;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.type.UnitType;
import mindustry.ui.Menus;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    private static final int INFO_MENU_COLUMNS = 2;

    private final TeamManager teamManager;
    private final AttritionManager attritionManager;
    private final ExtinctionManager extinctionManager;
    private final EvictSettings settings;
    private final PlayerDataManager playerDataManager;
    private final int playerInfoMenuId;
    private final Set<Integer> fullAssaultTeamIds = new HashSet<>();
    private final Map<String, List<String>> infoMenuTargetsByAdminUuid =
        new HashMap<>();

    private float fullAssaultRefreshTimer = 0f;
    private int extraCoreCapPerCore = 0;

    EvictCommands(
        TeamManager teamManager,
        AttritionManager attritionManager,
        ExtinctionManager extinctionManager,
        EvictSettings settings,
        PlayerDataManager playerDataManager
    ) {
        this.teamManager = teamManager;
        this.attritionManager = attritionManager;
        this.extinctionManager = extinctionManager;
        this.settings = settings;
        this.playerDataManager = playerDataManager;
        this.playerInfoMenuId =
            Menus.registerMenu(this::handleInfoMenuSelection);
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
            "extinction",
            "Admin only: start EXTINCTION immediately for testing or an early event.",
            (args, player) -> forceExtinction(args, player)
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

        handler.<Player>register(
            "info",
            "[online-player]",
            "Admin only: show stored stats for one online player.",
            (args, player) -> showOnlinePlayerInfo(args, player)
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

    private void forceExtinction(String[] args, Player player) {
        if (!requireAdmin(player)) {
            return;
        }

        if (args.length != 0) {
            player.sendMessage("[scarlet]Use: /extinction[]");
            return;
        }

        if (extinctionManager.forceStart()) {
            player.sendMessage("[green]EXTINCTION started immediately.[]");
        } else {
            player.sendMessage(
                "[scarlet]EXTINCTION cannot start right now. "
                    + "The round must be active, at least one personal team "
                    + "must exist, and EXTINCTION must not already be active.[]"
            );
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

    private void showOnlinePlayerInfo(String[] args, Player player) {
        if (!requireAdmin(player)) {
            return;
        }

        if (args.length == 0) {
            showPlayerInfoSelectionMenu(player);
            return;
        }

        InfoTargetRequest request = parseInfoTarget(args);

        if (request.nameQuery().isEmpty()) {
            player.sendMessage("[scarlet]Use: /info <online-player> [team][]");
            return;
        }

        List<Player> matches =
            onlinePlayersMatching(request.nameQuery(), request.teamId());

        if (matches.isEmpty()) {
            player.sendMessage(
                "[scarlet]No online player matches '"
                    + request.nameQuery()
                    + "'.[]"
            );
            return;
        }

        if (request.matchNumber() != null) {
            int matchIndex = request.matchNumber() - 1;

            if (matchIndex < 0 || matchIndex >= matches.size()) {
                player.sendMessage(
                    "[scarlet]Match number must be between 1 and "
                        + matches.size()
                        + ".[]"
                );
                return;
            }

            showStoredInfoForOnlinePlayer(player, matches.get(matchIndex));
            return;
        }

        if (matches.size() > 1) {
            player.sendMessage(
                "[scarlet]Multiple online players match '"
                    + request.nameQuery()
                    + "': []"
                    + compactOnlinePlayerMatches(matches)
                    + "\n[lightgray]Use: [orange]/info <name> #<number>[]"
                    + " [lightgray]or [orange]/info <name> <team> #<number>[]"
            );
            return;
        }

        showStoredInfoForOnlinePlayer(player, matches.get(0));
    }

    private void showPlayerInfoSelectionMenu(Player player) {
        List<Player> players = onlinePlayers();

        if (players.isEmpty()) {
            player.sendMessage("[scarlet]No online players to select.[]");
            return;
        }

        List<String> targetUuids = new ArrayList<>();
        List<String[]> rows = new ArrayList<>();
        List<String> currentRow = new ArrayList<>();

        for (Player target : players) {
            targetUuids.add(target.uuid());
            currentRow.add(target.plainName());

            if (currentRow.size() == INFO_MENU_COLUMNS) {
                rows.add(currentRow.toArray(new String[0]));
                currentRow.clear();
            }
        }

        if (!currentRow.isEmpty()) {
            rows.add(currentRow.toArray(new String[0]));
        }

        rows.add(new String[] {"[red]Cancel"});
        infoMenuTargetsByAdminUuid.put(player.uuid(), targetUuids);

        Call.menu(
            player.con,
            playerInfoMenuId,
            "[accent]Select a player",
            "Select a player for the argument\n\"target\"",
            rows.toArray(new String[0][])
        );
    }

    private List<Player> onlinePlayers() {
        List<Player> players = new ArrayList<>();

        Groups.player.each(player -> {
            if (player != null) {
                players.add(player);
            }
        });

        players.sort(
            Comparator.comparing(
                Player::plainName,
                String.CASE_INSENSITIVE_ORDER
            )
        );

        return players;
    }

    private void handleInfoMenuSelection(Player player, int option) {
        if (player == null || !player.admin) {
            return;
        }

        List<String> targetUuids = infoMenuTargetsByAdminUuid.remove(
            player.uuid()
        );

        if (
            targetUuids == null
                || option < 0
                || option >= targetUuids.size()
        ) {
            return;
        }

        Player target = Groups.player.find(
            onlinePlayer -> onlinePlayer != null
                && onlinePlayer.uuid().equals(targetUuids.get(option))
        );

        if (target == null) {
            player.sendMessage("[scarlet]That player is no longer online.[]");
            return;
        }

        showStoredInfoForOnlinePlayer(player, target);
    }

    private void showStoredInfoForOnlinePlayer(Player player, Player target) {
        playerDataManager.findPlayerInfoByUuid(
            target.uuid(),
            info -> {
                if (info == null) {
                    player.sendMessage(
                        "[scarlet]No stored player data for "
                            + target.plainName()
                            + " yet.[]"
                    );
                    return;
                }

                player.sendMessage(formatPlayerInfo(info, false));
            }
        );
    }

    private InfoTargetRequest parseInfoTarget(String[] args) {
        Integer teamId = null;
        Integer matchNumber = null;
        int nameEnd = args.length;

        if (args.length > 1 && args[args.length - 1].startsWith("#")) {
            try {
                matchNumber = Integer.parseInt(
                    args[args.length - 1].substring(1)
                );
                nameEnd = args.length - 1;
            } catch (NumberFormatException ignored) {
                // Last word is part of the player name.
            }
        }

        if (args.length > 1) {
            try {
                teamId = Integer.parseInt(args[nameEnd - 1]);
                nameEnd--;
            } catch (NumberFormatException ignored) {
                // Last word is part of the player name.
            }
        }

        StringBuilder name = new StringBuilder();

        for (int index = 0; index < nameEnd; index++) {
            if (index > 0) {
                name.append(" ");
            }

            name.append(args[index]);
        }

        return new InfoTargetRequest(name.toString().trim(), teamId, matchNumber);
    }

    private List<Player> onlinePlayersMatching(
        String query,
        Integer teamId
    ) {
        List<Player> matches = new ArrayList<>();
        List<Player> exactMatches = new ArrayList<>();
        String normalizedQuery = query.toLowerCase();

        Groups.player.each(player -> {
            if (player == null) {
                return;
            }

            if (teamId != null && player.team().id != teamId) {
                return;
            }

            String normalizedName = player.plainName().toLowerCase();

            if (normalizedName.equals(normalizedQuery)) {
                exactMatches.add(player);
            }

            if (normalizedName.contains(normalizedQuery)) {
                matches.add(player);
            }
        });

        if (exactMatches.size() == 1) {
            return exactMatches;
        }

        return matches;
    }

    private String compactOnlinePlayerMatches(List<Player> matches) {
        StringBuilder result = new StringBuilder();

        for (int index = 0; index < matches.size(); index++) {
            if (index > 0) {
                result.append(", ");
            }

            Player player = matches.get(index);

            result.append("[")
                .append(index + 1)
                .append("] ")
                .append(player.plainName())
                .append(" team #")
                .append(player.team().id);
        }

        return result.toString();
    }

    static String formatPlayerInfo(
        PlayerDataManager.PlayerInfo info,
        boolean includeUuid
    ) {
        StringBuilder message = new StringBuilder();

        message.append("[accent]Player: [white]")
            .append(info.lastName())
            .append("[]");

        if (!info.knownNames().isEmpty()) {
            message.append("\n[accent]Known names: [white]")
                .append(String.join(", ", info.knownNames()))
                .append("[]");
        }

        if (includeUuid) {
            message.append("\n[accent]UUID: [white]")
                .append(info.uuid())
                .append("[]");
        }

        message.append("\n[accent]Total playtime: [white]")
            .append(formatDuration(info.totalPlaytimeMillis()))
            .append("[]\n[accent]FFA playtime: [white]")
            .append(formatDuration(info.ffaPlaytimeMillis()))
            .append("[]\n[accent]FFA: [white]")
            .append(info.ffaWon())
            .append(" wins / ")
            .append(info.ffaPlayed())
            .append(" played[]")
            .append("\n[accent]Ranked: [white]")
            .append(info.rankedWins())
            .append(" wins / ")
            .append(info.rankedLosses())
            .append(" losses / ")
            .append(info.rankedMatchesPlayed())
            .append(" played[]")
            .append("\n[accent]ELO: [white]")
            .append(info.elo())
            .append(" current / ")
            .append(info.peakElo())
            .append(" peak[]");

        return message.toString();
    }

    static String formatDuration(long durationMillis) {
        long totalSeconds = Math.max(0L, durationMillis / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;

        StringBuilder result = new StringBuilder();

        if (hours > 0L) {
            result.append(hours).append("h ");
        }

        if (hours > 0L || minutes > 0L) {
            result.append(minutes).append("m ");
        }

        result.append(seconds).append("s");
        return result.toString();
    }

    private record InfoTargetRequest(
        String nameQuery,
        Integer teamId,
        Integer matchNumber
    ) {
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
