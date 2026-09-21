// A stored player's stats and playtime: /info for players, playerinfo and elo for the console.
package Extinction;

import Extinction.core.util.PluginLog;
import Extinction.data.PlayerDataManager;

import mindustry.Vars;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.ui.Menus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PlayerStats {

    private static final int PICKER_MENU_COLUMNS = 2;

    // A name search offers at most this many matches in the picker; a tighter name reaches the rest.
    private static final int MAX_PICKER_MATCHES = 20;

    private final PlayerDataManager playerData;
    private final int pickerMenuId;

    // Viewer UUID -> ordered player UUIDs shown in their picker.
    private final Map<String, List<String>> pickerTargetsByViewerUuid = new HashMap<>();

    public PlayerStats(PlayerDataManager playerData) {
        this.playerData = playerData;
        this.pickerMenuId = Menus.registerMenu(this::handlePicker);
    }

    public void handlePlayerLeave(Player player) {
        if (player != null) {
            pickerTargetsByViewerUuid.remove(player.uuid());
        }
    }

    // /info [name]: no name opens a picker of the online players; a name searches the stored players.
    public void show(Player player, String query) {
        if (player == null) {
            return;
        }

        if (query.isEmpty()) {
            openPicker(player);
            return;
        }

        playerData.searchPlayerInfo(query, matches -> {
            if (matches.isEmpty()) {
                player.sendMessage("[scarlet]No player matches '" + query + "'.[]");
                return;
            }

            if (matches.size() == 1) {
                player.sendMessage(format(matches.get(0), player.admin));
                return;
            }

            openMatchesPicker(player, query, matches);
        });
    }

    private void openPicker(Player player) {
        List<Player> players = onlinePlayers();

        if (players.isEmpty()) {
            player.sendMessage("[scarlet]No players are online.[]");
            return;
        }

        List<String> targetUuids = new ArrayList<>();
        List<String> labels = new ArrayList<>();

        for (Player target : players) {
            targetUuids.add(target.uuid());
            labels.add(PlayerNames.displayName(target));
        }

        showPickerMenu(player, targetUuids, labels, "Select a player to view their stats.");
    }

    // Several stored players matched a name: the same picker, built from the matches (newest-seen first).
    private void openMatchesPicker(Player viewer, String query, List<PlayerDataManager.PlayerInfo> matches) {
        int shown = Math.min(matches.size(), MAX_PICKER_MATCHES);

        List<String> targetUuids = new ArrayList<>();
        List<String> labels = new ArrayList<>();

        for (int index = 0; index < shown; index++) {
            targetUuids.add(matches.get(index).uuid());
            labels.add(matches.get(index).lastName());
        }

        String description = matches.size() > shown
                ? "[lightgray]" + matches.size() + " players match '" + query
                        + "'. Showing the " + shown
                        + " most recent - refine the name for the rest.[]"
                : "Select a player to view their stats.";

        showPickerMenu(viewer, targetUuids, labels, description);
    }

    // Labels laid out in rows plus Cancel; targetUuids (index-aligned) resolve the tapped option.
    private void showPickerMenu(Player viewer, List<String> targetUuids, List<String> labels, String description) {
        List<String[]> rows = new ArrayList<>();
        List<String> currentRow = new ArrayList<>();

        for (String label : labels) {
            currentRow.add(label);

            if (currentRow.size() == PICKER_MENU_COLUMNS) {
                rows.add(currentRow.toArray(new String[0]));
                currentRow.clear();
            }
        }

        if (!currentRow.isEmpty()) {
            rows.add(currentRow.toArray(new String[0]));
        }

        rows.add(new String[]{"[red]Cancel"});
        pickerTargetsByViewerUuid.put(viewer.uuid(), targetUuids);

        Call.menu(
                viewer.con,
                pickerMenuId,
                "[accent]Player info",
                description,
                rows.toArray(new String[0][])
        );
    }

    private void handlePicker(Player player, int option) {
        if (player == null) {
            return;
        }

        List<String> targetUuids = pickerTargetsByViewerUuid.remove(player.uuid());

        if (targetUuids == null || option < 0 || option >= targetUuids.size()) {
            return;
        }

        String subjectUuid = targetUuids.get(option);
        Player subject = Groups.player.find(online -> online != null && online.uuid().equals(subjectUuid));
        String subjectName = subject != null ? PlayerNames.displayName(subject) : subjectUuid;

        playerData.findPlayerInfoByUuid(subjectUuid, info -> {
            if (info == null) {
                player.sendMessage("[scarlet]No stored data for " + subjectName + " yet.[]");
                return;
            }

            player.sendMessage(format(info, player.admin));
        });
    }

    // The /info text; only a server admin viewer gets the UUID (IPs stay console-only).
    private static String format(PlayerDataManager.PlayerInfo info, boolean showUuid) {
        StringBuilder message = new StringBuilder();

        message.append("[accent]Player: [white]")
                .append(info.lastName())
                .append("[]");

        if (showUuid) {
            message.append("\n[accent]UUID: [white]")
                    .append(info.uuid())
                    .append("[]");
        }

        if (!info.knownNames().isEmpty()) {
            message.append("\n[accent]Known names: [white]")
                    .append(String.join(", ", info.knownNames()))
                    .append("[]");
        }

        message.append("\n[accent]Total playtime: [white]")
                .append(RoundTime.formatDuration(info.totalPlaytimeMillis()))
                .append("[]")
                .append("\n[accent]Normal: [white]")
                .append(info.normalWins())
                .append(" wins / ")
                .append(info.normalLosses())
                .append(" losses / ")
                .append(info.normalMatchesPlayed())
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

    private static List<Player> onlinePlayers() {
        List<Player> players = new ArrayList<>();

        Groups.player.each(player -> {
            if (player != null) {
                players.add(player);
            }
        });

        players.sort(Comparator.comparing(Player::plainName, String.CASE_INSENSITIVE_ORDER));
        return players;
    }

    // Console 'playerinfo [query]': one match prints everything, several print one compact line each.
    public void log(String query) {
        playerData.searchPlayerInfo(query, matches -> {
            if (matches.isEmpty()) {
                PluginLog.err("No stored players match '@'.", query);
                return;
            }

            if (matches.size() == 1) {
                PluginLog.info("@", plainLine(matches.get(0)));
                return;
            }

            PluginLog.info("Stored player matches (@):", matches.size());
            for (PlayerDataManager.PlayerInfo info : matches) {
                PluginLog.info("@", compactLine(info));
            }
        });
    }

    // Console 'elo <name/uuid> <value>': an ambiguous name lists the candidates and changes nothing.
    public void setElo(String[] args) {
        if (args.length < 2) {
            PluginLog.err("Use: elo <name/uuid> <value>");
            return;
        }

        int newElo;
        try {
            newElo = Integer.parseInt(args[1].trim());
        } catch (NumberFormatException exception) {
            PluginLog.err("ELO must be a whole number.");
            return;
        }

        if (newElo < 0) {
            PluginLog.err("ELO cannot be negative.");
            return;
        }

        String query = args[0].trim();

        playerData.searchPlayerInfo(query, matches -> {
            if (matches.isEmpty()) {
                PluginLog.err("No stored players match '@'.", query);
                return;
            }

            if (matches.size() > 1) {
                PluginLog.err("'@' matches @ players; be more specific or use a UUID:", query, matches.size());
                for (PlayerDataManager.PlayerInfo info : matches) {
                    PluginLog.info("@", compactLine(info));
                }
                return;
            }

            PlayerDataManager.PlayerInfo target = matches.get(0);
            int previousElo = target.elo();

            playerData.setElo(target.uuid(), newElo, updated -> {
                if (updated) {
                    PluginLog.info("Set @'s ELO to @ (was @).", target.lastName(), newElo, previousElo);
                } else {
                    PluginLog.err("Could not update ELO for @.", target.lastName());
                }
            });
        });
    }

    // One console line per candidate when a name is ambiguous (also used by banplayer).
    public static String compactLine(PlayerDataManager.PlayerInfo info) {
        return info.lastName()
                + " | uuid=" + info.uuid()
                + " | names=" + String.join(", ", info.knownNames())
                + " | playtime=" + RoundTime.formatDuration(info.totalPlaytimeMillis());
    }

    private static String plainLine(PlayerDataManager.PlayerInfo info) {
        return info.lastName()
                + " | uuid=" + info.uuid()
                + " | names=" + String.join(", ", info.knownNames())
                + ipInfo(info.uuid())
                + " | totalPlaytime=" + RoundTime.formatDuration(info.totalPlaytimeMillis())
                + " | normalWins=" + info.normalWins()
                + " | normalLosses=" + info.normalLosses()
                + " | normalPlayed=" + info.normalMatchesPlayed()
                + " | rankedWins=" + info.rankedWins()
                + " | rankedLosses=" + info.rankedLosses()
                + " | rankedPlayed=" + info.rankedMatchesPlayed()
                + " | elo=" + info.elo()
                + " | peakElo=" + info.peakElo();
    }

    // Last and all known IPs from Mindustry's admin store, console only, to feed 'ban ip <ip>'.
    private static String ipInfo(String uuid) {
        if (Vars.netServer == null) {
            return "";
        }

        mindustry.net.Administration.PlayerInfo vanilla = Vars.netServer.admins.getInfoOptional(uuid);

        if (vanilla == null) {
            return " | lastIP=never connected here";
        }

        return " | lastIP=" + vanilla.lastIP
                + " | knownIPs=" + vanilla.ips.toString(", ");
    }
}
