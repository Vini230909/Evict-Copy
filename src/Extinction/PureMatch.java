// A Pure match on a worker: the map's own teams, one roster on each, vanilla PvP, wiped teams freed.
package Extinction;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import arc.util.Log;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.server.ServerControl;
import mindustry.world.blocks.storage.CoreBlock;

public final class PureMatch {

    private final Referee referee;

    // Roster index -> the map team it plays as, in team-id order; filled once the map is hosted.
    private final List<Team> teamByRoster = new ArrayList<>();

    public PureMatch(Referee referee) {
        this.referee = referee;
    }

    // True on a worker launched for a Pure match; readable before the referee has begun, from
    // the handshake the hub wrote before this process started (commands register that early).
    public static boolean pureWorker() {
        return "true".equals(System.getProperty("evict.duelWorker"))
                && MatchHandshake.read(new File(MatchHandshake.FILE_NAME)).mode.pure();
    }

    // Server global rules have already run at PlayEvent; Pure owns completion and allows unit building.
    void begin() {
        Vars.state.rules.pvpAutoPause = false;
        Vars.state.rules.canGameOver = false;
        Vars.state.rules.logicUnitControl = true;
        Vars.state.rules.logicUnitBuild = true;
        // Map-script and console game overs must also return players instead of rotating the map.
        if (ServerControl.instance != null) {
            ServerControl.instance.gameOverListener = event -> referee.handleVictory(event.winner);
        }
        teamByRoster.clear();

        for (Team team : Team.all) {
            if (team != Team.derelict && !team.cores().isEmpty()) {
                teamByRoster.add(team);
            }
        }

        if (teamByRoster.size() < referee.rosterTeams().size()) {
            Log.err(
                    "[EvictMapGenerator] Pure match: the map has @ core team(s) but the match has @ roster(s).",
                    teamByRoster.size(),
                    referee.rosterTeams().size()
            );
        }

        // A leaver only pauses the match while their team still has a core.
        referee.setStillCompeting(player -> player != null && !player.team().cores().isEmpty());

        Log.info(
                "[EvictMapGenerator] Pure match: @ roster(s) on map teams @.",
                referee.rosterTeams().size(),
                teamNames()
        );
    }

    private String teamNames() {
        StringBuilder names = new StringBuilder();

        for (int index = 0; index < referee.rosterTeams().size() && index < teamByRoster.size(); index++) {
            if (!names.isEmpty()) {
                names.append(", ");
            }

            names.append(teamByRoster.get(index).name);
        }

        return names.toString();
    }

    // Every join of a participant (first or rejoin): onto their roster's map team, spawn there.
    void place(Player player) {
        Team team = teamOf(player.uuid());

        if (team == null || referee.outUuids().contains(player.uuid())) {
            return;
        }

        player.team(team);
        player.clearUnit();
        player.checkSpawn();
    }

    private Team teamOf(String uuid) {
        List<List<String>> rosters = referee.rosterTeams();

        for (int index = 0; index < rosters.size() && index < teamByRoster.size(); index++) {
            if (rosters.get(index).contains(uuid)) {
                return teamByRoster.get(index);
            }
        }

        return null;
    }

    // A winner need not be connected: the original roster still belongs to its map team.
    List<String> winnerUuids(Team team) {
        int index = teamByRoster.indexOf(team);
        return index >= 0 && index < referee.rosterTeams().size()
                ? new ArrayList<>(referee.rosterTeams().get(index)) : new ArrayList<>();
    }

    // Core counts decide every Pure mode, even when the map disables vanilla game over.
    void update() {
        if (referee.resolved() || teamByRoster.isEmpty() || !Vars.state.isGame()) {
            return;
        }

        boolean eliminated = false;
        for (int index = 0; index < referee.rosterTeams().size() && index < teamByRoster.size(); index++) {
            if (!teamByRoster.get(index).cores().isEmpty()) continue;
            for (String uuid : referee.rosterTeams().get(index)) {
                if (!referee.isParticipant(uuid)) continue;
                referee.demoteToSpectator(uuid);
                eliminated = true;
                Player player = Groups.player.find(online -> uuid.equals(online.uuid()));
                if (player == null) continue;
                player.team(Team.derelict);
                player.clearUnit();
                player.sendMessage(
                        "[scarlet]You are out of the " + referee.matchMode().label()
                                + " match.[] [accent]You are now spectating - use [white]/s[accent] to return to the lobby.[]"
                );
            }
        }

        int alive = 0;
        Team winner = Team.derelict;
        for (Team team : teamByRoster) {
            if (!team.cores().isEmpty()) {
                alive++;
                winner = team;
            }
        }

        boolean solo = referee.matchMode().solo();
        if ((solo && teamByRoster.get(0).cores().isEmpty())
                || (alive <= 1 && (!solo || teamByRoster.size() > 1))) {
            referee.handleVictory(winner);
        }
        // Publish before a newly freed spectator can reconnect to the hub and be bounced back.
        if (eliminated) referee.status.write();
    }

    // /die on a Pure worker: Training ends the session; in a PvP match the team's cores fall.
    public static void surrender(Referee referee, Player player) {
        if (player == null || referee.resolved() || !referee.isParticipant(player.uuid())) {
            return;
        }

        if (referee.matchMode().solo()) {
            referee.handleParticipantSurrender(player);
            return;
        }

        Team team = referee.pure.teamOf(player.uuid());
        if (team == null) return;
        List<CoreBlock.CoreBuild> cores = new ArrayList<>(team.cores().list());

        if (cores.isEmpty()) {
            referee.pure.update();
            return;
        }

        referee.gate.release();
        Call.sendMessage(
                "[scarlet]" + PlayerNames.displayName(player)
                        + "[scarlet] surrendered for team " + team.name + ".[]"
        );

        for (CoreBlock.CoreBuild core : cores) {
            core.kill();
        }
        referee.pure.update();
    }
}
