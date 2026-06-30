package vini.evictmap;

import arc.util.CommandHandler;

/**
 * Single entry point for every player-facing chat command.
 */
final class EvictClientCommands {

    private final FullassaultManager fullassault;
    private final InviteManager invites;
    private final RoundEndCommands roundEnd;
    private final RoundTimeCommands roundTime;
    private final DuelCommands duels;
    private final HistoryCommands history;
    private final EvictHelpCommands help;

    EvictClientCommands(
            FullassaultManager fullassault,
            InviteManager invites,
            RoundEndCommands roundEnd,
            RoundTimeCommands roundTime,
            DuelCommands duels,
            HistoryCommands history,
            EvictHelpCommands help
    ) {
        this.fullassault = fullassault;
        this.invites = invites;
        this.roundEnd = roundEnd;
        this.roundTime = roundTime;
        this.duels = duels;
        this.history = history;
        this.help = help;
    }

    void register(CommandHandler handler) {
        fullassault.registerClientCommands(handler);
        invites.registerClientCommands(handler);
        roundEnd.registerClientCommands(handler);
        roundTime.registerClientCommands(handler);
        duels.registerClientCommands(handler);
        history.registerClientCommands(handler);

        // Register last so the filtered menu replaces vanilla /help.
        help.registerClientCommands(handler);
    }
}
