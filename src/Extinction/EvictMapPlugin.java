package Extinction;

import Extinction.gen.*;
import Extinction.data.*;
import Extinction.round.*;

import arc.Core;
import arc.Events;
import arc.util.CommandHandler;
import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mindustry.gen.Groups;
import mindustry.game.EventType.GameOverEvent;
import mindustry.game.EventType.PlayEvent;
import mindustry.game.EventType.PlayerJoin;
import mindustry.game.EventType.PlayerLeave;
import mindustry.game.EventType.TileChangeEvent;
import mindustry.game.EventType.TilePreChangeEvent;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.game.Team;
import mindustry.gen.Player;
import mindustry.mod.Plugin;
import mindustry.world.blocks.storage.CoreBlock;
import Extinction.gameplay.AttritionManager;
import Extinction.gameplay.RulesApplier;
import Extinction.gameplay.AttackManager;
import Extinction.gameplay.WaveExtinction;
import Extinction.discord.DiscordStatusReporter;
import Extinction.commands.*;
import Extinction.core.util.MessageIdFilter;
import Extinction.core.util.PluginLog;

import java.util.HashMap;

/**
 * Plugin composition root.
 * This class intentionally contains only lifecycle wiring. Game systems live
 * in focused classes such as EvictTerrainGenerator, CaptureManager,
 * TeamManager and the command registrars.
 */
public class EvictMapPlugin extends Plugin {

    private static final float CONNECTED_PLAYER_SCAN_INITIAL_DELAY_TICKS = 1f;
    private static final float CONNECTED_PLAYER_SCAN_INTERVAL_TICKS = 15f;
    private static final int CONNECTED_PLAYER_SCAN_ATTEMPTS = 120;
    /**
     * How often the worker status files are polled. Named for the advertised
     * player count it was written for, but that poll is now what carries every
     * worker's chat, playtime and ban requests to the hub. The reads sit on a
     * background thread and cover at most ten small files.
     */
    private static final long ADVERTISED_COUNT_REFRESH_MILLIS = 1000L;

    /**
     * How long the exit's shutdown hooks may take before the halt guard
     * forces the JVM down. Generous: the hooks (worker cleanup, player-DB
     * flush) are themselves capped well below this.
     */
    private static final long EXIT_HALT_GUARD_MILLIS = 10_000L;

    /**
     * When launched with -Devict.duelWorker=true this process is a spawned 1v1
     * worker. It runs Evict normally but shuts itself down once the match is
     * empty so the hub can free the slot.
     *
     * <p>Declared first: several managers below are wired differently on a
     * worker, and a field initialiser can only read fields declared above it.
     */
    private final boolean duelWorker =
            "true".equals(System.getProperty("evict.duelWorker"));

    private final EvictRuntimeState runtime = new EvictRuntimeState();
    private final EvictSettings settings = new EvictSettings();
    private final PlayerDataManager playerDataManager =
            new PlayerDataManager();
    private final AdminSync adminSync = new AdminSync();

    private final TeamManager teamManager =
            new TeamManager(this::handleVictory);

    private final Referee duelWorkerReferee = new Referee();

    private final MatchChat duelChat = new MatchChat(duelWorkerReferee);

    private final AttritionManager attritionManager =
            new AttritionManager(teamManager, settings);

    private final InviteManager inviteManager =
            new InviteManager(teamManager);

    /**
     * The late-game collapse. Driven by the round clock alone; there is no
     * command that starts it.
     */
    private final WaveExtinction waveExtinction =
            new WaveExtinction(teamManager);

    private final AttackManager attackManager =
            new AttackManager(
                    teamManager
            );

    private final RoundEnd roundEnd = new RoundEnd(teamManager, duelWorkerReferee);

    private final RoundTime roundTime = new RoundTime(teamManager);

    /**
     * Hub-only Discord chat mirror (one channel for the hub, one per worker
     * port). Constructed on a worker too, but never started there - a worker
     * reports its chat through its chat.log file instead.
     */
    private final Extinction.discord.ChatLogReporter chatLogReporter =
            new Extinction.discord.ChatLogReporter(settings);

    /** Worker only: where captured chat lines go for the hub to relay. */
    private final Extinction.discord.ChatLogFile chatLogFile =
            new Extinction.discord.ChatLogFile();

    /**
     * Captures chat, commands, joins and leaves for the chat mirror. The sink
     * is the only difference between the roles: the hub queues lines for
     * Discord directly, a worker appends them to its chat.log.
     */
    private final Extinction.discord.ChatLogCapture chatLogCapture =
            new Extinction.discord.ChatLogCapture(
                    duelWorker ? chatLogFile::append : chatLogReporter::hubLine
            );

