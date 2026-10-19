// Durable hub mute state and atomic worker requests, shared through files without any worker database writes.
package Extinction;

import Extinction.core.util.PluginLog;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

final class MuteFiles {
    record Request(Path file, String uuid, String name, String actor, String server, boolean muted) { }
    final Path state;
    private final Path requests;
    private long serial;

    MuteFiles(Path config) {
        state = config.resolve("evict-mutes.txt");
        requests = config.resolve("evict-mute-requests");
    }

    Map<String, String> read() {
        Properties values = load(state);
        if (values == null) return null;
        Map<String, String> muted = new LinkedHashMap<>();
        values.stringPropertyNames().stream().sorted().forEach(uuid -> muted.put(uuid, values.getProperty(uuid)));
        return muted;
    }

    boolean save(Map<String, String> muted) {
        Properties values = new Properties();
        values.putAll(muted);
        return write(state, values);
    }

    Request request(String uuid, String name, String actor, String server, boolean muted) {
        String filename = String.format("%019d-%08d-%s.request", System.currentTimeMillis(), serial++, UUID.randomUUID());
        Request request = new Request(requests.resolve(filename), uuid, name, actor, server, muted);
        Properties values = new Properties();
        values.setProperty("uuid", uuid);
        values.setProperty("name", name);
        values.setProperty("actor", actor);
        values.setProperty("server", server);
        values.setProperty("muted", Boolean.toString(muted));
        return write(request.file, values) ? request : null;
    }

    List<Request> requests() {
        List<Request> found = new ArrayList<>();
        if (!Files.isDirectory(requests)) return found;
        try (var paths = Files.list(requests)) {
            for (Path file : paths.filter(path -> path.getFileName().toString().endsWith(".request"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString())).toList()) {
                Properties values = load(file);
                if (values == null || values.getProperty("uuid", "").isBlank()) continue;
                found.add(new Request(file, values.getProperty("uuid"), values.getProperty("name", ""),
                        values.getProperty("actor", "Console"), values.getProperty("server", "hub"),
                        Boolean.parseBoolean(values.getProperty("muted"))));
            }
        } catch (IOException exception) {
            PluginLog.err("Could not read mute requests: @", exception.getMessage());
        }
        return found;
    }

    boolean acknowledge(Request request) {
        try {
            Files.deleteIfExists(request.file);
            return true;
        } catch (IOException exception) {
            PluginLog.err("Could not acknowledge mute request: @", exception.getMessage());
            return false;
        }
    }

    private Properties load(Path file) {
        Properties values = new Properties();
        if (!Files.exists(file)) return values;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            values.load(reader);
            return values;
        } catch (IOException | IllegalArgumentException exception) {
            PluginLog.err("Could not read mute file @: @", file, exception.getMessage());
            return null;
        }
    }

    private boolean write(Path file, Properties values) {
        Path temporary = null;
        try {
            Path parent = file.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, "evict-mute-", ".tmp");
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                values.store(writer, "Extinction mute state - the hub owns the list; workers publish requests.");
            }
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException unavailable) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException exception) {
            PluginLog.err("Could not write mute file @: @", file, exception.getMessage());
            return false;
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
        }
    }
}
