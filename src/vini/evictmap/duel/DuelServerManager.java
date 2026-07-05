package vini.evictmap.duel;

import vini.evictmap.EvictSettings;
import vini.evictmap.PlayerDataManager;
import vini.evictmap.PlayerNameFormatter;

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
public final class DuelServerManager {

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

    public DuelServerManager(
            EvictSettings settings,
            PlayerDataManager playerDataManager
    ) {
        this.settings = settings;
        this.playerDataManager = playerDataManager;

        Runtime.getRuntime().addShutdownHook(
                new Thread(this::destroyAllWorkers, "evict-duel-shutdown")
        );
    }

    public boolean isConfigured() {
        return settings.duelServerConfigured();
    }

    /**
     * Reserves a worker and, once it is hosting, redirects every rostered
     * player to it. Each inner list is one match team (FFA passes N teams of
     * one player, Training/Sandbox a single team). Returns false immediately
     * if the feature is unconfigured or all worker slots are in use; the
     * caller is responsible for notifying the players in that case.
     */
    public boolean requestMatch(MatchMode mode, List<List<Player>> rosterTeams) {
        if (!isConfigured() || rosterTeams == null || rosterTeams.isEmpty()) {
            return false;
        }

        int port = reserveFreePort();

        if (port < 0) {
            return false;
        }

        WorkerHandle handle = new WorkerHandle(port);
        handle.mode = mode;

        List<List<String>> rosterUuids = new ArrayList<>();

        for (int teamIndex = 0; teamIndex < rosterTeams.size(); teamIndex++) {
            List<String> uuids = new ArrayList<>();

            for (Player player : rosterTeams.get(teamIndex)) {
                uuids.add(player.uuid());
                handle.participants.add(new Participant(
                        player.uuid(),
                        player.plainName(),
                        PlayerNameFormatter.displayName(player),
                        teamIndex
                ));
            }

            rosterUuids.add(uuids);
        }

        handle.label = matchLabel(mode, rosterTeams);
        handle.adminUuids = snapshotAdminUuids();
        handle.bannedBlocks = snapshotBannedBlockNames();
        workers.put(port, handle);

        spawnExecutor.submit(() -> spawnAndRedirect(handle, rosterUuids));

        scheduleLifetimeKill(handle);
        return true;
    }

    /**
     * Caps how many player names a /v or /h label spells out before folding
     * the rest into "+N more" - FFA has no participant cap, and a match (or
     * history entry) with a lot of players in it used to render as one huge,
     * broken-looking line of text.
     */
    private static final int MAX_LABEL_NAMES = 4;

    /**
     * Menu/log label for a match, e.g. "A vs B", "A, B vs C (Teams)",
     * "A vs B vs C (FFA)" or "A (Training)". Once a roster runs past
     * {@link #MAX_LABEL_NAMES} names it is shortened to "A, B, C, D (+N
     * more)" instead of listing everyone.
     */
    private static String matchLabel(
            MatchMode mode,
            List<List<Player>> rosterTeams
    ) {
        String label;

        if (mode == MatchMode.FFA) {
            // FFA passes one single-player "team" per participant, so the
            // vs-chain length is the whole player count - shorten the flat
            // list instead of the per-team names.
            List<String> allNames = new ArrayList<>();

            for (List<Player> roster : rosterTeams) {
                for (Player player : roster) {
                    allNames.add(PlayerNameFormatter.displayName(player));
                }
            }

            label = PlayerNameFormatter.joinShortened(
                    allNames, "[white], []", MAX_LABEL_NAMES
            );
        } else {
            List<String> teamNames = new ArrayList<>();

            for (List<Player> roster : rosterTeams) {
                List<String> names = new ArrayList<>();

                for (Player player : roster) {
                    names.add(PlayerNameFormatter.displayName(player));
                }

                teamNames.add(
                        PlayerNameFormatter.joinShortened(
                                names, "[white], []", MAX_LABEL_NAMES
                        )
                );
            }

            label = String.join(" [white]vs[] ", teamNames);
        }

        if (mode != MatchMode.ONE_VS_ONE) {
            label += " [lightgray](" + mode.label() + ")[]";
        }

        return label;
    }

