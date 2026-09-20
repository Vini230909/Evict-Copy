// 1v1 and Ranked challenges: pick one opponent, they accept or decline, both go to a worker.
package Extinction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import arc.util.Time;
import mindustry.gen.Call;
import mindustry.gen.Player;
import mindustry.ui.Menus;

public final class Challenges {

    private final Matchmaking matchmaking;
    private final Matches matches;

    private final int selectionMenuId;
    private final int challengeMenuId;

    // Challenger UUID -> the mode they chose and the ordered opponents shown in their menu.
    private final Map<String, Selection> selectionByChallengerUuid = new HashMap<>();

    // Opponent UUID -> outstanding challenge against them; the serial tells the expiry task apart.
    private final Map<String, PendingChallenge> challengeByOpponentUuid = new HashMap<>();

    private int pendingSerial = 0;

    public Challenges(Matchmaking matchmaking, Matches matches) {
        this.matchmaking = matchmaking;
        this.matches = matches;
        this.selectionMenuId = Menus.registerMenu(this::handleSelection);
        this.challengeMenuId = Menus.registerMenu(this::handleChallengeResponse);
    }

    void handlePlayerLeave(String uuid) {
        selectionByChallengerUuid.remove(uuid);
        challengeByOpponentUuid.remove(uuid);
        challengeByOpponentUuid.values().removeIf(
                pending -> pending.challengerUuid().equals(uuid)
        );
    }

    // A challenge to answer, or one sent and awaiting an answer.
    boolean isBusy(String uuid) {
        if (challengeByOpponentUuid.containsKey(uuid)) {
            return true;
        }

        for (PendingChallenge pending : challengeByOpponentUuid.values()) {
            if (pending.challengerUuid().equals(uuid)) {
                return true;
            }
        }

        return false;
    }

    void openSelectionMenu(Player player, MatchMode mode) {
        openSelectionMenu(player, mode, null);
    }

    // The same flow for 1v1, Ranked and Pure 1v1; mode (and map) are carried through.
    void openSelectionMenu(Player player, MatchMode mode, String map) {
        List<Player> opponents = matchmaking.otherOnlinePlayers(player);

        if (opponents.isEmpty()) {
            player.sendMessage("[scarlet]No other players are online.[]");
            return;
        }

        List<String> targetUuids = new ArrayList<>();
        List<String[]> rows = new ArrayList<>();
        List<String> currentRow = new ArrayList<>();

        for (Player opponent : opponents) {
            targetUuids.add(opponent.uuid());
            currentRow.add(PlayerNameFormatter.displayName(opponent));

            if (currentRow.size() == Matchmaking.SELECTION_MENU_COLUMNS) {
                rows.add(currentRow.toArray(new String[0]));
                currentRow.clear();
            }
        }

        if (!currentRow.isEmpty()) {
            rows.add(currentRow.toArray(new String[0]));
        }

        rows.add(new String[]{"[red]Cancel"});
        selectionByChallengerUuid.put(
                player.uuid(),
                new Selection(mode, targetUuids, map)
        );

        Call.menu(
                player.con,
                selectionMenuId,
                "[accent]" + mode.label(),
                "Select a player to challenge to a " + Matchmaking.describe(mode, map) + ".",
                rows.toArray(new String[0][])
        );
    }

