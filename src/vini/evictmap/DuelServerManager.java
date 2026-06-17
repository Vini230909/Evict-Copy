package vini.evictmap;

import arc.Core;
import arc.util.Log;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * On-demand 1v1 worker orchestration for the hub server.
 *
 * Each duel runs on its own Mindustry server process, because a single process
 * hosts only one game. A worker is spawned only when a match is requested and
 * terminates itself once the match empties, so idle duels cost no CPU.
 *
 * Flow per match:
 * 1. Reserve a free port from the configured range (main thread, no locking).
 * 2. On a background thread: provision the worker folder if missing, launch
 *    {@code java -Devict.duelWorker=true -jar <jar>}, inject the port and host
 *    command on stdin, then poll the port until it accepts connections.
 * 3. Back on the main thread: redirect both players, or release the slot and
 *    notify them on failure.
 *
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
    private static final long MAX_WORKER_LIFETIME_MINUTES = 30L;

    private final EvictSettings settings;

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

    /** Port -> reserved/running worker. Only mutated on the main thread. */
    private final Map<Integer, WorkerHandle> workers = new HashMap<>();

    DuelServerManager(EvictSettings settings) {
        this.settings = settings;

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
            File workerDir = provisionWorkerDir(handle.port);

            Process process = launchWorker(workerDir, handle.port);
            handle.process = process;

            process.onExit().thenRun(
                () -> Core.app.post(() -> releaseSlot(handle))
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

    private File provisionWorkerDir(int port) throws IOException {
        File baseDir = new File(WORKER_BASE_DIR);
        File workerDir = new File(baseDir, WORKER_DIR_PREFIX + port);
        File jar = new File(workerDir, settings.duelWorkerJarName());

        if (jar.exists()) {
            return workerDir;
        }

        Log.info(
            "[EvictMapGenerator] 1v1: provisioning worker folder @ from the hub files.",
            workerDir.getPath()
        );

        File workerConfig = new File(workerDir, "config");
        Files.createDirectories(workerConfig.toPath());

        copyFile(new File(settings.duelWorkerJarName()), jar);
        copyDirectory(new File("config/mods"), new File(workerConfig, "mods"));
        copyDirectory(new File("config/maps"), new File(workerConfig, "maps"));

        return workerDir;
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
                sleepQuietly(READINESS_POLL_MILLIS);
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

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static Player onlinePlayer(String uuid) {
        return Groups.player.find(
            player -> player != null && player.uuid().equals(uuid)
        );
    }

    private static final class WorkerHandle {
        final int port;
        volatile Process process;

        WorkerHandle(int port) {
            this.port = port;
        }
    }
}
