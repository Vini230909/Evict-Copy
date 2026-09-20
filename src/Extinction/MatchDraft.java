// One challenger's Teams/Random Teams/FFA draft: the rosters being built, then the invite state.
package Extinction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class MatchDraft {
    final MatchMode mode;
    final String challengerUuid;

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
        this.mode = mode;
        this.challengerUuid = challengerUuid;
        this.teamCount = teamCount;

        List<String> firstTeam = new ArrayList<>();
        firstTeam.add(challengerUuid);
        teams.add(firstTeam);
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
