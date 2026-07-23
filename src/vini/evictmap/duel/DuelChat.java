package vini.evictmap.duel;

import arc.util.CommandHandler;
import mindustry.Vars;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import vini.evictmap.data.RankManager;

/**
 * Duel-worker chat routing. In most modes chat is untouched; a mode that
 * {@link vini.evictmap.duel.modes.DuelMode#restrictsSpectatorChat() restricts
 * spectator chat} (Ranked) keeps global chat for the two duelists only and
 * routes everyone else - viewers and casting admins - to the spectators' chat.
 * A casting admin still reaches global, but through an inverted /t.
 */
public final class DuelChat {

    private final DuelWorker referee;
    private final RankManager rankManager;

    public DuelChat(DuelWorker referee, RankManager rankManager) {
        this.referee = referee;
        this.rankManager = rankManager;
    }

    /**
     * Routes normal (global) chat. Only a spectator-chat-restricted mode does
     * anything; every other mode leaves chat completely alone.
     */
    public void installChatFilter() {
        if (Vars.netServer == null) {
            return;
        }

        Vars.netServer.admins.addChatFilter((player, message) -> {
            if (player == null || message == null) {
                return message;
            }

            if (!referee.duelMode().restrictsSpectatorChat()) {
                return message;
            }

            // The two duelists chat normally on global.
            if (referee.isParticipant(player.uuid())) {
                return message;
            }

            // Viewers and casting admins alike have their normal chat sent to
            // the spectators' chat instead of global.
            sendTeamChat(player, message);
            return null;
        });
    }

    /**
     * Overrides vanilla /t. Normally plain team chat, but in a
     * spectator-chat-restricted mode a casting admin's /t is inverted to reach
     * global chat instead: their normal chat already goes to the spectators, so
     * /t is how they broadcast to the two duelists and everyone else at once.
     */
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
                            referee.duelMode().restrictsSpectatorChat()
                                    && !referee.isParticipant(player.uuid())
                                    && rankManager.canRestartMatches(player)
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
