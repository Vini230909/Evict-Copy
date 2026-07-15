package vini.evictmap.core.io;

import vini.evictmap.core.util.PluginLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

/**
 * Loading and saving {@link Properties} files with the mkdirs + error-logging
 * dance done once.
 *
 * <p>The handshake ({@code duel.properties}), result ({@code result.properties})
 * and status ({@code status.properties}) files, plus the settings file, all
 * repeated this boilerplate by hand across several classes. Centralising it also
 * gives typed getters that fall back to a default instead of throwing on a
 * missing or malformed value.
 */
public final class PropertiesFile {

    private PropertiesFile() {
    }

    /** Loads a properties file, returning an empty set on a missing file/error. */
    public static Properties load(File file) {
        Properties properties = new Properties();
        if (file == null || !file.exists()) {
            return properties;
        }
        try (InputStream in = new FileInputStream(file)) {
            properties.load(in);
        } catch (IOException exception) {
            PluginLog.err("Could not read @: @", file.getPath(), exception.getMessage());
        }
        return properties;
    }

    /** Writes a properties file, creating parent directories. Returns success. */
    public static boolean save(File file, Properties properties, String comment) {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            PluginLog.err("Could not create directory: @", parent.getPath());
            return false;
        }
        try (OutputStream out = new FileOutputStream(file)) {
            properties.store(out, comment);
            return true;
        } catch (IOException exception) {
            PluginLog.err("Could not write @: @", file.getPath(), exception.getMessage());
            return false;
        }
    }

    public static String getString(Properties p, String key, String fallback) {
        String value = p.getProperty(key);
        return value == null ? fallback : value;
    }

    public static int getInt(Properties p, String key, int fallback) {
        try {
            String value = p.getProperty(key);
            return value == null ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    public static long getLong(Properties p, String key, long fallback) {
        try {
            String value = p.getProperty(key);
            return value == null ? fallback : Long.parseLong(value.trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    public static double getDouble(Properties p, String key, double fallback) {
        try {
            String value = p.getProperty(key);
            return value == null ? fallback : Double.parseDouble(value.trim().replace(',', '.'));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    public static boolean getBool(Properties p, String key, boolean fallback) {
        String value = p.getProperty(key);
        if (value == null) {
            return fallback;
        }
        return value.trim().equalsIgnoreCase("true");
    }
}
