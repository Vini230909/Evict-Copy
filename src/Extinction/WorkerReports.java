// What a worker reports back through its files: status every second, chat lines, the result.
package Extinction;

import Extinction.data.PlayerDataManager;
import Extinction.discord.ChatLogReporter;
import Extinction.discord.ChatLogTail;
import Extinction.discord.DiscordFormat;
import Extinction.moderation.ban.BanOrigin;
import Extinction.moderation.ban.BanRequest;
import Extinction.moderation.ban.WordFilterHit;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.IntFunction;

import arc.Core;
import arc.util.Log;

public final class WorkerReports {

    private final PlayerDataManager playerDataManager;

    // Hands a ban made on a match server to the hub's normal ban path, with its story.
    private final Consumer<BanRequest> banRequestSink;

    // The Discord chat mirror: worker chat lines go into the match's port channel.
    private final ChatLogReporter chatLog;

    // The background thread the status files are read on; never the game loop.
    private final ExecutorService executor;

    // The live slot for a port, or null once released. Main-thread only.
    private final IntFunction<MatchSlot> slotByPort;

    // How far each worker's chat.log has been relayed. Reset at spawn, drained on poll and exit.
    private final ChatLogTail chatTail = new ChatLogTail();

    // Port -> per-UUID playtime already credited; workers publish running totals. Main-thread only.
    private final Map<Integer, Map<String, Long>> creditedPlaytimeByPort =
            new HashMap<>();

    // Ban requests already applied; workers republish their whole list every write. Main-thread only.
    private final Set<String> appliedWorkerBanRequests = new LinkedHashSet<>();

    // Last computed total of players inside the workers; at most one refresh in flight.
    private volatile int cachedConnectedDuelPlayers = 0;
    private final AtomicBoolean refreshRunning = new AtomicBoolean(false);

    public WorkerReports(
            PlayerDataManager playerDataManager,
            Consumer<BanRequest> banRequestSink,
            ChatLogReporter chatLog,
            ExecutorService executor,
            IntFunction<MatchSlot> slotByPort
    ) {
        this.playerDataManager = playerDataManager;
        this.banRequestSink = banRequestSink;
        this.chatLog = chatLog;
        this.executor = executor;
        this.slotByPort = slotByPort;
    }

