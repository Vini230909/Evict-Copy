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
    private final BanCommands bans;

    public ClientCommands(
            AttackManager fullassault,
            InviteManager invites,
            BanCommands bans
    ) {
        this.fullassault = fullassault;
        this.invites = invites;
        this.bans = bans;
    }

    public void register(CommandHandler handler) {
        fullassault.registerClientCommands(handler);
        invites.registerClientCommands(handler);
        bans.registerClientCommands(handler);
    }
}
