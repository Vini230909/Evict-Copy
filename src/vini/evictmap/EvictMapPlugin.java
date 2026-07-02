package vini.evictmap;

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
    private static final float ADVERTISED_COUNT_REFRESH_TICKS = 120f;

    private final EvictRuntimeState runtime = new EvictRuntimeState();
    private final EvictSettings settings = new EvictSettings();
    private final PlayerDataManager playerDataManager =
            new PlayerDataManager();
    private final RankManager rankManager = new RankManager();

    private final TeamManager teamManager =
            new TeamManager(this::handleVictory);

    private final DuelWorker duelWorkerReferee = new DuelWorker();

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

    private final DuelCommands duelCommands =
            new DuelCommands(
                    duelServerManager,
                    duelWorkerReferee,
                    rankManager,
                    this::restartDuelMatch
            );

    private final HistoryCommands historyCommands =
            new HistoryCommands(playerDataManager);

    private final EvictHelpCommands helpCommands =
            new EvictHelpCommands();

    private final EvictClientCommands clientCommands =
            new EvictClientCommands(
                    attackManager,
                    inviteManager,
                    roundEndCommands,
                    roundTimeCommands,
                    duelCommands,
                    historyCommands,
                    helpCommands
            );

    private final EvictTerrainGenerator terrainGenerator =
            new EvictTerrainGenerator(settings);

    private final EvictConsoleCommands consoleCommands =
            new EvictConsoleCommands(
                    runtime,
                    settings,
                    terrainGenerator,
                    teamManager,
                    playerDataManager,
                    duelServerManager,
                    rankManager,
                    // evictgen regenerates the live map in place with no fresh snapshot,
                    // so connected clients only see the new terrain via the per-tile sync.
                    seed -> generate(seed, true)
            );

    private boolean refreshingWorldIndexes = false;
    private long connectedPlayerScanSerial = 0L;
    private int advertisedPlayerCount = -1;

    /**
     * When launched with -Devict.duelWorker=true this process is a spawned 1v1
     * worker. It runs Evict normally but shuts itself down once the match is
     * empty so the hub can free the slot.
     */
    private final boolean duelWorker =
            "true".equals(System.getProperty("evict.duelWorker"));

    // pre-changing detector
    private final HashMap<Integer, CoreBlock.CoreBuild> prechanged =
            new HashMap<>();

    @Override
    public void init() {
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

            installDuelChatFilter();
        }
        RulesApplier.applyRules();
        teamManager.setInviteManager(inviteManager);
        teamManager.setDuelMode(duelWorker);

        // Teams-mode workers put a whole handshake roster on one Mindustry
        // team; on the hub (and in other modes) the resolver finds no
        // teammates and normal per-player assignment applies.
        if (duelWorker) {
            teamManager.setTeammateResolver(duelWorkerReferee::rosterTeammates);

            // A leaving participant only pauses the match while their team is
            // still in the running; an eliminated FFA player walking out must
            // not freeze the survivors.
            duelWorkerReferee.setStillCompeting(
                    player -> player != null
                            && teamManager.isActivePersonalTeam(player.team().id)
            );

            // A knocked-out FFA or Teams player is free: demoted to a
            // spectator, they can watch, /v back to the lobby, or disconnect -
            // the hub will let them join the main round normally instead of
            // bouncing them back into this match. In two-team games the
            // deciding elimination fires this too, harmlessly: the victory
            // resolves right after from the unchanged rosters.
            teamManager.setDuelEliminationHandler(team -> {
                MatchMode workerMode = duelWorkerReferee.matchMode();

                if (
                        workerMode != MatchMode.FFA
                                && workerMode != MatchMode.TEAMS
                ) {
                    return;
                }

                for (String uuid : teamManager.playerUuidsForTeam(team)) {
                    duelWorkerReferee.demoteToSpectator(uuid);

                    Player member = Groups.player.find(
                            online -> online != null && online.uuid().equals(uuid)
                    );

                    if (member != null) {
                        teamManager.assignSpectator(member);
                        member.sendMessage(
                                "[scarlet]You are out of the "
                                        + workerMode.label()
                                        + " match.[] [accent]You are now spectating - use [white]/v[accent] to return to the lobby.[]"
                        );
                    }
                }
            });
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

                if (duelWorkerReferee.isSandboxMode()) {
                    inviteManager.enableSandboxJoinMode(
                            duelWorkerReferee::addSandboxParticipant
                    );
                }
            }

            RulesApplier.applyRules();

            // A sandbox session plays with infinite resources; applyRules
            // resets the flag, so re-apply it after every rules pass.
            if (duelWorker && duelWorkerReferee.isSandboxMode()) {
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
                teamManager.assignSpectator(event.player);
                duelWorkerReferee.handlePlayerJoin(event.player);
                event.player.sendMessage(
                        "[accent]Spectating this match. Use [white]/v[accent] to return to the lobby.[]"
                );

                if (duelWorkerReferee.isSandboxMode()) {
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

            playerDataManager.handlePlayerJoin(event.player);
            roundTimeCommands.handlePlayerJoin(event.player);
            teamManager.handlePlayerJoin(event.player);
            playerDataManager.recordConnectedFfaParticipants(teamManager);
            duelWorkerReferee.handlePlayerJoin(event.player);
        });

        Events.on(PlayerLeave.class, event -> {
            playerDataManager.handlePlayerLeave(event.player);
            inviteManager.handlePlayerLeave(event.player);
            duelCommands.handlePlayerLeave(event.player);
            historyCommands.handlePlayerLeave(event.player);
            duelWorkerReferee.handlePlayerLeave(event.player);
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
            attritionManager.update();
            attackManager.update();
            extinctionManager.update();
        });

        // Only the hub is listed in the multiplayer browser; keep its advertised
        // count folded with the players inside the duel workers.
        if (!duelWorker) {
            scheduleAdvertisedPlayerCountRefresh();
        }

        Log.info(
                "[EvictMapGenerator] Loaded. Code revision 1.2.32. Use 'evictstatus' for commands and current settings."
        );
    }

    /**
     * Keeps the hub's advertised player count in sync with the FFA hub plus the
     * players in every duel worker. Mindustry's server ping reports the
     * "totalPlayers" setting, falling back to the live hub count, so folding the
     * duel players in makes the multiplayer menu show everyone online.
     */
    private void scheduleAdvertisedPlayerCountRefresh() {
        Time.run(ADVERTISED_COUNT_REFRESH_TICKS, () -> {
            int total =
                    Groups.player.size() + duelServerManager.connectedDuelPlayers();

            if (total != advertisedPlayerCount) {
                advertisedPlayerCount = total;
                Core.settings.put("totalPlayers", total);
            }

            scheduleAdvertisedPlayerCountRefresh();
        });
    }

    @Override
    public void registerClientCommands(CommandHandler handler) {
        clientCommands.register(handler);
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

        teamManager.beginRound(round.slots(), seed);
        playerDataManager.beginFfaRound();
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
        playerDataManager.recordFfaWinner(teamManager, winner);
        runtime.nextSeed = runtime.randomSeed();

        Log.info(
                "[EvictMapGenerator] Round winner: team #@. Prepared random seed @ for the next generated round.",
                winner.id,
                runtime.nextSeed
        );

        Events.fire(new GameOverEvent(winner));
    }

    private void assignConnectedPlayersAndRecordStats() {
        roundTimeCommands.rememberConnectedPlayers();
        teamManager.assignConnectedPlayers(this::isDuelSpectator);
        playerDataManager.recordConnectedFfaParticipants(teamManager);
    }

    /**
     * On a duel worker, global chat is for the two players plus commentators and
     * admins (the casters). Viewers have their normal messages routed to their
     * own team chat instead, so they never need to type /t.
     */
    private void installDuelChatFilter() {
        if (Vars.netServer == null) {
            return;
        }

        Vars.netServer.admins.addChatFilter((player, message) -> {
            if (player == null || message == null) {
                return message;
            }

            // Training and Sandbox are casual sessions: the players and the
            // viewers share one global chat instead of viewers being routed
            // to their own team chat.
            if (duelWorkerReferee.matchMode().solo()) {
                return message;
            }

            if (
                    duelWorkerReferee.isParticipant(player.uuid())
                            || rankManager.canRestartMatches(player)
            ) {
                return message;
            }

            sendDuelTeamChat(player, message);
            return null;
        });
    }

    private void sendDuelTeamChat(Player sender, String message) {
        String line = "[#" + sender.team().color + "]<T>[] "
                + sender.name + "[white]: " + message;

        Groups.player.each(target -> {
            if (target != null && target.team() == sender.team()) {
                target.sendMessage(line);
            }
        });
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
