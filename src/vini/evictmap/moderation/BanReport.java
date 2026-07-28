package vini.evictmap.moderation;

import java.util.List;

/**
 * What one moderation action came to. Plain data with no Mindustry types: the
 * ban log ships it to Discord from another thread. {@code seedLabel} is what
 * the admin acted on, {@code names} every name every hit account has used.
 *
 * <p>{@code origin} says who decided it, where and when - set for anything that
 * did not simply happen on the hub. {@code wordFilterHit} is set only for
 * {@link Kind#WORD_FILTER} and carries what the filter saw, so the log entry can
 * be read without the console next to it.
 */
public record BanReport(
        Kind kind,
        String seedLabel,
        List<String> names,
        List<String> uuids,
        List<String> ips,
        BanOrigin origin,
        WordFilterHit wordFilterHit
) {

    /** An action with no story to tell: an admin's ban, straight on the hub. */
    public BanReport(
            Kind kind,
            String seedLabel,
            List<String> names,
            List<String> uuids,
            List<String> ips
    ) {
        this(kind, seedLabel, names, uuids, ips, null, null);
    }

    public enum Kind {

        /** An admin banned somebody; the cascade ran. */
        BAN,

        /** The word filter banned somebody on its own. */
        WORD_FILTER,

        /** An admin lifted a ban. Left exactly as Mindustry applies it. */
        UNBAN,

        /** A ban that predates this feature, pulled through the cascade once. */
        IMPORT
    }

    public boolean isEmpty() {
        return uuids.isEmpty() && ips.isEmpty();
    }
}
