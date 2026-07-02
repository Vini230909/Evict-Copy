package vini.evictmap;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import arc.Core;
import arc.util.Log;
import mindustry.Vars;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.net.Administration;
import mindustry.world.Block;

/**
 * On-demand 1v1 worker orchestration for the hub server.
 * Each duel runs on its own Mindustry server process, because a single process
 * hosts only one game. A worker is spawned only when a match is requested and
 * terminates itself once the match empties, so idle duels cost no CPU.
 * Flow per match:
 * 1. Reserve a free port from the configured range (main thread, no locking).
 * 2. On a background thread: provision the worker folder if missing, launch
 * {@code java -Devict.duelWorker=true -jar <jar>}, inject the port and host
 * command on stdin, then poll the port until it accepts connections.
 * 3. Back on the main thread: redirect both players, or release the slot and
 * notify them on failure.
 * The slot map is only ever touched on the main thread; background work posts
 * results back with {@link Core#app}.
 */
final class DuelServerManager {

    private static final String WORKER_DIR_PREFIX = "duel-";
    private static final String WORKER_BASE_DIR = "duel-workers";
    private static final String WORKER_LOG_FILE = "worker.log";
    private static final long READINESS_TIMEOUT_MILLIS = 45_000L;
    private static final long READINESS_POLL_MILLIS = 500L;
    private static final int READINESS_CONNECT_TIMEOUT_MILLIS = 1_000;
    private static final long MAX_WORKER_LIFETIME_MINUTES = 110L;

    private final EvictSettings settings;
    private final PlayerDataManager playerDataManager;

