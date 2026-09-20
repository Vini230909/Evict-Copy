// A match worker's folder on disk: provisioned from the hub's files, handed a handshake, launched.
package Extinction;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import arc.util.Log;
import mindustry.Vars;
import mindustry.net.Administration;
import mindustry.world.Block;

public final class WorkerFolder {

    // Worker folders share one base folder and are named by port: duel-workers/duel-<port>.
    private static final String WORKER_DIR_PREFIX = "duel-";
    private static final String WORKER_BASE_DIR = "duel-workers";
    private static final String WORKER_LOG_FILE = "worker.log";
    private static final long READINESS_TIMEOUT_MILLIS = 45_000L;
    private static final long READINESS_POLL_MILLIS = 500L;
    private static final int READINESS_CONNECT_TIMEOUT_MILLIS = 1_000;

    private WorkerFolder() {
    }

    public static File dir(int port) {
        return new File(new File(WORKER_BASE_DIR), WORKER_DIR_PREFIX + port);
    }

    // Hub startup: brings every existing worker folder onto the hub's current server jar.
    // See docs/GAMEPLAY.md, Matches: a stale worker must never host a version-mismatched server.
    public static void refreshJars() {
        File[] workerDirs = new File(WORKER_BASE_DIR).listFiles(
                file -> file.isDirectory()
                        && file.getName().startsWith(WORKER_DIR_PREFIX)
        );

        if (workerDirs == null) {
            return;
        }

        File sourceJar = new File(Config.duelWorkerJar);

        if (!sourceJar.exists()) {
            return;
        }

        int refreshed = 0;

        for (File workerDir : workerDirs) {
            File jar = new File(workerDir, Config.duelWorkerJar);

            if (!jar.exists() || !jarOutOfDate(sourceJar, jar)) {
                continue;
            }

            try {
                copyFile(sourceJar, jar);
                refreshed++;
            } catch (IOException exception) {
                // Non-fatal: a locked worker jar gets its refresh on the next spawn instead.
                Log.err(
                        "[EvictMapGenerator] 1v1: could not refresh the jar in "
                                + workerDir.getPath() + ".",
                        exception
                );
            }
        }

        if (refreshed > 0) {
            Log.info(
                    "[EvictMapGenerator] 1v1: refreshed the server jar in @ worker folder(s) to the hub's current version.",
                    refreshed
            );
        }
    }

    // The big static files are copied once; mods, admins and settings are refreshed every spawn.
    public static File provision(MatchSlot slot) throws IOException {
        File workerDir = dir(slot.port);
        File workerConfig = new File(workerDir, "config");
        Files.createDirectories(workerConfig.toPath());

        File jar = new File(workerDir, Config.duelWorkerJar);
        if (!jar.exists()) {
            Log.info(
                    "[EvictMapGenerator] 1v1: provisioning worker folder @ from the hub files.",
                    workerDir.getPath()
            );
            copyFile(new File(Config.duelWorkerJar), jar);
        }

        // Maps are refreshed every spawn: a Pure map added to the hub is playable at once.
        copyDirectory(new File("config/maps"), new File(workerConfig, "maps"));

        copyDirectory(new File("config/mods"), new File(workerConfig, "mods"));

        // Refreshed every spawn so the worker recognizes the hub's admins.
        writeAdminsFile(workerConfig, slot.adminUuids);

        // Refreshed every spawn so the worker's generation tuning and banned blocks match the hub's.
        copySettingsFile(workerConfig, slot.bannedBlocks);

        return workerDir;
    }

    // The hub's settings file, with the duel target cleared (no nested duels), the live banned
    // blocks written in, and every discord.* key dropped (the bot token is a credential).
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

        properties.setProperty("duel.server.ip", "");
        properties.setProperty(
                "rules.bannedBlocks",
                String.join(",", bannedBlocks)
        );

        for (String key : new ArrayList<>(properties.stringPropertyNames())) {
            if (key.startsWith("discord.")) {
                properties.remove(key);
            }
        }

        try (FileOutputStream output = new FileOutputStream(
                new File(workerConfig, "evict-map-generator.properties")
        )) {
            properties.store(output, "Evict synced hub generation settings");
        }
    }

    // Rewritten (even when empty) every spawn so a reused folder never keeps stale admins.
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

    // The blocks currently banned on the hub (internal ids), read from the live rules. Main thread.
    public static List<String> snapshotBannedBlockNames() {
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

    // The hub's admin UUIDs. Main thread.
    public static List<String> snapshotAdminUuids() {
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

    // java -Devict.duelWorker=true -jar <jar>, then port, votekick and host over stdin.
    // A Pure worker hosts the chosen map as it is: generation is switched off before the host.
    public static Process launch(File workerDir, int port, String pureMap) throws IOException {
        String javaExe = new File(
                System.getProperty("java.home"),
                "bin/java"
        ).getPath();

        ProcessBuilder builder = new ProcessBuilder(
                javaExe,
                "-Devict.duelWorker=true",
                "-jar",
                Config.duelWorkerJar
        );

        builder.directory(workerDir);
        builder.redirectErrorStream(true);
        builder.redirectOutput(new File(workerDir, WORKER_LOG_FILE));

        Process process = builder.start();

        OutputStream stdin = process.getOutputStream();
        writeCommand(stdin, "config port " + port);
        // Votekick lives in Mindustry's settings.bin, which is not copied; mirror the hub's value.
        writeCommand(stdin,
                "config enableVotekick " + Administration.Config.enableVotekick.bool());

        if (pureMap == null) {
            writeCommand(stdin, "host " + Config.duelWorkerMap + " pvp");
        } else {
            writeCommand(stdin, "oregen auto off");
            writeCommand(stdin, "host " + pureMap.replace(' ', '_') + " pvp");
        }

        return process;
    }

    private static void writeCommand(OutputStream stdin, String command)
            throws IOException {
        stdin.write((command + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();
    }

    // Polls the port until it accepts a connection, or gives up after the readiness timeout.
    public static boolean waitUntilReady(int port) {
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

    private static void sleepQuietly() {
        try {
            Thread.sleep(READINESS_POLL_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
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

    // Length plus last-modified catches a replaced jar without hashing every spawn.
    private static boolean jarOutOfDate(File source, File target) {
        if (!source.exists()) {
            return false;
        }

        return source.length() != target.length()
                || source.lastModified() > target.lastModified();
    }

    private static void copyFile(File source, File target) throws IOException {
        Files.createDirectories(target.toPath().getParent());
        Files.copy(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING
        );
    }
}
