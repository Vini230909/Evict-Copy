// Player commands: one entry each, nothing else. Registered by EvictMapPlugin.
package Extinction.commands;

import Extinction.Matchmaking;
import Extinction.PureMatch;
import Extinction.Referee;
import Extinction.SpectateMenu;
import Extinction.core.cmd.Commands;

import arc.util.CommandHandler;

public final class Player {

    private Player() {
    }

    public static void register(
            CommandHandler handler,
            Matchmaking matchmaking,
            SpectateMenu spectate,
            Referee referee
    ) {
        Commands commands = new Commands();

        // A Pure worker has no Evict round for the normal /die; registered last, so it replaces it.
        if (PureMatch.pureWorker()) {
            commands.command("die").client()
                    .description("Leader only: surrender your complete team after 10 minutes.")
                    .run(ctx -> PureMatch.surrender(referee, ctx.sender()));
        }

        commands.command("play").client()
                .description("Start an Extinction or Pure match.")
                .run(ctx -> matchmaking.openGameMenu(ctx.sender()));

        // Aliases are their own rows so /help folds them into their target's row.
        commands.command("p").client()
                .description("Alias for /play.")
                .run(ctx -> matchmaking.openGameMenu(ctx.sender()));

        commands.command("spectate").client()
                .description("Spectate an ongoing match; while spectating, switch matches or return to the lobby.")
                .run(ctx -> spectate.handleViewCommand(ctx.sender()));

        commands.command("maps").client()
                .description("Choose your Pure maps; deselected maps are vetoed in your matches (hub only).")
                .run(ctx -> matchmaking.mapVetoes.openMenu(ctx.sender()));

        commands.command("s").client()
                .description("Alias for /spectate.")
                .run(ctx -> spectate.handleViewCommand(ctx.sender()));

        commands.installClient(handler);
    }
}
