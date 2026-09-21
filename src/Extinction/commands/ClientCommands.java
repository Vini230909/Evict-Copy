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
    private final HistoryCommands history;
    private final InfoCommands info;
    private final BanCommands bans;
    private final JsCommands js;
    private final LeaderboardCommands leaderboard;

    public ClientCommands(
            AttackManager fullassault,
            InviteManager invites,
            HistoryCommands history,
            InfoCommands info,
            BanCommands bans,
            JsCommands js,
            LeaderboardCommands leaderboard
    ) {
        this.fullassault = fullassault;
        this.invites = invites;
        this.history = history;
        this.info = info;
        this.bans = bans;
        this.js = js;
        this.leaderboard = leaderboard;
    }

    public void register(CommandHandler handler) {
        fullassault.registerClientCommands(handler);
        invites.registerClientCommands(handler);
        history.registerClientCommands(handler);
        info.registerClientCommands(handler);
        bans.registerClientCommands(handler);
        js.registerClientCommands(handler);
        leaderboard.registerClientCommands(handler);
    }
}