    // The hub's match worker pool; inert on a worker (its copied settings blank the ip).
    private final Matches matches =
            new Matches(playerDataManager, this::seedBan, chatLogReporter);

    private final Extinction.metrics.MetricsReporter metricsReporter =
            new Extinction.metrics.MetricsReporter(
                    () -> matches.activeDuels().size(),
                    matches::connectedDuelPlayers
            );

    private final Matchmaking matchmaking = new Matchmaking(matches);
    private final SpectateMenu spectateMenu = new SpectateMenu(matches, duelWorkerReferee);

    private final HistoryCommands historyCommands =
            new HistoryCommands(playerDataManager);

    private final InfoCommands infoCommands =
            new InfoCommands(playerDataManager);

    /**
     * /ban works on the hub and on a match server; a worker's ban is applied
     * locally and forwarded to the hub, which owns bans.
     */
    private final BanCommands banCommands =
            new BanCommands(playerDataManager, !duelWorker, this::seedBan);

    /** /js: the console's js command in chat, admin-only, hub and worker. */
    private final JsCommands jsCommands =
            new JsCommands();

    private final LeaderboardCommands leaderboardCommands =
            new LeaderboardCommands(playerDataManager);

    private final ClientCommands clientCommands =
            new ClientCommands(
                    attackManager,
                    inviteManager,
                    historyCommands,
                    infoCommands,
                    banCommands,
                    jsCommands,
                    leaderboardCommands
            );

    private final EvictTerrainGenerator terrainGenerator =
            new EvictTerrainGenerator(settings);

    /**
     * Graceful-restart coordinator. The plugin only exits cleanly; an external
     * start-script loop (docs/RESTART_LOOP.md) brings the server back up.
     */
    private final RestartManager restartManager =
            new RestartManager(
                    () -> matches.activeDuels().size(),
                    Groups.player::size,
                    teamManager::roundRuntimeMillis,
                    this::exitHubProcess
            );

    /**
     * Hub-only live status message in Discord. Constructed on a worker too (it
     * is a plain object with no side effects until started), but only the hub
     * ever calls start()/update() - four workers editing the same message would
     * fight over it.
     */
    private final DiscordStatusReporter discordStatusReporter =
            new DiscordStatusReporter(
                    settings,
                    playerDataManager,
                    teamManager,
                    waveExtinction,
                    matches,
                    restartManager
            );

    /**
     * Hub-only ban log. Constructed on a worker too (inert until started), but
     * only the hub ever starts it - each worker would report the same ban.
     */
    private final Extinction.discord.BanLogReporter banLogReporter =
            new Extinction.discord.BanLogReporter(settings);

    /**
     * Hub-only VPN scan, log only: the address of every join is looked up and
     * a VPN or proxy is written to the console and the ban log, with the
     * account's age next to it. Decides nothing - it is the week of evidence
     * the rule against ban evasion is to be drawn from.
     */
    private final Extinction.moderation.vpn.VpnScan vpnScan =
            new Extinction.moderation.vpn.VpnScan(
                    settings,
                    banLogReporter::logVpnHit,
                    banLogReporter::logVpnTest,
                    banLogReporter::isConfigured,
                    playerDataManager::findPlayerInfoByUuid
            );

    /**
     * The lock itself, both roles: what a locked account cannot do, and who
     * is locked. The hub owns the list and writes it; a worker follows it.
     */
    private final Extinction.moderation.lock.PlayerLock playerLock =
            new Extinction.moderation.lock.PlayerLock(
                    !duelWorker,
                    settings::banAppealUrl,
                    // A freed player who is online gets their team and hex
                    // the way a fresh join would.
                    teamManager::releaseLocked,
                    duelWorker ? null : banLogReporter::logLock
            );

    /**
     * Hub only: the decision at the door - a first join through a VPN is
     * parked until its verdict is in and locked when the verdict says so.
     */
    private final Extinction.moderation.lock.LockGate lockGate =
            new Extinction.moderation.lock.LockGate(
                    settings::vpnLockEnabled,
                    playerLock,
                    vpnScan,
                    teamManager::assignLocked,
                    teamManager::releaseLocked
            );

    /** /free: the in-game way to free a locked account, with /ban's picker UX. */
    private final Extinction.commands.FreeCommands freeCommands =
            new Extinction.commands.FreeCommands(playerLock);

    /**
     * What a banned player reads: the ban plus the Discord invite to appeal it.
     * Every ban kick goes through it, on the hub and on a match server, and it
     * refuses a banned player's later join attempts with the same text.
     */
    private final Extinction.moderation.ban.BanScreen banScreen =
            new Extinction.moderation.ban.BanScreen(settings::banAppealUrl);

