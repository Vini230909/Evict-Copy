// The match referee on a worker: the handshake roster, joins and leaves, victory, surrender, result.
package Extinction;

import Extinction.moderation.ban.BanRequest;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;

import arc.Core;
import arc.util.Align;
import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mindustry.core.GameState;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;

public final class Referee {

    private static final File HANDSHAKE_FILE = new File(MatchHandshake.FILE_NAME);
    private static final File RESULT_FILE = new File("result.properties");
    private static final int STATUS_INTERVAL_SECONDS = 1;
    private static final float RETURN_DELAY_TICKS = 5f * 60f;
    // One popup id, so each HUD update replaces the previous one; raised just above centre.
    private static final String HUD_ID = "duel-hud";
    private static final int HUD_RAISE = 220;

    // One ongoing match on a sibling worker that a spectator may hop to.
    public record SiblingMatch(int port, String label) {
    }

    // Only active when launched as a duel worker (-Devict.duelWorker=true); inert on the hub.
    private final boolean active;

    // Countdowns, status writes and the empty-shutdown run in real time: the game is paused.
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "evict-duel-worker");
                thread.setDaemon(true);
                return thread;
            });

    public final StartGate gate = new StartGate(this, scheduler);
    public final DisconnectPause pause = new DisconnectPause(this, scheduler);
    public final Sandbox sandbox = new Sandbox(this);
    public final WorkerExit exit = new WorkerExit(this, scheduler);
    public final WorkerStatus status = new WorkerStatus(this);
    public final PureMatch pure = new PureMatch(this);

    // The mode: its rules (gated, solo, ...) are asked of it, never compared by constant.
    private MatchMode mode = MatchMode.ONE_VS_ONE;

    // Each inner list is one match team; FFA is N teams of one, Training/Sandbox a single team.
    private final List<List<String>> rosterTeams = new ArrayList<>();
    private final Set<String> participantUuids = new LinkedHashSet<>();

    // Eliminated FFA/Teams players: still rostered (recorded as losers) but no longer waited for.
    private final Set<String> outUuids = new LinkedHashSet<>();

    // Whether a leaving participant is still in the running (their team still holds hexes).
    private Predicate<Player> stillCompeting;

    private boolean handshakeLoaded = false;
    private boolean begun = false;
    private boolean resolved = false;

    public Referee() {
        this.active = "true".equals(System.getProperty("evict.duelWorker"));
    }

    public boolean isActive() {
        return active;
    }

    public boolean handshakeLoaded() {
        return handshakeLoaded;
    }

    public boolean resolved() {
        return resolved;
    }

    // The match players named in the handshake (plus spectators promoted into a sandbox).
    public boolean isParticipant(String uuid) {
        return handshakeLoaded
                && uuid != null
                && participantUuids.contains(uuid);
    }

    public MatchMode matchMode() {
        return mode;
    }

    public List<List<String>> rosterTeams() {
        return rosterTeams;
    }

    public Set<String> participantUuids() {
        return participantUuids;
    }

    public Set<String> outUuids() {
        return outUuids;
    }

    public void setStillCompeting(Predicate<Player> predicate) {
        this.stillCompeting = predicate;
    }

    public void setPlaytimeSource(Supplier<Map<String, Long>> source) {
        status.setPlaytimeSource(source);
    }

    public void requestBan(BanRequest request) {
        status.requestBan(request);
    }

    // Solo modes never end through an Evict victory, only through /die or everyone leaving.
    public int victoryMinimumTeams() {
        if (mode.solo()) {
            return Integer.MAX_VALUE;
        }

        return Math.max(2, rosterTeams.size());
    }

    // A participant's roster team minus themselves; keeps a Teams roster on one Mindustry team.
    public List<String> rosterTeammates(String uuid) {
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

    // An eliminated FFA/Teams player keeps their roster spot but stops being a participant.
    public void demoteToSpectator(String uuid) {
        if (!active || uuid == null) {
            return;
        }

        if (participantUuids.remove(uuid)) {
            outUuids.add(uuid);
        }
    }

    // Called once the worker has hosted its round. Vanilla reloads a Pure map after its game over;
    // the second PlayEvent must not start the referee over.
    public void begin() {
        if (!active || begun) {
            return;
        }

        begun = true;
        loadHandshake();
        exit.scheduleShutdownIfEmpty(WorkerExit.STARTUP_GRACE_SECONDS);

        if (mode.pure()) {
            pure.begin();
        }

        if (!mode.gated()) {
            gate.startUngated();
        }

        scheduler.scheduleAtFixedRate(
                () -> Core.app.post(status::write),
                STATUS_INTERVAL_SECONDS,
                STATUS_INTERVAL_SECONDS,
                TimeUnit.SECONDS
        );
    }

    public void handlePlayerJoin(Player player) {
        if (!active) {
            return;
        }

        if (
                player != null
                        && handshakeLoaded
                        && !isParticipant(player.uuid())
        ) {
            welcomeSpectator(player);
        }

        // Vanilla assigns a joiner to any team; a Pure participant belongs to their roster's team.
        if (mode.pure() && player != null && isParticipant(player.uuid())) {
            pure.place(player);
        }

        if (gate.started()) {
            if (player != null) {
                pause.unwaive(player.uuid());

                // Their absence stops being charged the moment they are back.
                if (pause.active()) {
                    pause.chargeAbsence(player.uuid());
                }
            }

            if (pause.active()) {
                if (everyonePresent()) {
                    pause.end();
                } else {
                    // Someone is still missing: refresh the HUD so the rejoiner's line goes.
                    pause.showRejoinHud();
                }
            }
            return;
        }

        if (gate.countingDown()) {
            return;
        }

        if (player != null && isParticipant(player.uuid())) {
            gate.participantJoined();
            return;
        }

        gate.spectatorJoined();
    }

    // The hub's "connecting you..." line is lost with the server switch, so repeat the essentials.
    private void welcomeSpectator(Player player) {
        String message = "[accent]You are now spectating this "
                + mode.label()
                + " match. Use /s to switch matches or return to the lobby.[]";

        if (mode.allowsSpectatorInvites()) {
            message += "\n[lightgray]Use /invite to ask to join the sandbox.[]";
        }

        player.sendMessage(message);
    }

    public void handlePlayerLeave(Player player) {
        if (!active) {
            return;
        }

        exit.scheduleShutdownIfEmpty(
                resolved ? WorkerExit.RESOLVED_GRACE_SECONDS : WorkerExit.EMPTY_GRACE_SECONDS
        );

        if (
                !gate.started()
                        || resolved
                        || !mode.gated()
                        || player == null
        ) {
            return;
        }

        // A timed-out ghost connection fires its leave after the same player already rejoined.
        if (isOnlineElsewhere(player)) {
            return;
        }

        // Only a participant still competing pauses the match; an eliminated FFA player does not.
        if (
                !isParticipant(player.uuid())
                        || (stillCompeting != null && !stillCompeting.test(player))
        ) {
            return;
        }

        if (pause.active()) {
            pause.addLeaver(player);
        } else {
            pause.begin(player);
        }
    }

    // Wired in place of the hub's round-victory handler: records the result, returns everyone.
    // On a Pure worker vanilla's PvP game over calls this with its winner.
    public void handleVictory(Team winner) {
        if (!active || resolved) {
            return;
        }

        // A Pure Training that ran out of enemy cores has no winner to name: it just ends.
        if (mode.pure() && mode.solo()) {
            endSoloSession("gameover");
            return;
        }

        resolved = true;

        gate.release();

        Player winnerPlayer = Groups.player.find(
                player -> player != null
                        && player.team() == winner
                        && isParticipant(player.uuid())
        );

        List<String> winnerUuids = winnerPlayer != null
                ? rosterOf(winnerPlayer.uuid())
                : new ArrayList<>();

        // Losers come from the full rosters, so eliminated (demoted) FFA players still count.
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

        Time.run(RETURN_DELAY_TICKS, exit::returnPlayersToHub);

        Log.info(
                "[EvictMapGenerator] Match result (@): winner=@ loser=@.",
                mode.id(),
                winnerUuids.isEmpty() ? "unknown" : String.join(",", winnerUuids),
                loserUuids.isEmpty() ? "unknown" : String.join(",", loserUuids)
        );
    }

    // The whole roster team a participant belongs to (including themselves).
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

    // Live names of the winning roster, else the one member known to be online.
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

            names.append(PlayerNames.displayName(member));
        }

        return names.isEmpty()
                ? PlayerNames.displayName(fallback)
                : names.toString();
    }

    // Every successful surrender releases the freeze; Training also returns everyone to the hub.
    public void handleParticipantSurrender(Player player) {
        if (active && !resolved) {
            gate.release();
        }
        if (
                !active
                        || resolved
                        || !mode.solo()
                        || player == null
                        || !isParticipant(player.uuid())
        ) {
            return;
        }

        endSoloSession("surrender");
    }

    // A Training surrender or a Sandbox owner /die: no winner, everyone back after the delay.
    void endSoloSession(String reason) {
        resolved = true;

        gate.release();

        List<String> allRosterUuids = new ArrayList<>();

        for (List<String> roster : rosterTeams) {
            allRosterUuids.addAll(roster);
        }

        writeResult(new ArrayList<>(), allRosterUuids, reason);

        Call.sendMessage(
                "[accent]The " + mode.label()
                        + " session is over. Returning to the lobby in 5 seconds...[]"
        );

        Time.run(RETURN_DELAY_TICKS, exit::returnPlayersToHub);

        Log.info(
                "[EvictMapGenerator] @ session ended (@).",
                mode.label(),
                reason
        );
    }

    public void showHud(String text) {
        Call.infoPopup(text, HUD_ID, 3600f, Align.center, 0, 0, HUD_RAISE, 0);
    }

    public void hideHud() {
        Call.infoPopup((String) null, HUD_ID, 0f, Align.center, 0, 0, HUD_RAISE, 0);
    }

    // Plugin update loop, which fires even while paused: the resume must not hinge on join order.
    public void update() {
        if (!active) {
            return;
        }

        if (pause.active() && everyonePresent()) {
            pause.end();
        }

        pause.update();

        if (mode.pure()) {
            pure.update();
        }
    }

    public void pauseGame() {
        Vars.state.set(GameState.State.paused);
        Log.info("[EvictMapGenerator] Duel worker paused the match.");
    }

    public void resumeGame() {
        Vars.state.set(GameState.State.playing);
        Log.info("[EvictMapGenerator] Duel worker resumed the match.");
    }

    public String stateName() {
        if (resolved) {
            return "finished";
        }

        if (pause.active()) {
            return "paused";
        }

        if (!gate.started()) {
            return gate.countingDown() ? "countdown" : "waiting";
        }

        return "running";
    }

    public long matchElapsedSeconds() {
        return gate.elapsedSeconds();
    }

    // Everyone required is connected: every participant not waived.
    public boolean everyonePresent() {
        if (!handshakeLoaded || participantUuids.isEmpty()) {
            return false;
        }

        for (String uuid : participantUuids) {
            if (!pause.isWaived(uuid) && !isOnline(uuid)) {
                return false;
            }
        }

        return true;
    }

    private boolean isOnlineElsewhere(Player leaving) {
        return Groups.player.find(
                player -> player != null
                        && player != leaving
                        && player.uuid().equals(leaving.uuid())
        ) != null;
    }

    public boolean isOnline(String uuid) {
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

        MatchHandshake handshake = MatchHandshake.read(HANDSHAKE_FILE);

        exit.setHub(handshake.hubIp, handshake.hubPort);
        mode = handshake.mode;

        rosterTeams.clear();
        participantUuids.clear();

        for (List<String> roster : handshake.rosterTeams) {
            rosterTeams.add(new ArrayList<>(roster));
        }

        participantUuids.addAll(handshake.participantUuids);

        // Seed the names the waiting HUD needs before anyone has arrived.
        pause.rememberNames(handshake.names);

        handshakeLoaded = !participantUuids.isEmpty();

        // The Sandbox owner is its sole launch participant; guests are never the owner.
        if (mode.allowsSpectatorInvites() && handshakeLoaded) {
            sandbox.claimOwner(participantUuids.iterator().next());
        }

        Log.info(
                "[EvictMapGenerator] Duel worker loaded handshake: hub=@:@ mode=@ teams=@ players=@.",
                handshake.hubIp,
                handshake.hubPort,
                mode.id(),
                rosterTeams.size(),
                String.join(",", participantUuids)
        );
    }

    private void writeResult(
            List<String> winnerUuids,
            List<String> loserUuids,
            String reason
    ) {
        new MatchResult(mode.id(), winnerUuids, loserUuids, reason).write(RESULT_FILE);
    }
}
