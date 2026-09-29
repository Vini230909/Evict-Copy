// Console commands: one entry each, nothing else. Registered by EvictMapPlugin.
package Extinction.commands;

import Extinction.BanLog;
import Extinction.Config;
import Extinction.DiscordStatus;
import Extinction.LockList;
import Extinction.Matches;
import Extinction.PlayerLock;
import Extinction.PlayerStats;
import Extinction.Restart;
import Extinction.RoundTime;
import Extinction.WordFilter;
import Extinction.core.cmd.Commands;
import Extinction.core.util.PluginLog;

import arc.util.CommandHandler;
import arc.util.Strings;

import java.util.List;

public final class Console {

    private Console() {
    }

    public static void register(CommandHandler handler, Matches matches, RoundTime roundTime, PlayerStats playerStats, Restart restart, BanLog banLog, DiscordStatus discordStatus, PlayerLock lock) {
        Commands commands = new Commands();

        commands.command("matchstatus").console()
                .description("The worker pool: its settings, the active match servers and who is in them.")
                .run(ctx -> {
                    PluginLog.info("duel server: @", duelServerSettings());
                    matches.logStatus();
                });

        commands.command("playerinfo").console()
                .args("query:text?")
                .description("Look up a stored player by name or UUID; no argument lists all.")
                .run(ctx -> playerStats.log(ctx.str("query", "").trim()));

        commands.command("elo").console()
                .args("name/uuid:string", "value:string")
                .description("Set a stored player's ranked ELO.")
                .run(ctx -> playerStats.setElo(ctx.raw()));

        commands.command("round").console()
                .args("action:string?", "value:string?")
                .description("This round: team assignment and elapsed time; 'time <seconds>' sets the time.")
                .run(ctx -> {
                    String action = ctx.str("action", "").trim().toLowerCase();
                    String value = ctx.str("value", "").trim();

                    switch (action) {
                        case "" -> roundTime.logStatus();
                        case "time" -> {
                            if (value.isEmpty()) {
                                roundTime.logElapsed();
                            } else {
                                roundTime.setElapsed(value);
                            }
                        }
                        default -> PluginLog.err("Use: round [time <seconds>]");
                    }
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

        // discordStatus is null on a duel worker, which never reports to Discord.
        commands.command("discordstatus").console()
                .args("url/off/test:string?")
                .description("Discord webhook for the live status message.")
                .run(ctx -> {
                    String argument = ctx.str("url/off/test", "").trim();

                    if (discordStatus == null) {
                        PluginLog.err("Discord status reporting only runs on the hub.");
                        return;
                    }

                    switch (argument.toLowerCase()) {
                        case "" -> PluginLog.info("Discord status: @", discordStatus.statusLine());
                        case "off" -> {
                            discordStatus.disable();
                            PluginLog.info("Discord status reporting is off; the message now reads Offline.");
                        }
                        case "test" -> {
                            discordStatus.publishNow();
                            PluginLog.info("Discord status update requested.");
                        }
                        default -> {
                            if (discordStatus.configure(argument)) {
                                PluginLog.info("Discord webhook set. A fresh status message is being posted.");
                            } else {
                                PluginLog.err("That is not a Discord webhook URL. Copy it from Channel Settings > Integrations > Webhooks.");
                            }
                        }
                    }
                });

        // banLog is null on a duel worker: the hub owns the ban log.
        commands.command("banlog").console()
                .args("action:string?")
                .description("Discord ban log: status, <webhook-url>, off, test. Staff-only: it posts IPs.")
                .run(ctx -> {
                    String argument = ctx.str("action", "").trim();

                    if (banLog == null) {
                        PluginLog.err("The ban log only runs on the hub.");
                        return;
                    }

                    switch (argument.toLowerCase()) {
                        case "" -> PluginLog.info("Discord ban log: @", banLog.statusLine());
                        case "off" -> {
                            banLog.disable();
                            PluginLog.info("Discord ban logging is off.");
                        }
                        case "test" -> {
                            if (banLog.publishTest()) {
                                PluginLog.info("Test entry queued.");
                            } else {
                                PluginLog.err("No ban-log webhook is set.");
                            }
                        }
                        default -> {
                            if (banLog.configure(argument)) {
                                PluginLog.info("Ban-log webhook set. Bans will be posted there from now on.");
                            } else {
                                PluginLog.err("That is not a Discord webhook URL. Copy it from Channel Settings > Integrations > Webhooks.");
                            }
                        }
                    }
                });

        // lock is null on a duel worker: the hub owns the lock list.
        commands.command("free").console()
                .args("target:text?")
                .description("Free a locked account by name or UUID; no argument lists the locked ones.")
                .run(ctx -> {
                    String target = ctx.str("target", "").trim();

                    if (lock == null) {
                        PluginLog.err("Locks are freed on the hub.");
                        return;
                    }

                    if (target.isEmpty()) {
                        List<LockList.Entry> entries = lock.lockedEntries();

                        if (entries.isEmpty()) {
                            PluginLog.info("No account is locked.");
                            return;
                        }

                        PluginLog.info("@ locked account(s):", entries.size());

                        for (LockList.Entry entry : entries) {
                            PluginLog.info("  @ (@) from @ - @", Strings.stripColors(entry.name()), entry.uuid(), entry.ip(), entry.reason());
                        }

                        return;
                    }

                    List<LockList.Entry> found = lock.matching(target);

                    if (found.isEmpty()) {
                        PluginLog.err("No locked account matches '@'. 'free' lists them.", target);
                        return;
                    }

                    if (found.size() > 1) {
                        PluginLog.err("'@' matches @ locked accounts - give the UUID:", target, found.size());

                        for (LockList.Entry entry : found) {
                            PluginLog.info("  @ (@)", Strings.stripColors(entry.name()), entry.uuid());
                        }

                        return;
                    }

                    PlayerLock.FreeResult result = lock.free(found.get(0).uuid(), "the console");

                    if (result.freed()) {
                        PluginLog.info("@", result.line());
                    } else {
                        PluginLog.err("@", result.line());
                    }
                });

        commands.command("restart").console()
                .args("action:string?")
                .description("Queue a graceful restart; 'cancel' drops it, 'now' exits.")
                .run(ctx -> {
                    switch (ctx.str("action", "").trim().toLowerCase()) {
                        case "" -> restart.request();
                        case "cancel" -> restart.cancel();
                        case "now" -> restart.now();
                        default -> PluginLog.err("Use: restart [cancel/now]");
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