    private void handleSelection(Player player, int option) {
        if (player == null) {
            return;
        }

        Selection selection =
                selectionByChallengerUuid.remove(player.uuid());

        if (
                selection == null
                        || option < 0
                        || option >= selection.targetUuids().size()
        ) {
            return;
        }

        MatchMode mode = selection.mode();
        Player opponent =
                Matchmaking.onlinePlayerByUuid(selection.targetUuids().get(option));

        if (opponent == null || opponent == player) {
            player.sendMessage("[scarlet]That player is no longer online.[]");
            return;
        }

        // Refuse instead of overwriting their pending entry.
        if (matchmaking.isBusy(opponent.uuid())) {
            player.sendMessage(
                    "[scarlet]"
                            + PlayerNameFormatter.displayName(opponent)
                            + "[scarlet] is already in another match setup. Try again shortly.[]"
            );
            return;
        }

        int serial = ++pendingSerial;
        String opponentUuid = opponent.uuid();

        challengeByOpponentUuid.put(
                opponentUuid,
                new PendingChallenge(player.uuid(), mode, serial, selection.map())
        );
        Time.run(
                Matchmaking.PENDING_RESPONSE_TIMEOUT_TICKS,
                () -> expireChallenge(opponentUuid, serial)
        );

        player.sendMessage(
                "[accent]Challenge sent to "
                        + PlayerNameFormatter.displayName(opponent)
                        + "[accent].[]"
        );

        Call.menu(
                opponent.con,
                challengeMenuId,
                "[accent]" + mode.label() + " Challenge",
                PlayerNameFormatter.displayName(player)
                        + "[white] has challenged you to a "
                        + Matchmaking.describe(mode, selection.map()) + ".",
                new String[][]{
                        {"[green]Accept"},
                        {"[red]Decline"}
                }
        );
    }

    private void handleChallengeResponse(Player opponent, int option) {
        if (opponent == null) {
            return;
        }

        PendingChallenge pending =
                challengeByOpponentUuid.remove(opponent.uuid());

        if (pending == null) {
            // Only answer an actual click; dismissing a stale menu is silent.
            if (option == Matchmaking.ACCEPT_OPTION) {
                opponent.sendMessage(
                        "[lightgray]That challenge is no longer active.[]"
                );
            }

            return;
        }

        String challengerUuid = pending.challengerUuid();
        MatchMode mode = pending.mode();

        Player challenger = Matchmaking.onlinePlayerByUuid(challengerUuid);

        if (challenger == null || challenger == opponent) {
            opponent.sendMessage(
                    "[scarlet]The challenger is no longer online.[]"
            );
            return;
        }

        if (option != Matchmaking.ACCEPT_OPTION) {
            challenger.sendMessage(
                    "[scarlet]"
                            + PlayerNameFormatter.displayName(opponent)
                            + "[scarlet] declined your "
                            + mode.label() + ".[]"
            );
            return;
        }

        // The pool reserves a worker and redirects both once it is hosting; false = no free slot.
        List<List<Player>> rosters = new ArrayList<>();
        rosters.add(List.of(challenger));
        rosters.add(List.of(opponent));

        if (!matches.requestMatch(mode, rosters, pending.map())) {
            challenger.sendMessage(
                    "[scarlet]All match servers are busy right now. Try again shortly.[]"
            );
            opponent.sendMessage(
                    "[scarlet]All match servers are busy right now. Try again shortly.[]"
            );
        }
    }

    // Expires an unanswered challenge; the serial leaves a newer one against the same opponent alone.
    private void expireChallenge(String opponentUuid, int serial) {
        PendingChallenge pending = challengeByOpponentUuid.get(opponentUuid);

        if (pending == null || pending.serial() != serial) {
            return;
        }

        challengeByOpponentUuid.remove(opponentUuid);

        String modeLabel = pending.mode().label();
        Player opponent = Matchmaking.onlinePlayerByUuid(opponentUuid);
        Player challenger = Matchmaking.onlinePlayerByUuid(pending.challengerUuid());

        if (challenger != null) {
            challenger.sendMessage(
                    "[scarlet]"
                            + (opponent == null
                            ? "Your opponent"
                            : PlayerNameFormatter.displayName(opponent))
                            + "[scarlet] did not answer your "
                            + modeLabel + " challenge in time.[]"
            );
        }

        if (opponent != null) {
            opponent.sendMessage(
                    "[lightgray]The " + modeLabel
                            + " challenge against you expired.[]"
            );
        }
    }

    // A challenger's open selection menu: the mode and the ordered opponents shown.
    private record Selection(MatchMode mode, List<String> targetUuids, String map) {
    }

    // An outstanding challenge: who sent it, in which mode, and its expiry serial.
    private record PendingChallenge(
            String challengerUuid,
            MatchMode mode,
            int serial,
            String map
    ) {
    }
}
