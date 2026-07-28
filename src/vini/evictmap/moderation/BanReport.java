package vini.evictmap.moderation;

import java.util.List;

/**
 * What one moderation action came to. Plain data with no Mindustry types: the
 * ban log ships it to Discord from another thread. {@code seedLabel} is what
 * the admin acted on, {@code names} every name every hit account has used.
 */
public record BanReport(
        Kind kind,
        String seedLabel,
        List<String> names,
        List<String> uuids,
        List<String> ips
) {

    public enum Kind {

        /** An admin banned somebody; the cascade ran. */
        BAN,

        /** An admin lifted a ban. Left exactly as Mindustry applies it. */
        UNBAN,

        /** A ban that predates this feature, pulled through the cascade once. */
        IMPORT
    }

    public boolean isEmpty() {
        return uuids.isEmpty() && ips.isEmpty();
    }
}