    /**
     * Widens every ban to the accounts and addresses linked to it, and writes
     * the result where the duel workers can see it. Hub only: the hub decides
     * who is banned, the workers apply it.
     */
    private final Extinction.moderation.ban.BanManager banManager =
            new Extinction.moderation.ban.BanManager(
                    settings,
                    banLogReporter::log,
                    banLogReporter::logImport,
                    // The ban announcement was visible in the hub's chat, so
                    // the chat mirror shows the same line.
                    line -> chatLogReporter.hubLine(
                            Extinction.discord.DiscordFormat.playerText(line)
                    ),
                    banScreen
            );

    /**
     * Turns a Discord {@code /ban} or {@code /unban} into an ordinary ban, so
     * it is widened, kicked, synced, announced and logged like any other.
     */
    private final Extinction.moderation.ban.RemoteBan remoteBan =
            new Extinction.moderation.ban.RemoteBan(this::seedBan);

    /**
     * Hub-only Discord slash commands. The hub is the single writer of bans,
     * and one bot answering per match server would be four answers to one
     * command.
     */
    private final Extinction.discord.DiscordModCommands discordModCommands =
            new Extinction.discord.DiscordModCommands(
                    settings,
                    remoteBan::ban,
                    remoteBan::unban,
                    (target, actor) -> playerLock.free(target.trim(), actor).line()
            );

    /** Worker only: applies the hub's ban list to this match server. */
    private final Extinction.moderation.ban.BanSync banSync =
            new Extinction.moderation.ban.BanSync(banScreen);

    /**
     * Worker only: hands a ban made here to the hub. Without it the ban lives
     * only in this worker's throwaway admin store, is lifted again by the next
     * sync, and never reaches the hub, the other match servers or the log.
     */
    private final Extinction.moderation.ban.BanForwarder banForwarder =
            new Extinction.moderation.ban.BanForwarder(
                    duelWorkerReferee::requestBan,
                    banSync::isApplying,
                    banScreen
            );

    /** Bans anyone using a filtered word in chat or in their name. */
    private final WordFilter wordFilter = new WordFilter(!duelWorker, this::seedBan, banScreen);

    private final ConsoleCommands consoleCommands =
            new ConsoleCommands(
                    runtime,
                    settings,
                    terrainGenerator,
                    teamManager,
                    playerDataManager,
                    restartManager,
                    duelWorker ? null : discordStatusReporter,
                    duelWorker ? null : banLogReporter,
                    duelWorker ? null : banManager,
                    duelWorker ? null : vpnScan,
                    duelWorker ? null : playerLock,
                    duelWorker ? null : chatLogReporter,
                    duelWorker ? null : discordModCommands,
                    // oregen gen regenerates the live map in place with no fresh snapshot,
                    // so connected clients only see the new terrain via the per-tile sync.
                    seed -> generate(seed, true)
            );

    private boolean refreshingWorldIndexes = false;
    private long connectedPlayerScanSerial = 0L;
    private int advertisedPlayerCount = -1;
    private long advertisedPlayerCountRefreshedAtMillis = 0L;

    // pre-changing detector
    private final HashMap<Integer, CoreBlock.CoreBuild> prechanged =
            new HashMap<>();

