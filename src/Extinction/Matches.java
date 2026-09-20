// The hub's match worker pool: one match server per match, spawned on demand, gone when empty.
package Extinction;

import Extinction.data.PlayerDataManager;
import Extinction.discord.ChatLogReporter;
import Extinction.moderation.ban.BanRequest;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import arc.Core;
import arc.util.Log;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;

public final class Matches {

    private static final long MAX_WORKER_LIFETIME_MINUTES = 110L;

    // Spawning and readiness waits run here; slot bookkeeping is posted back to the main thread.
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

    // Port -> reserved/running worker. Only mutated on the main thread.
    private final Map<Integer, MatchSlot> workers = new HashMap<>();

    // Player UUID -> the worker port of their in-progress match, to bounce a reconnect back.
    private final Map<String, Integer> activeDuelByUuid = new HashMap<>();

    // The worker -> hub side: status polls, playtime, ban requests, chat, results.
    private final WorkerReports reports;

    public Matches(
            PlayerDataManager playerDataManager,
            Consumer<BanRequest> banRequestSink,
            ChatLogReporter chatLog
    ) {
        this.reports = new WorkerReports(
                playerDataManager,
                banRequestSink,
                chatLog,
                spawnExecutor,
                workers::get
        );

        Runtime.getRuntime().addShutdownHook(
                new Thread(this::destroyAllWorkers, "evict-duel-shutdown")
        );
    }

    // A blank duel server ip leaves /play inert.
    public boolean isConfigured() {
        return Config.duelServerIp != null && !Config.duelServerIp.isBlank();
    }

    // Reserves a worker and, once it is hosting, redirects every rostered player to it.
    // False at once when unconfigured or every slot is busy; the caller tells the players.
    public boolean requestMatch(MatchMode mode, List<List<Player>> rosterTeams) {
        if (!isConfigured() || rosterTeams == null || rosterTeams.isEmpty()) {
            return false;
        }

        int port = reserveFreePort();

        if (port < 0) {
            return false;
        }

        MatchSlot slot = new MatchSlot(port);
        slot.mode = mode;

        List<List<String>> rosterUuids = new ArrayList<>();

        for (int teamIndex = 0; teamIndex < rosterTeams.size(); teamIndex++) {
            List<String> uuids = new ArrayList<>();

            for (Player player : rosterTeams.get(teamIndex)) {
                uuids.add(player.uuid());
                slot.participants.add(new MatchSlot.Participant(
                        player.uuid(),
                        player.plainName(),
                        PlayerNameFormatter.displayName(player),
                        teamIndex
                ));
            }

            rosterUuids.add(uuids);
        }

        slot.label = matchLabel(mode, rosterTeams);
        slot.adminUuids = WorkerFolder.snapshotAdminUuids();
        slot.bannedBlocks = WorkerFolder.snapshotBannedBlockNames();

        // Here, not on the spawn thread: the slot goes live on the next line, and a status
        // poll in between would credit the reused folder's old playtime a second time.
        reports.clearFiles(port);

        workers.put(port, slot);

        announceMatchStart(mode, slot.label);
        reports.matchStarted(slot);

        spawnExecutor.submit(() -> spawnAndRedirect(slot, rosterUuids));

        scheduleLifetimeKill(slot);
        return true;
    }

