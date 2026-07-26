package vini.evictmap.moderation;

import java.util.List;

/**
 * What one moderation action came to, ready to be written somewhere a human
 * reads it. Deliberately plain data with no Mindustry types: the ban log ships
 * it off to Discord from another thread.
 *
 * @param kind      what happened
 * @param seedLabel the name or address the admin acted on
 * @param names     every name every hit account has ever used
 * @param uuids     every account hit
 * @param ips       every address hit
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
