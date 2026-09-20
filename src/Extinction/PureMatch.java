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

    // The map is hosted: vanilla rules stay, except the auto-pause the referee replaces.
    void begin() {
        Vars.state.rules.pvpAutoPause = false;
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

    // Every tick: a participant whose team lost its last core is out and spectates.
    void update() {
        if (referee.resolved() || !referee.matchMode().eliminatesWipedTeams()) {
            return;
        }

        Groups.player.each(player -> {
            if (
                    player == null
                            || !referee.isParticipant(player.uuid())
                            || player.team() == Team.derelict
                            || !player.team().cores().isEmpty()
            ) {
                return;
            }

            referee.demoteToSpectator(player.uuid());
            player.team(Team.derelict);
            player.clearUnit();
            player.sendMessage(
                    "[scarlet]You are out of the "
                            + referee.matchMode().label()
                            + " match.[] [accent]You are now spectating - use [white]/s[accent] to return to the lobby.[]"
            );
        });
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

        List<CoreBlock.CoreBuild> cores = new ArrayList<>(player.team().cores().list());

        if (cores.isEmpty()) {
            player.sendMessage("[scarlet]Your team has no core left to surrender.[]");
            return;
        }

        referee.gate.release();
        Call.sendMessage(
                "[scarlet]" + PlayerNameFormatter.displayName(player)
                        + "[scarlet] surrendered for team " + player.team().name + ".[]"
        );

        for (CoreBlock.CoreBuild core : cores) {
            core.kill();
        }
    }
}
