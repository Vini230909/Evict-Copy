package vini.evictmap;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import arc.Core;
import arc.util.Align;
import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mindustry.core.GameState;
import mindustry.entities.units.BuildPlan;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.Unit;

/**
 * Worker-side match referee (1v1, Teams, FFA, Training and Sandbox). Only
 * active when launched as a duel worker (-Devict.duelWorker=true); on the hub
 * this class does nothing.
 * - reads the hub handshake (mode + roster team UUIDs + hub address),
 * - lets the world run for a brief settle window when a duelist joins so their
 * unit spawns at their core and their client camera snaps onto it, then
 * freezes the match (an instant freeze leaves the camera stuck at the map
 * origin until the first unpause, because the spawn resolves on a world tick).
 * - once both are present, runs a 5-second countdown (HUD text), then unfreezes,
 * - if a player disconnects mid-match, pauses and shows a "Xs to rejoin"
 * countdown; resumes when they return, or after the window if they do not,
 * - on an Evict victory writes a result file, returns both players to the hub,
 * - shuts down once it has sat empty for a grace period,
 * - writes status.properties periodically so the hub's evictduelstatus can show
 * the live state, game time and connected players.
 * Countdowns, status writes and the empty-shutdown run on a real-time executor,
 * because the game is paused during them and logic-timed tasks would stall.
 */
public final class DuelWorker {

    private static final File HANDSHAKE_FILE = new File("duel.properties");
    private static final File RESULT_FILE = new File("result.properties");
    private static final File STATUS_FILE = new File("status.properties");

    private static final int COUNTDOWN_SECONDS = 5;
    private static final int REJOIN_SECONDS = 120;
    private static final int STARTUP_GRACE_SECONDS = 90;
    private static final int EMPTY_GRACE_SECONDS = 60;
    // Once the match is decided there is nothing to wait for, so shut down soon
    // after the players have been sent back. The hub records the result on worker
    // exit, so this is what gets a finished match into /history within seconds.
    private static final int RESOLVED_GRACE_SECONDS = 5;
    private static final int STATUS_INTERVAL_SECONDS = 2;
    private static final float RETURN_DELAY_TICKS = 5f * 60f;
    /**
     * How long the world keeps ticking after a duelist joins before the match is
     * (re-)frozen. The client camera follows its unit even while paused, but on a
     * join that lands during the freeze it never receives the target; a couple of
     * live ticks let the server hand it over so the camera hops onto the player.
     * Kept tiny so the window of free movement is imperceptible (~0.08s at 60tps).
     */
    private static final float CAMERA_SETTLE_TICKS = 18f;

