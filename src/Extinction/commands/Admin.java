// Admin commands: one entry each, nothing else. Registered by EvictMapPlugin.
package Extinction.commands;

import Extinction.BanMenu;
import Extinction.FreeMenu;
import Extinction.Js;
import Extinction.core.cmd.Commands;
import Extinction.core.text.Text;

import arc.util.CommandHandler;

public final class Admin {

    private Admin() {
    }

    public static void register(CommandHandler handler, BanMenu bans, FreeMenu free) {
        Commands commands = new Commands();

        // Gated in the handler, like /js; the picker and confirmation live in BanMenu.
        commands.command("ban").client()
                .args("player:text?")
                .description("Admin only: ban a player and their known addresses. No name opens a picker.")
                .run(ctx -> bans.handleBan(ctx.raw(), ctx.sender()));

        // Gated in the handler, not by Perm, so the reply stays the one /js has always given.
        commands.command("js").client()
                .args("script:text")
                .description("Admin only: run a Javascript snippet, like the console's js.")
                .run(ctx -> {
                    if (!ctx.sender().admin) {
                        ctx.reply("[scarlet]Only admins can run scripts.[]");
                        return;
                    }
                    String script = ctx.str("script", "").trim();
                    if (script.isEmpty()) {
                        ctx.reply(Text.of().lightGray("Usage: ").white("/js <script>"));
                        return;
                    }
                    Js.run(ctx.sender(), script);
                });

        // Gated in the handler, like /ban; the picker and confirmation live in FreeMenu.
        commands.command("free").client()
                .args("player:text?")
                .description("Admin only: free a locked account. No name opens a picker of the locked ones.")
                .run(ctx -> free.handleFree(ctx.raw(), ctx.sender()));

        commands.installClient(handler);
    }
}
