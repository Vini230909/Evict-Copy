package Extinction.commands;

import Extinction.*;
import Extinction.gen.*;
import Extinction.data.*;
import Extinction.round.*;

import arc.util.CommandHandler;
import Extinction.gameplay.AttackManager;

/**
 * Single entry point for every player-facing chat command.
 */
public final class ClientCommands {

    private final AttackManager fullassault;
    private final InviteManager invites;
    private final RoundEndCommands roundEnd;
    private final RoundTimeCommands roundTime;
    private final HistoryCommands history;
    private final InfoCommands info;
    private final BanCommands bans;
    private final JsCommands js;
    private final LeaderboardCommands leaderboard;
    private final HelpCommands help;

    public ClientCommands(
            AttackManager fullassault,
            InviteManager invites,
            RoundEndCommands roundEnd,
            RoundTimeCommands roundTime,
            HistoryCommands history,
            InfoCommands info,
            BanCommands bans,
            JsCommands js,
            LeaderboardCommands leaderboard,
            HelpCommands help
    ) {
        this.fullassault = fullassault;
        this.invites = invites;
        this.roundEnd = roundEnd;
        this.roundTime = roundTime;
        this.history = history;
        this.info = info;
        this.bans = bans;
        this.js = js;
        this.leaderboard = leaderboard;
        this.help = help;
    }

    public void register(CommandHandler handler) {
        fullassault.registerClientCommands(handler);
        invites.registerClientCommands(handler);
        roundEnd.registerClientCommands(handler);
        roundTime.registerClientCommands(handler);
        history.registerClientCommands(handler);
        info.registerClientCommands(handler);
        bans.registerClientCommands(handler);
        js.registerClientCommands(handler);
        leaderboard.registerClientCommands(handler);

        // Register last so the filtered menu replaces vanilla /help.
        help.registerClientCommands(handler);
    }
}
