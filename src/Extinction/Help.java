// The /help menu: which registered commands it lists, in what order, and how a page renders.
package Extinction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import arc.math.Mathf;
import arc.struct.Seq;
import arc.util.CommandHandler;
import arc.util.CommandHandler.Command;
import arc.util.Strings;

public final class Help {

    private static final int COMMANDS_PER_PAGE = 6;

    // A registration whose description starts with this is a pure alias and folds into its target's row.
    private static final String ALIAS_DESCRIPTION_PREFIX = "Alias for /";

    // Menu order; a registered command not named here still shows up, after these rows.
    static final List<String> DISPLAY_ORDER = List.of(
            "t",
            "sync",
            "invite",
            "die",
            "over",
            "time",
            "play",
            "spectate",
            "fullassault",
            "history",
            "info",
            "top",
            "a",
            "ban",
            "free",
            "js"
    );

    // Never listed: /help itself, plus vanilla's vote-kick pair, which is disabled on this server.
    static final Set<String> HIDDEN = Set.of(
            "help",
            "votekick",
            "vote"
    );

    // One rendered row: a real command plus the alias names folded into it.
    private record Entry(Command command, List<String> aliases) {
    }

    private Help() {
    }

    // The one message /help [page] answers with: the page, or why the page argument was refused.
    public static String page(CommandHandler handler, String[] args) {
        if (args.length > 0 && !Strings.canParseInt(args[0])) {
            return "[scarlet]'page' must be a number.";
        }

        int page = args.length == 0 ? 1 : Strings.parseInt(args[0]);
        List<Entry> entries = entriesFor(handler);
        int pages = Math.max(1, Mathf.ceil((float) entries.size() / COMMANDS_PER_PAGE));

        if (page < 1 || page > pages) {
            return "[scarlet]'page' must be a number between[orange] 1[] and[orange] "
                    + pages
                    + "[scarlet].";
        }

        StringBuilder result = new StringBuilder();
        result.append(Strings.format("[orange]-- Page[lightgray] @[gray]/[lightgray]@[orange] --\n\n", page, pages));

        int start = COMMANDS_PER_PAGE * (page - 1);
        int end = Math.min(start + COMMANDS_PER_PAGE, entries.size());

        for (int index = start; index < end; index++) {
            appendEntry(result, entries.get(index));
        }

        if (pages > 1 && page < pages) {
            result.append("\n[lightgray]Next page: [orange]/help ").append(page + 1).append("[]");
        }

        return result.toString();
    }

    // Hidden and alias registrations are dropped; the rest become rows in DISPLAY_ORDER, unlisted ones trailing.
    private static List<Entry> entriesFor(CommandHandler handler) {
        Seq<Command> commands = handler.getCommandList();
        Map<String, List<String>> aliasesByTarget = new LinkedHashMap<>();

        for (Command command : commands) {
            String target = aliasTarget(command);
            if (target != null) {
                aliasesByTarget.computeIfAbsent(target, ignored -> new ArrayList<>()).add(command.text);
            }
        }

        List<Entry> entries = new ArrayList<>();

        for (Command command : commands) {
            if (HIDDEN.contains(command.text) || aliasTarget(command) != null) {
                continue;
            }
            entries.add(new Entry(command, aliasesByTarget.getOrDefault(command.text, List.of())));
        }

        // Stable sort, so unlisted commands keep their registration order.
        entries.sort((left, right) -> Integer.compare(
                displayRank(left.command().text),
                displayRank(right.command().text)
        ));

        return entries;
    }

    private static int displayRank(String name) {
        int index = DISPLAY_ORDER.indexOf(name);
        return index < 0 ? DISPLAY_ORDER.size() : index;
    }

    // "Alias for /play." gives "play"; null when the registration is a real command.
    private static String aliasTarget(Command command) {
        if (command.description == null || !command.description.startsWith(ALIAS_DESCRIPTION_PREFIX)) {
            return null;
        }

        String target = command.description.substring(ALIAS_DESCRIPTION_PREFIX.length()).trim();
        return target.endsWith(".") ? target.substring(0, target.length() - 1) : target;
    }

    private static void appendEntry(StringBuilder result, Entry entry) {
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
}