    @Override
    public void init() {
        bootstrap();

        // Both roles: a banned player who comes back is refused with the
        // appeal link, not vanilla's bare "banned" screen.
        banScreen.install();

        // Both roles: a locked account can watch and nothing else - the hub
        // decides who, every server enforces it off the hub's list. The
        // chat mirror marks their lines with a padlock.
        playerLock.install();
        chatLogCapture.setLockMarker(player -> playerLock.isLocked(player.uuid()));

        if (duelWorker) {
            configureWorkerReferee();

            // A ban made on a match server - /ban, the hammer, the console -
            // only sticks if the hub hears about it.
            banForwarder.install();
        } else {
            // Hub only: a server update means a new jar + a restart, so on
            // startup bring every existing duel-worker folder onto the current
            // jar. This keeps idle workers off a version-mismatched server
            // without deleting the folders and losing their logs.
            WorkerFolder.refreshJars();

            // Hub only: the Pure map list, and the next round pinned to the Extinction map.
            PureMaps.load();

            // Hub only: one live status message in Discord. Workers must stay
            // out of it - they would all edit the same message.
            discordStatusReporter.start();

            // Hub only: the hub is the single source of truth for bans. It
            // widens them, writes the list the workers read, and logs them.
            banLogReporter.start();
            banManager.install();

            // Hub only: who arrives through a VPN. The lookup starts the
            // moment a connection opens, so the verdict is in before the
            // join and the lock gate never has to make a newcomer wait.
            vpnScan.start();
            Events.on(mindustry.game.EventType.ConnectionEvent.class, event -> {
                if (event.connection != null) {
                    guarded("vpn prefetch", () -> vpnScan.prefetch(event.connection.address));
                }
            });

            // Hub only: the Discord chat mirror. Worker chat arrives through
            // the chat.log files the duel manager tails.
            chatLogReporter.start();

            // Hub only: Discord's /ban and /unban. Started after the mirror,
            // which shares the same bot token out of the secrets file.
            discordModCommands.start();
        }

        Events.on(WorldLoadEvent.class, event -> {
            if (!runtime.autoGenerate || refreshingWorldIndexes) {
                return;
            }

            long seed = runtime.consumeNextSeed();

            Log.info(
                    "[EvictMapGenerator] World loaded. Generating Evict terrain with seed @.",
                    seed
            );

            try {
                // World (re)load: the vanilla world snapshot already carries the
                // generated terrain to clients, so skip the per-tile client sync.
                // That redundant flood, layered on the snapshot stream, is what
                // dropped connected players with "(error)" at match end.
                generate(seed, false);
            } catch (Exception exception) {
                Log.err(
                        "[EvictMapGenerator] Generation failed.",
                        exception
                );
            }
        });

        Events.on(PlayEvent.class, event -> {
            // A Pure worker hosts a real map with generation off: the referee still runs, the
            // hex rules, hex assignment and the rest below do not.
            duelWorkerReferee.begin();

            if (!runtime.autoGenerate) {
                return;
            }

            scheduleConnectedPlayerAssignmentScan();

            if (duelWorker) {

                // The handshake is loaded now, so the referee knows the mode:
                // gate the victory check on the full roster count, and open
                // the sandbox /invite flow for spectators.
                teamManager.setDuelMinimumTeams(
                        duelWorkerReferee.victoryMinimumTeams()
                );

                // FFA and Teams matches keep running after a surrender, so
                // the surrendered hexes need their Fallen backup cores back;
                // 1v1/Training/Sandbox end right away and leave them derelict.
                MatchMode workerMatchMode = duelWorkerReferee.matchMode();
                teamManager.setDuelSurrenderRestoresFallenCores(
                        workerMatchMode.restoresFallenCoresOnSurrender()
                );

                // FFA has no participant cap; the normal start-hex distance
                // could run every safe hex out before everyone got a start
                // once enough players piled into one duel-worker FFA.
                teamManager.setDuelFfaReducedStartDistance(
                        workerMatchMode.reducedStartDistance()
                );

                if (workerMatchMode.allowsSpectatorInvites()) {
                    inviteManager.enableSandboxJoinMode(
                            duelWorkerReferee.sandbox::addParticipant
                    );
                }
            }

            RulesApplier.applyRules();

            // A sandbox session plays with infinite resources; applyRules
            // resets the flag, so re-apply it after every rules pass.
            if (duelWorker && duelWorkerReferee.matchMode().infiniteResources()) {
                Vars.state.rules.infiniteResources = true;
            }
        });

        // A Pure worker: vanilla PvP's game over names the winner, the referee does the rest.
        Events.on(GameOverEvent.class, event -> {
            if (duelWorker && duelWorkerReferee.matchMode().pure()) {
                duelWorkerReferee.handleVictory(event.winner);
            }
        });

        Events.on(PlayerJoin.class, event -> {
            // First: a hit bans and kicks, so there is nothing to onboard.
            if (wordFilter.checkName(event.player)) {
                return;
            }

            // On a duel worker, restore admin for players the hub synced over;
            // the worker has no access to the hub's own admin list.
            if (duelWorker) {
                adminSync.markSyncedAdmin(event.player);

                // A locked player who hopped over to watch is reminded here
                // too; the list came from the hub.
                guarded("lock join", () -> playerLock.handlePlayerJoin(event.player));
            }

            // On the hub: a player who is mid-duel is bounced straight back to
            // their worker instead of being onboarded into the FFA round.
            if (
                    !duelWorker
                            && matches.tryReturnToActiveDuel(event.player)
            ) {
                // Still written down if they came through a VPN.
                guarded("vpn scan", () -> vpnScan.handlePlayerJoin(event.player, null));
                return;
            }

            // On a duel worker anyone who is not a rostered participant is a
            // /spectate spectator: park them on derelict (no cores) and skip the
            // normal FFA onboarding so they only watch.
            if (
                    duelWorker
                            && !duelWorkerReferee.isParticipant(event.player.uuid())
            ) {
                guarded("spectator assign", () -> teamManager.assignSpectator(event.player));
                guarded("duelWorker join", () -> duelWorkerReferee.handlePlayerJoin(event.player));
                event.player.sendMessage(
                        "[accent]Spectating this match. Use [white]/s[accent] to return to the lobby.[]"
                );

                if (duelWorkerReferee.matchMode().allowsSpectatorInvites()) {
                    event.player.sendMessage(
                            "[accent]This is a sandbox - use [white]/invite[accent] to ask to join it.[]"
                    );

                    // Tell the sandbox players someone arrived who could be
                    // invited in.
                    String viewerName =
                            PlayerNameFormatter.displayName(event.player);

                    Groups.player.each(online -> {
                        if (
                                online != null
                                        && online != event.player
                                        && duelWorkerReferee.isParticipant(online.uuid())
                        ) {
                            online.sendMessage(
                                    "[accent]" + viewerName
                                            + "[accent] is watching your sandbox. They can ask to join with [white]/invite[accent]; use [white]/invite[accent] to accept requests.[]"
                            );
                        }
                    });
                }

                return;
            }

            // Each handler runs isolated: one failing must not starve the ones
            // after it - above all the referee's disconnect-pause bookkeeping,
            // which has to see every join or a paused match never resumes.
            guarded("playerData join", () -> playerDataManager.handlePlayerJoin(event.player));
            guarded("roundTime join", () -> roundTime.handlePlayerJoin(event.player));

            // Hub: the lock gate looks at the join first. A locked account
            // stays on the Fallen team; a first join through a VPN is parked
            // until its verdict is in; everyone else is onboarded right away.
            // The gate also hands the join to the VPN scan for its line.
            boolean[] onboardNow = {true};

            if (!duelWorker) {
                guarded("lock gate", () -> onboardNow[0] = lockGate.handlePlayerJoin(event.player));
            }

            if (onboardNow[0]) {
                guarded("teamManager join", () -> teamManager.handlePlayerJoin(event.player));
            }

            guarded("duelWorker join", () -> duelWorkerReferee.handlePlayerJoin(event.player));
        });

        Events.on(PlayerLeave.class, event -> {
            guarded("playerData leave", () -> playerDataManager.handlePlayerLeave(event.player));
            guarded("invite leave", () -> inviteManager.handlePlayerLeave(event.player));
            guarded("matchmaking leave", () -> matchmaking.handlePlayerLeave(event.player));
            guarded("spectate leave", () -> spectateMenu.handlePlayerLeave(event.player));
            guarded("history leave", () -> historyCommands.handlePlayerLeave(event.player));
            guarded("info leave", () -> infoCommands.handlePlayerLeave(event.player));
            guarded("ban leave", () -> banCommands.handlePlayerLeave(event.player));
            guarded("free leave", () -> freeCommands.handlePlayerLeave(event.player));
            guarded("duelWorker leave", () -> duelWorkerReferee.handlePlayerLeave(event.player));
        });

        Events.on(TilePreChangeEvent.class, tilePreChangeEvent -> {
            if (!(tilePreChangeEvent.tile.build instanceof CoreBlock.CoreBuild coreBuild)) {
                return;
            }
            if (coreBuild.health > 0f) {
                return;
            }

            prechanged.put(tilePreChangeEvent.tile.pos(), coreBuild);
        });

        Events.on(TileChangeEvent.class, tileChangeEvent -> {
            CoreBlock.CoreBuild coreBuild = prechanged.remove(tileChangeEvent.tile.pos());
            if (coreBuild == null) {
                return;
            }

            teamManager.coreCapture().handleCoreChange(coreBuild, attritionManager);
        });

        Events.run(Trigger.update, () -> {
            // First, so the managers below already see pause-corrected time.
            teamManager.updatePauseTracking();

            // Scrubs blueprints queued during a disconnect pause; must run in
            // this trigger because it fires even while the game is paused.
            duelWorkerReferee.update();

            // Both roles: a worker follows the hub's lock list, and locked
            // players are kept slow.
            playerLock.update();

            attritionManager.update();
            attackManager.update();
            waveExtinction.update();

            // Only the hub is listed in the multiplayer browser; keep its
            // advertised count folded with the players inside the duel workers.
            if (duelWorker) {
                // Picks up bans made on the hub, including mid-match ones.
                banSync.update();
            } else {
                refreshAdvertisedPlayerCount();

                // Lets a queued restart fire once the hub runs empty, instead
                // of only when the round ends.
                restartManager.update();

                metricsReporter.update();
                discordStatusReporter.update();

                // Runs the one-off import of pre-existing bans once the admin
                // store exists, then paces the ban log's queue.
                banManager.update();
                banLogReporter.update();
                chatLogReporter.update();
            }
        });

        // After the join/leave handlers above: by the time the mirror's join
        // listener runs, a name the word filter banned is already kicked and
        // stays out of the mirror.
        chatLogCapture.installEvents();

        Log.info(
                "[EvictMapGenerator] Loaded. Code revision 1.15.3. Use 'help' for the commands and 'oregen' for the generator settings."
        );
    }

