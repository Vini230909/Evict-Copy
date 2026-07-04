package vini.evictmap.commands;

import vini.evictmap.*;

import arc.util.CommandHandler;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.ui.Menus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * /info: view a player's stored stats and playtime. Available to every player.
 * With no argument it opens a picker of the online players; with a name it
 * searches the stored players by name. UUIDs are never shown - unlike the old
 * admin-only /info, this is public, so it exposes stats but not identifiers.
 */
public final class InfoCommands {

    private static final int PICKER_MENU_COLUMNS = 2;

    /**
     * Caps how many matching names /info spells out when a name search returns
     * more than one stored player, before asking the caller to be specific.
     */
    private static final int MAX_LISTED_MATCHES = 8;

    private final PlayerDataManager playerDataManager;
    private final int pickerMenuId;

    /**
     * Viewer UUID -> ordered online player UUIDs shown in their picker.
     */
    private final Map<String, List<String>> pickerTargetsByViewerUuid =
            new HashMap<>();

    public InfoCommands(PlayerDataManager playerDataManager) {
        this.playerDataManager = playerDataManager;
        this.pickerMenuId = Menus.registerMenu(this::handlePicker);
    }

    public void registerClientCommands(CommandHandler handler) {
        handler.<Player>register(
                "info",
                "[player...]",
                "View a player's stats and playtime. No name opens a picker.",
                this::handleInfo
        );
    }

    public void handlePlayerLeave(Player player) {
        if (player != null) {
            pickerTargetsByViewerUuid.remove(player.uuid());
        }
    }

    private void handleInfo(String[] args, Player player) {
        if (player == null) {
            return;
        }

        String query = String.join(" ", args).trim();

        if (query.isEmpty()) {
            openPicker(player);
            return;
        }

        playerDataManager.searchPlayerInfo(query, matches -> {
            if (matches.isEmpty()) {
                player.sendMessage(
                        "[scarlet]No player matches '" + query + "'.[]"
                );
                return;
            }

            if (matches.size() == 1) {
                player.sendMessage(formatPlayerInfo(matches.get(0)));
                return;
            }

            player.sendMessage(multipleMatchesMessage(query, matches));
        });
    }

    private void openPicker(Player player) {
        List<Player> players = onlinePlayers();

        if (players.isEmpty()) {
            player.sendMessage("[scarlet]No players are online.[]");
            return;
        }

        List<String> targetUuids = new ArrayList<>();
        List<String[]> rows = new ArrayList<>();
        List<String> currentRow = new ArrayList<>();

        for (Player target : players) {
            targetUuids.add(target.uuid());
            currentRow.add(PlayerNameFormatter.displayName(target));

            if (currentRow.size() == PICKER_MENU_COLUMNS) {
                rows.add(currentRow.toArray(new String[0]));
                currentRow.clear();
            }
        }

        if (!currentRow.isEmpty()) {
            rows.add(currentRow.toArray(new String[0]));
        }

        rows.add(new String[]{"[red]Cancel"});
        pickerTargetsByViewerUuid.put(player.uuid(), targetUuids);

        Call.menu(
                player.con,
                pickerMenuId,
                "[accent]Player info",
                "Select a player to view their stats.",
                rows.toArray(new String[0][])
        );
    }

    private void handlePicker(Player player, int option) {
        if (player == null) {
            return;
        }

        List<String> targetUuids =
                pickerTargetsByViewerUuid.remove(player.uuid());

        if (
                targetUuids == null
                        || option < 0
                        || option >= targetUuids.size()
        ) {
            return;
        }

        String subjectUuid = targetUuids.get(option);
        Player subject = Groups.player.find(
                online -> online != null && online.uuid().equals(subjectUuid)
        );
        String subjectName = subject != null
                ? PlayerNameFormatter.displayName(subject)
                : subjectUuid;

        playerDataManager.findPlayerInfoByUuid(subjectUuid, info -> {
            if (info == null) {
                player.sendMessage(
                        "[scarlet]No stored data for " + subjectName + " yet.[]"
                );
                return;
            }

            player.sendMessage(formatPlayerInfo(info));
        });
    }

    private static String multipleMatchesMessage(
            String query,
            List<PlayerDataManager.PlayerInfo> matches
    ) {
        StringBuilder message = new StringBuilder(
                "[scarlet]Multiple players match '" + query + "': []"
        );

        int shown = Math.min(matches.size(), MAX_LISTED_MATCHES);

        for (int index = 0; index < shown; index++) {
            if (index > 0) {
                message.append("[lightgray], []");
            }

            message.append("[white]")
                    .append(matches.get(index).lastName())
                    .append("[]");
        }

        if (matches.size() > shown) {
            message.append("[lightgray] (+")
                    .append(matches.size() - shown)
                    .append(" more)[]");
        }

        message.append(
                "\n[lightgray]Use [orange]/info <exact name>[] to pick one.[]"
        );

        return message.toString();
    }

    private static String formatPlayerInfo(PlayerDataManager.PlayerInfo info) {
        StringBuilder message = new StringBuilder();

        message.append("[accent]Player: [white]")
                .append(info.lastName())
                .append("[]");

        if (!info.knownNames().isEmpty()) {
            message.append("\n[accent]Known names: [white]")
                    .append(String.join(", ", info.knownNames()))
                    .append("[]");
        }

        message.append("\n[accent]Total playtime: [white]")
                .append(ConsoleCommands.formatDuration(info.totalPlaytimeMillis()))
                .append("[]\n[accent]FFA playtime: [white]")
                .append(ConsoleCommands.formatDuration(info.ffaPlaytimeMillis()))
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
}
