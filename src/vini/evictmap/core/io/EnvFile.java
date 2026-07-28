package vini.evictmap.core.io;

import vini.evictmap.core.util.PluginLog;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * A {@code KEY=VALUE} secrets file in the usual {@code .env} shape, read into
 * memory and never written back.
 *
 * <p>Secrets do not belong in the places this plugin otherwise keeps
 * configuration: a console command writes whatever is typed into the server
 * log (and the start script's screen log) permanently, and the settings
 * properties file is rewritten by the plugin and synced into worker folders.
 * A file the plugin only reads has neither problem, and one file with named
 * keys scales to the next credential without inventing a new place for it.
 *
 * <p>Format: {@code KEY=value} per line, {@code #} comments and blank lines
 * ignored, an optional {@code export } prefix tolerated, surrounding single
 * or double quotes stripped. Keys are matched case-insensitively.
 *
 * <p>The file is created once with a template if it is missing, and never
 * touched again. Deliberately with the same permissions as every other
 * config file the plugin creates: the server is administered from elsewhere
 * (FTP, a panel), and a locked-down file would be one an admin cannot edit.
 * Restricting it is the operator's call, on a box where that makes sense.
 */
public final class EnvFile {

    private final File file;
    private final String templateHeader;

    /** Canonical (upper-case) key to value. Replaced wholesale on reload. */
    private volatile Map<String, String> values = Map.of();

    public EnvFile(File file, String templateHeader) {
        this.file = file;
        this.templateHeader = templateHeader;
    }

    /** Where the file is expected, for console hints and documentation. */
    public String path() {
        return file.getPath();
    }

    /**
     * Re-reads the file. Creates the commented template first if the file is
     * missing, so an admin has something to fill in rather than having to
     * know the key names.
     *
     * @return how many keys were found
     */
    public synchronized int reload() {
        if (!file.exists()) {
            writeTemplate();
            values = Map.of();
            return 0;
        }

        Map<String, String> parsed = new HashMap<>();

        try {
            for (String raw
                    : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                parse(raw, parsed);
            }
        } catch (Exception exception) {
            PluginLog.err(
                    "Could not read @: @",
                    file.getPath(),
                    exception.getMessage()
            );
            return values.size();
        }

        values = Map.copyOf(parsed);
        return values.size();
    }

    /** The value for a key, or {@code ""} when it is absent or empty. */
    public String get(String key) {
        if (key == null) {
            return "";
        }

        return values.getOrDefault(key.toUpperCase(java.util.Locale.ROOT), "");
    }

    /** True when the key holds a non-empty value. */
    public boolean has(String key) {
        return !get(key).isEmpty();
    }

    /** The key names found, for a console listing that never prints values. */
    public Set<String> keys() {
        return new LinkedHashSet<>(values.keySet());
    }

    private static void parse(String raw, Map<String, String> into) {
        String line = stripBom(raw).trim();

        if (line.isEmpty() || line.startsWith("#")) {
            return;
        }

        int separator = line.indexOf('=');

        if (separator <= 0) {
            return;
        }

        String key = line.substring(0, separator).trim();

        if (key.startsWith("export ")) {
            key = key.substring("export ".length()).trim();
        }

        if (key.isEmpty()) {
            return;
        }

        into.put(
                key.toUpperCase(java.util.Locale.ROOT),
                unquote(line.substring(separator + 1).trim())
        );
    }

    /** Strips one matching pair of surrounding quotes, if present. */
    private static String unquote(String value) {
        if (value.length() < 2) {
            return value;
        }

        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);

        if ((first == '"' || first == '\'') && first == last) {
            return value.substring(1, value.length() - 1);
        }

        return value;
    }

    private static String stripBom(String line) {
        return line.startsWith("﻿") ? line.substring(1) : line;
    }

    /** Writes the empty template, so the key names are there to fill in. */
    private void writeTemplate() {
        try {
            File parent = file.getParentFile();

            if (parent != null) {
                Files.createDirectories(parent.toPath());
            }

            Files.writeString(
                    file.toPath(),
                    templateHeader,
                    StandardCharsets.UTF_8
            );

            PluginLog.info(
                    "Created the secrets file @ - fill in what you need, it is only ever read.",
                    file.getPath()
            );
        } catch (Exception exception) {
            PluginLog.err(
                    "Could not create @: @",
                    file.getPath(),
                    exception.getMessage()
            );
        }
    }
}
