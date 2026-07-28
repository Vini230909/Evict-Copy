package vini.evictmap.moderation;

/**
 * One account to ban, with why and where it was decided.
 *
 * <p>Every automatic and command-driven ban path in the plugin produces one of
 * these, so a ban carries its story from wherever it happened - a match server
 * included - to the hub that applies it and to the Discord log that records it.
 * {@code wordFilterHit} is set only when the word filter decided it.
 */
public record BanRequest(
        String uuid,
        BanOrigin origin,
        WordFilterHit wordFilterHit
) {

    /** A person's ban: an admin's {@code /ban}, hammer or console command. */
    public static BanRequest admin(String uuid, BanOrigin origin) {
        return new BanRequest(uuid, origin, null);
    }

    /** The word filter's ban, with what it saw. */
    public static BanRequest wordFilter(
            String uuid,
            BanOrigin origin,
            WordFilterHit hit
    ) {
        return new BanRequest(uuid, origin, hit);
    }

    public boolean isEmpty() {
        return uuid == null || uuid.isBlank();
    }
}