    public static Properties readStatus(int port) {
        File statusFile = new File(WorkerFolder.dir(port), "status.properties");

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

    // At spawn: the reused folder's old status (already credited) and chat must not be read again.
    public void clearFiles(int port) {
        //noinspection ResultOfMethodCallIgnored
        new File(WorkerFolder.dir(port), "status.properties").delete();
        //noinspection ResultOfMethodCallIgnored
        new File(WorkerFolder.dir(port), "chat.log").delete();
        chatTail.reset(port);
    }

    // The start embed in the match's port channel: mode and the rosters.
    public void matchStarted(MatchSlot slot) {
        chatLog.matchStarted(slot.port, slot.mode.label(), slot.rosterNames());
    }

    // At release: the slot's credited totals go with it (the final total was credited on exit).
    public void forget(int port) {
        creditedPlaytimeByPort.remove(port);
    }

    // Players connected across all workers (duelists plus spectators), from the status files.
    // Called per frame: returns the last total and starts at most one background refresh.
    public int connectedDuelPlayers(List<Integer> ports) {
        if (ports.isEmpty()) {
            cachedConnectedDuelPlayers = 0;
            return 0;
        }

        if (refreshRunning.compareAndSet(false, true)) {
            executor.submit(() -> {
                try {
                    int total = 0;
                    Map<Integer, Properties> statuses = new LinkedHashMap<>();

                    for (int port : ports) {
                        Properties status = readStatus(port);

                        if (status != null) {
                            statuses.put(port, status);
                            total += countPlayers(
                                    status.getProperty("players", "")
                            );
                        }

                        // Same poll, same thread: relay what the worker appended to its chat.log.
                        drainWorkerChat(port);
                    }

                    cachedConnectedDuelPlayers = total;

                    // The same files carry playtime and ban requests; deal with both now.
                    Core.app.post(() -> {
                        creditPlaytime(statuses);
                        applyBanRequests(statuses);
                    });
                } finally {
                    refreshRunning.set(false);
                }
            });
        }

        return cachedConnectedDuelPlayers;
    }

    // Credits only the growth since the last poll, so a repeated or missed poll costs nothing.
    private void creditPlaytime(Map<Integer, Properties> statuses) {
        for (Map.Entry<Integer, Properties> entry : statuses.entrySet()) {
            int port = entry.getKey();

            if (slotByPort.apply(port) == null) {
                // Slot already released; its final total was credited on exit.
                continue;
            }

            creditPlaytime(
                    port,
                    entry.getValue().getProperty("playtime", ""),
                    entry.getValue().getProperty("players", "")
            );
        }
    }

    // Applies the bans the workers asked for, so every ban is decided and logged in one place.
    private void applyBanRequests(Map<Integer, Properties> statuses) {
        for (Map.Entry<Integer, Properties> entry : statuses.entrySet()) {
            Properties status = entry.getValue();

            for (String uuid : splitUuidList(status.getProperty("banrequests", ""))) {
                if (!appliedWorkerBanRequests.add(uuid)) {
                    continue;
                }

                Log.info(
                        "[EvictMapGenerator] Applying a ban made on a match server: @.",
                        uuid
                );

                banRequestSink.accept(workerBanRequest(status, uuid, entry.getKey()));
            }
        }
    }

    // What the worker published about a ban: who banned, its console time, what the filter saw.
    private static BanRequest workerBanRequest(
            Properties status,
            String uuid,
            int port
    ) {
        String prefix = "banrequest." + uuid + ".";

        return new BanRequest(
                uuid,
                BanOrigin.fromWorker(
                        status.getProperty(prefix + "actor", ""),
                        port,
                        status.getProperty(prefix + "time", "")
                ),
                WordFilterHit.fromWorker(
                        status.getProperty(prefix + "source", ""),
                        status.getProperty(prefix + "word", ""),
                        status.getProperty(prefix + "text", "")
                )
        );
    }

    private void creditPlaytime(int port, String packed, String packedPlayers) {
        if (packed == null || packed.isBlank()) {
            return;
        }

        Map<String, Long> credited =
                creditedPlaytimeByPort.computeIfAbsent(port, key -> new HashMap<>());

        for (String entry : packed.split(",")) {
            String[] parts = entry.split(":", 2);

            if (parts.length != 2) {
                continue;
            }

            String uuid = parts[0].trim();
            long reported = parseLong(parts[1]);

            if (uuid.isEmpty() || reported <= 0L) {
                continue;
            }

            long delta = reported - credited.getOrDefault(uuid, 0L);

            if (delta <= 0L) {
                continue;
            }

            credited.put(uuid, reported);
            playerDataManager.addExternalPlaytime(
                    uuid,
                    playtimeName(port, uuid, packedPlayers),
                    delta
            );
        }
    }

    // The name stored with credited playtime: the roster first, then the worker's connected list.
    private String playtimeName(int port, String uuid, String packedPlayers) {
        MatchSlot slot = slotByPort.apply(port);

        if (slot != null) {
            MatchSlot.Participant participant = slot.participant(uuid);

            if (participant != null) {
                return participant.plainName();
            }
        }

        if (packedPlayers == null || packedPlayers.isBlank()) {
            return null;
        }

        for (String entry : packedPlayers.split(",")) {
            String[] parts = entry.split("\\|", 2);

            if (parts.length == 2 && parts[1].trim().equals(uuid)) {
                return parts[0];
            }
        }

        return null;
    }

    // Relays one worker's new chat lines into its port channel; the tail serialises callers.
    public void drainWorkerChat(int port) {
        chatTail.drain(
                port,
                new File(WorkerFolder.dir(port), "chat.log"),
                line -> chatLog.portLine(port, line)
        );
    }

    // Last chance to credit a closing worker's playtime from its final status write.
    public void creditFinalPlaytime(MatchSlot slot) {
        Properties status = readStatus(slot.port);

        if (status != null) {
            creditPlaytime(
                    slot.port,
                    status.getProperty("playtime", ""),
                    status.getProperty("players", "")
            );
        }
    }

    private static int countPlayers(String packed) {
        return packed == null || packed.isBlank() ? 0 : packed.split(",").length;
    }

    // Reads result.properties on worker exit: log line, Discord end embed, /history and ELO.
    public void logResult(MatchSlot slot) {
        int port = slot.port;
        File resultFile = new File(WorkerFolder.dir(port), "result.properties");

        if (!resultFile.exists()) {
            return;
        }

        MatchResult result = MatchResult.read(resultFile);

        try {
            String winnerUuid = result.firstWinner();
            String loserUuid = result.firstLoser();
            MatchMode mode = MatchMode.fromId(result.modeId(slot.mode.id()));

            Log.info(
                    "[EvictMapGenerator] @ result on port @: winner=@ loser=@ reason=@.",
                    mode.id(),
                    port,
                    winnerUuid.isEmpty() ? "?" : winnerUuid,
                    loserUuid.isEmpty() ? "?" : loserUuid,
                    result.reason()
            );

            reportMatchEnd(slot, mode, result);

            // Only Ranked feeds ELO; 1v1, Teams and FFA are unranked history; Training and
            // Sandbox leave no history. The hub is the only process that writes the database.
            if (mode.ranked()) {
                String winnerChatName = chatName(slot, winnerUuid);
                String loserChatName = chatName(slot, loserUuid);

                playerDataManager.recordRankedResult(
                        winnerUuid,
                        slot.displayNameFor(winnerUuid),
                        loserUuid,
                        slot.displayNameFor(loserUuid),
                        // The rating movement lands after the write: its own follow-up line.
                        elo -> chatLog.portLine(port, String.format(
                                "📈 %s %d → %d  •  %s %d → %d",
                                winnerChatName,
                                elo.winnerBefore(),
                                elo.winnerAfter(),
                                loserChatName,
                                elo.loserBefore(),
                                elo.loserAfter()
                        ))
                );
            } else if (mode == MatchMode.ONE_VS_ONE) {
                playerDataManager.recordCasualDuelResult(
                        winnerUuid,
                        slot.displayNameFor(winnerUuid),
                        loserUuid,
                        slot.displayNameFor(loserUuid)
                );
            } else if (mode == MatchMode.FFA && !winnerUuid.isEmpty()) {
                List<String> participantUuids = new ArrayList<>();
                List<String> participantNames = new ArrayList<>();

                for (MatchSlot.Participant participant : slot.participants) {
                    participantUuids.add(participant.uuid());
                    participantNames.add(participant.display());
                }

                playerDataManager.recordFfaMatch(
                        winnerUuid,
                        slot.displayNameFor(winnerUuid),
                        participantUuids,
                        participantNames
                );
            } else if (mode == MatchMode.TEAMS && !winnerUuid.isEmpty()) {
                List<String> winnerUuids = result.winnerUuids();
                List<String> loserUuids = result.loserUuids();

                playerDataManager.recordTeamsMatch(
                        winnerUuids,
                        teamLabelFor(slot, winnerUuids),
                        loserUuids,
                        losingTeamsLabel(slot, winnerUuids)
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

    // A participant's name as the chat mirror shows it; the uuid when unknown (staff-only channel).
    private static String chatName(MatchSlot slot, String uuid) {
        MatchSlot.Participant participant = slot.participant(uuid);

        if (participant != null) {
            return DiscordFormat.playerName(participant.plainName());
        }

        return uuid == null || uuid.isEmpty() ? "?" : uuid;
    }

    private static String chatNames(MatchSlot slot, List<String> uuids) {
        StringBuilder names = new StringBuilder();

        for (String uuid : uuids) {
            if (!names.isEmpty()) {
                names.append(", ");
            }

            names.append(chatName(slot, uuid));
        }

        return names.toString();
    }

    // The end embed: winners, losers, duration, how it ended. Solo sessions name nobody.
    private void reportMatchEnd(
            MatchSlot slot,
            MatchMode mode,
            MatchResult result
    ) {
        slot.endReported = true;

        boolean solo = mode.solo();
        boolean decided = !solo && !result.winnerUuids().isEmpty();

        String howItEnded = switch (result.reason()) {
            case "victory" -> "Victory.";
            case "surrender" -> "Ended with /die.";
            case "sandbox-ended" -> "Closed by the sandbox owner.";
            default -> DiscordFormat.playerText(result.reason());
        };

        chatLog.matchEnded(
                slot.port,
                mode.label(),
                solo ? null : chatNames(slot, result.winnerUuids()),
                solo ? null : chatNames(slot, result.loserUuids()),
                matchDurationSeconds(slot),
                howItEnded,
                decided
        );
    }

    // Every start embed gets its end embed: a worker that left no result still closes the frame.
    public void reportAbandoned(MatchSlot slot) {
        if (slot.endReported) {
            return;
        }

        slot.endReported = true;
        chatLog.matchEnded(
                slot.port,
                slot.mode.label(),
                null,
                null,
                matchDurationSeconds(slot),
                "No result — the match was abandoned or the worker stopped.",
                false
        );
    }

    // The worker's own game clock from its final status write, else worker uptime.
    private static long matchDurationSeconds(MatchSlot slot) {
        Properties status = readStatus(slot.port);
        long elapsed = status == null
                ? 0L
                : parseLong(status.getProperty("elapsedSeconds"));

        if (elapsed > 0L) {
            return elapsed;
        }

        return (System.currentTimeMillis() - slot.spawnedAtMillis) / 1000L;
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

    // One Teams roster's label, shortened past MAX_LABEL_NAMES so /history never blows up.
    private static String teamLabelFor(MatchSlot slot, List<String> uuids) {
        List<String> names = new ArrayList<>();

        for (String uuid : uuids) {
            names.add(slot.displayNameFor(uuid));
        }

        return names.isEmpty()
                ? "?"
                : PlayerNameFormatter.joinShortened(
                        names, "[white], []", MatchSlot.MAX_LABEL_NAMES
                );
    }

    // Every losing roster kept as its own team, joined with "vs".
    private static String losingTeamsLabel(MatchSlot slot, List<String> winnerUuids) {
        Map<Integer, List<String>> loserTeams = new LinkedHashMap<>();

        for (MatchSlot.Participant participant : slot.participants) {
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

            label.append(teamLabelFor(slot, team));
        }

        return label.isEmpty() ? "?" : label.toString();
    }

    public static String formatPlayers(String packed) {
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

    public static long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }

        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }

    public static String formatHms(long totalSeconds) {
        long seconds = Math.max(0L, totalSeconds);
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long secs = seconds % 60L;

        return String.format("%02d:%02d:%02d", hours, minutes, secs);
    }
}
