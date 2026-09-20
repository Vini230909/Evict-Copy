// Console commands: one entry each, nothing else. Registered by EvictMapPlugin.
package Extinction.commands;

import Extinction.Config;
import Extinction.Matches;
import Extinction.WordFilter;
import Extinction.core.cmd.Commands;
import Extinction.core.util.PluginLog;

import arc.util.CommandHandler;

public final class Console {

    private Console() {
    }

    public static void register(CommandHandler handler, Matches matches) {
        Commands commands = new Commands();

        commands.command("matchstatus").console()
                .description("The worker pool: its settings, the active match servers and who is in them.")
                .run(ctx -> {
                    PluginLog.info("duel server: @", duelServerSettings());
                    matches.logStatus();
                });

        commands.command("wordfilter").console()
                .args("action:string?", "text:text?")
                .description("Banned-word filter: status, on/off, or test a line.")
                .run(ctx -> {
                    String action = ctx.str("action", "").trim().toLowerCase();
                    String text = ctx.str("text", "");

                    switch (action) {
                        case "" -> PluginLog.info(
                                "Word filter: @, watching @ word(s) everywhere plus @ banned in names only. Bans on chat and on names. Edit the lists in BannedWords.java and rebuild; 'wordfilter test <text>' tries a line.",
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
                                PluginLog.err("Give the text to try: wordfilter test <text>");
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
                        default -> PluginLog.err("Usage: wordfilter [on/off/test <text>]");
                    }
                });

        commands.installConsole(handler);
    }

    // "<ip> ports a-b (n workers, map=m)", or "not set" while /play is off.
    private static String duelServerSettings() {
        if (Config.duelServerIp.isBlank()) {
            return "not set";
        }

        int lastPort = Config.duelServerPort + Config.duelMaxWorkers - 1;

        return Config.duelServerIp
                + " ports " + Config.duelServerPort + "-" + lastPort
                + " (" + Config.duelMaxWorkers + " workers, map=" + Config.duelWorkerMap + ")";
    }
}
