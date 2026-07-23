package vini.evictmap.commands;

import vini.evictmap.*;
import vini.evictmap.gen.*;
import vini.evictmap.data.*;
import vini.evictmap.round.*;

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
 * searches the stored players by name. UUIDs are never shown to normal players
 * - unlike the old admin-only /info, this is public, so it exposes stats but
 * not identifiers. A server admin additionally sees the subject's UUID (never
 * the IP - that stays console-only via evictplayerinfo).
 */
public final class InfoCommands {

    private static final int PICKER_MENU_COLUMNS = 2;

    /**
     * Caps how many matching players a name search offers in the picker before
     * asking the caller to refine; the rest stay reachable by a tighter name.
     */
    private static final int MAX_PICKER_MATCHES = 20;

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
                player.sendMessage(
                        formatPlayerInfo(matches.get(0), player.admin)
                );
                return;
            }

            // More than one stored player matched: same picker as the no-arg
            // /info, but built from the search results so the caller taps the
            // one they meant instead of retyping an exact name.
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
            labels.add(PlayerNameFormatter.displayName(target));
        }

        showPickerMenu(
                player,
                targetUuids,
                labels,
                "Select a player to view their stats."
        );
    }

    /**
     * A name search that hit more than one stored player: offer them in the
     * same picker as the no-arg /info so the caller taps the right one. Capped
     * at {@link #MAX_PICKER_MATCHES} (newest-seen first); a tighter name reaches
     * anyone past the cap.
     */
    private void openMatchesPicker(
            Player viewer,
            String query,
            List<PlayerDataManager.PlayerInfo> matches
    ) {
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

    /**
     * Renders the shared picker: {@code labels} laid out in rows plus a Cancel
     * button, with {@code targetUuids} (index-aligned to labels) remembered so
     * {@link #handlePicker} can resolve the tapped option to a player.
     */
    private void showPickerMenu(
            Player viewer,
            List<String> targetUuids,
            List<String> labels,
            String description
    ) {
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

            player.sendMessage(formatPlayerInfo(info, player.admin));
        });
    }

    /**
     * @param showUuid whether the viewer is a server admin; only then is the
     *                 subject's UUID included (IPs are never shown here - the
     *                 console's evictplayerinfo is the only place for those).
     */
    private static String formatPlayerInfo(
            PlayerDataManager.PlayerInfo info,
            boolean showUuid
    ) {
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
                .append(ConsoleCommands.formatDuration(info.totalPlaytimeMillis()))
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
