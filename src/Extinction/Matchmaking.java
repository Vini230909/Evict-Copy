// /play on the hub: the mode menu, then a challenge (1v1/Ranked), a draft (Teams/Random/FFA) or a solo start.
package Extinction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.ui.Menus;

public final class Matchmaking {

    // Player pickers show this many names per row.
    public static final int SELECTION_MENU_COLUMNS = 2;
    public static final int ACCEPT_OPTION = 0;

    // How long a challenge or invite may sit unanswered before it expires (with a message to both).
    // Without it a menu lost client-side left the pending entry behind and blocked both players.
    public static final float PENDING_RESPONSE_TIMEOUT_TICKS = 60f * 60f;

    private static final int MIN_RANDOM_TEAMS = 2;

    // Option indexes of the fixed mode menu (see openModeMenu's rows).
    private static final MatchMode[] MODE_MENU_OPTIONS = {
            MatchMode.ONE_VS_ONE,
            MatchMode.RANKED,
            MatchMode.TEAMS,
            MatchMode.RANDOM_TEAMS,
            MatchMode.FFA,
            MatchMode.TRAINING,
            MatchMode.SANDBOX
    };

    private final Matches matches;
    final Challenges challenges;
    final MatchDrafts drafts;
    final PureMenus pure;
    public final MapVetoes mapVetoes = new MapVetoes();

    private final int gameMenuId;
    private final int modeMenuId;
    private final int teamCountMenuId;
    private final Map<String, Boolean> nerfByUuid = new HashMap<>();

    // Players never offered in a picker: a locked account rostered in would sit there unable to play.
    private Predicate<Player> excludedFromPickers = player -> false;

    public Matchmaking(Matches matches) {
        this.matches = matches;
        this.challenges = new Challenges(this, matches);
        this.drafts = new MatchDrafts(this, matches);
        this.pure = new PureMenus(this);
        this.gameMenuId = Menus.registerMenu(this::handleGameSelection);
        this.modeMenuId = Menus.registerMenu(this::handleModeSelection);
        this.teamCountMenuId = Menus.registerMenu(this::handleTeamCountSelection);
    }

    public void excludeFromPickers(Predicate<Player> excluded) {
        this.excludedFromPickers = excluded;
    }

    // Drops any menu state, challenge or draft involving a player who just left.
    public void handlePlayerLeave(Player player) {
        if (player == null) {
            return;
        }

        nerfByUuid.remove(player.uuid());
        challenges.handlePlayerLeave(player.uuid());
        drafts.handlePlayerLeave(player);
        pure.handlePlayerLeave(player);
        mapVetoes.handlePlayerLeave(player);
    }

    // "Teams", or "Pure 1v1 PvP on Frontier": a mode as the menus and invites name it.
    static String describe(MatchMode mode, String map) {
        return map == null ? mode.label() : mode.label() + " on " + map;
    }

    static String describe(MatchMode mode, String map, boolean nerf) {
        return describe(mode, map) + (nerf ? " (Nerf)" : "");
    }

    static String nerfTitle(boolean nerf) {
        return nerf ? " [scarlet](Nerf)[]" : "";
    }

    static String nerfWarning(boolean nerf) {
        return nerf ? "\n\n[scarlet]This match runs the Nerf rebalance:[] overdrive projectors and domes,"
                + " T5 reconstructors, phase weavers, surge smelters, navanax, quasar and vela are changed." : "";
    }

    // /play: Extinction (the generated hex map, today's modes) or Pure (vanilla PvP on a real map).
    public void openGameMenu(Player player) {
        openGameMenu(player, false);
    }

    public void openGameMenu(Player player, String option) {
        String word = option == null ? "" : option.trim();
        if (!word.isEmpty() && !word.equalsIgnoreCase("none") && !word.equalsIgnoreCase("nerf")) {
            if (player != null) player.sendMessage("[scarlet]'" + word
                    + "' is not a /play option. Available: []none, nerf");
            return;
        }
        openGameMenu(player, word.equalsIgnoreCase("nerf"));
    }

