// Holds players still and silent while an admin's in-game ban reason prompt is open.
package Extinction;

import arc.func.Cons;
import arc.struct.Seq;
import arc.util.CommandHandler;
import mindustry.Vars;
import mindustry.content.StatusEffects;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.net.Administration.ActionType;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

public final class BanFreeze {
    private static final long TIMEOUT_MILLIS = 120_000L;
    private static final String NOTICE = "An admin is checking you. You can't move or chat for a moment.";
    private record Hold(String uuid, long deadline) { }
    private final Map<String, Hold> holds = new HashMap<>();
    private final Map<Player, Unit> affected = new HashMap<>();
    private final LongSupplier clock;
    private boolean installed;

    public BanFreeze() {
        this(System::currentTimeMillis);
    }

    BanFreeze(LongSupplier clock) {
        this.clock = clock;
    }

    // Installed before the mirror and word filter; commands pass through the same gate as PlayerLock's.
    public void install() {
        if (installed || Vars.netServer == null) return;
        installed = true;
        Vars.netServer.admins.addChatFilter((player, message) -> frozen(player) ? null : message);
        Vars.netServer.admins.addActionFilter(action -> action == null || action.player == null
                || action.type == ActionType.respawn || !frozen(action.player));
        Vars.netServer.clientCommands = new CommandGate(Vars.netServer.clientCommands, this);
    }

    public void begin(String adminUuid, String targetUuid) {
        holds.put(adminUuid, new Hold(targetUuid, clock.getAsLong() + TIMEOUT_MILLIS));
        update();
    }

    public void close(String adminUuid) {
        holds.remove(adminUuid);
        update();
    }

    public boolean frozen(Player player) {
        return player != null && frozen(player.uuid());
    }

    boolean frozen(String uuid) {
        long now = clock.getAsLong();
        return holds.values().stream().anyMatch(hold -> hold.uuid.equals(uuid) && hold.deadline > now);
    }

    // Real time for expiration, vanilla statuses for clients, even across respawns and reconnections.
    public void update() {
        long now = clock.getAsLong();
        holds.values().removeIf(hold -> hold.deadline <= now);
        affected.entrySet().removeIf(entry -> {
            Player player = entry.getKey();
            if (frozen(player) && player.isAdded() && (player.con == null || !player.con.kicked)) return false;
            release(entry.getValue());
            return true;
        });
        Groups.player.each(player -> {
            if (!frozen(player) || (player.con != null && player.con.kicked)) return;
            if (!affected.containsKey(player)) player.sendMessage(NOTICE);
            Unit unit = player.unit();
            Unit previous = affected.put(player, unit);
            if (previous != unit) release(previous);
            player.shooting = false;
            if (unit == null || player.dead()) return;
            unit.apply(StatusEffects.unmoving, 2f);
            unit.apply(StatusEffects.disarmed, 2f);
            unit.vel.setZero();
            unit.isShooting = false;
            unit.clearBuilding();
        });
    }

    private void release(Unit unit) {
        if (unit != null) unit.unapply(StatusEffects.unmoving);
        // Disarmed expires within two live ticks; never strip a VPN lock's longer disarm.
    }

    private static final class CommandGate extends CommandHandler {

        private final CommandHandler delegate;
        private final BanFreeze freeze;

        CommandGate(CommandHandler delegate, BanFreeze freeze) {
            super(delegate.getPrefix());
            this.delegate = delegate;
            this.freeze = freeze;
        }

        // A refused command answers valid, so the text is neither treated as chat nor followed by "unknown command".
        @Override
        public CommandResponse handleMessage(String message, Object params) {
            if (
                    params instanceof Player player
                            && message != null
                            && message.startsWith(delegate.getPrefix())
                            && freeze.frozen(player)
            ) {
                return new CommandResponse(ResponseType.valid, null, message);
            }

            return delegate.handleMessage(message, params);
        }

        @Override
        public CommandResponse handleMessage(String message) {
            return delegate.handleMessage(message);
        }

        @Override
        public void setPrefix(String prefix) {
            super.setPrefix(prefix);
            delegate.setPrefix(prefix);
        }

        @Override
        public String getPrefix() {
            return delegate.getPrefix();
        }

        @Override
        public void removeCommand(String text) {
            delegate.removeCommand(text);
        }

        @Override
        public <T> Command register(String text, String description, CommandRunner<T> runner) {
            return delegate.register(text, description, runner);
        }

        @Override
        public <T> Command register(String text, String params, String description, CommandRunner<T> runner) {
            return delegate.register(text, params, description, runner);
        }

        @Override
        public Command register(String text, String description, Cons<String[]> runner) {
            return delegate.register(text, description, runner);
        }

        @Override
        public Command register(String text, String params, String description, Cons<String[]> runner) {
            return delegate.register(text, params, description, runner);
        }

        @Override
        public Seq<Command> getCommandList() {
            return delegate.getCommandList();
        }

    }
}