    private final boolean active;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "evict-duel-worker");
                thread.setDaemon(true);
                return thread;
            });

    private String hubIp = "";
    private int hubPort = 6567;

    /**
     * The mode and rosters this worker referees, from the hub handshake. Each
     * inner list is one match team; FFA rosters are N teams of one player,
     * Training/Sandbox a single team.
     */
    private MatchMode mode = MatchMode.ONE_VS_ONE;
    private final List<List<String>> rosterTeams = new ArrayList<>();
    private final Set<String> participantUuids = new LinkedHashSet<>();

    /**
     * Eliminated FFA/Teams players. They stay in the rosters (so the final
     * result still records them as losers) but stop being participants: the
     * start gate and disconnect pause ignore them, /v lets them leave, and
     * the hub reads this list from status.properties to stop bouncing them
     * back.
     */
    private final Set<String> outUuids = new LinkedHashSet<>();

    /**
     * Whether a leaving participant is still in the running (their team still
     * holds hexes). Eliminated players leaving must not pause the match.
     */
    private Predicate<Player> stillCompeting;

    void setStillCompeting(Predicate<Player> predicate) {
        this.stillCompeting = predicate;
    }

    private boolean handshakeLoaded = false;
    private boolean startFreezeApplied = false;
    private boolean countdownStarted = false;
    private boolean matchStarted = false;
    private boolean pausedForDisconnect = false;
    private boolean resolved = false;

    private long matchStartMillis = 0L;
    private int disconnectSerial = 0;
    private int matchSerial = 0;
    private int settleSerial = 0;
    private boolean settlePending = false;
    private String disconnectedName = "A player";

    /**
     * Disconnect-pause time each participant has already consumed this match,
     * in milliseconds. A player only freezes the match for whatever is left of
     * their personal {@link #REJOIN_SECONDS} budget - leaving again does not
     * refill it, so leave/rejoin cycling cannot pause the match forever.
     * Cleared on /restart so a fresh match grants fresh budgets.
     */
    private final Map<String, Long> usedPauseMillisByUuid = new HashMap<>();

    /**
     * Whose budget the currently running disconnect pause is charged to, and
     * since when. Null while no disconnect pause runs.
     */
    private String pauseChargedUuid = null;
    private long pauseChargeStartMillis = 0L;

    /**
     * The build plans each player already had queued when the disconnect
     * pause began. While the guard is active, every update tick removes any
     * plan not in here from both the server-side queue and the placing
     * client's own queue, so nobody can lay out blueprints during the frozen
     * time. Plans queued before the pause survive untouched.
     */
    private final Map<String, Set<String>> pausePlanKeysByUuid = new HashMap<>();
    private boolean planGuardActive = false;

    /**
     * Shared id so each duel HUD update replaces the previous popup instead of stacking.
     */
    private static final String HUD_ID = "duel-hud";
    /**
     * How far above screen center the duel HUD sits, in UI units. This is bottom
     * padding on a center-aligned popup, so a larger value lifts the text higher.
     * Kept just above center so it reads above Mindustry's own status text without
     * drifting up to the out-of-focus top of the screen.
     */
    private static final int HUD_RAISE = 220;

    DuelWorker() {
        this.active = "true".equals(System.getProperty("evict.duelWorker"));
    }

    public boolean isActive() {
        return active;
    }

    /**
     * True for the match players named in the handshake (plus spectators later
     * promoted into a sandbox); everyone else spectates.
     */
    public boolean isParticipant(String uuid) {
        return handshakeLoaded
                && uuid != null
                && participantUuids.contains(uuid);
    }

    MatchMode matchMode() {
        return mode;
    }

    boolean isSandboxMode() {
        return active && handshakeLoaded && mode == MatchMode.SANDBOX;
    }

    /**
     * How many personal teams must exist before a victory may be decided.
     * Solo modes return an unreachable minimum: Training and Sandbox never end
     * through an Evict victory, only through /die or everyone leaving.
     */
    int victoryMinimumTeams() {
        if (mode.solo()) {
            return Integer.MAX_VALUE;
        }

        return Math.max(2, rosterTeams.size());
    }

    /**
     * The handshake teammates of a participant (their roster team minus
     * themselves). TeamManager uses this to keep a Teams roster on one
     * Mindustry team.
     */
    List<String> rosterTeammates(String uuid) {
        List<String> teammates = new ArrayList<>();

        if (uuid == null) {
            return teammates;
        }

        for (List<String> roster : rosterTeams) {
            if (roster.contains(uuid)) {
                for (String memberUuid : roster) {
                    if (!memberUuid.equals(uuid)) {
                        teammates.add(memberUuid);
                    }
                }

                return teammates;
            }
        }

        return teammates;
    }

    /**
     * Marks an eliminated FFA/Teams player as out. They keep their roster
     * spot (so the final result still lists them as a loser) but are no
     * longer a participant: the match stops waiting for them, /v returns
     * them to the lobby, and the hub stops bouncing them back into this
     * worker.
     */
    void demoteToSpectator(String uuid) {
        if (!active || uuid == null) {
            return;
        }

        if (participantUuids.remove(uuid)) {
            outUuids.add(uuid);
        }
    }

    /**
     * A commentator /restart begins a fresh match, so previously eliminated
     * FFA players rejoin the roster as full participants. Must run before the
     * map regenerates so the team assignment picks them up again.
     */
    void restoreOutParticipants() {
        if (!active || outUuids.isEmpty()) {
            return;
        }

        participantUuids.addAll(outUuids);
        outUuids.clear();
    }

    /**
     * Promotes a spectator into the sandbox: they become a full participant
     * on the sandbox roster (chat, no /v exit, counted by the start gate).
     */
    void addSandboxParticipant(Player player) {
        if (
                !active
                        || !handshakeLoaded
                        || mode != MatchMode.SANDBOX
                        || player == null
                        || rosterTeams.isEmpty()
        ) {
            return;
        }

        if (participantUuids.add(player.uuid())) {
            rosterTeams.get(0).add(player.uuid());
        }
    }

    /**
     * Sends a spectator back to the hub this worker was launched from.
     */
    public void returnSpectatorToHub(Player player) {
        if (!active || player == null) {
            return;
        }

        if (hubIp == null || hubIp.isBlank()) {
            player.sendMessage(
                    "[scarlet]Cannot find the lobby to return you to.[]"
            );
            return;
        }

        player.sendMessage("[accent]Returning you to the lobby...[]");
        Call.connect(player.con, hubIp, hubPort);
    }

    /**
     * Called once the worker has hosted its round.
     */
    void begin() {
        if (!active) {
            return;
        }

        loadHandshake();
        scheduleShutdownIfEmpty(STARTUP_GRACE_SECONDS);

        scheduler.scheduleAtFixedRate(
                () -> Core.app.post(this::writeStatus),
                STATUS_INTERVAL_SECONDS,
                STATUS_INTERVAL_SECONDS,
                TimeUnit.SECONDS
        );
    }

    void handlePlayerJoin(Player player) {
        if (!active) {
            return;
        }

        if (matchStarted) {
            if (pausedForDisconnect && bothPlayersPresent()) {
                endDisconnectPause();
            }
            return;
        }

        if (countdownStarted) {
            return;
        }

        if (player != null && isParticipant(player.uuid())) {
            // A duelist arrived: let the world settle their camera before the
            // freeze (see settleCamerasThenFreeze).
            settleCamerasThenFreeze();
            return;
        }

        // A spectator joined while the duel is still gathering. Keep the match
        // frozen and waiting, but don't slam the freeze on mid-settle and strand
        // a duelist's camera at the origin.
        if (!settlePending && !startFreezeApplied) {
            pauseGame();
            startFreezeApplied = true;
        }
        showWaitingHud();
    }

    /**
     * Keeps the world ticking for a brief window so a freshly joined duelist
     * spawns at their core and their client camera snaps onto the unit, then
     * re-freezes the match (or starts the countdown once both duelists are
     * present). Freezing instantly would block the spawn tick, leaving the
     * camera parked at the map origin (0,0) until the first unpause. Each call
     * supersedes the previous one via {@link #settleSerial} so rapid joins do
     * not stack conflicting freeze/start tasks.
     */
    private void settleCamerasThenFreeze() {
        // The first duelist arrives unpaused, but the second joins while the
        // first is frozen and waiting; resume so their camera settles too.
        if (Vars.state.isPaused()) {
            resumeGame();
        }
        startFreezeApplied = false;
        settlePending = true;
        showWaitingHud();

        int serial = ++settleSerial;
        Time.run(CAMERA_SETTLE_TICKS, () -> {
            if (serial != settleSerial) {
                return;
            }
            settlePending = false;

            if (matchStarted || countdownStarted) {
                return;
            }

            if (bothPlayersPresent()) {
                startCountdown();
            } else {
                pauseGame();
                startFreezeApplied = true;
            }
        });
    }

    void handlePlayerLeave(Player player) {
        if (!active) {
            return;
        }

        scheduleShutdownIfEmpty(
                resolved ? RESOLVED_GRACE_SECONDS : EMPTY_GRACE_SECONDS
        );

        if (
                !matchStarted
                        || resolved
                        || pausedForDisconnect
                        || player == null
        ) {
            return;
        }

        // Only pause for participants who are still competing: in FFA an
        // already-eliminated player leaving must not freeze the match for
        // everyone else.
        if (
                isParticipant(player.uuid())
                        && (stillCompeting == null || stillCompeting.test(player))
        ) {
            beginDisconnectPause(player);
        }
    }

    /**
     * Wired in place of the hub's normal round-victory handler when running as
     * a worker. Records the result and returns every player to the hub.
     */
    void handleVictory(Team winner) {
        if (!active || resolved) {
            return;
        }

        resolved = true;

        // The world must run for the return countdown to tick.
        if (pausedForDisconnect) {
            pausedForDisconnect = false;
            disconnectSerial++;
            chargeDisconnectPause();
            endPlanGuard();
            resumeGame();
        }

        hideHud();

        Player winnerPlayer = Groups.player.find(
                player -> player != null
                        && player.team() == winner
                        && isParticipant(player.uuid())
        );

        List<String> winnerUuids = winnerPlayer != null
                ? rosterOf(winnerPlayer.uuid())
                : new ArrayList<>();

        // Losers come from the full rosters, not the live participant set, so
        // FFA players who were eliminated (and demoted) earlier still count.
        List<String> loserUuids = new ArrayList<>();

        for (List<String> roster : rosterTeams) {
            for (String uuid : roster) {
                if (!winnerUuids.contains(uuid)) {
                    loserUuids.add(uuid);
                }
            }
        }

        writeResult(winnerUuids, loserUuids, "victory");

        String winnerName = winnerPlayer != null
                ? winningRosterNames(winnerUuids, winnerPlayer)
                : "The winner";

        Call.sendMessage(
                "[accent]" + winnerName
                        + "[accent] won the " + mode.label()
                        + ". Returning to the lobby in 5 seconds...[]"
        );

        Time.run(RETURN_DELAY_TICKS, this::returnPlayersToHub);

        Log.info(
                "[EvictMapGenerator] Match result (@): winner=@ loser=@.",
                mode.id(),
                winnerUuids.isEmpty() ? "unknown" : String.join(",", winnerUuids),
                loserUuids.isEmpty() ? "unknown" : String.join(",", loserUuids)
        );
    }

    /**
     * The whole roster team a participant belongs to (including themselves).
     */
    private List<String> rosterOf(String uuid) {
        for (List<String> roster : rosterTeams) {
            if (roster.contains(uuid)) {
                return new ArrayList<>(roster);
            }
        }

        List<String> single = new ArrayList<>();

        if (uuid != null && !uuid.isEmpty()) {
            single.add(uuid);
        }

        return single;
    }

    /**
     * Display names of the winning roster, preferring live player names and
     * falling back to the one member known to be online.
     */
    private String winningRosterNames(
            List<String> winnerUuids,
            Player fallback
    ) {
        StringBuilder names = new StringBuilder();

        for (String uuid : winnerUuids) {
            Player member = Groups.player.find(
                    player -> player != null && player.uuid().equals(uuid)
            );

            if (member == null) {
                continue;
            }

            if (!names.isEmpty()) {
                names.append("[accent], ");
            }

            names.append(PlayerNameFormatter.displayName(member));
        }

        return names.isEmpty()
                ? PlayerNameFormatter.displayName(fallback)
                : names.toString();
    }

    /**
     * Ends a Training or Sandbox session after its team surrendered with /die.
     * There is no winner and nothing is recorded; everyone returns to the hub.
     */
    public void handleParticipantSurrender(Player player) {
        if (
                !active
                        || resolved
                        || !mode.solo()
                        || player == null
                        || !isParticipant(player.uuid())
        ) {
            return;
        }

        resolved = true;

        if (pausedForDisconnect) {
            pausedForDisconnect = false;
            disconnectSerial++;
            chargeDisconnectPause();
            endPlanGuard();
            resumeGame();
        }

        hideHud();

        List<String> allRosterUuids = new ArrayList<>();

        for (List<String> roster : rosterTeams) {
            allRosterUuids.addAll(roster);
        }

        writeResult(new ArrayList<>(), allRosterUuids, "surrender");

        Call.sendMessage(
                "[accent]The " + mode.label()
                        + " session is over. Returning to the lobby in 5 seconds...[]"
        );

        Time.run(RETURN_DELAY_TICKS, this::returnPlayersToHub);

        Log.info(
                "[EvictMapGenerator] @ session ended by /die.",
                mode.label()
        );
    }

    private void returnPlayersToHub() {
        if (hubIp == null || hubIp.isBlank()) {
            Log.err(
                    "[EvictMapGenerator] Duel worker has no hub address; cannot return players."
            );
            return;
        }

        Groups.player.each(player -> {
            if (player != null) {
                Call.connect(player.con, hubIp, hubPort);
            }
        });

        Log.info(
                "[EvictMapGenerator] Duel worker returned players to the lobby at @:@.",
                hubIp,
                hubPort
        );
    }

    private void startCountdown() {
        countdownStarted = true;

        // The camera-settle window leaves the world running, so freeze it now to
        // hold both duelists still through the "starts in N" countdown. Harmless
        // if it is already paused (e.g. a commentator /restart).
        pauseGame();
        startFreezeApplied = true;

        int serial = ++matchSerial;

        for (int second = COUNTDOWN_SECONDS; second >= 1; second--) {
            int remaining = second;

            scheduler.schedule(
                    () -> Core.app.post(() -> showCountdown(serial, remaining)),
                    COUNTDOWN_SECONDS - second,
                    TimeUnit.SECONDS
            );
        }

        scheduler.schedule(
                () -> Core.app.post(() -> startMatch(serial)),
                COUNTDOWN_SECONDS,
                TimeUnit.SECONDS
        );
    }

    private void showCountdown(int serial, int remaining) {
        if (serial != matchSerial) {
            return;
        }

        showHud(
                "[accent]" + mode.label()
                        + " starts in [scarlet]" + remaining + "[]"
        );
    }

    /**
     * Shows the duel HUD text slightly above screen center. Replaces any existing
     * duel popup (same {@link #HUD_ID}) so repeated calls during the countdown do
     * not stack. Height is controlled by {@link #HUD_RAISE}.
     */
    private void showHud(String text) {
        Call.infoPopup(text, HUD_ID, 3600f, Align.center, 0, 0, HUD_RAISE, 0);
    }

    /**
     * Removes the duel HUD popup.
     */
    private void hideHud() {
        Call.infoPopup((String) null, HUD_ID, 0f, Align.center, 0, 0, HUD_RAISE, 0);
    }

    private void startMatch(int serial) {
        if (serial != matchSerial) {
            return;
        }

        matchStarted = true;
        startFreezeApplied = false;
        matchStartMillis = System.currentTimeMillis();
        resumeGame();
        showHud("[green]GO![]");

        scheduler.schedule(
                () -> Core.app.post(this::hideHud),
                2,
                TimeUnit.SECONDS
        );
    }

    /**
     * Resets the match back to the pre-countdown state after the world has been
     * regenerated, so a commentator's /restart re-freezes both duelists and runs
     * a fresh countdown. Bumping the serial invalidates any countdown still in
     * flight from the previous match.
     */
    void restartMatch() {
        if (!active) {
            return;
        }

        matchSerial++;
        disconnectSerial++;
        countdownStarted = false;
        matchStarted = false;
        pausedForDisconnect = false;
        resolved = false;
        matchStartMillis = 0L;

        // A restart is a fresh match: everyone gets their full rejoin budget
        // back, and a pause that was running is not charged to anyone. The
        // world regenerates, so the plan guard is dropped without a scrub.
        usedPauseMillisByUuid.clear();
        pauseChargedUuid = null;
        planGuardActive = false;
        pausePlanKeysByUuid.clear();

        hideHud();
        pauseGame();
        startFreezeApplied = true;
        Call.sendMessage("[accent]The match was restarted by a commentator.[]");

        if (bothPlayersPresent()) {
            startCountdown();
        } else {
            showWaitingHud();
        }
    }

    private void beginDisconnectPause(Player player) {
        int windowSeconds = remainingPauseSeconds(player.uuid());

        if (windowSeconds <= 0) {
            Call.sendMessage(
                    "[scarlet]" + PlayerNameFormatter.displayName(player)
                            + "[scarlet] left but has no rejoin time left this match. The match continues.[]"
            );
            return;
        }

        pausedForDisconnect = true;
        disconnectedName = PlayerNameFormatter.displayName(player);
        pauseChargedUuid = player.uuid();
        pauseChargeStartMillis = System.currentTimeMillis();
        pauseGame();
        beginPlanGuard();

        int serial = ++disconnectSerial;

        for (int second = windowSeconds; second >= 1; second--) {
            int remaining = second;

            scheduler.schedule(
                    () -> Core.app.post(() -> showRejoinCountdown(serial, remaining)),
                    windowSeconds - second,
                    TimeUnit.SECONDS
            );
        }

        scheduler.schedule(
                () -> Core.app.post(() -> expireDisconnectPause(serial)),
                windowSeconds,
                TimeUnit.SECONDS
        );
    }

    /**
     * Seconds left of this participant's per-match disconnect-pause budget.
     */
    private int remainingPauseSeconds(String uuid) {
        long usedMillis = usedPauseMillisByUuid.getOrDefault(uuid, 0L);

        return (int) ((REJOIN_SECONDS * 1000L - usedMillis) / 1000L);
    }

    /**
     * Charge the elapsed pause time to the leaver's per-match budget. Called
     * whenever the disconnect pause ends, however it ends.
     */
    private void chargeDisconnectPause() {
        if (pauseChargedUuid == null) {
            return;
        }

        long elapsedMillis = Math.max(
                0L,
                System.currentTimeMillis() - pauseChargeStartMillis
        );

        usedPauseMillisByUuid.merge(pauseChargedUuid, elapsedMillis, Long::sum);
        pauseChargedUuid = null;
    }

    /**
     * Snapshot everyone's queued build plans and start scrubbing anything
     * added while the game is frozen.
     */
    private void beginPlanGuard() {
        pausePlanKeysByUuid.clear();

        Groups.player.each(player -> {
            Unit unit = player == null || player.dead() ? null : player.unit();

            if (unit == null) {
                return;
            }

            Set<String> keys = new HashSet<>();

            for (BuildPlan plan : unit.plans()) {
                keys.add(planKey(plan));
            }

            pausePlanKeysByUuid.put(player.uuid(), keys);
        });

        planGuardActive = true;
    }

    private void endPlanGuard() {
        if (!planGuardActive) {
            return;
        }

        scrubPausePlans();
        planGuardActive = false;
        pausePlanKeysByUuid.clear();
    }

    /**
     * Ticked from the plugin's update loop, which Mindustry fires even while
     * the game is paused - that is what lets the guard see plans the moment
     * the still-syncing clients queue them.
     */
    void update() {
        if (!active || !planGuardActive) {
            return;
        }

        scrubPausePlans();
    }

    private void scrubPausePlans() {
        Groups.player.each(player -> {
            Unit unit = player == null || player.dead() ? null : player.unit();

            if (unit == null || unit.plans().isEmpty()) {
                return;
            }

            Set<String> allowed = pausePlanKeysByUuid.get(player.uuid());
            List<BuildPlan> added = new ArrayList<>();

            for (BuildPlan plan : unit.plans()) {
                if (allowed == null || !allowed.contains(planKey(plan))) {
                    added.add(plan);
                }
            }

            for (BuildPlan plan : added) {
                // Server queue first, then the owning client's local queue -
                // otherwise their next snapshot just re-sends the plan.
                unit.removeBuild(plan.x, plan.y, plan.breaking);
                Call.removeQueueBlock(
                        player.con,
                        plan.x,
                        plan.y,
                        plan.breaking
                );
            }
        });
    }

    /**
     * Identity of a plan across client re-syncs: the server rebuilds the
     * queue from every snapshot, so object identity does not survive, but
     * position, action and block do.
     */
    private String planKey(BuildPlan plan) {
        return plan.x + "," + plan.y + "," + plan.breaking + ","
                + (plan.block == null ? -1 : plan.block.id);
    }

    private void showRejoinCountdown(int serial, int remaining) {
        if (serial != disconnectSerial || !pausedForDisconnect) {
            return;
        }

        showHud(
                "[scarlet]" + disconnectedName
                        + "[scarlet] left  -  [accent]" + remaining
                        + "s[scarlet] to rejoin, or the match continues[]"
        );
    }

    /**
     * Central HUD shown while the match is frozen waiting for players: before
     * the countdown when not both are present, and after a mid-match leave. Sits
     * in the same HUD slot as the start countdown.
     */
    private void showWaitingHud() {
        StringBuilder names = new StringBuilder();

        Groups.player.each(connected -> {
            if (connected != null) {
                if (!names.isEmpty()) {
                    names.append("[white], ");
                }
                names.append(PlayerNameFormatter.displayName(connected));
            }
        });

        showHud(
                !names.isEmpty()
                        ? "[accent]Waiting for players\n[white]" + names + "[]"
                        : "[accent]Waiting for players...[]"
        );
    }

    private void expireDisconnectPause(int serial) {
        if (serial != disconnectSerial || !pausedForDisconnect) {
            return;
        }

        pausedForDisconnect = false;
        chargeDisconnectPause();
        endPlanGuard();
        resumeGame();
        hideHud();
        Call.sendMessage(
                "[scarlet]" + disconnectedName
                        + "[scarlet] did not return. The match continues.[]"
        );
    }

    private void endDisconnectPause() {
        pausedForDisconnect = false;
        disconnectSerial++;
        chargeDisconnectPause();
        endPlanGuard();
        resumeGame();
        hideHud();
        Call.sendMessage("[accent]Everyone is back. Resuming the match![]");
    }

    private void scheduleShutdownIfEmpty(int seconds) {
        scheduler.schedule(
                () -> Core.app.post(() -> {
                    if (Groups.player.isEmpty()) {
                        Log.info(
                                "[EvictMapGenerator] Duel worker is empty; shutting down to free the slot."
                        );
                        System.exit(0);
                    }
                }),
                seconds,
                TimeUnit.SECONDS
        );
    }

    private void pauseGame() {
        Vars.state.set(GameState.State.paused);
        Log.info("[EvictMapGenerator] Duel worker paused the match.");
    }

    private void resumeGame() {
        Vars.state.set(GameState.State.playing);
        Log.info("[EvictMapGenerator] Duel worker resumed the match.");
    }

    private void writeStatus() {
        Properties properties = new Properties();
        properties.setProperty("state", currentStateName());
        properties.setProperty(
                "elapsedSeconds",
                Long.toString(matchElapsedSeconds())
        );

        StringBuilder players = new StringBuilder();

        Groups.player.each(player -> {
            if (player != null) {
                if (!players.isEmpty()) {
                    players.append(",");
                }
                players.append(player.plainName()).append("|").append(player.uuid());
            }
        });

        properties.setProperty("players", players.toString());
        properties.setProperty("out", String.join(",", outUuids));

        try (FileOutputStream output = new FileOutputStream(STATUS_FILE)) {
            properties.store(output, "Evict duel status");
        } catch (Exception ignored) {
            // Status is best-effort; a missed write just shows stale data.
        }
    }

    private String currentStateName() {
        if (resolved) {
            return "finished";
        }

        if (pausedForDisconnect) {
            return "paused";
        }

        if (!matchStarted) {
            return countdownStarted ? "countdown" : "waiting";
        }

        return "running";
    }

    private long matchElapsedSeconds() {
        if (!matchStarted || matchStartMillis <= 0L) {
            return 0L;
        }

        return (System.currentTimeMillis() - matchStartMillis) / 1000L;
    }

    private boolean bothPlayersPresent() {
        if (!handshakeLoaded || participantUuids.isEmpty()) {
            return false;
        }

        for (String uuid : participantUuids) {
            if (!isOnline(uuid)) {
                return false;
            }
        }

        return true;
    }

    private boolean isOnline(String uuid) {
        if (uuid == null || uuid.isEmpty()) {
            return false;
        }

        return Groups.player.find(
                player -> player != null && player.uuid().equals(uuid)
        ) != null;
    }

    private void loadHandshake() {
        if (!HANDSHAKE_FILE.exists()) {
            Log.warn(
                    "[EvictMapGenerator] Duel worker found no handshake file (@); start gate and return disabled.",
                    HANDSHAKE_FILE.getPath()
            );
            return;
        }

        Properties properties = new Properties();

        try (FileInputStream input = new FileInputStream(HANDSHAKE_FILE)) {
            properties.load(input);

            hubIp = properties.getProperty("hub.ip", "").trim();
            hubPort = parsePort(properties.getProperty("hub.port"), 6567);
            mode = MatchMode.fromId(
                    properties.getProperty("mode", "1v1").trim()
            );

            rosterTeams.clear();
            participantUuids.clear();

            int teamCount = parsePort(properties.getProperty("team.count"), 0);

            for (int index = 1; index <= teamCount; index++) {
                List<String> roster = new ArrayList<>();

                for (
                        String uuid :
                        properties.getProperty("team." + index, "").split(",")
                ) {
                    String trimmed = uuid.trim();

                    if (!trimmed.isEmpty() && participantUuids.add(trimmed)) {
                        roster.add(trimmed);
                    }
                }

                if (!roster.isEmpty()) {
                    rosterTeams.add(roster);
                }
            }

            // Handshake written by an older hub: two 1v1 duelists.
            if (rosterTeams.isEmpty()) {
                for (String key : new String[]{"player1.uuid", "player2.uuid"}) {
                    String uuid = properties.getProperty(key, "").trim();

                    if (!uuid.isEmpty() && participantUuids.add(uuid)) {
                        List<String> roster = new ArrayList<>();
                        roster.add(uuid);
                        rosterTeams.add(roster);
                    }
                }
            }

            handshakeLoaded = !participantUuids.isEmpty();

            Log.info(
                    "[EvictMapGenerator] Duel worker loaded handshake: hub=@:@ mode=@ teams=@ players=@.",
                    hubIp,
                    hubPort,
                    mode.id(),
                    rosterTeams.size(),
                    String.join(",", participantUuids)
            );
        } catch (Exception exception) {
            Log.err(
                    "[EvictMapGenerator] Duel worker could not read the handshake file.",
                    exception
            );
        }
    }

    private void writeResult(
            List<String> winnerUuids,
            List<String> loserUuids,
            String reason
    ) {
        Properties properties = new Properties();
        properties.setProperty("mode", mode.id());
        properties.setProperty(
                "winner.uuid",
                winnerUuids.isEmpty() ? "" : winnerUuids.get(0)
        );
        properties.setProperty(
                "loser.uuid",
                loserUuids.isEmpty() ? "" : loserUuids.get(0)
        );
        properties.setProperty("winner.uuids", String.join(",", winnerUuids));
        properties.setProperty("loser.uuids", String.join(",", loserUuids));
        properties.setProperty("reason", reason);

        try (FileOutputStream output = new FileOutputStream(RESULT_FILE)) {
            properties.store(output, "Evict duel result");
        } catch (Exception exception) {
            Log.err(
                    "[EvictMapGenerator] Duel worker could not write the result file.",
                    exception
            );
        }
    }

    private static int parsePort(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }

        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }
}
