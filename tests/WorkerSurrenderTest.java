// Standalone regression checks for surrender during worker freezes and stale countdown callbacks.
package Extinction;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import mindustry.Vars;
import mindustry.core.GameState;
import mindustry.gen.Groups;
import mindustry.net.Net;

public final class WorkerSurrenderTest {
    public static void main(String[] args) throws Exception {
        System.setProperty("evict.duelWorker", "true");
        Vars.net = new Net(null) {
            @Override public boolean server() { return true; }
            @Override public void send(Object object, boolean reliable) { }
        };
        Groups.init();
        Vars.state = new GameState();
        Referee referee = new Referee();

        set(referee.gate, "countdownStarted", true);
        set(referee.gate, "settlePending", true);
        set(referee.gate, "startFreezeApplied", true);
        Vars.state.set(GameState.State.paused);
        referee.handleParticipantSurrender(null);
        require(Vars.state.isPlaying(), "surrender resumes the opening freeze");
        require(referee.gate.started() && !referee.gate.countingDown(), "opening gate is released");
        require(!(boolean) get(referee.gate, "settlePending"), "camera settle is cancelled");
        require((int) get(referee.gate, "settleSerial") == 1, "pending camera callback is invalidated");

        Vars.state.set(GameState.State.paused);
        Method staleStart = StartGate.class.getDeclaredMethod("startMatch", int.class);
        staleStart.setAccessible(true);
        staleStart.invoke(referee.gate, 0);
        require(Vars.state.isPaused(), "old countdown cannot restart the match");

        set(referee.pause, "paused", true);
        referee.handleParticipantSurrender(null);
        require(!referee.pause.active() && Vars.state.isPlaying(), "disconnect pause is released");
        referee.gate.release();
        require(Vars.state.isPlaying(), "releasing an already running match is safe");
        System.out.println("Worker surrender checks passed.");
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
