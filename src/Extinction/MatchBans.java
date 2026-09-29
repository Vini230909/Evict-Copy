// Compares bans with the whole launch roster, including eliminated and disconnected players.
package Extinction;

import arc.util.Strings;
import mindustry.Vars;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.net.Administration.PlayerInfo;

public final class MatchBans {
    private final Referee referee;

    public MatchBans(Referee referee) {
        this.referee = referee;
    }

    public boolean applies(String uuid) {
        return referee.isActive() && !referee.resolved() && !referee.matchMode().solo()
                && referee.rosterTeams().stream().anyMatch(roster -> roster.contains(uuid));
    }

    // Called before kicks: resolving first prevents a ban from starting a rejoin pause.
    public void check(BanList.Snapshot snapshot) {
        if (!referee.isActive() || referee.resolved() || referee.matchMode().solo()) return;
        for (var roster : referee.rosterTeams()) {
            for (String uuid : roster) {
                if (snapshot.uuids().contains(uuid) || snapshot.ips().contains(address(uuid))) {
                    referee.handleBan(uuid);
                    return;
                }
            }
        }
    }

    // Read once more at the decision boundary, so a hub ban between polls cannot award a win.
    public boolean checkHub() {
        check(BanList.read(BanList.WORKER_VIEW_FILE));
        return referee.resolved();
    }

    private String address(String uuid) {
        Player player = Groups.player.find(p -> uuid.equals(p.uuid()) && p.con != null && !p.con.kicked);
        if (player != null) return player.con.address;
        PlayerInfo info = Vars.netServer == null ? null : Vars.netServer.admins.getInfoOptional(uuid);
        return info == null || info.lastIP == null ? "" : info.lastIP;
    }

    public String name(String uuid) {
        return Strings.stripColors(referee.pause.nameOf(uuid)).replace("[", "[[");
    }
}
