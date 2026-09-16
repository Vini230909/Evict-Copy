package vini.evictmap.moderation.lock;

import vini.evictmap.moderation.vpn.VpnScan;
import vini.evictmap.moderation.vpn.VpnVerdict;

import arc.util.Strings;
import arc.util.Time;
import mindustry.Vars;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.net.Administration;

import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Hub only: decides at the door whether a join is onboarded, locked, or
 * held until the VPN verdict is in.
 *
 * <p>The rule is narrow on purpose: an account's <em>first</em> join, through
 * a VPN, proxy or hosting range. A known account on a VPN is a regular who
 * likes their privacy and is only written down ({@link VpnScan}); the evader
 * pattern is the fresh account, and that is the one held for a person to
 * look at. A verified account - one an admin freed - is never held again.
 *
 * <p>The verdict takes a lookup, so it is started the moment the connection
 * opens ({@link VpnScan#prefetch}), while the client is still downloading
 * the world; by the time the join event fires it is normally in the cache
 * and the decision is instant. When it is not, the join is parked on the
 * Fallen team for at most half a second and then onboarded anyway - a
 * newcomer must not wait for their hex - and a verdict that arrives later
 * still locks the account if it says so. A player who leaves in between is
 * locked all the same; the lock is on the account, not the connection.
 *
 * <p>A locked account that comes back from a <em>clean</em> address is freed
 * automatically: the evader's clean address is the one the ban cascade
 * already knows, so this exit is open to the newcomer who turned their VPN
 * off and closed to the evader. Automatic frees do not verify - the account
 * is scanned like any other from then on.
 */
public final class LockGate {

    /** How long a first join waits for the verdict before it is let in: half a second. */
    private static final float HOLD_TIMEOUT_TICKS = 30f;

    private final BooleanSupplier enabled;
    private final PlayerLock lock;
    private final VpnScan scan;

    /** Parks a player on the Fallen team with no hex - the held and the locked. */
    private final Consumer<Player> park;

    /** Gives a player their personal team and hex, as a normal join would. */
    private final Consumer<Player> onboard;

    /** Accounts waiting for their verdict. */
    private final Set<String> held = new HashSet<>();

    public LockGate(
            BooleanSupplier enabled,
            PlayerLock lock,
            VpnScan scan,
            Consumer<Player> park,
            Consumer<Player> onboard
    ) {
        this.enabled = enabled;
        this.lock = lock;
        this.scan = scan;
        this.park = park;
        this.onboard = onboard;
    }

    /**
     * One hub join. Returns true when the caller should onboard the player
     * right now, false when the gate has taken them: locked, or held until
     * the verdict decides.
     */
    public boolean handlePlayerJoin(Player player) {
        if (player == null) {
            return true;
        }

        String uuid = player.uuid();

        if (lock.isLocked(uuid)) {
            park.accept(player);
            lock.handlePlayerJoin(player);

            // Back from a clean address? Then the lock has done its job.
            scan.handlePlayerJoin(player, verdict -> {
                if (verdict != null && !verdict.flagged() && lock.isLocked(uuid)) {
                    lock.free(uuid, "a clean address (automatic)", false);
                }

                return false;
            });

            return false;
        }

        if (
                !enabled.getAsBoolean()
                        || lock.isVerified(uuid)
                        || (!held.contains(uuid) && !firstJoin(uuid))
        ) {
            scan.handlePlayerJoin(player, null);
            return true;
        }

        held.add(uuid);
        park.accept(player);

        String name = player.name;
        String ip = player.con == null ? "" : player.con.address;

        scan.handlePlayerJoin(player, verdict -> decide(uuid, name, ip, verdict));

        if (!held.contains(uuid)) {
            // Answered from the cache, inside the call: already onboarded or
            // locked, nothing to wait for.
            return false;
        }

        player.sendMessage("[lightgray]Checking your connection - one moment...[]");

        Time.run(HOLD_TIMEOUT_TICKS, () -> {
            if (held.remove(uuid)) {
                release(uuid);
            }
        });

        return false;
    }

    /** True while an account is parked waiting for its verdict. */
    public boolean isHeld(String uuid) {
        return uuid != null && held.contains(uuid);
    }

    /**
     * The verdict landed. Returns true when the account was locked, so the
     * scan leaves the line to the lock's own entry.
     */
    private boolean decide(String uuid, String name, String ip, VpnVerdict verdict) {
        boolean wasHeld = held.remove(uuid);

        if (verdict != null && verdict.flagged()) {
            if (lock.isLocked(uuid)) {
                return true;
            }

            if (!wasHeld) {
                // The half-second hold ran out and the player is already on
                // their hex. They are parked again all the same - the hex
                // stays behind as an unattended team, like a player who left.
                Player online = Groups.player.find(p -> p != null && uuid.equals(p.uuid()));

                if (online != null) {
                    park.accept(online);
                }
            }

            lock.lock(uuid, name, ip, verdict);
            tellAdmins(name, verdict);
            return true;
        }

        if (wasHeld) {
            release(uuid);
        }

        return false;
    }

    private void release(String uuid) {
        Player player = Groups.player.find(online -> online != null && uuid.equals(online.uuid()));

        if (player != null) {
            onboard.accept(player);
        }
    }

    private void tellAdmins(String name, VpnVerdict verdict) {
        String line = "[scarlet]Locked: [white]" + Strings.stripColors(name == null ? "" : name)
                + "[scarlet] - new account through " + verdict.flags()
                + ". Free them with [white]/free[scarlet] once you know who it is.[]";

        Groups.player.each(online -> {
            if (online != null && online.admin) {
                online.sendMessage(line);
            }
        });
    }

    /** Mindustry's own join count, incremented before the join event fires. */
    private static boolean firstJoin(String uuid) {
        if (Vars.netServer == null) {
            return false;
        }

        Administration.PlayerInfo info = Vars.netServer.admins.getInfoOptional(uuid);
        return info == null || info.timesJoined <= 1;
    }
}
