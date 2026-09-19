package Extinction.moderation.lock;

import Extinction.moderation.ban.BanList;

import Extinction.core.util.PluginLog;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The hub's locked and verified accounts as a file the duel workers can read.
 *
 * <p>Same arrangement as {@link BanList}: the hub decides, writes the file,
 * and a worker two directories below reads it back so a locked player who
 * {@code /s}-hops into a match is just as locked there. Plain lines rather
 * than properties, for the same reason - a UUID ends in {@code ==}.
 *
 * <pre>
 * locked   &lt;uuid&gt; &lt;since-millis&gt; &lt;ip&gt; &lt;reason&gt; &lt;name&gt;
 * verified &lt;uuid&gt; &lt;since-millis&gt; &lt;name&gt;
 * </pre>
 *
 * <p>Tab-separated; the name comes last because it is the one field that may
 * hold spaces. Tabs and line breaks inside a name are turned into spaces.
 */
public final class LockList {

    /** Where the hub keeps it. */
    public static final File HUB_FILE = new File("config/evict-locks.txt");

    /** Where a worker finds the hub's copy. */
    public static final File WORKER_VIEW_FILE =
            new File("../../config/evict-locks.txt");

    private static final String LOCKED = "locked";
    private static final String VERIFIED = "verified";
    private static final String SEPARATOR = "\t";

    private LockList() {
    }

    /** One locked account: who, from where, since when, and why. */
    public record Entry(
            String uuid,
            String name,
            String ip,
            long sinceMillis,
            String reason
    ) {
    }

    /** One freed account: never locked again. */
    public record Verified(String uuid, String name, long sinceMillis) {
    }

    /** A lock list read off disk. */
    public record Snapshot(Map<String, Entry> locked, Map<String, Verified> verified) {

        public static Snapshot empty() {
            return new Snapshot(Map.of(), Map.of());
        }
    }

    /** Rewrites the whole file through a temp file, so a reader never sees half of it. */
    public static void write(
            File file,
            Collection<Entry> locked,
            Collection<Verified> verified
    ) {
        Path target = file.toPath();
        Path parent = target.toAbsolutePath().getParent();

        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }

            Path temp = Files.createTempFile(parent, "evict-locks", ".tmp");

            try (BufferedWriter out = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                out.write("# Evict lock list - written by the hub, read by the match servers. Do not edit by hand.");
                out.newLine();

                for (Entry entry : locked) {
                    out.write(String.join(
                            SEPARATOR,
                            LOCKED,
                            entry.uuid(),
                            Long.toString(entry.sinceMillis()),
                            clean(entry.ip()),
                            clean(entry.reason()),
                            clean(entry.name())
                    ));
                    out.newLine();
                }

                for (Verified entry : verified) {
                    out.write(String.join(
                            SEPARATOR,
                            VERIFIED,
                            entry.uuid(),
                            Long.toString(entry.sinceMillis()),
                            clean(entry.name())
                    ));
                    out.newLine();
                }
            }

            try {
                Files.move(
                        temp,
                        target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );
            } catch (IOException notAtomic) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            PluginLog.err("Could not write the lock list @: @", file.getPath(), exception.getMessage());
        }
    }

    /** Reads a file; a missing or unreadable one is an empty list. */
    public static Snapshot read(File file) {
        if (file == null || !file.exists()) {
            return Snapshot.empty();
        }

        Map<String, Entry> locked = new LinkedHashMap<>();
        Map<String, Verified> verified = new LinkedHashMap<>();

        List<String> lines;

        try {
            lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            PluginLog.err("Could not read the lock list @: @", file.getPath(), exception.getMessage());
            return Snapshot.empty();
        }

        for (String raw : lines) {
            String line = raw.strip();

            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            String[] parts = line.split(SEPARATOR, -1);

            if (parts[0].equals(LOCKED) && parts.length >= 6) {
                locked.put(parts[1], new Entry(
                        parts[1],
                        parts[5],
                        parts[3],
                        parseLong(parts[2]),
                        parts[4]
                ));
            } else if (parts[0].equals(VERIFIED) && parts.length >= 4) {
                verified.put(parts[1], new Verified(parts[1], parts[3], parseLong(parts[2])));
            }
        }

        return new Snapshot(Map.copyOf(locked), Map.copyOf(verified));
    }

    private static long parseLong(String text) {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }

    private static String clean(String text) {
        return text == null ? "" : text.replaceAll("[\\t\\r\\n]+", " ").trim();
    }
}
