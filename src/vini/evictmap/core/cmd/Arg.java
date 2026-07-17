package vini.evictmap.core.cmd;

/**
 * One declared argument of a command, parsed from a compact spec string:
 * {@code "name:type"}, with a trailing {@code ?} marking it optional, e.g.
 * {@code "target:player"}, {@code "reason:text?"}, {@code "amount:int"}.
 * The type defaults to {@code string} when omitted ({@code "name"}).
 */
final class Arg {

    final String name;
    final ArgType<?> type;
    final boolean optional;

    private Arg(String name, ArgType<?> type, boolean optional) {
        this.name = name;
        this.type = type;
        this.optional = optional;
    }

    boolean greedy() {
        return type.greedy();
    }

    static Arg parse(String spec) {
        String trimmed = spec.trim();
        boolean optional = false;

        int colon = trimmed.indexOf(':');
        String name = colon >= 0 ? trimmed.substring(0, colon) : trimmed;
        String typeId = colon >= 0 ? trimmed.substring(colon + 1) : "string";

        if (typeId.endsWith("?")) {
            optional = true;
            typeId = typeId.substring(0, typeId.length() - 1);
        }

        return new Arg(name, ArgType.byId(typeId), optional);
    }

    /** Renders the Mindustry params token: {@code <name>}, {@code [name]}, {@code [name...]}. */
    String token() {
        String inner = greedy() ? name + "..." : name;
        return optional ? "[" + inner + "]" : "<" + inner + ">";
    }
}
