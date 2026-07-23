package vini.evictmap;

import vini.evictmap.gen.*;
import vini.evictmap.data.*;
import vini.evictmap.round.*;

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
import vini.evictmap.gameplay.AttritionManager;
import vini.evictmap.gameplay.RulesApplier;
import vini.evictmap.gameplay.ExtinctionManager;
import vini.evictmap.gameplay.AttackManager;
import vini.evictmap.discord.DiscordStatusReporter;
import vini.evictmap.duel.DuelChat;
import vini.evictmap.duel.DuelServerManager;
import vini.evictmap.duel.DuelWorker;
import vini.evictmap.duel.modes.DuelMode;
import vini.evictmap.commands.*;
import vini.evictmap.core.util.PluginLog;

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
    private static final long ADVERTISED_COUNT_REFRESH_MILLIS = 2000L;

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
    private final RankManager rankManager = new RankManager();

    private final TeamManager teamManager =
            new TeamManager(this::handleVictory);

    private final DuelWorker duelWorkerReferee = new DuelWorker();

    private final DuelChat duelChat =
            new DuelChat(duelWorkerReferee, rankManager);

    private final AttritionManager attritionManager =
            new AttritionManager(teamManager, settings);

    private final InviteManager inviteManager =
            new InviteManager(teamManager);

    private final ExtinctionManager extinctionManager =
            new ExtinctionManager(teamManager);

    private final AttackManager attackManager =
            new AttackManager(
                    teamManager
            );

    private final RoundEndCommands roundEndCommands =
            new RoundEndCommands(teamManager, duelWorkerReferee);

    private final RoundTimeCommands roundTimeCommands =
            new RoundTimeCommands(teamManager);

    private final DuelServerManager duelServerManager =
            new DuelServerManager(settings, playerDataManager);

    private final vini.evictmap.metrics.MetricsReporter metricsReporter =
            new vini.evictmap.metrics.MetricsReporter(
                    () -> duelServerManager.activeDuels().size(),
                    duelServerManager::connectedDuelPlayers
            );

    private final DuelCommands duelCommands =
            new DuelCommands(
                    duelServerManager,
                    duelWorkerReferee,
                    rankManager,
                    this::restartDuelMatch
            );

    private final HistoryCommands historyCommands =
            new HistoryCommands(playerDataManager);

    private final InfoCommands infoCommands =
            new InfoCommands(playerDataManager);

    private final LeaderboardCommands leaderboardCommands =
            new LeaderboardCommands(playerDataManager);

    private final HelpCommands helpCommands =
            new HelpCommands();

    private final ClientCommands clientCommands =
            new ClientCommands(
                    attackManager,
                    inviteManager,
                    roundEndCommands,
                    roundTimeCommands,
                    duelCommands,
                    historyCommands,
                    infoCommands,
                    leaderboardCommands,
                    helpCommands
            );

    private final EvictTerrainGenerator terrainGenerator =
            new EvictTerrainGenerator(settings);

    /**
     * Graceful-restart coordinator. The plugin only exits cleanly; an external
     * start-script loop (docs/RESTART_LOOP.md) brings the server back up.
     */
    private final RestartManager restartManager =
            new RestartManager(
                    () -> duelServerManager.activeDuels().size(),
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
                    extinctionManager,
                    duelServerManager,
                    restartManager
            );

    private final ConsoleCommands consoleCommands =
            new ConsoleCommands(
                    runtime,
                    settings,
                    terrainGenerator,
                    teamManager,
                    playerDataManager,
                    duelServerManager,
                    rankManager,
                    restartManager,
                    duelWorker ? null : discordStatusReporter,
                    // evictgen regenerates the live map in place with no fresh snapshot,
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

        if (duelWorker) {
            configureWorkerReferee();
        } else {
            // Hub only: a server update means a new jar + a restart, so on
            // startup bring every existing duel-worker folder onto the current
            // jar. This keeps idle workers off a version-mismatched server
            // without deleting the folders and losing their logs.
            duelServerManager.refreshWorkerJars();

            // Hub only: one live status message in Discord. Workers must stay
            // out of it - they would all edit the same message.
            discordStatusReporter.start();
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
            if (!runtime.autoGenerate) {
                return;
            }

            scheduleConnectedPlayerAssignmentScan();

            if (duelWorker) {
                duelWorkerReferee.begin();

                // The handshake is loaded now, so the referee knows the mode:
                // gate the victory check on the full roster count, and open
                // the sandbox /invite flow for spectators.
                teamManager.setDuelMinimumTeams(
                        duelWorkerReferee.victoryMinimumTeams()
                );

                // FFA and Teams matches keep running after a surrender, so
                // the surrendered hexes need their Fallen backup cores back;
                // 1v1/Training/Sandbox end right away and leave them derelict.
                DuelMode workerMatchMode = duelWorkerReferee.duelMode();
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
                            duelWorkerReferee::addSandboxParticipant
                    );
                }
            }

            RulesApplier.applyRules();

            // A sandbox session plays with infinite resources; applyRules
            // resets the flag, so re-apply it after every rules pass.
            if (duelWorker && duelWorkerReferee.duelMode().infiniteResources()) {
                Vars.state.rules.infiniteResources = true;
            }
        });

        Events.on(PlayerJoin.class, event -> {
            // Re-apply any tournament name tag ([C] etc.); client names reset to
            // the player's own choice on every reconnect.
            rankManager.applyNameTag(event.player);

            // On a duel worker, restore admin for players the hub synced over;
            // the worker has no access to the hub's own admin list.
            if (duelWorker) {
                rankManager.markSyncedAdmin(event.player);
            }

            // On the hub: a player who is mid-duel is bounced straight back to
            // their worker instead of being onboarded into the FFA round.
            if (
                    !duelWorker
                            && duelServerManager.tryReturnToActiveDuel(event.player)
            ) {
                return;
            }

            // On a duel worker anyone who is not a rostered participant is a
            // /view spectator: park them on derelict (no cores) and skip the
            // normal FFA onboarding so they only watch.
            if (
                    duelWorker
                            && !duelWorkerReferee.isParticipant(event.player.uuid())
            ) {
                guarded("spectator assign", () -> teamManager.assignSpectator(event.player));
                guarded("duelWorker join", () -> duelWorkerReferee.handlePlayerJoin(event.player));
                event.player.sendMessage(
                        "[accent]Spectating this match. Use [white]/v[accent] to return to the lobby.[]"
                );

                if (duelWorkerReferee.duelMode().allowsSpectatorInvites()) {
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
            guarded("roundTime join", () -> roundTimeCommands.handlePlayerJoin(event.player));
            guarded("teamManager join", () -> teamManager.handlePlayerJoin(event.player));
            guarded("duelWorker join", () -> duelWorkerReferee.handlePlayerJoin(event.player));
        });

        Events.on(PlayerLeave.class, event -> {
            guarded("playerData leave", () -> playerDataManager.handlePlayerLeave(event.player));
            guarded("invite leave", () -> inviteManager.handlePlayerLeave(event.player));
            guarded("duelCommands leave", () -> duelCommands.handlePlayerLeave(event.player));
            guarded("history leave", () -> historyCommands.handlePlayerLeave(event.player));
            guarded("info leave", () -> infoCommands.handlePlayerLeave(event.player));
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

            attritionManager.update();
            attackManager.update();
            extinctionManager.update();

            // Only the hub is listed in the multiplayer browser; keep its
            // advertised count folded with the players inside the duel workers.
            if (!duelWorker) {
                refreshAdvertisedPlayerCount();
                metricsReporter.update();
                discordStatusReporter.update();
            }
        });

        Log.info(
                "[EvictMapGenerator] Loaded. Code revision 1.4.4. Use 'evictstatus' for commands and current settings."
        );
    }

    /**
     * Exit path for {@link RestartManager}: the duel-worker way. Close the
     * network so clients disconnect cleanly, then {@code System.exit(0)} -
     * the same hard exit duel workers use to self-terminate. The cooperative
     * {@code Core.app.exit()} teardown proved unreliable on the production
     * host (the JVM lingered until console input killed it), and a restart
     * must never depend on someone at the keyboard. {@code System.exit}
     * still runs the shutdown hooks (worker cleanup, player-DB flush), and
     * exit code 0 sends the start-script loop down its fast relaunch path.
     * The halt guard is the final backstop: if a shutdown hook itself ever
     * wedges, {@code Runtime.halt} takes the process down regardless; it
     * logs on raw stderr because the normal logger may already be gone
     * during shutdown.
     */
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

    /** Loads persisted state and applies the fixed round rules and team wiring. */
    private void bootstrap() {
        settings.load();
        rankManager.load();
        playerDataManager.start();

        // A duel worker's own DB has no real matches; point /history at the hub
        // DB (the worker runs in duel-workers/duel-<port>/, so the hub config is
        // two levels up) so spectators and players still see real history.
        if (duelWorker) {
            playerDataManager.useHistoryDatabase(
                    new java.io.File("../../config/evict-players.db")
            );

            duelChat.installChatFilter();
        }

        RulesApplier.applyRules();
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
     * can watch, /v back to the lobby, or disconnect - the hub will let them
     * join the main round normally instead of bouncing them back into this
     * match. In two-team games the deciding elimination fires this too,
     * harmlessly: the victory resolves right after from the unchanged rosters.
     */
    private void freeEliminatedDuelTeam(Team team) {
        DuelMode workerMode = duelWorkerReferee.duelMode();

        if (!workerMode.eliminatesWipedTeams()) {
            return;
        }

        for (String uuid : teamManager.playerUuidsForTeam(team)) {
            duelWorkerReferee.demoteToSpectator(uuid);

            Player member = vini.evictmap.core.util.Players.byUuid(uuid);

            if (member != null) {
                teamManager.assignSpectator(member);
                member.sendMessage(
                        "[scarlet]You are out of the "
                                + workerMode.mode().label()
                                + " match.[] [accent]You are now spectating - use [white]/v[accent] to return to the lobby.[]"
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
                Groups.player.size() + duelServerManager.connectedDuelPlayers();

        if (total != advertisedPlayerCount) {
            advertisedPlayerCount = total;
            Core.settings.put("totalPlayers", total);
        }
    }

    @Override
    public void registerClientCommands(CommandHandler handler) {
        clientCommands.register(handler);

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
    }

    /**
     * @param syncToClients whether the generated terrain is pushed to connected
     *                      clients tile-by-tile. Pass {@code false} for generation triggered by a
     *                      world (re)load - the vanilla world snapshot already carries the terrain,
     *                      and the extra per-tile flood is what dropped connected players with
     *                      "(error)" at match end. Pass {@code true} for in-place regeneration
     *                      (duel restart, evictgen) where no fresh snapshot is sent.
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
        roundEndCommands.beginRound();
        roundTimeCommands.beginRound();
        extinctionManager.beginRound();
        assignConnectedPlayersAndRecordStats();

        runtime.lastSeed = seed;

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

        Events.fire(new GameOverEvent(winner));

        // Best moment for a queued update restart: the fresh process will
        // generate the next round, so no player loses progress.
        restartManager.onRoundEnded();
    }

    private void assignConnectedPlayersAndRecordStats() {
        roundTimeCommands.rememberConnectedPlayers();
        teamManager.assignConnectedPlayers(this::isDuelSpectator);
    }

    /**
     * On a duel worker, anyone who is not one of the two duelists is a viewer.
     */
    private boolean isDuelSpectator(Player player) {
        return duelWorker
                && player != null
                && !duelWorkerReferee.isParticipant(player.uuid());
    }

    /**
     * Commentator/admin /restart on a duel worker: regenerate a fresh map and
     * re-run the countdown without ending the duel or moving anyone, so the two
     * players and the spectators stay connected.
     */
    private void restartDuelMatch() {
        if (!duelWorker) {
            return;
        }

        long seed = runtime.randomSeed();

        Log.info(
                "[EvictMapGenerator] Duel restart requested. Regenerating with seed @.",
                seed
        );

        // A restart is a fresh match: knocked-out FFA players are participants
        // again, and the regeneration below re-onboards them onto a team.
        duelWorkerReferee.restoreOutParticipants();

        try {
            // In-place duel restart keeps both duelists and spectators connected
            // (no world reload / snapshot), so the new terrain must be pushed to
            // them tile-by-tile.
            generate(seed, true);
        } catch (Exception exception) {
            Log.err(
                    "[EvictMapGenerator] Duel restart generation failed.",
                    exception
            );
            return;
        }

        duelWorkerReferee.restartMatch();
    }


    /**
     * Runs one player-event handler isolated from the others. Arc's Events.fire
     * lets an exception abort every remaining listener statement, so without
     * this a single failing handler would silently skip the rest of the chain
     * (and take the vanilla listeners registered after this plugin down with
     * it). The failure is logged with its stage name instead.
     */
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