    public void openGameMenu(Player player, boolean nerf) {
        if (player == null) {
            return;
        }

        if (!matches.isConfigured()) {
            player.sendMessage(
                    "[scarlet]The match server is not set up yet. Ask an admin.[]"
            );
            return;
        }

        nerfByUuid.put(player.uuid(), nerf);
        Call.menu(
                player.con,
                gameMenuId,
                "[accent]Play" + nerfTitle(nerf),
                "Extinction: the generated hex map.\nPure: plain Mindustry PvP on a real map.",
                new String[][]{
                        {"Extinction", "Pure"}
                }
        );
    }

    private void handleGameSelection(Player player, int option) {
        if (player == null || !matches.isConfigured()) {
            return;
        }

        if (option == 0) {
            openModeMenu(player);
        } else if (option == 1) {
            pure.openModeMenu(player, currentNerf(player));
        }
    }

    private boolean currentNerf(Player player) {
        return nerfByUuid.getOrDefault(player.uuid(), false);
    }

    private void openModeMenu(Player player) {
        Call.menu(
                player.con,
                modeMenuId,
                "[accent]Play" + nerfTitle(currentNerf(player)),
                "Select a game mode.",
                new String[][]{
                        {"Unranked", "1v1"},
                        {"Teams", "Random Teams"},
                        {"FFA", "Training"},
                        {"Sandbox"},
                        {"[red]Cancel"}
                }
        );
    }

    private void handleModeSelection(Player player, int option) {
        if (player == null || option < 0 || option >= MODE_MENU_OPTIONS.length) {
            return;
        }

        MatchMode mode = MODE_MENU_OPTIONS[option];
        boolean nerf = currentNerf(player);

        switch (mode) {
            case ONE_VS_ONE, RANKED -> challenges.openSelectionMenu(player, mode, nerf);
            case TEAMS, FFA -> drafts.begin(player, mode, 0, nerf);
            case RANDOM_TEAMS -> openTeamCountMenu(player);
            case TRAINING, SANDBOX -> startSoloMatch(player, mode, null, nerf);
        }
    }

    // Random Teams: team count first, then one FFA-style player pool.
    private void openTeamCountMenu(Player player) {
        if (otherOnlinePlayers(player).isEmpty()) {
            player.sendMessage("[scarlet]No other players are online.[]");
            return;
        }

        Call.menu(
                player.con,
                teamCountMenuId,
                "[accent]Random Teams" + nerfTitle(currentNerf(player)),
                "How many teams should the players be shuffled into?\n"
                        + "Teams are drawn randomly once everyone accepted.",
                new String[][]{
                        {"2", "3"},
                        {"4", "5"},
                        {"6", "7"},
                        {"8"},
                        {"[red]Cancel"}
                }
        );
    }

    private void handleTeamCountSelection(Player player, int option) {
        if (player == null) {
            return;
        }

        int teamCount = MIN_RANDOM_TEAMS + option;

        if (option < 0 || teamCount > MatchDrafts.MAX_TEAMS) {
            if (teamCount == MatchDrafts.MAX_TEAMS + 1) {
                player.sendMessage("[lightgray]Match setup cancelled.[]");
            }

            return;
        }

        drafts.begin(player, MatchMode.RANDOM_TEAMS, teamCount, currentNerf(player));
    }

    // Training or Sandbox: the requester is the only rostered player, nobody to pick or invite.
    private void startSoloMatch(Player player, MatchMode mode) {
        startSoloMatch(player, mode, null);
    }

    void startSoloMatch(Player player, MatchMode mode, String map) {
        startSoloMatch(player, mode, map, false);
    }

    void startSoloMatch(Player player, MatchMode mode, String map, boolean nerf) {
        List<List<Player>> rosters = new ArrayList<>();
        rosters.add(List.of(player));

        if (!matches.requestMatch(mode, rosters, map, nerf)) {
            player.sendMessage(
                    "[scarlet]All match servers are busy right now. Try again shortly.[]"
            );
        }
    }

    // True while this player has something pending: they cannot be targeted by new challenges or
    // invites (a clobbered pending entry left the other match setup waiting forever).
    boolean isBusy(String uuid) {
        return challenges.isBusy(uuid) || drafts.isBusy(uuid);
    }

    List<Player> otherOnlinePlayers(Player self) {
        List<Player> players = new ArrayList<>();

        Groups.player.each(player -> {
            if (player != null && player != self && !excludedFromPickers.test(player)) {
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

    static Player onlinePlayerByUuid(String uuid) {
        return Groups.player.find(
                player -> player != null && player.uuid().equals(uuid)
        );
    }
}
