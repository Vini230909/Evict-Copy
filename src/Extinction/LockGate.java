// Hub only: at the door, a join is onboarded, locked, or held until its VPN verdict is in.
package Extinction;

import arc.util.Strings;
import arc.util.Time;
import mindustry.Vars;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.net.Administration;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

// Only new and locked accounts are looked up at all: a first join through a VPN is locked, a locked one back clean is freed.
public final class LockGate {

    // How long a first join waits for the verdict before it is let in: half a second.
    private static final float HOLD_TIMEOUT_TICKS = 30f;

    private final PlayerLock lock;
    private final VpnScan scan;

    // Parks a player on the Fallen team with no hex - the held and the locked.
    private final Consumer<Player> park;

    // Gives a player their personal team and hex, as a normal join would.
    private final Consumer<Player> onboard;

    // Accounts waiting for their verdict.
    private final Set<String> held = new HashSet<>();

    public LockGate(PlayerLock lock, VpnScan scan, Consumer<Player> park, Consumer<Player> onboard) {
        this.lock = lock;
        this.scan = scan;
        this.park = park;
        this.onboard = onboard;
    }

    // The connect packet, before the join is counted: starts the lookup early, for exactly the joins that get one.
    public void prefetch(String uuid, String ip) {
        if (uuid != null && (lock.isLocked(uuid) || lockable(uuid, 0))) {
            scan.prefetch(ip);
        }
    }

    // One hub join: true when the caller should onboard the player now, false when the gate took them.
    public boolean handlePlayerJoin(Player player) {
        if (player == null) {
            return true;
        }

        String uuid = player.uuid();

        if (lock.isLocked(uuid)) {
            park.accept(player);
            lock.handlePlayerJoin(player);

            String lockedName = player.name;
            String lockedIp = player.con == null ? "" : player.con.address;
            int lockedJoins = timesJoined(uuid);

            // Back from a clean address? Then the lock has done its job. Otherwise staff see the return.
            scan.handlePlayerJoin(player, verdict -> {
                if (!lock.isLocked(uuid)) {
                    return true;
                }

                if (verdict != null && !verdict.flagged()) {
                    lock.free(uuid, "a clean address (automatic)", false);
                } else {
                    lock.noteJoin(uuid, lockedName, lockedIp, verdict, lockedJoins);
                }

                return true;
            });

            return false;
        }

        // Everyone else - known accounts, verified ones, or all of them while the lock is off - is not looked up at all.
        if (!lockable(uuid, 1)) {
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

    // True while an account is parked waiting for its verdict.
    public boolean isHeld(String uuid) {
        return uuid != null && held.contains(uuid);
    }

    // The verdict landed. True when the account was locked, so the scan leaves the line to the lock's own entry.
    private boolean decide(String uuid, String name, String ip, VpnVerdict verdict) {
        boolean wasHeld = held.remove(uuid);

        if (verdict != null && verdict.flagged()) {
            if (lock.isLocked(uuid)) {
                return true;
            }

            if (!wasHeld) {
                // The half-second hold ran out and the player is already on
                // their hex; parked again, the hex stays behind unattended.
                Player online = Groups.player.find(p -> p != null && uuid.equals(p.uuid()));

                if (online != null) {
                    park.accept(online);
                }
            }

            lock.lock(uuid, name, ip, verdict, timesJoined(uuid));
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

    // A new account the lock would take: a first join, or one still held, while the lock is on and nobody verified it.
    private boolean lockable(String uuid, int counted) {
        return Config.vpnLock && !lock.isVerified(uuid) && (held.contains(uuid) || firstJoin(uuid, counted));
    }

    // Mindustry's own join count: counted is 1 at the join event (this join included), 0 at the connect packet.
    private static boolean firstJoin(String uuid, int counted) {
        if (Vars.netServer == null) {
            return false;
        }

        Administration.PlayerInfo info = Vars.netServer.admins.getInfoOptional(uuid);
        return info == null || info.timesJoined <= counted;
    }

    // Mindustry's own join count for the ban log's "joined first time / N times"; this join included.
    private static int timesJoined(String uuid) {
        Administration.PlayerInfo info = Vars.netServer == null ? null : Vars.netServer.admins.getInfoOptional(uuid);
        return info == null ? 1 : info.timesJoined;
    }
}
