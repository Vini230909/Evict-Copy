package vini.evictmap.core.util;

import arc.util.Log;

/**
 * Server-console logging with the plugin's {@code [EvictMapGenerator]} prefix
 * applied once, instead of hand-typing the tag in every {@link Log} call.
 *
 * <p>Uses arc's {@code @} placeholder formatting, exactly like {@link Log}.
 */
public final class PluginLog {

    private static final String PREFIX = "[EvictMapGenerator] ";

    private PluginLog() {
    }

    public static void info(String message, Object... args) {
        Log.info(PREFIX + message, args);
    }

    public static void warn(String message, Object... args) {
        Log.warn(PREFIX + message, args);
    }

    public static void err(String message, Object... args) {
        Log.err(PREFIX + message, args);
    }

    public static void err(String message, Throwable throwable) {
        Log.err(PREFIX + message, throwable);
    }
}
