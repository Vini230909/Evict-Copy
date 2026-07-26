package vini.evictmap.moderation;

import vini.evictmap.core.util.PluginLog;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The hub's ban list as a file the duel workers can read.
 *
 * <p>A worker is its own Mindustry process with its own {@code config/}, so it
 * knows nothing about the hub's bans - a banned player who has the worker's
 * port can walk straight into a match. The hub therefore writes every ban to
 * {@code config/evict-bans.txt} and the workers, which live two directories
 * below it, read it back and apply it locally. Same route the workers already
 * use for the player database.
 *
 * <p>Plain lines rather than a properties file on purpose: Mindustry UUIDs are
 * base64 and end in {@code ==}, which is exactly the character a properties key
 * would have to escape.
 *
 * <pre>
 * uuid AAAAAAAAAAAAAAAAAAAAAA==
 * ip 203.0.113.7
 * </pre>
 */
public final class BanList {

    /** Where the hub keeps it. */
    public static final File HUB_FILE = new File("config/evict-bans.txt");

    /**
     * Where a worker finds the hub's copy. Workers run in
     * {@code duel-workers/duel-<port>/}, so the hub config is two levels up.
     */
    public static final File WORKER_VIEW_FILE =
            new File("../../config/evict-bans.txt");

    private static final String UUID_PREFIX = "uuid ";
    private static final String IP_PREFIX = "ip ";

    private BanList() {
    }

    /** A ban list read off disk. */
    public record Snapshot(Set<String> uuids, Set<String> ips) {

        public static Snapshot empty() {
            return new Snapshot(Set.of(), Set.of());
        }

        public boolean isEmpty() {
            return uuids.isEmpty() && ips.isEmpty();
        }
    }

    /**
     * Rewrites the file from the given sets. Written to a temporary file and
     * moved into place, so a worker polling the file mid-write can never read a
     * half-finished list and unban half the server.
     */
    public static void write(File file, Set<String> uuids, Set<String> ips) {
        File parent = file.getParentFile();

        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            PluginLog.err("Could not create @ for the ban list.", parent.getPath());
            return;
        }

        Path target = file.toPath();
        Path temporary = target.resolveSibling(file.getName() + ".tmp");

        try {
            try (BufferedWriter writer = Files.newBufferedWriter(
                    temporary, StandardCharsets.UTF_8
            )) {
                writer.write("# Evict bans, written by the hub. Do not edit by hand.");
                writer.newLine();

                for (String uuid : uuids) {
                    writer.write(UUID_PREFIX + uuid);
                    writer.newLine();
                }

                for (String ip : ips) {
                    writer.write(IP_PREFIX + ip);
                    writer.newLine();
                }
            }

            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (IOException exception) {
            PluginLog.err(
                    "Could not write the ban list @: @",
                    file.getPath(),
                    exception.getMessage()
            );
        }
    }

    /** Reads the list; a missing or unreadable file simply reads as empty. */
    public static Snapshot read(File file) {
        if (!file.exists()) {
            return Snapshot.empty();
        }

        Set<String> uuids = new LinkedHashSet<>();
        Set<String> ips = new LinkedHashSet<>();

        try {
            List<String> lines = Files.readAllLines(
                    file.toPath(),
                    StandardCharsets.UTF_8
            );

            for (String raw : lines) {
                String line = raw.trim();

                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                if (line.startsWith(UUID_PREFIX)) {
                    add(uuids, line.substring(UUID_PREFIX.length()));
                } else if (line.startsWith(IP_PREFIX)) {
                    add(ips, line.substring(IP_PREFIX.length()));
                }
            }
        } catch (IOException exception) {
            PluginLog.err(
                    "Could not read the ban list @: @",
                    file.getPath(),
                    exception.getMessage()
            );
            return Snapshot.empty();
        }

        return new Snapshot(uuids, ips);
    }

    private static void add(Set<String> target, String value) {
        String trimmed = value.trim();

        if (!trimmed.isEmpty()) {
            target.add(trimmed);
        }
    }
}
