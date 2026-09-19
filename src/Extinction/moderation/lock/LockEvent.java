package Extinction.moderation.lock;

import Extinction.moderation.vpn.VpnVerdict;

/**
 * One lock decision, for the ban log: an account locked on arrival, or one
 * freed by an admin.
 *
 * @param verdict what the lookup said, for a lock; null for a free
 * @param actor   who freed the account, for a free; blank for a lock
 */
public record LockEvent(
        Kind kind,
        String name,
        String uuid,
        String ip,
        VpnVerdict verdict,
        String actor
) {

    public enum Kind {
        LOCKED,
        FREED
    }

    public static LockEvent locked(String name, String uuid, String ip, VpnVerdict verdict) {
        return new LockEvent(Kind.LOCKED, name, uuid, ip, verdict, "");
    }

    public static LockEvent freed(String name, String uuid, String actor) {
        return new LockEvent(Kind.FREED, name, uuid, "", null, actor);
    }
}
