// Stores warnings atomically on the hub and carries worker submissions and acknowledgements in durable files.
package Extinction;

import Extinction.core.util.PluginLog;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

final class WarningList {
    record Entry(String id, String uuid, String name, String actor, String server, String reason,
                 long time, boolean accepted) {
        Entry accept() { return new Entry(id, uuid, name, actor, server, reason, time, true); }
    }

    private final boolean hub;
    private final Path list;
    private final Path mailbox;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, Entry> local = new LinkedHashMap<>();

    WarningList(boolean hub) {
        this(hub, Path.of(hub ? "config" : "../../config"));
    }

    WarningList(boolean hub, Path config) {
        this.hub = hub;
        list = config.resolve("evict-warnings.properties");
        mailbox = config.resolve("evict-warning-mailbox");
    }

    Map<String, Entry> entries() {
        Map<String, Entry> result = new LinkedHashMap<>(entries);
        result.putAll(local);
        return result;
    }

    void refresh(Consumer<Entry> logged) {
        if (!hub) {
            Map<String, Entry> loaded = readList();
            if (loaded != null) {
                entries.clear();
                entries.putAll(loaded);
                local.entrySet().removeIf(item -> entries.containsKey(item.getKey())
                        && (!item.getValue().accepted || entries.get(item.getKey()).accepted));
            }
            return;
        }
        if (entries.isEmpty()) {
            Map<String, Entry> loaded = readList();
            if (loaded != null) entries.putAll(loaded);
        }
        scan("warn-", file -> {
            Properties properties = read(file);
            if (properties == null) return;
            Entry entry = decode(properties, "");
            if (entry == null) return;
            if (entries.containsKey(entry.id)) { remove(file); return; }
            entries.put(entry.id, entry);
            if (save()) { logged.accept(entry); remove(file); }
            else entries.remove(entry.id);
        });
        scan("accept-", file -> {
            Properties properties = read(file);
            if (properties == null) return;
            String id = properties.getProperty("id", "");
            String uuid = properties.getProperty("uuid", "");
            Entry entry = entries.get(id);
            if (entry == null || !entry.uuid.equals(uuid)) return;
            if (entry.accepted) { remove(file); return; }
            entries.put(id, entry.accept());
            if (save()) remove(file);
            else entries.put(id, entry);
        });
    }

    boolean add(Entry entry) {
        if (hub) {
            entries.put(entry.id, entry);
            if (save()) return true;
            entries.remove(entry.id);
            return false;
        }
        Properties properties = new Properties();
        encode(properties, "", entry);
        if (!write(mailbox.resolve("warn-" + entry.id + ".properties"), properties)) return false;
        local.put(entry.id, entry);
        return true;
    }

    boolean accept(Entry entry) {
        if (hub) {
            entries.put(entry.id, entry.accept());
            if (save()) return true;
            entries.put(entry.id, entry);
            return false;
        }
        Properties properties = new Properties();
        properties.setProperty("id", entry.id);
        properties.setProperty("uuid", entry.uuid);
        if (!write(mailbox.resolve("accept-" + entry.id + ".properties"), properties)) return false;
        local.put(entry.id, entry.accept());
        return true;
    }

    private Map<String, Entry> readList() {
        if (!Files.exists(list)) return Map.of();
        Properties properties = read(list);
        if (properties == null) return null;
        Map<String, Entry> result = new LinkedHashMap<>();
        for (String id : properties.getProperty("ids", "").split(",")) {
            if (id.isBlank()) continue;
            Entry entry = decode(properties, id + ".");
            if (entry != null) result.put(entry.id, entry);
        }
        return result;
    }

    private boolean save() {
        Properties properties = new Properties();
        properties.setProperty("ids", String.join(",", entries.keySet()));
        entries.forEach((id, entry) -> encode(properties, id + ".", entry));
        return write(list, properties);
    }

    private void scan(String prefix, Consumer<Path> action) {
        if (!Files.isDirectory(mailbox)) return;
        try (Stream<Path> files = Files.list(mailbox)) {
            files.filter(file -> file.getFileName().toString().startsWith(prefix)
                    && file.getFileName().toString().endsWith(".properties"))
                    .sorted().forEach(action);
        } catch (IOException exception) {
            PluginLog.err("Could not scan warning requests: @", exception.getMessage());
        }
    }

    private static void encode(Properties properties, String prefix, Entry entry) {
        properties.setProperty(prefix + "id", entry.id);
        properties.setProperty(prefix + "uuid", entry.uuid);
        properties.setProperty(prefix + "name", entry.name);
        properties.setProperty(prefix + "actor", entry.actor);
        properties.setProperty(prefix + "server", entry.server);
        properties.setProperty(prefix + "reason", entry.reason);
        properties.setProperty(prefix + "time", Long.toString(entry.time));
        properties.setProperty(prefix + "accepted", Boolean.toString(entry.accepted));
    }

    private static Entry decode(Properties properties, String prefix) {
        try {
            String id = properties.getProperty(prefix + "id", "");
            UUID.fromString(id);
            String uuid = properties.getProperty(prefix + "uuid", "");
            String reason = properties.getProperty(prefix + "reason", "").trim();
            if (uuid.isBlank() || reason.isBlank()) return null;
            return new Entry(id, uuid, properties.getProperty(prefix + "name", "unknown"),
                    properties.getProperty(prefix + "actor", "Console"),
                    properties.getProperty(prefix + "server", "hub"), reason,
                    Long.parseLong(properties.getProperty(prefix + "time", "0")),
                    Boolean.parseBoolean(properties.getProperty(prefix + "accepted", "false")));
        } catch (IllegalArgumentException exception) {
            PluginLog.err("Could not read a malformed warning: @", exception.getMessage());
            return null;
        }
    }

    private static Properties read(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            Properties properties = new Properties();
            properties.load(input);
            return properties;
        } catch (IOException | IllegalArgumentException exception) {
            PluginLog.err("Could not read warnings @: @", path, exception.getMessage());
            return null;
        }
    }

    private static boolean write(Path target, Properties properties) {
        Path temp = null;
        try {
            Files.createDirectories(target.toAbsolutePath().getParent());
            temp = Files.createTempFile(target.toAbsolutePath().getParent(), "warnings-", ".tmp");
            try (OutputStream output = Files.newOutputStream(temp)) {
                properties.store(output, "Extinction warnings - hub owns the list; workers send requests and acknowledgements.");
            }
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (IOException notAtomic) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
            return true;
        } catch (IOException exception) {
            PluginLog.err("Could not save warnings @: @", target, exception.getMessage());
            return false;
        } finally {
            if (temp != null) remove(temp);
        }
    }

    private static void remove(Path path) {
        try { Files.deleteIfExists(path); }
        catch (IOException exception) { PluginLog.err("Could not remove warning request @: @", path, exception.getMessage()); }
    }
}
