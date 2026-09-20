// One challenger's Teams/Random Teams/FFA draft: the rosters being built, then the invite state.
package Extinction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class MatchDraft {
    final MatchMode mode;
    final String challengerUuid;

    // Pure matches: the map the worker hosts; null for the generated Extinction map.
    final String map;

    // Random Teams only: how many teams the accepted pool is shuffled into. 0 otherwise.
    final int teamCount;

    // Teams grows this one team at a time via Next team; FFA keeps everyone in the first list.
    final List<List<String>> teams = new ArrayList<>();

    boolean inviting = false;

    // Serial of the invite round, so the expiry task only cancels the round it was armed for.
    int inviteSerial = 0;

    // Candidates of the most recently shown pick menu, so the clicked index resolves right.
    List<String> candidateUuids = new ArrayList<>();
    final Set<String> pendingInviteeUuids = new HashSet<>();

    MatchDraft(MatchMode mode, String challengerUuid, int teamCount) {
        this(mode, challengerUuid, teamCount, null);
    }

    // A Pure team draft starts with every column in place: the challenger in the first, the rest empty.
    MatchDraft(MatchMode mode, String challengerUuid, int teamCount, String map) {
        this.mode = mode;
        this.challengerUuid = challengerUuid;
        this.teamCount = teamCount;
        this.map = map;

        List<String> firstTeam = new ArrayList<>();
        firstTeam.add(challengerUuid);
        teams.add(firstTeam);

        for (int column = 1; column < mode.mapTeams(); column++) {
            teams.add(new ArrayList<>());
        }
    }

    // Rosters listed one team per line (Teams and Pure), or one line of players (Random Teams, FFA).
    String summary() {
        if (mode == MatchMode.TEAMS || mode.pure()) {
            StringBuilder text = new StringBuilder();

            for (int index = 0; index < teams.size(); index++) {
                if (index > 0) {
                    text.append("\n");
                }

                text.append("Team ")
                        .append(index + 1)
                        .append(": ")
                        .append(namesOf(teams.get(index)));
            }

            return text.toString();
        }

        if (mode == MatchMode.RANDOM_TEAMS) {
            return "Players: " + namesOf(teams.get(0))
                    + "\n[lightgray]Shuffled into " + teamCount
                    + " random teams.[]";
        }

        return "Players: " + namesOf(teams.get(0));
    }

    static String namesOf(List<String> uuids) {
        if (uuids.isEmpty()) {
            return "[lightgray](nobody yet)[]";
        }

        StringBuilder names = new StringBuilder();

        for (String uuid : uuids) {
            mindustry.gen.Player player = Matchmaking.onlinePlayerByUuid(uuid);

            if (!names.isEmpty()) {
                names.append("[white], ");
            }

            names.append(
                    player == null
                            ? "[lightgray](left)[]"
                            : PlayerNameFormatter.displayName(player)
            );
        }

        return names.toString();
    }

    List<String> currentRoster() {
        return teams.get(teams.size() - 1);
    }

    List<String> allPickedUuids() {
        List<String> all = new ArrayList<>();

        for (List<String> team : teams) {
            all.addAll(team);
        }

        return all;
    }

    boolean involves(String uuid) {
        for (List<String> team : teams) {
            if (team.contains(uuid)) {
                return true;
            }
        }

        return false;
    }
}
