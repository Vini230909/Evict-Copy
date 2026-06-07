package vini.evictmap;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Shared command categories used by registration and filtered help.
 */
final class EvictCommandCatalog {

    static final Set<String> DEV_COMMANDS;

    static {
        Set<String> commands = new HashSet<>();

        commands.add("forceend");
        commands.add("extinction");
        commands.add("attritioncore");
        commands.add("attritionrange");
        commands.add("wall");
        commands.add("corecap");
        commands.add("spawnunit");
        commands.add("info");

        DEV_COMMANDS = Collections.unmodifiableSet(commands);
    }

    private EvictCommandCatalog() {
    }
}
