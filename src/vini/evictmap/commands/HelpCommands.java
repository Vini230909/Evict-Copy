package vini.evictmap.commands;

import arc.math.Mathf;
import arc.struct.Seq;
import arc.util.CommandHandler;
import arc.util.CommandHandler.Command;
import arc.util.Strings;
import mindustry.gen.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;


/**
 * Replaces vanilla /help with the Evict help menu.
 * /help never lists itself, and an alias registration (description
 * "Alias for /x.") gets no row of its own - it is folded into its target's
 * row, e.g. "/history (/h)". Permission-gated commands such as /ban stay gated
 * at execution; the menu only lists them.
 * Supported forms:
 * - /help
 * - /help 2
 */
public final class HelpCommands {

    private static final int COMMANDS_PER_PAGE = 6;

    /**
     * Description prefix that marks a registered command as a pure alias of
     * another command.
     */
    private static final String ALIAS_DESCRIPTION_PREFIX = "Alias for /";

    /**
     * The order the menu lists commands in - registration order mixes vanilla
     * and plugin commands and reads arbitrarily. A registered command that is
     * not named here still shows up, after these rows, so a new command is
     * never silently invisible.
     */
    private static final List<String> DISPLAY_ORDER = List.of(
            "t",
            "sync",
            "invite",
            "die",
            "over",
            "time",
            "play",
            "view",
            "fullassault",
            "history",
            "info",
            "top",
            "a",
            "ban"
    );

    /**
     * Commands that exist but are never listed: /help itself, plus the vanilla
     * vote-kick pair, which is disabled on this server.
     */
    private static final Set<String> HIDDEN = Set.of(
            "help",
            "votekick",
            "vote"
    );

    void registerClientCommands(CommandHandler handler) {
        /*
         * CommandHandler.register intentionally replaces any earlier command
         * with the same name. NetServer registers vanilla /help before plugins
         * register their client commands, so registering this last gives Evict
         * a filtered help menu without modifying Mindustry itself.
         */
        handler.<Player>register(
                "help",
                "[page]",
                "Lists commands.",
                (args, player) -> showHelp(handler, args, player)
        );
    }

    private void showHelp(
            CommandHandler handler,
            String[] args,
            Player player
    ) {
        Integer page = parsePage(args, player);

        if (page == null) {
            return;
        }

        List<HelpEntry> entries = entriesFor(handler);

        int pages = Math.max(
                1,
                Mathf.ceil((float) entries.size() / COMMANDS_PER_PAGE)
        );

        if (page < 1 || page > pages) {
            player.sendMessage(
                    "[scarlet]'page' must be a number between[orange] 1[] and[orange] "
                            + pages
                            + "[scarlet]."
            );
            return;
        }

        int pageIndex = page - 1;

        StringBuilder result = new StringBuilder();

        result.append(
                Strings.format(
                        "[orange]-- Page[lightgray] @[gray]/[lightgray]@[orange] --\n\n",
                        page,
                        pages
                )
        );

        int start = COMMANDS_PER_PAGE * pageIndex;
        int end = Math.min(start + COMMANDS_PER_PAGE, entries.size());

        for (int index = start; index < end; index++) {
            appendEntry(result, entries.get(index));
        }

        if (pages > 1 && page < pages) {
            result.append("\n[lightgray]Next page: [orange]/help ")
                    .append(page + 1)
                    .append("[]");
        }

        player.sendMessage(result.toString());
    }

    /**
     * The rows /help shows: hidden and alias registrations are dropped, every
     * remaining command becomes one row carrying its aliases, and the rows are
     * put into {@link #DISPLAY_ORDER} (anything unlisted trails behind in
     * registration order).
     */
    private List<HelpEntry> entriesFor(CommandHandler handler) {
        Seq<Command> commands = handler.getCommandList();

        Map<String, List<String>> aliasesByTarget = new LinkedHashMap<>();

        for (Command command : commands) {
            String target = aliasTarget(command);

            if (target != null) {
                aliasesByTarget
                        .computeIfAbsent(target, ignored -> new ArrayList<>())
                        .add(command.text);
            }
        }

        List<HelpEntry> entries = new ArrayList<>();

        for (Command command : commands) {
            if (
                    HIDDEN.contains(command.text)
                            || aliasTarget(command) != null
            ) {
                continue;
            }

            entries.add(new HelpEntry(
                    command,
                    aliasesByTarget.getOrDefault(command.text, List.of())
            ));
        }

        entries.sort((left, right) -> Integer.compare(
                displayRank(left.command().text),
                displayRank(right.command().text)
        ));

        return entries;
    }

    /**
     * Position in the menu; an unlisted command sorts after every listed one.
     * The sort is stable, so unlisted commands keep their registration order.
     */
    private static int displayRank(String name) {
        int index = DISPLAY_ORDER.indexOf(name);

        return index < 0 ? DISPLAY_ORDER.size() : index;
    }

    /**
     * The command name an alias registration points at ("Alias for /play."
     * gives "play"), or null when the registration is a real command.
     */
    private static String aliasTarget(Command command) {
        if (
                command.description == null
                        || !command.description.startsWith(ALIAS_DESCRIPTION_PREFIX)
        ) {
            return null;
        }

        String target = command.description
                .substring(ALIAS_DESCRIPTION_PREFIX.length())
                .trim();

        return target.endsWith(".")
                ? target.substring(0, target.length() - 1)
                : target;
    }

    private static void appendEntry(StringBuilder result, HelpEntry entry) {
        Command command = entry.command();

        result.append("[orange] /").append(command.text);

        if (!entry.aliases().isEmpty()) {
            result.append("[white] (");

            for (int index = 0; index < entry.aliases().size(); index++) {
                if (index > 0) {
                    result.append(", ");
                }

                result.append("/").append(entry.aliases().get(index));
            }

            result.append(")");
        }

        result.append("[white] ")
                .append(command.paramText)
                .append("[lightgray] - ")
                .append(command.description)
                .append("\n");
    }

    private Integer parsePage(String[] args, Player player) {
        if (args.length == 0) {
            return 1;
        }

        if (!Strings.canParseInt(args[0])) {
            player.sendMessage("[scarlet]'page' must be a number.");
            return null;
        }

        return Strings.parseInt(args[0]);
    }

    /**
     * One rendered help row: a real command plus the alias names folded into
     * it.
     */
    private record HelpEntry(
            Command command,
            List<String> aliases
    ) {
    }
}
