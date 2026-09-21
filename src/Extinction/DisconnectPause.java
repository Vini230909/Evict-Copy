// The disconnect pause of a match: per-player rejoin budgets, the rejoin HUD, the build-plan guard.
package Extinction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import arc.Core;
import mindustry.entities.units.BuildPlan;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.Unit;

public final class DisconnectPause {

    private static final int REJOIN_SECONDS = 120;

    // How many queued build plans a client syncs at most (TypeIO.getMaxPlans); beyond this the
    // server cannot know a player's full queue, so the guard must not scrub what it cannot see.
    private static final int PLAN_SYNC_CAP = 20;

    private final Referee referee;

    // Real-time ticks: the game is paused during them, so logic-timed tasks would stall.
    private final ScheduledExecutorService scheduler;

    private boolean paused = false;
    private int serial = 0;

    // Pause time each participant already consumed this match, in ms; leaving again never refills.
    private final Map<String, Long> usedPauseMillisByUuid = new HashMap<>();

    // The participants the current pause waits on: uuid -> when their absence started being charged.
    private final Map<String, Long> absenceStartMillis = new LinkedHashMap<>();

    // Participants a pause already moved on without; a later pause does not wait for them.
    private final Set<String> waivedUuids = new HashSet<>();

    // Last display name seen per participant: the HUD must name players who are gone.
    private final Map<String, String> lastKnownNameByUuid = new HashMap<>();

    // The per-second task driving a running pause; cancelled when the pause ends.
    private ScheduledFuture<?> ticker = null;

    // Each player's queued plans when the pause began; anything else queued while frozen is scrubbed.
    private final Map<String, Set<String>> planKeysByUuid = new HashMap<>();

    // Players whose synced queue may be truncated: their full queue is unknowable, so no scrubbing.
    private final Set<String> planGuardExemptUuids = new HashSet<>();
    private boolean planGuardActive = false;

    public DisconnectPause(Referee referee, ScheduledExecutorService scheduler) {
        this.referee = referee;
        this.scheduler = scheduler;
    }

    public boolean active() {
        return paused;
    }

    // The match no longer waits for this participant; rejoining lifts it.
    public void waive(String uuid) {
        waivedUuids.add(uuid);
    }

    public void unwaive(String uuid) {
        waivedUuids.remove(uuid);
    }

    public boolean isWaived(String uuid) {
        return waivedUuids.contains(uuid);
    }

    // Seeds the names the waiting HUD needs before anyone has arrived (from the handshake).
    public void rememberNames(Map<String, String> names) {
        lastKnownNameByUuid.putAll(names);
    }

    public void rememberName(Player player) {
        lastKnownNameByUuid.put(
                player.uuid(),
                PlayerNames.displayName(player)
        );
    }

    // Display name for a participant who may be offline.
    public String nameOf(String uuid) {
        Player online = Groups.player.find(
                player -> player != null && player.uuid().equals(uuid)
        );

        if (online != null) {
            return PlayerNames.displayName(online);
        }

        return lastKnownNameByUuid.getOrDefault(uuid, "A player");
    }

    // A participant left mid-match: freeze for whatever is left of their rejoin budget.
    public void begin(Player player) {
        rememberName(player);

        if (remainingSeconds(player.uuid()) <= 0) {
            waive(player.uuid());
            Call.sendMessage(
                    "[scarlet]" + PlayerNames.displayName(player)
                            + "[scarlet] left but has no rejoin time left this match. The match continues.[]"
            );
            return;
        }

        paused = true;
        absenceStartMillis.put(player.uuid(), System.currentTimeMillis());
        referee.pauseGame();
        beginPlanGuard();

        int serial = ++this.serial;

        ticker = scheduler.scheduleAtFixedRate(
                () -> Core.app.post(() -> tick(serial)),
                0,
                1,
                TimeUnit.SECONDS
        );
    }

    // A participant leaving while already frozen joins the waited-on set on their own budget.
    public void addLeaver(Player player) {
        String uuid = player.uuid();

        if (absenceStartMillis.containsKey(uuid)) {
            return;
        }

        rememberName(player);

        if (remainingSeconds(uuid) <= 0) {
            waive(uuid);
            Call.sendMessage(
                    "[scarlet]" + PlayerNames.displayName(player)
                            + "[scarlet] left but has no rejoin time left this match.[]"
            );
            return;
        }

        absenceStartMillis.put(uuid, System.currentTimeMillis());
    }

    // Seconds left of this participant's budget, counting any absence currently being charged.
    private int remainingSeconds(String uuid) {
        long usedMillis = usedPauseMillisByUuid.getOrDefault(uuid, 0L);
        Long absenceStart = absenceStartMillis.get(uuid);

        if (absenceStart != null) {
            usedMillis += Math.max(
                    0L,
                    System.currentTimeMillis() - absenceStart
            );
        }

        return (int) ((REJOIN_SECONDS * 1000L - usedMillis) / 1000L);
    }

    // Stops charging one absentee: elapsed pause time goes onto their budget, they leave the set.
    public void chargeAbsence(String uuid) {
        Long absenceStart = absenceStartMillis.remove(uuid);

        if (absenceStart != null) {
            usedPauseMillisByUuid.merge(
                    uuid,
                    Math.max(0L, System.currentTimeMillis() - absenceStart),
                    Long::sum
            );
        }
    }