    // Exit path for RestartManager: close the net, then System.exit(0) like a worker (Core.app.exit
    // proved unreliable on the host); the halt guard takes the JVM down if a shutdown hook wedges.
    private void exitHubProcess() {
        Thread haltGuard = new Thread(() -> {
            try {
                Thread.sleep(EXIT_HALT_GUARD_MILLIS);
            } catch (InterruptedException ignored) {
                return;
            }

            System.err.println(
                    "[EvictMapGenerator] Shutdown hooks hung; halting the JVM."
            );
            Runtime.getRuntime().halt(0);
        }, "evict-exit-halt-guard");
        haltGuard.setDaemon(true);
        haltGuard.start();

        Vars.net.dispose();
        System.exit(0);
    }

    /**
     * Puts one account into the ban system, wherever this process happens to
     * be. On the hub {@code BanManager} widens, kicks, syncs, announces and
     * logs it; on a match server it takes effect at once and is forwarded to
     * the hub, which does all of that there.
     *
     * <p>The request carries who decided the ban and, for the word filter, what
     * it saw, so the log entry says more than "an account was banned".
     */
    private void seedBan(Extinction.moderation.ban.BanRequest request) {
        if (request == null || request.isEmpty()) {
            return;
        }

        if (duelWorker) {
            banForwarder.ban(request);
        } else {
            banManager.ban(request);
        }
    }

