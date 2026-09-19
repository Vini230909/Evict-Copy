// Console commands: one entry each, nothing else. Registered by EvictMapPlugin.
package Extinction.commands;

import Extinction.Config;
import Extinction.WordFilter;
import Extinction.core.cmd.Commands;
import Extinction.core.util.PluginLog;

import arc.util.CommandHandler;

public final class Console {

    private Console() {
    }

    public static void register(CommandHandler handler) {
        Commands commands = new Commands();

        commands.command("evictwordfilter").console()
                .args("action:string?", "text:text?")
                .description("Banned-word filter: status, on/off, or test a line.")
                .run(ctx -> {
                    String action = ctx.str("action", "").trim().toLowerCase();
                    String text = ctx.str("text", "");

                    switch (action) {
                        case "" -> PluginLog.info(
                                "Word filter: @, watching @ word(s) everywhere plus @ banned in names only. Bans on chat and on names. Edit the lists in BannedWords.java and rebuild; 'evictwordfilter test <text>' tries a line.",
                                Config.wordFilter ? "on" : "off",
                                WordFilter.wordCount(),
                                WordFilter.nameWordCount()
                        );
                        case "on" -> {
                            Config.wordFilter = true;
                            Config.save();
                            PluginLog.info("Word filter on: a filtered word now bans automatically.");
                        }
                        case "off" -> {
                            Config.wordFilter = false;
                            Config.save();
                            PluginLog.info("Word filter off. Nothing is filtered until it is switched back on.");
                        }
                        case "test" -> {
                            if (text.isBlank()) {
                                PluginLog.err("Give the text to try: evictwordfilter test <text>");
                                return;
                            }

                            String word = WordFilter.find(text);

                            if (word != null) {
                                PluginLog.info("Would ban in chat and as a name: matches '@' from the word list.", word);
                                return;
                            }

                            String name = WordFilter.findNameOnly(text);

                            if (name == null) {
                                PluginLog.info("Clean - no ban.");
                            } else {
                                PluginLog.info("Would ban as a player name only: matches '@' from the name list. The same text in chat passes.", name);
                            }
                        }
                        default -> PluginLog.err("Usage: evictwordfilter [on/off/test <text>]");
                    }
                });

        commands.installConsole(handler);
    }
}
