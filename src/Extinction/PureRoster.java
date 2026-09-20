// The Pure team roster grid: one column per map team, "+" slots that open the player picker, Done, Cancel.
package Extinction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import mindustry.gen.Call;
import mindustry.gen.Player;
import mindustry.ui.Menus;

public final class PureRoster {

    private static final int MIN_ROWS = 3;
    private static final int MAX_PER_TEAM = 20;

    private final Matchmaking matchmaking;

    private final int gridMenuId;
    private final int slotMenuId;

    // Challenger UUID -> the grid they see (columns x rows), so a clicked option resolves to a slot.
    private final Map<String, Grid> gridByUuid = new HashMap<>();

    // Challenger UUID -> the column whose "+" they clicked, and the candidates the picker showed.
    private final Map<String, Integer> pickingColumnByUuid = new HashMap<>();
    private final Map<String, List<String>> candidatesByUuid = new HashMap<>();

    public PureRoster(Matchmaking matchmaking) {
        this.matchmaking = matchmaking;
        this.gridMenuId = Menus.registerMenu(this::handleGridSelection);
        this.slotMenuId = Menus.registerMenu(this::handleSlotSelection);
    }

    void handlePlayerLeave(Player player) {
        gridByUuid.remove(player.uuid());
        pickingColumnByUuid.remove(player.uuid());
        candidatesByUuid.remove(player.uuid());
    }

    void begin(Player challenger, MatchMode mode) {
        if (matchmaking.otherOnlinePlayers(challenger).isEmpty()) {
            challenger.sendMessage("[scarlet]No other players are online.[]");
            return;
        }

        matchmaking.drafts.beginPure(challenger, mode);
        openGrid(challenger);
    }

    // The challenger is fixed in the left column's first slot; every empty slot is a "+".
    private void openGrid(Player challenger) {
        MatchDraft draft = matchmaking.drafts.draftOf(challenger);

        if (draft == null) {
            return;
        }

        int columns = draft.teams.size();
        int rows = rowsFor(draft);
        String[][] options = new String[rows + 2][];

        for (int row = 0; row < rows; row++) {
            options[row] = new String[columns];

            for (int column = 0; column < columns; column++) {
                List<String> team = draft.teams.get(column);

                if (row < team.size()) {
                    String uuid = team.get(row);
                    options[row][column] = uuid.equals(draft.challengerUuid)
                            ? "[accent]" + MatchDraft.namesOf(List.of(uuid))
                            : MatchDraft.namesOf(List.of(uuid));
                } else {
                    options[row][column] = team.size() >= MAX_PER_TEAM ? "" : "[green]+";
                }
            }
        }

        options[rows] = new String[]{"[green]Done - pick map"};
        options[rows + 1] = new String[]{"[red]Cancel"};

        gridByUuid.put(challenger.uuid(), new Grid(columns, rows));

        Call.menu(
                challenger.con,
                gridMenuId,
                "[accent]" + Matchmaking.describe(draft.mode, draft.map),
                "Each column is one team; Team 1 is the left one.\n"
                        + "Click + to add a player, a name to remove them. Uneven teams are allowed, up to "
                        + MAX_PER_TEAM + " per team.",
                options
        );
    }

    // Three rows to start; a new row appears once any column is full, up to the per-team cap.
    private static int rowsFor(MatchDraft draft) {
        int largest = 0;

        for (List<String> team : draft.teams) {
            largest = Math.max(largest, team.size());
        }

        return Math.min(MAX_PER_TEAM, Math.max(MIN_ROWS, largest + 1));
    }

    private void handleGridSelection(Player challenger, int option) {
        if (challenger == null) {
            return;
        }

        Grid grid = gridByUuid.remove(challenger.uuid());
        MatchDraft draft = matchmaking.drafts.draftOf(challenger);

        if (grid == null || draft == null) {
            return;
        }
        if (option < 0) {
            matchmaking.drafts.cancel(challenger);
            return;
        }

        int cells = grid.columns() * grid.rows();

        if (option == cells) {
            if (!matchmaking.drafts.done(challenger, draft)) {
                openGrid(challenger);
            }
            return;
        }

        if (option == cells + 1) {
            matchmaking.drafts.cancel(challenger);
            return;
        }

        if (option > cells + 1) {
            return;
        }

        int column = option % grid.columns();
        int row = option / grid.columns();
        List<String> team = draft.teams.get(column);

        if (row < team.size()) {
            // A name: remove that player (the challenger stays).
            if (!team.get(row).equals(draft.challengerUuid)) {
                team.remove(row);
            }

            openGrid(challenger);
            return;
        }

        if (team.size() >= MAX_PER_TEAM) {
            openGrid(challenger);
            return;
        }

        openSlotPicker(challenger, draft, column);
    }

    // The online-player picker for one column: everyone not yet in the draft.
    private void openSlotPicker(Player challenger, MatchDraft draft, int column) {
        List<String> candidates = new ArrayList<>();
        List<String[]> rows = new ArrayList<>();
        List<String> currentRow = new ArrayList<>();

        for (Player candidate : matchmaking.otherOnlinePlayers(challenger)) {
            if (draft.involves(candidate.uuid())) {
                continue;
            }

            candidates.add(candidate.uuid());
            currentRow.add(PlayerNameFormatter.displayName(candidate));

            if (currentRow.size() == Matchmaking.SELECTION_MENU_COLUMNS) {
                rows.add(currentRow.toArray(new String[0]));
                currentRow.clear();
            }
        }

        if (!currentRow.isEmpty()) {
            rows.add(currentRow.toArray(new String[0]));
        }

        if (candidates.isEmpty()) {
            challenger.sendMessage("[scarlet]Everyone online is already in this match.[]");
            openGrid(challenger);
            return;
        }

        rows.add(new String[]{"[lightgray]Back"});
        pickingColumnByUuid.put(challenger.uuid(), column);
        candidatesByUuid.put(challenger.uuid(), candidates);

        Call.menu(
                challenger.con,
                slotMenuId,
                "[accent]Team " + (column + 1),
                "Pick a player for Team " + (column + 1) + ".",
                rows.toArray(new String[0][])
        );
    }

    private void handleSlotSelection(Player challenger, int option) {
        if (challenger == null) {
            return;
        }

        Integer column = pickingColumnByUuid.remove(challenger.uuid());
        List<String> candidates = candidatesByUuid.remove(challenger.uuid());
        MatchDraft draft = matchmaking.drafts.draftOf(challenger);

        if (column == null || candidates == null || draft == null) {
            return;
        }

        if (option >= 0 && option < candidates.size()) {
            String uuid = candidates.get(option);

            if (Matchmaking.onlinePlayerByUuid(uuid) == null) {
                challenger.sendMessage("[scarlet]That player is no longer online.[]");
            } else if (!draft.involves(uuid) && draft.teams.get(column).size() < MAX_PER_TEAM) {
                draft.teams.get(column).add(uuid);
            }
        }

        // A pick, Back, or a closed menu all return to the grid.
        openGrid(challenger);
    }

    private record Grid(int columns, int rows) {
    }
}