    private void chargeAllAbsences() {
        for (String uuid : new ArrayList<>(absenceStartMillis.keySet())) {
            chargeAbsence(uuid);
        }
    }

    private void beginPlanGuard() {
        planKeysByUuid.clear();
        planGuardExemptUuids.clear();

        Groups.player.each(player -> {
            Unit unit = player == null || player.dead() ? null : player.unit();

            if (unit != null) {
                baselinePlans(player, unit);
            }
        });

        planGuardActive = true;
    }

    // Records this player's synced plans as their allowed set, or exempts them when the synced
    // head may be truncated (at the cap, or carrying byte[]/String configs that shrink it).
    private void baselinePlans(Player player, Unit unit) {
        Set<String> keys = new HashSet<>();
        boolean truncatable = unit.plans().size >= PLAN_SYNC_CAP;

        for (BuildPlan plan : unit.plans()) {
            if (
                    plan.config instanceof byte[]
                            || plan.config instanceof String
            ) {
                truncatable = true;
            }

            keys.add(planKey(plan));
        }

        if (truncatable) {
            planGuardExemptUuids.add(player.uuid());
        } else {
            planKeysByUuid.put(player.uuid(), keys);
        }
    }

    private void endPlanGuard() {
        if (!planGuardActive) {
            return;
        }

        scrubPlans();
        planGuardActive = false;
        planKeysByUuid.clear();
        planGuardExemptUuids.clear();
    }

    // Ticked from the plugin's update loop, which fires even while the game is paused.
    public void update() {
        if (!planGuardActive) {
            return;
        }

        scrubPlans();
    }

    private void scrubPlans() {
        Groups.player.each(player -> {
            Unit unit = player == null || player.dead() ? null : player.unit();

            if (unit == null || unit.plans().isEmpty()) {
                return;
            }

            if (planGuardExemptUuids.contains(player.uuid())) {
                return;
            }

            Set<String> allowed = planKeysByUuid.get(player.uuid());

            if (allowed == null) {
                // First sight of this queue during the guard (a rejoiner re-sending their
                // pre-disconnect plans, or a player dead at pause start): baseline, do not wipe.
                baselinePlans(player, unit);
                return;
            }

            List<BuildPlan> added = new ArrayList<>();

            for (BuildPlan plan : unit.plans()) {
                if (!allowed.contains(planKey(plan))) {
                    added.add(plan);
                }
            }

            for (BuildPlan plan : added) {
                // Server queue first, then the owning client's local queue.
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

    // Identity of a plan across client re-syncs: position, action and block.
    private String planKey(BuildPlan plan) {
        return plan.x + "," + plan.y + "," + plan.breaking + ","
                + (plan.block == null ? -1 : plan.block.id);
    }

    // One second of a running pause: settle rejoins, expire spent budgets, end or refresh the HUD.
    private void tick(int serial) {
        if (serial != this.serial || !paused) {
            return;
        }

        // Players who are back stop being charged.
        for (String uuid : new ArrayList<>(absenceStartMillis.keySet())) {
            if (referee.isOnline(uuid)) {
                chargeAbsence(uuid);
            }
        }

        // Absentees whose budget ran out are waived; rejoining later still lets them play on.
        List<String> expiredNames = new ArrayList<>();

        for (String uuid : new ArrayList<>(absenceStartMillis.keySet())) {
            if (remainingSeconds(uuid) <= 0) {
                chargeAbsence(uuid);
                waive(uuid);
                expiredNames.add(nameOf(uuid));
            }
        }

        if (absenceStartMillis.isEmpty()) {
            // Nobody left to wait for: waive any straggler the presence check still counts.
            for (String uuid : referee.participantUuids()) {
                if (!referee.isOnline(uuid)) {
                    waive(uuid);
                }
            }

            end(
                    expiredNames.isEmpty()
                            ? "[accent]Everyone is back. Resuming the match![]"
                            : "[scarlet]" + String.join("[scarlet], ", expiredNames)
                            + "[scarlet] did not return. The match continues.[]"
            );
            return;
        }

        for (String name : expiredNames) {
            Call.sendMessage(
                    "[scarlet]" + name
                            + "[scarlet] did not return. The match still waits for the others.[]"
            );
        }

        showRejoinHud();
    }

    // One line per missing player with their own remaining budget.
    public void showRejoinHud() {
        StringBuilder lines = new StringBuilder();

        for (String uuid : absenceStartMillis.keySet()) {
            if (!lines.isEmpty()) {
                lines.append("\n");
            }

            lines.append("[scarlet]")
                    .append(nameOf(uuid))
                    .append("[scarlet] left  -  [accent]")
                    .append(remainingSeconds(uuid))
                    .append("s[scarlet] to rejoin, or the match continues[]");
        }

        referee.showHud(lines.toString());
    }

    public void end() {
        end("[accent]Everyone is back. Resuming the match![]");
    }

    private void end(String announcement) {
        paused = false;
        serial++;
        chargeAllAbsences();
        cancelTicker();
        endPlanGuard();
        referee.resumeGame();
        referee.hideHud();
        Call.sendMessage(announcement);
    }

    // Tears the pause down without an announcement; the victory / session-end paths speak.
    public void forceEnd() {
        paused = false;
        serial++;
        chargeAllAbsences();
        cancelTicker();
        endPlanGuard();
        referee.resumeGame();
    }

    private void cancelTicker() {
        if (ticker != null) {
            ticker.cancel(false);
            ticker = null;
        }
    }
}
