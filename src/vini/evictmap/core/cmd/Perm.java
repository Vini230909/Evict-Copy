package vini.evictmap.core.cmd;

import mindustry.gen.Player;

import java.util.function.Predicate;

/**
 * A command's permission requirement.
 *
 * <p>Declared once on a command and enforced centrally by {@link Commands},
 * instead of every handler re-checking {@code player.admin} by hand. The server
 * console (a {@code null} sender) always passes - the operator
 * running the console is trusted.
 */
public final class Perm {

    /** Anyone may run it. */
    public static final Perm EVERYONE = new Perm("everyone", player -> true);

    /** Only server admins (and the console). */
    public static final Perm ADMIN = new Perm("admins", player -> player != null && player.admin);

    private final String description;
    private final Predicate<Player> clientTest;

    private Perm(String description, Predicate<Player> clientTest) {
        this.description = description;
        this.clientTest = clientTest;
    }

    /** A custom requirement over the sending player. */
    public static Perm require(String description, Predicate<Player> test) {
        return new Perm(description, test);
    }

    /** True when {@code sender} may run the command ({@code null} = console). */
    public boolean allows(Player sender) {
        return sender == null || clientTest.test(sender);
    }

    public String description() {
        return description;
    }
}
