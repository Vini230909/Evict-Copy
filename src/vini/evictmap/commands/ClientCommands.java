package vini.evictmap.commands;

import vini.evictmap.*;
import vini.evictmap.gen.*;
import vini.evictmap.data.*;
import vini.evictmap.round.*;

import arc.util.CommandHandler;
import vini.evictmap.gameplay.AttackManager;

/**
 * Single entry point for every player-facing chat command.
 */
public final class ClientCommands {

    private final AttackManager fullassault;
    private final InviteManager invites;
    private final RoundEndCommands roundEnd;
    private final RoundTimeCommands roundTime;
    private final DuelCommands duels;
    private final HistoryCommands history;
    private final InfoCommands info;
    private final LeaderboardCommands leaderboard;
    private final HelpCommands help;

    public ClientCommands(
            AttackManager fullassault,
            InviteManager invites,
            RoundEndCommands roundEnd,
            RoundTimeCommands roundTime,
            DuelCommands duels,
            HistoryCommands history,
            InfoCommands info,
            LeaderboardCommands leaderboard,
            HelpCommands help
    ) {
        this.fullassault = fullassault;
        this.invites = invites;
        this.roundEnd = roundEnd;
        this.roundTime = roundTime;
        this.duels = duels;
        this.history = history;
        this.info = info;
        this.leaderboard = leaderboard;
        this.help = help;
    }

    public void register(CommandHandler handler) {
        fullassault.registerClientCommands(handler);
        invites.registerClientCommands(handler);
        roundEnd.registerClientCommands(handler);
        roundTime.registerClientCommands(handler);
        duels.registerClientCommands(handler);
        history.registerClientCommands(handler);
        info.registerClientCommands(handler);
        leaderboard.registerClientCommands(handler);

        // Register last so the filtered menu replaces vanilla /help.
        help.registerClientCommands(handler);
    }
}