    /** Loads persisted state and applies the fixed round rules and team wiring. */
    private void bootstrap() {
        // Console readability only, and the first thing set up so it covers
        // everything logged after it. The server installs its own log formatter
        // in the ServerControl constructor, which has already run by now.
        MessageIdFilter.install();

        settings.load();
        Config.load();
        adminSync.load();

        // A duel worker has no player database of its own: it reads the hub's
        // (it runs in duel-workers/duel-<port>/, so the hub config is two levels
        // up) so /info, /top and /history show real numbers on a match
        // server, and it never writes - the hub stays the single writer. Set
        // before start(), which would otherwise create a throwaway local DB.
        if (duelWorker) {
            playerDataManager.useHubDatabase(
                    new java.io.File("../../config/evict-players.db")
            );
        }

        playerDataManager.start();

        // First of all filters: the mirror is a staff channel and shows even
        // what the word filter then drops - the announcement of the resulting
        // ban follows right after it. Logging only, so the word filter's drop
        // and the ranked spectator routing behave exactly as before.
        chatLogCapture.installChatFilter();

        // Before every remaining chat filter, so a hit is dropped rather than
        // handed on to whatever the next filter would do with it.
        wordFilter.install();

        if (duelWorker) {
            // The worker's session bookkeeping is published in status.properties
            // for the hub to credit, so match time counts as playtime.
            duelWorkerReferee.setPlaytimeSource(
                    playerDataManager::sessionPlaytimeSnapshot
            );

            // After the word filter: a blocked message must be gone before
            // the ranked routing can deliver it to the spectators.
            duelChat.installChatFilter();
        } else {
            // A finished ranked match must show up on the Discord ladder now,
            // not whenever its slow refresh comes round.
            playerDataManager.setEloChangeListener(
                    discordStatusReporter::markLadderStale
            );
        }

        // A Pure worker stays vanilla: no Extinction rules, no turret or core-unit damage tweaks.
        if (!PureMatch.pureWorker()) RulesApplier.applyRules();
        teamManager.setInviteManager(inviteManager);
        teamManager.setDuelMode(duelWorker);
    }

    /**
     * Duel-worker-only: teach the team system the roster rules for the match
     * mode - how teammates group, when a leaver pauses the match, and what
     * happens to a wiped-out team (freed as a spectator, or a deciding loss).
     */
    private void configureWorkerReferee() {
        // Teams-mode workers put a whole handshake roster on one Mindustry
        // team; on the hub (and in other modes) the resolver finds no
        // teammates and normal per-player assignment applies.
        teamManager.setTeammateResolver(duelWorkerReferee::rosterTeammates);

        // A leaving participant only pauses the match while their team is
        // still in the running; an eliminated FFA player walking out must
        // not freeze the survivors.
        teamManager.setDuelEliminationHandler(this::freeEliminatedDuelTeam);

        duelWorkerReferee.setStillCompeting(
                player -> player != null
                        && teamManager.isActivePersonalTeam(player.team().id)
        );
    }