    private final ExecutorService spawnExecutor =
            Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "evict-duel-spawn");
                thread.setDaemon(true);
                return thread;
            });

    private final ScheduledExecutorService lifetimeScheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "evict-duel-lifetime");
                thread.setDaemon(true);
                return thread;
            });

    /**
     * Port -> reserved/running worker. Only mutated on the main thread.
     */
    private final Map<Integer, WorkerHandle> workers = new HashMap<>();

    /**
     * Player UUID -> the worker port of their in-progress duel. Lets the hub
     * bounce a reconnecting player back into their match. Main-thread only.
     */
    private final Map<String, Integer> activeDuelByUuid = new HashMap<>();

    DuelServerManager(
            EvictSettings settings,
            PlayerDataManager playerDataManager
    ) {
        this.settings = settings;
        this.playerDataManager = playerDataManager;

        Runtime.getRuntime().addShutdownHook(
                new Thread(this::destroyAllWorkers, "evict-duel-shutdown")
        );
    }

    boolean isConfigured() {
        return settings.duelServerConfigured();
    }

    /**
     * Reserves a worker and, once it is hosting, redirects both players to it.
     * Returns false immediately if the feature is unconfigured or all worker
     * slots are in use; the caller is responsible for notifying the players in
     * that case.
     */
    boolean requestDuel(Player challenger, Player opponent) {
        if (!isConfigured()) {
            return false;
        }

        int port = reserveFreePort();

        if (port < 0) {
            return false;
        }

        WorkerHandle handle = new WorkerHandle(port);
        handle.player1Name = challenger.plainName();
        handle.player1Uuid = challenger.uuid();
        handle.player1Display = PlayerNameFormatter.displayName(challenger);
        handle.player2Name = opponent.plainName();
        handle.player2Uuid = opponent.uuid();
        handle.player2Display = PlayerNameFormatter.displayName(opponent);
        handle.adminUuids = snapshotAdminUuids();
        handle.bannedBlocks = snapshotBannedBlockNames();
        workers.put(port, handle);

        String challengerUuid = challenger.uuid();
        String opponentUuid = opponent.uuid();

        spawnExecutor.submit(() -> spawnAndRedirect(
                handle,
                challengerUuid,
                opponentUuid
        ));

        scheduleLifetimeKill(handle);
        return true;
    }

    private void spawnAndRedirect(
            WorkerHandle handle,
            String challengerUuid,
            String opponentUuid
    ) {
        try {
            File workerDir = provisionWorkerDir(handle);

            writeHandshake(workerDir, challengerUuid, opponentUuid);

            Process process = launchWorker(workerDir, handle.port);
            handle.process = process;

            process.onExit().thenRun(
                    () -> Core.app.post(() -> {
                        logResult(handle);
                        releaseSlot(handle);
                    })
            );

            boolean ready = waitUntilReady(handle.port);

            Core.app.post(() -> {
                if (!ready || !process.isAlive()) {
                    Log.err(
                            "[EvictMapGenerator] 1v1: worker on port @ did not become ready; releasing slot.",
                            handle.port
                    );
                    notifyFailure(challengerUuid, opponentUuid);
                    destroyWorker(handle);
                    return;
                }

                redirectPlayers(handle, challengerUuid, opponentUuid);
            });
        } catch (Exception exception) {
            Log.err(
                    "[EvictMapGenerator] 1v1: failed to start a duel worker on port "
                            + handle.port + ".",
                    exception
            );

            Core.app.post(() -> {
                notifyFailure(challengerUuid, opponentUuid);
                destroyWorker(handle);
            });
        }
    }

    private void redirectPlayers(
            WorkerHandle handle,
            String challengerUuid,
            String opponentUuid
    ) {
        if (workers.get(handle.port) != handle) {
            // Slot was already released (e.g. the worker died) meanwhile.
            return;
        }

        Player challenger = onlinePlayer(challengerUuid);
        Player opponent = onlinePlayer(opponentUuid);

        if (challenger == null || opponent == null) {
            Log.info(
                    "[EvictMapGenerator] 1v1: a player left before the worker on port @ was ready; releasing slot.",
                    handle.port
            );
            destroyWorker(handle);
            return;
        }

        String ip = settings.duelServerIp();

        challenger.sendMessage("[accent]Connecting you to your 1v1...[]");
        opponent.sendMessage("[accent]Connecting you to your 1v1...[]");

        activeDuelByUuid.put(challengerUuid, handle.port);
        activeDuelByUuid.put(opponentUuid, handle.port);

        Call.connect(challenger.con, ip, handle.port);
        Call.connect(opponent.con, ip, handle.port);

        Log.info(
                "[EvictMapGenerator] 1v1: sent @ and @ to duel worker @:@.",
                challenger.plainName(),
                opponent.plainName(),
                ip,
                handle.port
        );
    }

    /**
     * On the hub: if a reconnecting player is still mid-duel, send them straight
     * back to their worker. Returns true when the player was bounced. Skips (and
     * forgets) finished or dead matches so a returning winner stays on the hub.
     */
    boolean tryReturnToActiveDuel(Player player) {
        if (player == null) {
            return false;
        }

        Integer port = activeDuelByUuid.get(player.uuid());

        if (port == null) {
            return false;
        }

        WorkerHandle handle = workers.get(port);

        if (!isOngoing(handle)) {
            activeDuelByUuid.remove(player.uuid());
            return false;
        }

        player.sendMessage("[accent]Returning you to your 1v1...[]");
        Call.connect(player.con, settings.duelServerIp(), port);
        return true;
    }

    /**
     * Snapshot of the duels in progress for the /view menu. Main-thread only.
     * Finished or not-yet-hosting workers are skipped so a viewer is never sent
     * to a match that is wrapping up or not ready, ordered by port for a stable
     * menu.
     */
    List<ActiveDuel> activeDuels() {
        List<ActiveDuel> duels = new ArrayList<>();

        for (WorkerHandle handle : workers.values()) {
            if (isOngoing(handle)) {
                duels.add(new ActiveDuel(
                        handle.port,
                        handle.player1Display,
                        handle.player2Display
                ));
            }
        }

        duels.sort(Comparator.comparingInt(ActiveDuel::port));
        return duels;
    }

    /**
     * Players currently connected across all duel workers (duelists plus /view
     * spectators), read from each worker's status.properties. Lets the hub fold
     * duel players into its advertised player count. Main-thread only.
     */
    int connectedDuelPlayers() {
        int total = 0;

        for (WorkerHandle handle : workers.values()) {
            Properties status = readStatus(handle.port);

            if (status != null) {
                total += countPlayers(status.getProperty("players", ""));
            }
        }

        return total;
    }

    private static int countPlayers(String packed) {
        return packed == null || packed.isBlank() ? 0 : packed.split(",").length;
    }

    /**
     * Connects a viewer to an ongoing duel worker as a spectator. Returns false
     * if that match is no longer available so the caller can tell the player.
     */
    boolean viewDuel(Player viewer, int port) {
        if (viewer == null || !isOngoing(workers.get(port))) {
            return false;
        }

        viewer.sendMessage(
                "[accent]Connecting you to the 1v1 as a spectator...[]"
        );
        Call.connect(viewer.con, settings.duelServerIp(), port);
        return true;
    }

    private boolean isOngoing(WorkerHandle handle) {
        return handle != null
                && handle.process != null
                && handle.process.isAlive()
                && !new File(
                workerDir(handle.port),
                "result.properties"
        ).exists();
    }

    /**
     * Logs the current worker pool for the console. Uptime is wall-clock since
     * spawn and the state is hub-inferred; live in-match state (freeze, game
     * time, who is actually connected) needs a worker status channel later.
     */
    void logStatus() {
        int basePort = settings.duelServerPort();
        int maxWorkers = settings.duelMaxWorkers();

        Log.info(
                "[EvictMapGenerator] Duel pool: @ active of @ slots, ip=@, ports @-@.",
                workers.size(),
                maxWorkers,
                isConfigured() ? settings.duelServerIp() : "not set",
                basePort,
                basePort + maxWorkers - 1
        );

        if (workers.isEmpty()) {
            Log.info("[EvictMapGenerator]   (no active duels)");
            return;
        }

        long now = System.currentTimeMillis();

        for (WorkerHandle handle : workers.values()) {
            boolean alive = handle.process != null && handle.process.isAlive();
            String uptime = formatHms((now - handle.spawnedAtMillis) / 1000L);
            Properties status = readStatus(handle.port);

            if (alive && status != null) {
                Log.info(
                        "[EvictMapGenerator]   port @ [@] uptime=@ game=@ players: @",
                        handle.port,
                        status.getProperty("state", "?"),
                        uptime,
                        formatHms(parseLong(status.getProperty("elapsedSeconds"))),
                        formatPlayers(status.getProperty("players", ""))
                );
            } else {
                Log.info(
                        "[EvictMapGenerator]   port @ [@] uptime=@ players: @ (@) vs @ (@)",
                        handle.port,
                        alive ? "starting" : "closing",
                        uptime,
                        handle.player1Name,
                        handle.player1Uuid,
                        handle.player2Name,
                        handle.player2Uuid
                );
            }
        }
    }

    private Properties readStatus(int port) {
        File statusFile = new File(workerDir(port), "status.properties");

        if (!statusFile.exists()) {
            return null;
        }

        Properties properties = new Properties();

        try (FileInputStream input = new FileInputStream(statusFile)) {
            properties.load(input);
            return properties;
        } catch (Exception exception) {
            return null;
        }
    }

    private static String formatPlayers(String packed) {
        if (packed == null || packed.isBlank()) {
            return "(none connected)";
        }

        StringBuilder result = new StringBuilder();

        for (String entry : packed.split(",")) {
            String[] parts = entry.split("\\|", 2);

            if (!result.isEmpty()) {
                result.append(", ");
            }

            result.append(parts[0]);

            if (parts.length > 1) {
                result.append(" (").append(parts[1]).append(")");
            }
        }

        return result.toString();
    }

    private static long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }

        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }

    private static String formatHms(long totalSeconds) {
        long seconds = Math.max(0L, totalSeconds);
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long secs = seconds % 60L;

        return String.format("%02d:%02d:%02d", hours, minutes, secs);
    }

    private void notifyFailure(String challengerUuid, String opponentUuid) {
        Player challenger = onlinePlayer(challengerUuid);
        Player opponent = onlinePlayer(opponentUuid);

        if (challenger != null) {
            challenger.sendMessage(
                    "[scarlet]The 1v1 server could not be started. Try again.[]"
            );
        }

        if (opponent != null) {
            opponent.sendMessage(
                    "[scarlet]The 1v1 server could not be started. Try again.[]"
            );
        }
    }

    private int reserveFreePort() {
        int basePort = settings.duelServerPort();
        int maxWorkers = settings.duelMaxWorkers();

        for (int offset = 0; offset < maxWorkers; offset++) {
            int port = basePort + offset;

            if (!workers.containsKey(port)) {
                return port;
            }
        }

        return -1;
    }

    private File provisionWorkerDir(WorkerHandle handle) throws IOException {
        File workerDir = workerDir(handle.port);
        File workerConfig = new File(workerDir, "config");
        Files.createDirectories(workerConfig.toPath());

        // The big static files are copied once; the plugin mods are refreshed on
        // every spawn so a rebuilt plugin is picked up without deleting the
        // duel-workers folder.
        File jar = new File(workerDir, settings.duelWorkerJarName());
        if (!jar.exists()) {
            Log.info(
                    "[EvictMapGenerator] 1v1: provisioning worker folder @ from the hub files.",
                    workerDir.getPath()
            );
            copyFile(new File(settings.duelWorkerJarName()), jar);
        }

        File maps = new File(workerConfig, "maps");
        if (!maps.exists()) {
            copyDirectory(new File("config/maps"), maps);
        }

        copyDirectory(new File("config/mods"), new File(workerConfig, "mods"));

        // Refreshed every spawn so the worker knows the current commentators
        // (name tags + /restart permission) and hub admins.
        copyRanksFile(workerConfig);
        writeAdminsFile(workerConfig, handle.adminUuids);

        // Refreshed every spawn so the worker's terrain/ore/water generation and
        // rules (build speed) match the hub's live tuning, and so the worker bans
        // exactly the blocks the hub currently bans.
        copySettingsFile(workerConfig, handle.bannedBlocks);

        return workerDir;
    }

    private static void copyRanksFile(File workerConfig) throws IOException {
        File source = new File("config/evict-ranks.properties");

        if (!source.exists()) {
            return;
        }

        Files.copy(
                source.toPath(),
                new File(workerConfig, "evict-ranks.properties").toPath(),
                StandardCopyOption.REPLACE_EXISTING
        );
    }

    /**
     * Copies the hub's persistent tuning file into the worker so its ore, water,
     * wall and extinction generation and build speed match the hub. The duel
     * target is cleared in the copy so a worker never inherits the hub's duel
     * config and tries to spawn nested duels of its own. The banned-block list is
     * overwritten with the hub's live bans (snapshotted at spawn) so the worker
     * bans exactly those blocks - the hub's own settings file does not track them.
     */
    private static void copySettingsFile(
            File workerConfig,
            List<String> bannedBlocks
    ) throws IOException {
        File source = new File("config/evict-map-generator.properties");

        if (!source.exists()) {
            return;
        }

        Properties properties = new Properties();

        try (FileInputStream input = new FileInputStream(source)) {
            properties.load(input);
        }

        // Keep the worker non-duel-configured: it hosts a single match and must
        // not relay /play to another instance.
        properties.setProperty("duel.server.ip", "");
        properties.setProperty(
                "rules.bannedBlocks",
                String.join(",", bannedBlocks)
        );

        try (FileOutputStream output = new FileOutputStream(
                new File(workerConfig, "evict-map-generator.properties")
        )) {
            properties.store(output, "Evict synced hub generation settings");
        }
    }

    /**
     * Writes the hub's admins into the worker so it can recognize them; the file
     * is rewritten (even when empty) every spawn so a reused worker folder never
     * keeps stale admins.
     */
    private static void writeAdminsFile(
            File workerConfig,
            List<String> adminUuids
    ) throws IOException {
        Properties properties = new Properties();

        for (String uuid : adminUuids) {
            if (uuid != null && !uuid.isBlank()) {
                properties.setProperty(uuid, "admin");
            }
        }

        try (FileOutputStream output = new FileOutputStream(
                new File(workerConfig, "evict-admins.properties")
        )) {
            properties.store(output, "Evict synced hub admins (uuid = admin)");
        }
    }

    /**
     * Snapshot of the blocks currently banned on the hub (internal ids). Read
     * straight from the live rules so a worker bans exactly what the hub bans,
     * however those bans were set (map rules / admin actions). Main-thread only.
     */
    private static List<String> snapshotBannedBlockNames() {
        List<String> names = new ArrayList<>();

        if (Vars.state == null || Vars.state.rules == null) {
            return names;
        }

        for (Block block : Vars.state.rules.bannedBlocks) {
            if (block != null && block.name != null) {
                names.add(block.name);
            }
        }

        return names;
    }

    /**
     * Snapshot of the hub's admin UUIDs. Must run on the main thread.
     */
    private static List<String> snapshotAdminUuids() {
        List<String> uuids = new ArrayList<>();

        if (Vars.netServer == null || Vars.netServer.admins == null) {
            return uuids;
        }

        for (Administration.PlayerInfo info : Vars.netServer.admins.getAdmins()) {
            if (info != null && info.id != null) {
                uuids.add(info.id);
            }
        }

        return uuids;
    }

    private void writeHandshake(
            File workerDir,
            String player1Uuid,
            String player2Uuid
    ) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("player1.uuid", player1Uuid);
        properties.setProperty("player2.uuid", player2Uuid);
        properties.setProperty("hub.ip", settings.duelServerIp());
        properties.setProperty("hub.port", Integer.toString(hubPort()));

        try (FileOutputStream output =
                     new FileOutputStream(new File(workerDir, "duel.properties"))) {
            properties.store(output, "Evict duel handshake");
        }
    }

    /**
     * Address clients use to reach the hub itself, so a worker can send players
     * back. Same IP as the workers (one machine); the port is the hub's own
     * host port from settings, defaulting to the Mindustry default.
     */
    private int hubPort() {
        if (Core.settings == null) {
            return 6567;
        }

        return Core.settings.getInt("port", 6567);
    }

    private void logResult(WorkerHandle handle) {
        int port = handle.port;
        File resultFile = new File(workerDir(port), "result.properties");

        if (!resultFile.exists()) {
            return;
        }

        Properties properties = new Properties();

        try (FileInputStream input = new FileInputStream(resultFile)) {
            properties.load(input);

            String winnerUuid = properties.getProperty("winner.uuid", "").trim();
            String loserUuid = properties.getProperty("loser.uuid", "").trim();

            Log.info(
                    "[EvictMapGenerator] 1v1 result on port @: winner=@ loser=@ reason=@.",
                    port,
                    winnerUuid.isEmpty() ? "?" : winnerUuid,
                    loserUuid.isEmpty() ? "?" : loserUuid,
                    properties.getProperty("reason", "?")
            );

            // Credit the ranked record and match history on the hub's database;
            // the worker runs in its own process and cannot reach this DB. The
            // colored display names captured at match start let /history render
            // them later without the players being online.
            playerDataManager.recordRankedResult(
                    winnerUuid,
                    displayNameFor(handle, winnerUuid),
                    loserUuid,
                    displayNameFor(handle, loserUuid)
            );
        } catch (Exception exception) {
            Log.err(
                    "[EvictMapGenerator] Could not read the duel result on port "
                            + port + ".",
                    exception
            );
        }

        // Drop it so a reused worker folder never reports a stale result.
        //noinspection ResultOfMethodCallIgnored
        resultFile.delete();
    }

    private static String displayNameFor(WorkerHandle handle, String uuid) {
        if (uuid.equals(handle.player1Uuid)) {
            return handle.player1Display;
        }

        if (uuid.equals(handle.player2Uuid)) {
            return handle.player2Display;
        }

        return uuid;
    }

    private File workerDir(int port) {
        return new File(new File(WORKER_BASE_DIR), WORKER_DIR_PREFIX + port);
    }

    private Process launchWorker(File workerDir, int port) throws IOException {
        String javaExe = new File(
                System.getProperty("java.home"),
                "bin/java"
        ).getPath();

        ProcessBuilder builder = new ProcessBuilder(
                javaExe,
                "-Devict.duelWorker=true",
                "-jar",
                settings.duelWorkerJarName()
        );

        builder.directory(workerDir);
        builder.redirectErrorStream(true);
        builder.redirectOutput(new File(workerDir, WORKER_LOG_FILE));

        Process process = builder.start();

        OutputStream stdin = process.getOutputStream();
        writeCommand(stdin, "config port " + port);
        writeCommand(stdin, "host " + settings.duelWorkerMap() + " pvp");

        return process;
    }

    private void writeCommand(OutputStream stdin, String command)
            throws IOException {
        stdin.write((command + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();
    }

    private boolean waitUntilReady(int port) {
        long deadline = System.currentTimeMillis() + READINESS_TIMEOUT_MILLIS;

        while (System.currentTimeMillis() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(
                        new InetSocketAddress("127.0.0.1", port),
                        READINESS_CONNECT_TIMEOUT_MILLIS
                );
                return true;
            } catch (IOException ignored) {
                sleepQuietly();
            }
        }

        return false;
    }

    private void scheduleLifetimeKill(WorkerHandle handle) {
        lifetimeScheduler.schedule(
                () -> Core.app.post(() -> {
                    if (workers.get(handle.port) == handle) {
                        Log.info(
                                "[EvictMapGenerator] 1v1: worker on port @ hit the max lifetime; stopping it.",
                                handle.port
                        );
                        destroyWorker(handle);
                    }
                }),
                MAX_WORKER_LIFETIME_MINUTES,
                TimeUnit.MINUTES
        );
    }

    private void releaseSlot(WorkerHandle handle) {
        if (workers.get(handle.port) == handle) {
            workers.remove(handle.port);
            activeDuelByUuid.values().removeIf(port -> port == handle.port);
            Log.info(
                    "[EvictMapGenerator] 1v1: duel worker on port @ ended; slot is free again.",
                    handle.port
            );
        }
    }

    private void destroyWorker(WorkerHandle handle) {
        Process process = handle.process;

        if (process != null && process.isAlive()) {
            process.destroy();
        }

        releaseSlot(handle);
    }

    private void destroyAllWorkers() {
        for (WorkerHandle handle : workers.values()) {
            Process process = handle.process;

            if (process != null && process.isAlive()) {
                process.destroy();
            }
        }
    }

    private static void copyDirectory(File source, File target)
            throws IOException {
        if (!source.exists()) {
            return;
        }

        Path sourcePath = source.toPath();
        Path targetPath = target.toPath();

        Files.createDirectories(targetPath);

        try (Stream<Path> entries = Files.walk(sourcePath)) {
            for (Path entry : (Iterable<Path>) entries::iterator) {
                Path destination =
                        targetPath.resolve(sourcePath.relativize(entry));

                if (Files.isDirectory(entry)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(
                            entry,
                            destination,
                            StandardCopyOption.REPLACE_EXISTING
                    );
                }
            }
        }
    }

    private static void copyFile(File source, File target) throws IOException {
        Files.createDirectories(target.toPath().getParent());
        Files.copy(source.toPath(), target.toPath());
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(DuelServerManager.READINESS_POLL_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static Player onlinePlayer(String uuid) {
        return Groups.player.find(
                player -> player != null && player.uuid().equals(uuid)
        );
    }

    /**
     * One in-progress duel exposed to the /view menu.
     */
    record ActiveDuel(
            int port,
            String player1Display,
            String player2Display
    ) {
    }

    private static final class WorkerHandle {
        final int port;
        final long spawnedAtMillis = System.currentTimeMillis();
        volatile Process process;
        String player1Name = "?";
        String player1Uuid = "";
        String player1Display = "?";
        String player2Name = "?";
        String player2Uuid = "";
        String player2Display = "?";
        List<String> adminUuids = new ArrayList<>();
        List<String> bannedBlocks = new ArrayList<>();

        WorkerHandle(int port) {
            this.port = port;
        }
    }
}
