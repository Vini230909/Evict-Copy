// Chat on a match worker: Ranked keeps global chat for the duelists and routes everyone else to /t.
package Extinction;

import arc.util.CommandHandler;
import mindustry.Vars;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;

public final class MatchChat {

    private final Referee referee;

    public MatchChat(Referee referee) {
        this.referee = referee;
    }

    // Only a spectator-chat-restricted mode does anything; every other mode leaves chat alone.
    public void installChatFilter() {
        if (Vars.netServer == null) {
            return;
        }

        Vars.netServer.admins.addChatFilter((player, message) -> {
            if (player == null || message == null) {
                return message;
            }

            if (!referee.matchMode().restrictsSpectatorChat()) {
                return message;
            }

            // The two duelists chat normally on global.
            if (referee.isParticipant(player.uuid())) {
                return message;
            }

            // Viewers and casting admins alike go to the spectators' chat instead of global.
            sendTeamChat(player, message);
            return null;
        });
    }

    // Overrides vanilla /t. In a restricted mode a casting admin's /t is inverted to reach global:
    // their normal chat already goes to the spectators. Hub admins are admins here (AdminSync).
    public void registerTeamChatCommand(CommandHandler handler) {
        handler.<Player>register(
                "t",
                "<message...>",
                "Send a message only to your teammates.",
                (args, player) -> {
                    if (player == null) {
                        return;
                    }

                    String message = args[0];

                    if (
                            referee.matchMode().restrictsSpectatorChat()
                                    && !referee.isParticipant(player.uuid())
                                    && player.admin
                    ) {
                        Call.sendMessage(player.name + "[white]: " + message);
                        return;
                    }

                    sendTeamChat(player, message);
                }
        );
    }

    private void sendTeamChat(Player sender, String message) {
        String line = "[#" + sender.team().color + "]<T>[] "
                + sender.name + "[white]: " + message;

        Groups.player.each(target -> {
            if (target != null && target.team() == sender.team()) {
                target.sendMessage(line);
            }
        });
    }
}