    /**
     * A knocked-out FFA or Teams player is free: demoted to a spectator, they
     * can watch, /s back to the lobby, or disconnect - the hub will let them
     * join the main round normally instead of bouncing them back into this
     * match. In two-team games the deciding elimination fires this too,
     * harmlessly: the victory resolves right after from the unchanged rosters.
     */
    private void freeEliminatedDuelTeam(Team team) {
        MatchMode workerMode = duelWorkerReferee.matchMode();

        if (!workerMode.eliminatesWipedTeams()) {
            return;
        }

        for (String uuid : teamManager.playerUuidsForTeam(team)) {
            duelWorkerReferee.demoteToSpectator(uuid);

            Player member = Extinction.core.util.Players.byUuid(uuid);

            if (member != null) {
                teamManager.assignSpectator(member);
                member.sendMessage(
                        "[scarlet]You are out of the "
                                + workerMode.label()
                                + " match.[] [accent]You are now spectating - use [white]/s[accent] to return to the lobby.[]"
                );
            }
        }
    }

    /**
     * Keeps the hub's advertised player count in sync with the FFA hub plus the
     * players in every duel worker. Mindustry's server ping reports the
     * "totalPlayers" setting, falling back to the live hub count, so folding the
     * duel players in makes the multiplayer menu show everyone online.
     *
     * <p>Driven from the persistent {@link Trigger#update} hook rather than a
     * self-rescheduling {@link Time#run} chain: {@code Logic.reset()} calls
     * {@code Time.clear()} on every map/round reload, which would silently
     * destroy such a chain and freeze the count at its last (hub-only) value.
     * The wall-clock throttle keeps the per-frame work cheap.
     */
    private void refreshAdvertisedPlayerCount() {
        if (Time.timeSinceMillis(advertisedPlayerCountRefreshedAtMillis)
                < ADVERTISED_COUNT_REFRESH_MILLIS) {
            return;
        }

        advertisedPlayerCountRefreshedAtMillis = Time.millis();

        int total =
                Groups.player.size() + matches.connectedDuelPlayers();

        if (total != advertisedPlayerCount) {
            advertisedPlayerCount = total;
            Core.settings.put("totalPlayers", total);
        }
    }

    @Override
    public void registerClientCommands(CommandHandler handler) {
        clientCommands.register(handler);

        // /free, and no locked account in any /play picker (an invite is a
        // menu, which the lock's command gate cannot refuse).
        freeCommands.registerClientCommands(handler);
        matchmaking.excludeFromPickers(player -> playerLock.isLocked(player.uuid()));
        Extinction.commands.Player.register(handler, matchmaking, spectateMenu, duelWorkerReferee, roundEnd, roundTime);

        // On a duel worker, replace vanilla /t so a ranked match can invert it
        // for casting admins. The hub keeps vanilla /t untouched. Registering
        // last wins: CommandHandler.register replaces any earlier command.
        if (duelWorker) {
            duelChat.registerTeamChatCommand(handler);
        }
    }

    @Override
    public void registerServerCommands(CommandHandler handler) {
        consoleCommands.register(handler);
        Console.register(handler, matches, roundTime);
    }

    /**
     * @param syncToClients whether the generated terrain is pushed to connected
     *                      clients tile-by-tile. Pass {@code false} for generation triggered by a
     *                      world (re)load - the vanilla world snapshot already carries the terrain,
     *                      and the extra per-tile flood is what dropped connected players with
     *                      "(error)" at match end. Pass {@code true} for in-place regeneration
     *                      (duel restart, oregen gen) where no fresh snapshot is sent.
     */
    private void generate(long seed, boolean syncToClients) {
        EvictTerrainGenerator.GeneratedRound round =
                terrainGenerator.generate(seed, syncToClients);

        refreshWorldIndexes();

        teamManager.beginRound(round.slots(), round.filledSlots(), seed);
        playerDataManager.beginRound();
        attritionManager.beginRound();
        attackManager.beginRound();
        inviteManager.beginRound();
        roundEnd.beginRound();
        roundTime.beginRound();
        waveExtinction.beginRound();
        assignConnectedPlayersAndRecordStats();

        runtime.lastSeed = seed;

        // Hub only: one line in the chat mirror per round. A worker's match
        // is framed by the hub's start/end embeds instead.
        if (!duelWorker) {
            chatLogReporter.hubLine(
                    "🌍 A new round has started (seed " + seed + ")."
            );
        }

        Log.info(
                "[EvictMapGenerator] Done. seed=@ normalHexes=@ filledHexes=@ nucleusCores=@ repairedConnectivityEdges=@ resources=@ teams=@",
                seed,
                round.normalHexes(),
                round.filledHexes(),
                round.normalHexes(),
                round.repairedConnectivityEdges(),
                round.resources().compact(),
                teamManager.compactStatus()
        );
    }

    private void scheduleConnectedPlayerAssignmentScan() {
        long scanSerial = ++connectedPlayerScanSerial;

        scheduleConnectedPlayerAssignmentScan(
                scanSerial,
                CONNECTED_PLAYER_SCAN_ATTEMPTS,
                CONNECTED_PLAYER_SCAN_INITIAL_DELAY_TICKS
        );

        Log.info(
                "[EvictMapGenerator] Scheduled connected-player start assignment scan for up to 30 seconds."
        );
    }

