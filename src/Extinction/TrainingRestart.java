// /restart in a Training match: a fresh world through the server's own reload, then the start countdown again.
package Extinction;

import Extinction.core.text.Text;
import Extinction.core.util.PluginLog;
import Extinction.core.util.Players;
import Extinction.gen.EvictRuntimeState;
import Extinction.round.HexSlot;
import Extinction.round.TeamManager;

import arc.Core;
import arc.util.Timer;
import mindustry.Vars;
import mindustry.gen.Player;
import mindustry.maps.Map;
import mindustry.server.ServerControl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class TrainingRestart {

    // The two admin words; /help never lists them (Help.UNLISTED_ARGS).
    private static final String NEW_WORLD_NOW = "bot";
    private static final String SAME_SEED_NOW = "again";

    // A plain /restart counts this long to the reload; the new world then runs the 5 s start countdown.
    private static final int COUNTDOWN_SECONDS = 3;

    // How often the new world is checked for the player's start hex, and how long after the reload it keeps trying.
    private static final float POLL_SECONDS = 0.5f;
    private static final long PLACEMENT_WINDOW_MILLIS = 30_000L;

    private final EvictRuntimeState runtime;
    private final TeamManager teamManager;
    private final Referee referee;

    // True from the request until the reload runs, so a second /restart cannot stack.
    private boolean pending;

    // Watches the latest restart's new world; a newer restart replaces it.
    private Timer.Task watch;

    public TrainingRestart(EvictRuntimeState runtime, TeamManager teamManager, Referee referee) {
        this.runtime = runtime;
        this.teamManager = teamManager;
        this.referee = referee;
    }

    // '/restart': 3 s to a new world, then the start countdown; 'bot' and 'again' are admin-only and immediate.
    public void handle(Player player, String option) {
        if (!referee.isActive() || !referee.matchMode().restartable()) {
            player.sendMessage("[scarlet]/restart only works in a Training match.[]");
            return;
        }

        String word = option == null ? "" : option.trim().toLowerCase(Locale.ROOT);
        boolean adminWord = word.equals(NEW_WORLD_NOW) || word.equals(SAME_SEED_NOW);

        if (!word.isEmpty() && !adminWord) {
            player.sendMessage(player.admin
                    ? "[scarlet]Use /restart, /restart bot or /restart again.[]"
                    : "[scarlet]Use /restart.[]");
            return;
        }

        if (adminWord && !player.admin) {
            player.sendMessage("[scarlet]Only admins can do that.[]");
            return;
        }

        String refusal = refusal(player, word.equals(SAME_SEED_NOW));
        if (refusal != null) {
            player.sendMessage(refusal);
            return;
        }

        boolean countdown = !adminWord;
        long seed = word.equals(SAME_SEED_NOW) ? runtime.lastSeed : runtime.randomSeed();

        // Consumed by the world-load generation, exactly like a seed set with 'oregen seed'.
        runtime.nextSeed = seed;
        pending = true;

        Text.of().player(player).accent(
                word.equals(SAME_SEED_NOW) ? " restarts the training on the same seed now."
                        : countdown ? " restarts the training: a new world in " + COUNTDOWN_SECONDS + " seconds."
                        : " restarts the training: a new world now."
        ).sendAll();

        PluginLog.info("Training restart by @ (@): seed @.", player.plainName(), word.isEmpty() ? "normal" : word, seed);

        watchNewWorld(teamManager.roundSerial(), seed, countdown, player.admin ? player.uuid() : null);

        if (!countdown) {
            // Posted, so the reload never runs inside the chat packet that asked for it.
            Core.app.post(this::reload);
            return;
        }

        for (int second = COUNTDOWN_SECONDS; second >= 1; second--) {
            int remaining = second;
            Timer.schedule(() -> {
                if (pending && !referee.resolved()) {
                    referee.showHud("[accent]New world in [scarlet]" + remaining + "[]");
                }
            }, COUNTDOWN_SECONDS - second);
        }
        Timer.schedule(this::reload, COUNTDOWN_SECONDS);
    }

    // Why this restart cannot happen now, or null when it can.
    private String refusal(Player player, boolean sameSeed) {
        if (!player.admin && !referee.isParticipant(player.uuid())) {
            return "[scarlet]Only the player in training can restart it.[]";
        }
        if (referee.resolved()) {
            return "[scarlet]This training is over.[]";
        }
        if (!referee.gate.started()) {
            return "[scarlet]Wait until the training has started.[]";
        }
        if (pending || ServerControl.instance == null || ServerControl.instance.inGameOverWait) {
            return "[scarlet]A new world is already on its way.[]";
        }
        if (sameSeed && runtime.lastSeed == null) {
            return "[scarlet]There is no seed to repeat yet.[]";
        }
        return null;
    }

    // The server's own round change onto the same base map; a /die during the countdown cancels it.
    private void reload() {
        pending = false;
        if (referee.resolved()) {
            return;
        }

        referee.hideHud();
        ServerControl server = ServerControl.instance;
        Map map = Vars.state.map;
        server.play(false, () -> Vars.world.loadMap(map, map.applyRules(server.lastMode)));
    }

    // Once the join scan has placed every participant: the start countdown (plain /restart), the admin's private report.
    private void watchNewWorld(long serialBefore, long seed, boolean countdown, String adminUuid) {
        if (watch != null) {
            watch.cancel();
        }

        long deadline = System.currentTimeMillis() + COUNTDOWN_SECONDS * 1000L + PLACEMENT_WINDOW_MILLIS;
        Set<String> placed = new HashSet<>();
        List<Text> lines = new ArrayList<>();

        watch = Timer.schedule(new Timer.Task() {
            @Override
            public void run() {
                boolean late = System.currentTimeMillis() > deadline;

                // The reload has not happened (yet); a reload that never came does nothing.
                if (teamManager.roundSerial() == serialBefore) {
                    if (late) {
                        cancel();
                    }
                    return;
                }

                // Offline counts as waiting: right after the reload every player is still loading the world.
                int waiting = 0;

                for (String uuid : referee.participantUuids()) {
                    if (placed.contains(uuid)) {
                        continue;
                    }

                    Player member = Players.byUuid(uuid);
                    HexSlot start = member == null ? null : startHex(member);
                    if (start == null) {
                        waiting++;
                        continue;
                    }

                    placed.add(uuid);
                    lines.add(Text.of().accent("New world, seed ").white(seed).accent(": ").player(member)
                            .accent(" starts at hex ").white(start.col + "," + start.row)
                            .accent(" (tile ").white(start.x + "," + start.y).accent(")."));
                    PluginLog.info("Training restart: seed @, @ starts at hex (@,@), tile (@,@).",
                            seed, member.plainName(), start.col, start.row, start.x, start.y);
                }

                if (waiting > 0 && !late) {
                    return;
                }

                if (countdown && !countdownStarted && !referee.resolved()) {
                    countdownStarted = true;
                    referee.gate.restart();
                }

                // The admin reloads too; the report waits for them, the countdown above does not.
                Player admin = adminUuid == null ? null : Players.byUuid(adminUuid);
                if (adminUuid != null && admin == null && !late) {
                    return;
                }

                if (lines.isEmpty()) {
                    lines.add(Text.of().accent("New world, seed ").white(seed).accent("."));
                }
                lines.forEach(line -> line.sendTo(admin));
                cancel();
            }

            private boolean countdownStarted;
        }, POLL_SECONDS, POLL_SECONDS);
    }

    // The hex the player's personal team holds right after the start; null while they are not placed yet.
    private HexSlot startHex(Player player) {
        if (!teamManager.isPersonalRoundPlayer(player)) {
            return null;
        }

        for (HexSlot slot : teamManager.slots()) {
            if (slot.ownerTeamId == player.team().id) {
                return slot;
            }
        }

        return null;
    }
}
