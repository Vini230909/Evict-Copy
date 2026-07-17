package vini.evictmap.core.text;

/**
 * Catalogue of reusable, standardised messages.
 *
 * <p>Anything worded the same way in more than one place lives here so the
 * phrasing (and colour) is edited once. Command handlers usually reach these
 * through {@code CommandContext} rather than directly.
 */
public final class Msg {

    private Msg() {
    }

    /** Standard "no such player" reply. */
    public static Text playerNotFound(String query) {
        return Text.of().scarlet("No online player matches ").white(query).scarlet(".");
    }

    /** Standard "this command is for team leaders" reply. */
    public static Text leaderOnly() {
        return Text.of().scarlet("Only the team leader can use this.");
    }

    /** Standard "you need a personal team first" reply. */
    public static Text needPersonalTeam() {
        return Text.of().scarlet("You need your own team to do that.");
    }

    /** Standard permission-denied reply. */
    public static Text noPermission() {
        return Text.of().scarlet("You don't have permission to use that.");
    }

    /** Standard "usage:" hint for a command. */
    public static Text usage(String usage) {
        return Text.of().lightGray("Usage: ").white(usage);
    }
}