    // "A vs B", "A, B vs C (Teams)", "A vs B vs C (FFA)" or "A (Training)", names shortened.
    private static String matchLabel(
            MatchMode mode,
            List<List<Player>> rosterTeams
    ) {
        String label;

        if (mode == MatchMode.FFA) {
            // FFA passes one single-player "team" per participant: shorten the flat list.
            List<String> allNames = new ArrayList<>();

            for (List<Player> roster : rosterTeams) {
                for (Player player : roster) {
                    allNames.add(PlayerNameFormatter.displayName(player));
                }
            }

            label = PlayerNameFormatter.joinShortened(
                    allNames, "[white], []", MatchSlot.MAX_LABEL_NAMES
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
                                names, "[white], []", MatchSlot.MAX_LABEL_NAMES
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

    // Tells the hub a match is starting; the label already names the mode for all but 1v1.
    private static void announceMatchStart(MatchMode mode, String label) {
        String announcement = mode == MatchMode.ONE_VS_ONE
                ? label + " [lightgray](" + mode.label() + ")[]"
                : label;

        Call.sendMessage("[accent]Match starting:[] " + announcement);
    }

    private void spawnAndRedirect(
            MatchSlot slot,
            List<List<String>> rosterUuids
    ) {
        try {
            File workerDir = WorkerFolder.provision(slot);

            MatchHandshake.write(workerDir, slot, rosterUuids);

            Process process = WorkerFolder.launch(workerDir, slot.port);
            slot.process = process;

            process.onExit().thenRun(
                    () -> Core.app.post(() -> {
                        // Chat first: the players' last words belong before the end embed.
                        reports.drainWorkerChat(slot.port);
                        reports.logResult(slot);
                        reports.creditFinalPlaytime(slot);
                        releaseSlot(slot);
                    })
            );

            boolean ready = WorkerFolder.waitUntilReady(slot.port);

            Core.app.post(() -> {
                if (!ready || !process.isAlive()) {
                    Log.err(
                            "[EvictMapGenerator] 1v1: worker on port @ did not become ready; releasing slot.",
                            slot.port
                    );
                    notifyFailure(slot);
                    destroyWorker(slot);
                    return;
                }

                redirectPlayers(slot);
            });
        } catch (Exception exception) {
            Log.err(
                    "[EvictMapGenerator] 1v1: failed to start a duel worker on port "
                            + slot.port + ".",
                    exception
            );

            Core.app.post(() -> {
                notifyFailure(slot);
                destroyWorker(slot);
            });
        }
    }

    private void redirectPlayers(MatchSlot slot) {
        if (workers.get(slot.port) != slot) {
            // Slot was already released (e.g. the worker died) meanwhile.
            return;
        }

        List<Player> players = new ArrayList<>();

        for (MatchSlot.Participant participant : slot.participants) {
            Player player = onlinePlayer(participant.uuid());

            if (player == null) {
                Log.info(
                        "[EvictMapGenerator] 1v1: a player left before the worker on port @ was ready; releasing slot.",
                        slot.port
                );
                notifyFailure(slot);
                destroyWorker(slot);
                return;
            }

            players.add(player);
        }

        String ip = Config.duelServerIp;

        for (Player player : players) {
            player.sendMessage(
                    "[accent]Connecting you to your "
                            + slot.mode.label() + "...[]"
            );
            activeDuelByUuid.put(player.uuid(), slot.port);
            Call.connect(player.con, ip, slot.port);
        }

        Log.info(
                "[EvictMapGenerator] 1v1: sent @ player(s) to @ worker @:@.",
                players.size(),
                slot.mode.id(),
                ip,
                slot.port
        );
    }

    // Hub join: a player still mid-match is sent straight back to their worker. True when bounced.
    public boolean tryReturnToActiveDuel(Player player) {
        if (player == null) {
            return false;
        }

        Integer port = activeDuelByUuid.get(player.uuid());

        // Sandbox guests join after launch: find their worker from its live roster and cache it.
        if (port == null) {
            port = findWorkerHostingParticipant(player.uuid());

            if (port == null) {
                return false;
            }

            activeDuelByUuid.put(player.uuid(), port);
        }

        MatchSlot slot = workers.get(port);

        if (!isOngoing(slot)) {
            activeDuelByUuid.remove(player.uuid());
            return false;
        }

        // A knocked-out FFA player is "out" in the status file: free to join the hub round.
        Properties status = WorkerReports.readStatus(port);

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
        Call.connect(player.con, Config.duelServerIp, port);
        return true;
    }

    // The ongoing worker still listing this uuid as a live, not "out", participant; else null.
    private Integer findWorkerHostingParticipant(String uuid) {
        for (MatchSlot slot : workers.values()) {
            if (!isOngoing(slot)) {
                continue;
            }

            Properties status = WorkerReports.readStatus(slot.port);

            if (
                    status != null
                            && isListedUuid(
                            status.getProperty("participants", ""),
                            uuid
                    )
                            && !isListedUuid(status.getProperty("out", ""), uuid)
            ) {
                return slot.port;
            }
        }

        return null;
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

    // The matches in progress for the /spectate menu, by port. Main-thread only.
    public List<ActiveDuel> activeDuels() {
        List<ActiveDuel> duels = new ArrayList<>();

        for (MatchSlot slot : workers.values()) {
            if (isOngoing(slot)) {
                duels.add(new ActiveDuel(slot.port, slot.label));
            }
        }

        duels.sort(Comparator.comparingInt(ActiveDuel::port));
        return duels;
    }

    // The running matches for the Discord status message: in-memory only, by pool slot not port.
    public List<MatchStatus> matchStatuses() {
        List<MatchStatus> statuses = new ArrayList<>();
        long now = System.currentTimeMillis();

        for (MatchSlot slot : workers.values()) {
            if (!isOngoing(slot)) {
                continue;
            }

            statuses.add(new MatchStatus(
                    slot.port - Config.duelServerPort + 1,
                    slot.mode.label(),
                    slot.rosterNames(),
                    (now - slot.spawnedAtMillis) / 1000L
            ));
        }

        statuses.sort(Comparator.comparingInt(MatchStatus::slot));
        return statuses;
    }

    // Players inside all workers, folded into the hub's advertised count. Per frame, main thread.
    public int connectedDuelPlayers() {
        return reports.connectedDuelPlayers(new ArrayList<>(workers.keySet()));
    }

    // Connects a viewer to an ongoing worker as a spectator; false when that match is gone.
    public boolean viewDuel(Player viewer, int port) {
        MatchSlot slot = workers.get(port);

        if (viewer == null || !isOngoing(slot)) {
            return false;
        }

        viewer.sendMessage(
                "[accent]Connecting you to the " + slot.mode.label()
                        + " as a spectator...[]"
        );
        Call.sendMessage(
                PlayerNameFormatter.displayName(viewer)
                        + "[accent] is now spectating[] " + slot.label
        );
        Call.connect(viewer.con, Config.duelServerIp, port);
        return true;
    }

    private static boolean isOngoing(MatchSlot slot) {
        return slot != null
                && slot.alive()
                && !new File(
                WorkerFolder.dir(slot.port),
                "result.properties"
        ).exists();
    }

    // The worker pool for the console: uptime is wall-clock since spawn.
    public void logStatus() {
        int basePort = Config.duelServerPort;
        int maxWorkers = Config.duelMaxWorkers;

        Log.info(
                "[EvictMapGenerator] Duel pool: @ active of @ slots, ip=@, ports @-@.",
                workers.size(),
                maxWorkers,
                isConfigured() ? Config.duelServerIp : "not set",
                basePort,
                basePort + maxWorkers - 1
        );

        if (workers.isEmpty()) {
            Log.info("[EvictMapGenerator]   (no active duels)");
            return;
        }

        long now = System.currentTimeMillis();

        for (MatchSlot slot : workers.values()) {
            boolean alive = slot.alive();
            String uptime = WorkerReports.formatHms((now - slot.spawnedAtMillis) / 1000L);
            Properties status = WorkerReports.readStatus(slot.port);

            if (alive && status != null) {
                Log.info(
                        "[EvictMapGenerator]   port @ [@] uptime=@ game=@ players: @",
                        slot.port,
                        status.getProperty("state", "?"),
                        uptime,
                        WorkerReports.formatHms(WorkerReports.parseLong(status.getProperty("elapsedSeconds"))),
                        WorkerReports.formatPlayers(status.getProperty("players", ""))
                );
            } else {
                Log.info(
                        "[EvictMapGenerator]   port @ [@] uptime=@ mode=@ players: @",
                        slot.port,
                        alive ? "starting" : "closing",
                        uptime,
                        slot.mode.id(),
                        slot.summary()
                );
            }
        }
    }

    private void notifyFailure(MatchSlot slot) {
        for (MatchSlot.Participant participant : slot.participants) {
            Player player = onlinePlayer(participant.uuid());

            if (player != null) {
                player.sendMessage(
                        "[scarlet]The match server could not be started. Try again.[]"
                );
            }
        }
    }

    private int reserveFreePort() {
        int basePort = Config.duelServerPort;
        int maxWorkers = Config.duelMaxWorkers;

        for (int offset = 0; offset < maxWorkers; offset++) {
            int port = basePort + offset;

            if (!workers.containsKey(port)) {
                return port;
            }
        }

        return -1;
    }

    private void scheduleLifetimeKill(MatchSlot slot) {
        lifetimeScheduler.schedule(
                () -> Core.app.post(() -> {
                    if (workers.get(slot.port) == slot) {
                        Log.info(
                                "[EvictMapGenerator] 1v1: worker on port @ hit the max lifetime; stopping it.",
                                slot.port
                        );
                        destroyWorker(slot);
                    }
                }),
                MAX_WORKER_LIFETIME_MINUTES,
                TimeUnit.MINUTES
        );
    }

    private void releaseSlot(MatchSlot slot) {
        if (workers.get(slot.port) == slot) {
            workers.remove(slot.port);
            reports.forget(slot.port);
            activeDuelByUuid.values().removeIf(port -> port == slot.port);

            reports.reportAbandoned(slot);

            Log.info(
                    "[EvictMapGenerator] 1v1: duel worker on port @ ended; slot is free again.",
                    slot.port
            );
        }
    }

    private void destroyWorker(MatchSlot slot) {
        Process process = slot.process;

        if (process != null && process.isAlive()) {
            process.destroy();
        }

        releaseSlot(slot);
    }

    private void destroyAllWorkers() {
        for (MatchSlot slot : workers.values()) {
            Process process = slot.process;

            if (process != null && process.isAlive()) {
                process.destroy();
            }
        }
    }

    private static Player onlinePlayer(String uuid) {
        return Groups.player.find(
                player -> player != null && player.uuid().equals(uuid)
        );
    }

    // One in-progress match exposed to the /spectate menu.
    public record ActiveDuel(int port, String label) {
    }

    // One running match for the Discord status: 1-based pool slot, mode, names by team, uptime.
    public record MatchStatus(
            int slot,
            String modeLabel,
            List<List<String>> teamNames,
            long seconds
    ) {
    }
}