    private void scheduleConnectedPlayerAssignmentScan(
            long scanSerial,
            int attemptsRemaining,
            float delayTicks
    ) {
        Time.run(
                delayTicks,
                () -> {
                    if (
                            !runtime.autoGenerate
                                    || scanSerial != connectedPlayerScanSerial
                                    || attemptsRemaining <= 0
                    ) {
                        return;
                    }

                    assignConnectedPlayersAndRecordStats();

                    if (attemptsRemaining > 1) {
                        scheduleConnectedPlayerAssignmentScan(
                                scanSerial,
                                attemptsRemaining - 1,
                                CONNECTED_PLAYER_SCAN_INTERVAL_TICKS
                        );
                    }
                }
        );
    }

    /**
     * Routes a victory to the duel referee on a worker, or to the normal
     * next-round reset on the hub.
     */
    private void handleVictory(Team winner) {
        if (duelWorker) {
            duelWorkerReferee.handleVictory(winner);
            return;
        }

        handleRoundVictory(winner);
    }

    private void handleRoundVictory(Team winner) {
        runtime.nextSeed = runtime.randomSeed();

        Log.info(
                "[EvictMapGenerator] Round winner: team #@. Prepared random seed @ for the next generated round.",
                winner.id,
                runtime.nextSeed
        );

        reportRoundEnd(winner);

        Events.fire(new GameOverEvent(winner));

        // Best moment for a queued update restart: the fresh process will
        // generate the next round, so no player loses progress.
        restartManager.onRoundEnded();
    }

    /**
     * The chat mirror's round-end entry: who won, with whom, how, and how
     * long the round ran. Read here, before the reset tears the round state
     * down.
     */
    private void reportRoundEnd(Team winner) {
        boolean fallenWon = winner == TeamManager.FALLEN_TEAM;
        String leaderUuid = teamManager.leaderUuidOf(winner);
        StringBuilder members = new StringBuilder();

        if (!fallenWon) {
            for (String uuid : teamManager.playerUuidsForTeam(winner)) {
                if (!members.isEmpty()) {
                    members.append('\n');
                }

                members.append(
                        Extinction.discord.DiscordFormat.playerName(
                                storedPlayerName(uuid)
                        )
                );

                if (uuid.equals(leaderUuid)) {
                    members.append(" 👑");
                }
            }
        }

        chatLogReporter.roundEnded(
                Extinction.discord.DiscordFormat.playerName(
                        teamManager.displayTeam(winner)
                ),
                members.toString(),
                teamManager.roundRuntimeMillis() / 1000L,
                teamManager.lastVictoryDescription(),
                fallenWon
        );
    }

    /**
     * A round participant's name for the round-end entry: the live name when
     * connected, the admin store's last name otherwise.
     */
    private static String storedPlayerName(String uuid) {
        Player online = Extinction.core.util.Players.byUuid(uuid);

        if (online != null) {
            return online.name;
        }

        mindustry.net.Administration.PlayerInfo info =
                Vars.netServer == null
                        ? null
                        : Vars.netServer.admins.getInfoOptional(uuid);

        return info == null || info.lastName == null || info.lastName.isBlank()
                ? uuid
                : info.lastName;
    }

    private void assignConnectedPlayersAndRecordStats() {
        roundTime.rememberConnectedPlayers();
        teamManager.assignConnectedPlayers(this::isDuelSpectator, this::isLockedOrHeld);
    }

    /** On the hub, a locked account - or one still waiting for its verdict - stays on Fallen. */
    private boolean isLockedOrHeld(Player player) {
        return !duelWorker
                && player != null
                && (playerLock.isLocked(player.uuid()) || lockGate.isHeld(player.uuid()));
    }

    /**
     * On a duel worker, anyone who is not one of the two duelists is a viewer.
     */
    private boolean isDuelSpectator(Player player) {
        return duelWorker
                && player != null
                && !duelWorkerReferee.isParticipant(player.uuid());
    }

    // Runs one player-event handler isolated: a throwing listener would otherwise silently skip
    // every handler after it (and the vanilla ones), so the failure is logged with its stage instead.
    private static void guarded(String stage, Runnable handler) {
        try {
            handler.run();
        } catch (Exception exception) {
            PluginLog.err(
                    "Player event handler '" + stage + "' failed.",
                    exception
            );
        }
    }

    private void refreshWorldIndexes() {
        refreshingWorldIndexes = true;

        try {
            Events.fire(new WorldLoadEvent());

            Log.info(
                    "[EvictMapGenerator] Rebuilt vanilla world indexes after runtime terrain generation."
            );
        } finally {
            refreshingWorldIndexes = false;
        }
    }
}
