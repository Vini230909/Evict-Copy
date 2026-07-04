package vini.evictmap.commands;

import vini.evictmap.*;

import arc.math.Mathf;
import arc.struct.Seq;
import arc.util.CommandHandler;
import arc.util.CommandHandler.Command;
import arc.util.Strings;
import mindustry.gen.Player;


/**
 * Replaces vanilla /help with a filtered Evict help menu.
 * Normal /help never exposes development commands. Development commands remain
 * discoverable for every player through /help dev and are paginated separately.
 * Supported forms:
 * - /help
 * - /help 2
 * - /help dev
 * - /help dev 2
 */
public final class EvictHelpCommands {

    private static final int COMMANDS_PER_PAGE = 6;

    void registerClientCommands(CommandHandler handler) {
        /*
         * CommandHandler.register intentionally replaces any earlier command
         * with the same name. NetServer registers vanilla /help before plugins
         * register their client commands, so registering this last gives Evict
         * a filtered help menu without modifying Mindustry itself.
         */
        handler.<Player>register(
                "help",
                "[category] [page]",
                "Lists commands.",
                (args, player) -> showHelp(handler, args, player)
        );
    }

    private void showHelp(
            CommandHandler handler,
            String[] args,
            Player player
    ) {
        HelpRequest request = parseRequest(args, player);

        if (request == null) {
            return;
        }

        Seq<Command> commands = handler.getCommandList();

        int pages = Math.max(
                1,
                Mathf.ceil((float) commands.size / COMMANDS_PER_PAGE)
        );

        if (request.page < 1 || request.page > pages) {
            player.sendMessage(
                    "[scarlet]'page' must be a number between[orange] 1[] and[orange] "
                            + pages
                            + "[scarlet]."
            );
            return;
        }

        int pageIndex = request.page - 1;

        StringBuilder result = new StringBuilder();

        result.append(
                Strings.format(
                        "[orange]-- Page[lightgray] @[gray]/[lightgray]@[orange] --\n\n",
                        request.page,
                        pages
                )
        );

        int start = COMMANDS_PER_PAGE * pageIndex;
        int end = Math.min(start + COMMANDS_PER_PAGE, commands.size);

        for (int index = start; index < end; index++) {
            Command command = commands.get(index);

            result.append("[orange] /")
                    .append(command.text)
                    .append("[white] ")
                    .append(command.paramText)
                    .append("[lightgray] - ")
                    .append(command.description)
                    .append("\n");
        }

        if (request.devCommands && pages > 1 && request.page < pages) {
            result.append("\n[lightgray]Next page: [orange]/help dev ").append(request.page + 1).append("[]");
        }

        player.sendMessage(result.toString());
    }

    private HelpRequest parseRequest(String[] args, Player player) {
        if (args.length == 0) {
            return new HelpRequest(false, 1);
        }

        if (args.length == 1) {
            Integer page = parsePositivePage(args[0], player);

            return page == null ? null : new HelpRequest(false, page);
        }

        player.sendMessage(
                "[scarlet]Use: /help [page] or /help dev [page][]"
        );

        return null;
    }

    private Integer parsePositivePage(String value, Player player) {
        if (!Strings.canParseInt(value)) {
            player.sendMessage("[scarlet]'page' must be a number.");
            return null;
        }

        return Strings.parseInt(value);
    }

    private record HelpRequest(
            boolean devCommands,
            int page
    ) {
    }
}