    private void spawnAndRedirect(
            WorkerHandle handle,
            List<List<String>> rosterUuids
    ) {
        try {
            File workerDir = provisionWorkerDir(handle);

            writeHandshake(workerDir, handle.mode, rosterUuids);

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
                    notifyFailure(handle);
                    destroyWorker(handle);
                    return;
                }

                redirectPlayers(handle);
            });
        } catch (Exception exception) {
            Log.err(
                    "[EvictMapGenerator] 1v1: failed to start a duel worker on port "
                            + handle.port + ".",
                    exception
            );

            Core.app.post(() -> {
                notifyFailure(handle);
                destroyWorker(handle);
            });
        }
    }

    private void redirectPlayers(WorkerHandle handle) {
        if (workers.get(handle.port) != handle) {
            // Slot was already released (e.g. the worker died) meanwhile.
            return;
        }

        List<Player> players = new ArrayList<>();

        for (Participant participant : handle.participants) {
            Player player = onlinePlayer(participant.uuid());

            if (player == null) {
                Log.info(
                        "[EvictMapGenerator] 1v1: a player left before the worker on port @ was ready; releasing slot.",
                        handle.port
                );
                notifyFailure(handle);
                destroyWorker(handle);
                return;
            }

            players.add(player);
        }

        String ip = settings.duelServerIp();

        for (Player player : players) {
            player.sendMessage(
                    "[accent]Connecting you to your "
                            + handle.mode.label() + "...[]"
            );
            activeDuelByUuid.put(player.uuid(), handle.port);
            Call.connect(player.con, ip, handle.port);
        }

        Log.info(
                "[EvictMapGenerator] 1v1: sent @ player(s) to @ worker @:@.",
                players.size(),
                handle.mode.id(),
                ip,
                handle.port
        );
    }

    /**
     * On the hub: if a reconnecting player is still mid-duel, send them straight
     * back to their worker. Returns true when the player was bounced. Skips (and
     * forgets) finished or dead matches so a returning winner stays on the hub.
     */
    public boolean tryReturnToActiveDuel(Player player) {
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

        // A knocked-out FFA player is free: the worker lists them as "out"
        // in its status file, so they join the normal hub round instead.
        Properties status = readStatus(port);

        if (
                status != null
                        && isListedUuid(
                        status.getProperty("out", ""),
                        player.uuid()
                )
        ) {
            activeDuelByUuid.remove(player.uuid());
            return false;
        }

        player.sendMessage("[accent]Returning you to your match...[]");
        Call.connect(player.con, settings.duelServerIp(), port);
        return true;
    }

    private static boolean isListedUuid(String packed, String uuid) {
        if (packed == null || packed.isBlank()) {
            return false;
        }

        for (String entry : packed.split(",")) {
            if (entry.trim().equals(uuid)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Snapshot of the duels in progress for the /view menu. Main-thread only.
     * Finished or not-yet-hosting workers are skipped so a viewer is never sent
     * to a match that is wrapping up or not ready, ordered by port for a stable
     * menu.
     */
    public List<ActiveDuel> activeDuels() {
        List<ActiveDuel> duels = new ArrayList<>();

        for (WorkerHandle handle : workers.values()) {
            if (isOngoing(handle)) {
                duels.add(new ActiveDuel(handle.port, handle.label));
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
    public int connectedDuelPlayers() {
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
    public boolean viewDuel(Player viewer, int port) {
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
    public void logStatus() {
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
                        "[EvictMapGenerator]   port @ [@] uptime=@ mode=@ players: @",
                        handle.port,
                        alive ? "starting" : "closing",
                        uptime,
                        handle.mode.id(),
                        participantSummary(handle)
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

    private void notifyFailure(WorkerHandle handle) {
        for (Participant participant : handle.participants) {
            Player player = onlinePlayer(participant.uuid());

            if (player != null) {
                player.sendMessage(
                        "[scarlet]The match server could not be started. Try again.[]"
                );
            }
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
            MatchMode mode,
            List<List<String>> rosterUuids
    ) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("mode", mode.id());
        properties.setProperty(
                "team.count",
                Integer.toString(rosterUuids.size())
        );

        for (int index = 0; index < rosterUuids.size(); index++) {
            properties.setProperty(
                    "team." + (index + 1),
                    String.join(",", rosterUuids.get(index))
            );
        }

        properties.setProperty("hub.ip", settings.duelServerIp());
        properties.setProperty("hub.port", Integer.toString(hubPort()));

        try (FileOutputStream output =
                     new FileOutputStream(new File(workerDir, "duel.properties"))) {
            properties.store(output, "Evict duel handshake");
        }
    }

    private static String participantSummary(WorkerHandle handle) {
        StringBuilder summary = new StringBuilder();

        for (Participant participant : handle.participants) {
            if (!summary.isEmpty()) {
                summary.append(", ");
            }

            summary.append(participant.plainName())
                    .append(" (")
                    .append(participant.uuid())
                    .append(")");
        }

        return summary.isEmpty() ? "(none)" : summary.toString();
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
            MatchMode mode = MatchMode.fromId(
                    properties.getProperty("mode", handle.mode.id()).trim()
            );

            Log.info(
                    "[EvictMapGenerator] @ result on port @: winner=@ loser=@ reason=@.",
                    mode.id(),
                    port,
                    winnerUuid.isEmpty() ? "?" : winnerUuid,
                    loserUuid.isEmpty() ? "?" : loserUuid,
                    properties.getProperty("reason", "?")
            );

            // Credit the ranked record and match history on the hub's database;
            // the worker runs in its own process and cannot reach this DB. The
            // colored display names captured at match start let /history render
            // them later without the players being online. Only 1v1 is ranked;
            // Teams and FFA are stored as unranked history entries listing
            // everyone; Training and Sandbox leave no history at all.
            if (mode.duel().ranked()) {
                playerDataManager.recordRankedResult(
                        winnerUuid,
                        displayNameFor(handle, winnerUuid),
                        loserUuid,
                        displayNameFor(handle, loserUuid)
                );
            } else if (mode == MatchMode.FFA && !winnerUuid.isEmpty()) {
                List<String> participantUuids = new ArrayList<>();
                List<String> participantNames = new ArrayList<>();

                for (Participant participant : handle.participants) {
                    participantUuids.add(participant.uuid());
                    participantNames.add(participant.display());
                }

                playerDataManager.recordFfaMatch(
                        winnerUuid,
                        displayNameFor(handle, winnerUuid),
                        participantUuids,
                        participantNames
                );
            } else if (mode == MatchMode.TEAMS && !winnerUuid.isEmpty()) {
                List<String> winnerUuids = splitUuidList(
                        properties.getProperty("winner.uuids", winnerUuid)
                );
                List<String> loserUuids = splitUuidList(
                        properties.getProperty("loser.uuids", loserUuid)
                );

                playerDataManager.recordTeamsMatch(
                        winnerUuids,
                        teamLabelFor(handle, winnerUuids),
                        loserUuids,
                        losingTeamsLabel(handle, winnerUuids)
                );
            }
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
        for (Participant participant : handle.participants) {
            if (participant.uuid().equals(uuid)) {
                return participant.display();
            }
        }

        return uuid;
    }

    private static List<String> splitUuidList(String packed) {
        List<String> uuids = new ArrayList<>();

        if (packed == null || packed.isBlank()) {
            return uuids;
        }

        for (String entry : packed.split(",")) {
            String trimmed = entry.trim();

            if (!trimmed.isEmpty()) {
                uuids.add(trimmed);
            }
        }

        return uuids;
    }

    /**
     * Display label of one Teams roster, e.g. "A[white], []B", shortened to
     * "A, B, C, D (+N more)" past {@link #MAX_LABEL_NAMES} names so a big
     * roster never blows up its /history entry.
     */
    private static String teamLabelFor(
            WorkerHandle handle,
            List<String> uuids
    ) {
        List<String> names = new ArrayList<>();

        for (String uuid : uuids) {
            names.add(displayNameFor(handle, uuid));
        }

        return names.isEmpty()
                ? "?"
                : PlayerNameFormatter.joinShortened(
                        names, "[white], []", MAX_LABEL_NAMES
                );
    }

    /**
     * Labels of every losing roster (kept as separate teams), joined with
     * "vs" so a 3+ team match renders as e.g. "C vs D, E" in /history.
     */
    private static String losingTeamsLabel(
            WorkerHandle handle,
            List<String> winnerUuids
    ) {
        Map<Integer, List<String>> loserTeams = new LinkedHashMap<>();

        for (Participant participant : handle.participants) {
            if (!winnerUuids.contains(participant.uuid())) {
                loserTeams
                        .computeIfAbsent(
                                participant.teamIndex(),
                                ignored -> new ArrayList<>()
                        )
                        .add(participant.uuid());
            }
        }

        StringBuilder label = new StringBuilder();

        for (List<String> team : loserTeams.values()) {
            if (!label.isEmpty()) {
                label.append(" [white]vs[] ");
            }

            label.append(teamLabelFor(handle, team));
        }

        return label.isEmpty() ? "?" : label.toString();
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
     * One in-progress match exposed to the /view menu.
     */
    public record ActiveDuel(
            int port,
            String label
    ) {
    }

    /**
     * One rostered player of a match, snapshotted at spawn so names render
     * even after the player disconnects. teamIndex groups Teams rosters for
     * the /history labels.
     */
    private record Participant(
            String uuid,
            String plainName,
            String display,
            int teamIndex
    ) {
    }

    private static final class WorkerHandle {
        final int port;
        final long spawnedAtMillis = System.currentTimeMillis();
        volatile Process process;
        MatchMode mode = MatchMode.ONE_VS_ONE;
        String label = "?";
        final List<Participant> participants = new ArrayList<>();
        List<String> adminUuids = new ArrayList<>();
        List<String> bannedBlocks = new ArrayList<>();

        WorkerHandle(int port) {
            this.port = port;
        }
    }
}
