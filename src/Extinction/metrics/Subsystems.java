package Extinction.metrics;

/**
 * Turns one stack trace into the name of the thing the server was busy with:
 * pathfinding, unit AI, block updates, power, network sync, the Evict plugin
 * itself, and so on.
 *
 * <p>Raw method names are the honest answer but not a readable one -
 * {@code Pathfinder.updateFrontier 41%} only helps a reader who already knows
 * what that class is. Rolling the same samples up into a dozen named
 * subsystems turns the profile into something anyone can act on, and answers
 * the question that actually matters when a server is slow: is this the game's
 * own work, or is it mine?
 *
 * <p>The stack is read from the top down and the first frame that matches a
 * rule wins, so the most specific thing on the stack names the sample - a
 * pathfinding call made from inside a unit's update counts as pathfinding, not
 * as unit work.
 *
 * <p>Two deliberate approximations. {@code mindustry.gen} holds generated
 * entity classes with no package to tell them apart, so {@code Building} and
 * {@code Bullet} are matched by name and everything else there is treated as a
 * unit, which is what almost all of them are. And a frame from neither
 * Mindustry nor the plugin is {@link #OTHER} rather than a category of its own:
 * a library hotspot is real but rare, and {@code evictprofile} still names the
 * method.
 */
public final class Subsystems {

    public static final String OTHER = "Other";

    /** The plugin's own code - the category worth looking at first. */
    private static final String PLUGIN = "Evict plugin";

    private Subsystems() {
    }

    /**
     * The subsystem this stack belongs to, or {@link #OTHER} when nothing on it
     * is recognisable.
     */
    public static String classify(StackTraceElement[] stack) {
        for (StackTraceElement frame : stack) {
            String category = category(frame.getClassName());

            if (category != null) {
                return category;
            }
        }

        return OTHER;
    }

    /** The category one class belongs to, or null when it names nothing. */
    private static String category(String className) {
        if (className.startsWith("Extinction.")) {
            return PLUGIN;
        }

        if (!className.startsWith("mindustry.")) {
            return null;
        }

        // Flow fields and path requests: the classic reason a big PvP map
        // loses its tick rate, and the one worth naming on its own.
        if (className.startsWith("mindustry.ai.Pathfinder")
                || className.startsWith("mindustry.ai.ControlPathfinder")) {
            return "Pathfinding";
        }

        if (className.startsWith("mindustry.ai.types.")) {
            return "Unit AI";
        }

        if (className.startsWith("mindustry.ai.BlockIndexer")
                || className.startsWith("mindustry.entities.EntityIndexer")) {
            return "Indexing";
        }

        if (className.startsWith("mindustry.ai.")) {
            return "Unit AI";
        }

        if (className.startsWith("mindustry.world.blocks.power.PowerGraph")
                || className.startsWith("mindustry.world.modules.PowerModule")) {
            return "Power";
        }

        if (className.startsWith("mindustry.logic.")
                || className.startsWith("mindustry.world.blocks.logic.")) {
            return "Logic blocks";
        }

        if (className.startsWith("mindustry.world.")) {
            return "Blocks";
        }

        if (className.startsWith("mindustry.entities.bullet.")) {
            return "Bullets";
        }

        if (className.startsWith("mindustry.entities.units.")
                || className.startsWith("mindustry.entities.comp.")
                || className.startsWith("mindustry.entities.abilities.")
                || className.startsWith("mindustry.type.UnitType")) {
            return "Units";
        }

        if (className.startsWith("mindustry.entities.")) {
            return "Entities";
        }

        if (className.startsWith("mindustry.async.")) {
            return "Physics";
        }

        if (className.startsWith("mindustry.net.")
                || className.startsWith("mindustry.core.NetServer")
                || className.startsWith("mindustry.io.TypeIO")) {
            return "Net sync";
        }

        if (className.startsWith("mindustry.core.World")
                || className.startsWith("mindustry.io.")) {
            return "World";
        }

        if (className.startsWith("mindustry.gen.")) {
            return generated(className);
        }

        return null;
    }

    /**
     * {@code mindustry.gen} is generated code with everything in one package,
     * so the class name is all there is to go on. Building and Bullet are named
     * outright; the rest of what updates every tick from there is units.
     */
    private static String generated(String className) {
        String simpleName = className.substring("mindustry.gen.".length());

        if (simpleName.startsWith("Building")) {
            return "Blocks";
        }

        if (simpleName.startsWith("Bullet")) {
            return "Bullets";
        }

        return "Units";
    }
}
