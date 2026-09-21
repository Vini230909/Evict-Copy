// Player commands: one entry each, nothing else. Registered by EvictMapPlugin.
package Extinction.commands;

import Extinction.FullAssault;
import Extinction.Help;
import Extinction.History;
import Extinction.Leaderboard;
import Extinction.Matchmaking;
import Extinction.PlayerStats;
import Extinction.PureMatch;
import Extinction.Referee;
import Extinction.RoundEnd;
import Extinction.RoundTime;
import Extinction.SpectateMenu;
import Extinction.core.cmd.Commands;
import Extinction.round.InviteManager;

import arc.util.CommandHandler;

public final class Player {

    private Player() {
    }

    public static void register(
            CommandHandler handler,
            Matchmaking matchmaking,
            SpectateMenu spectate,
            Referee referee,
            RoundEnd roundEnd,
            RoundTime roundTime,
            History history,
            PlayerStats playerStats,
            Leaderboard leaderboard,
            FullAssault fullAssault,
            InviteManager invites
    ) {
        Commands commands = new Commands();

        commands.command("fullassault").client()
                .description("Send your team's idle combat units at the nearest enemy core every 5 seconds.")
                .run(ctx -> fullAssault.toggle(ctx.raw(), ctx.sender()));

        commands.command("fa").client()
                .description("Alias for /fullassault.")
                .run(ctx -> fullAssault.toggle(ctx.raw(), ctx.sender()));

        commands.command("invite").client()
                .args("number:string?")
                .description("List or use team invitations.")
                .run(ctx -> invites.handleInvite(ctx.raw(), ctx.sender()));

        // A Pure worker has no Evict round: its /die surrenders the map team instead.
        commands.command("die").client()
                .description("Leader only: surrender your complete team after 10 minutes.")
                .run(ctx -> {
                    if (PureMatch.pureWorker()) {
                        PureMatch.surrender(referee, ctx.sender());
                    } else {
                        roundEnd.surrender(ctx.sender());
                    }
                });

        commands.command("over").client()
                .description("End an eligible round immediately.")
                .run(ctx -> roundEnd.endEarly(ctx.sender()));

        commands.command("time").client()
                .description("Show round time and your time since first joining this round.")
                .run(ctx -> ctx.reply(roundTime.show(ctx.sender())));

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

        commands.command("history").client()
                .description("Pick a player and view their Unranked, 1v1, Teams and FFA match history.")
                .run(ctx -> history.openPicker(ctx.sender()));

        commands.command("h").client()
                .description("Alias for /history.")
                .run(ctx -> history.openPicker(ctx.sender()));

        commands.command("info").client()
                .args("player:text?")
                .description("View a player's stats and playtime. No name opens a picker.")
                .run(ctx -> playerStats.show(ctx.sender(), ctx.str("player", "").trim()));

        commands.command("top").client()
                .args("count:int?")
                .description("Show the top 1v1 players by ELO.")
                .run(ctx -> leaderboard.show(ctx.sender(), ctx.getInt("count", Leaderboard.DEFAULT_COUNT)));

        // Replaces vanilla /help: CommandHandler.register drops the earlier command of the same name.
        commands.command("help").client()
                .args("page:string?")
                .description("Lists commands.")
                .run(ctx -> ctx.reply(Help.page(handler, ctx.raw())));

        commands.installClient(handler);
    }
}
