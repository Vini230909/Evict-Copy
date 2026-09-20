// /spectate (/s): on the hub the list of matches to watch; on a worker, hop to another or go back.
package Extinction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import mindustry.gen.Call;
import mindustry.gen.Player;
import mindustry.ui.Menus;

public final class SpectateMenu {

    // Sentinel in a worker-side menu's port list for the "Return to the lobby" row; never a real port.
    private static final int RETURN_TO_LOBBY_PORT = -1;

    private final Matches matches;
    private final Referee referee;
    private final int viewMenuId;

    // Viewer UUID -> ordered match ports shown in their menu.
    private final Map<String, List<Integer>> viewTargetsByViewerUuid = new HashMap<>();

    public SpectateMenu(Matches matches, Referee referee) {
        this.matches = matches;
        this.referee = referee;
        this.viewMenuId = Menus.registerMenu(this::handleViewSelection);
    }

    public void handlePlayerLeave(Player player) {
        if (player != null) {
            viewTargetsByViewerUuid.remove(player.uuid());
        }
    }

    // On a worker /s returns a spectator to the lobby (and refuses participants); on the hub it
    // opens the menu of matches to spectate.
    public void handleViewCommand(Player player) {
        if (player == null) {
            return;
        }

        if (referee.isActive()) {
            if (referee.isParticipant(player.uuid())) {
                // A sandbox guest may leave with /s (the owner is told to use /die).
                if (referee.sandbox.handleLeave(player)) {
                    return;
                }

                player.sendMessage(
                        "[scarlet]You are in this match; you cannot leave it with /s.[]"
                );
                return;
            }

            openWorkerViewMenu(player);
            return;
        }

        openViewMenu(player);
    }

    // The lobby row is stored as RETURN_TO_LOBBY_PORT so the selection can tell it from a port.
    private void openWorkerViewMenu(Player player) {
        List<Referee.SiblingMatch> siblings =
                referee.exit.listOtherOngoingMatches();

        List<Integer> targetPorts = new ArrayList<>();
        List<String[]> rows = new ArrayList<>();

        for (Referee.SiblingMatch match : siblings) {
            targetPorts.add(match.port());
            rows.add(new String[]{match.label()});
        }

        targetPorts.add(RETURN_TO_LOBBY_PORT);
        rows.add(new String[]{"[green]Return to the lobby"});
        rows.add(new String[]{"[red]Cancel"});

        viewTargetsByViewerUuid.put(player.uuid(), targetPorts);

        Call.menu(
                player.con,
                viewMenuId,
                "[accent]Spectate",
                siblings.isEmpty()
                        ? "No other matches are running right now."
                        : "Select another match to watch, or return to the lobby.",
                rows.toArray(new String[0][])
        );
    }

    private void openViewMenu(Player player) {
        if (!matches.isConfigured()) {
            player.sendMessage(
                    "[scarlet]The match server is not set up yet. Ask an admin.[]"
            );
            return;
        }

        List<Matches.ActiveDuel> duels = matches.activeDuels();

        if (duels.isEmpty()) {
            player.sendMessage("[scarlet]No matches are in progress.[]");
            return;
        }

        List<Integer> targetPorts = new ArrayList<>();
        List<String[]> rows = new ArrayList<>();

        for (Matches.ActiveDuel duel : duels) {
            targetPorts.add(duel.port());
            rows.add(new String[]{duel.label()});
        }

        rows.add(new String[]{"[red]Cancel"});
        viewTargetsByViewerUuid.put(player.uuid(), targetPorts);

        Call.menu(
                player.con,
                viewMenuId,
                "[accent]Spectate a match",
                "Select a match to watch. Use /s again to return to the lobby.",
                rows.toArray(new String[0][])
        );
    }

    private void handleViewSelection(Player player, int option) {
        if (player == null) {
            return;
        }

        List<Integer> targetPorts =
                viewTargetsByViewerUuid.remove(player.uuid());

        if (
                targetPorts == null
                        || option < 0
                        || option >= targetPorts.size()
        ) {
            return;
        }

        int port = targetPorts.get(option);

        if (referee.isActive()) {
            // Re-check: they may have been promoted into the sandbox while the menu was up.
            if (referee.isParticipant(player.uuid())) {
                return;
            }

            if (port == RETURN_TO_LOBBY_PORT) {
                referee.exit.returnSpectatorToHub(player);
            } else if (!referee.exit.connectSpectatorToSibling(player, port)) {
                player.sendMessage(
                        "[scarlet]That match is no longer available.[]"
                );
            }

            return;
        }

        if (!matches.viewDuel(player, port)) {
            player.sendMessage(
                    "[scarlet]That match is no longer available.[]"
            );
        }
    }
}
