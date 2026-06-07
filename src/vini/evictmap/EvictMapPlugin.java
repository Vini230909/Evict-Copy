package vini.evictmap;

import arc.Events;
import arc.util.CommandHandler;
import arc.util.Log;
import arc.util.Time;
import mindustry.game.EventType.CoreChangeEvent;
import mindustry.game.EventType.GameOverEvent;
import mindustry.game.EventType.PlayEvent;
import mindustry.game.EventType.PlayerJoin;
import mindustry.game.EventType.PlayerLeave;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.game.Team;
import mindustry.mod.Plugin;

/**
 * Plugin composition root.
 *
 * This class intentionally contains only lifecycle wiring. Game systems live
 * in focused classes such as EvictTerrainGenerator, CaptureManager,
 * TeamManager and the command registrars.
 */
public class EvictMapPlugin extends Plugin {

    private static final float CONNECTED_PLAYER_SCAN_INITIAL_DELAY_TICKS = 1f;
    private static final float CONNECTED_PLAYER_SCAN_INTERVAL_TICKS = 15f;
    private static final int CONNECTED_PLAYER_SCAN_ATTEMPTS = 120;

    private final EvictRuntimeState runtime = new EvictRuntimeState();
    private final EvictSettings settings = new EvictSettings();
    private final CoreUnitDamageManager coreUnitDamageManager =
        new CoreUnitDamageManager();
    private final PlayerDataManager playerDataManager =
        new PlayerDataManager();

    private final TeamManager teamManager =
        new TeamManager(this::handleRoundVictory);

    private final AttritionManager attritionManager =
        new AttritionManager(teamManager, settings);

    private final InviteManager inviteManager =
        new InviteManager(teamManager);

    private final ExtinctionManager extinctionManager =
        new ExtinctionManager(teamManager);

    private final EvictCommands evictCommands =
        new EvictCommands(
            teamManager,
            attritionManager,
            extinctionManager,
            settings,
            playerDataManager
        );

    private final RoundEndCommands roundEndCommands =
        new RoundEndCommands(teamManager, extinctionManager);

    private final RoundTimeCommands roundTimeCommands =
        new RoundTimeCommands();

    private final EvictHelpCommands helpCommands =
        new EvictHelpCommands();

    private final EvictClientCommands clientCommands =
        new EvictClientCommands(
            evictCommands,
            inviteManager,
            roundEndCommands,
            roundTimeCommands,
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
            this::generate
        );

    private boolean refreshingWorldIndexes = false;
    private long connectedPlayerScanSerial = 0L;

    @Override
    public void init() {
        settings.load();
        playerDataManager.start();
        coreUnitDamageManager.apply();
        teamManager.setExtinctionTerrainChangesPerTick(
            settings.extinctionTerrainChangesPerTick()
        );
        teamManager.setInviteManager(inviteManager);

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
                generate(seed);
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

            EvictRules.apply();
            scheduleConnectedPlayerAssignmentScan();

            Log.info(
                "[EvictMapGenerator] Re-applied Evict rules after host-mode initialization."
            );
        });

        Events.on(PlayerJoin.class, event -> {
            playerDataManager.handlePlayerJoin(event.player);
            roundTimeCommands.handlePlayerJoin(event.player);
            teamManager.handlePlayerJoin(event.player);
            playerDataManager.recordConnectedFfaParticipants(teamManager);
        });

        Events.on(PlayerLeave.class, event -> {
            playerDataManager.handlePlayerLeave(event.player);
            inviteManager.handlePlayerLeave(event.player);
        });

        Events.on(
            CoreChangeEvent.class,
            event -> teamManager.handleCoreChange(
                event.core,
                attritionManager
            )
        );

        Events.run(Trigger.update, () -> {
            teamManager.updateExtinctionTerrainQueue();
            attritionManager.update();
            evictCommands.update();
            extinctionManager.update();
        });

        Log.info(
            "[EvictMapGenerator] Loaded. Code revision 1.2.14. Use 'evictstatus' for commands and current settings."
        );
    }

    @Override
    public void registerClientCommands(CommandHandler handler) {
        clientCommands.register(handler);
    }

    @Override
    public void registerServerCommands(CommandHandler handler) {
        consoleCommands.register(handler);
    }

    private void generate(long seed) {
        EvictRules.apply();

        EvictTerrainGenerator.GeneratedRound round =
            terrainGenerator.generate(seed);

        refreshWorldIndexes();

        teamManager.beginRound(round.slots(), seed);
        playerDataManager.beginFfaRound();
        attritionManager.beginRound();
        evictCommands.beginRound();
        inviteManager.beginRound();
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
        teamManager.assignConnectedPlayers();
        playerDataManager.recordConnectedFfaParticipants(teamManager);
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
