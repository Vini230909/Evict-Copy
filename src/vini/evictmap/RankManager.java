package vini.evictmap;

import arc.util.Log;
import mindustry.gen.Player;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Persistent tournament ranks keyed by player UUID.
 *
 * Ranks live in a small properties file so the hub can grant them from the
 * console and the on-demand duel workers can read them: {@link DuelServerManager}
 * copies this file into each worker when it spawns. The hub is the only writer;
 * workers only read.
 *
 * A ranked player gets a name tag (e.g. [C] for a commentator) and, for
 * commentator and above, permission to /restart a duel they are spectating.
 */
final class RankManager {

    private static final File RANKS_FILE =
        new File("config/evict-ranks.properties");

    /**
     * Hub admins snapshotted into each duel worker at spawn (see
     * {@link DuelServerManager}). Workers have no access to the hub's own admin
     * list, so this is how an admin is recognised on a duel server. Only present
     * inside a worker's config; on the hub the set stays empty.
     */
    private static final File SYNCED_ADMINS_FILE =
        new File("config/evict-admins.properties");

    private final Map<String, Rank> ranksByUuid = new HashMap<>();
    private final Set<String> syncedAdminUuids = new HashSet<>();

    /** A grantable role. Ordered low-to-high so "commentator and above" works. */
    enum Rank {
        COMMENTATOR("Commentator", "[sky][[C][] ");

        final String title;
        final String namePrefix;

        Rank(String title, String namePrefix) {
            this.title = title;
            this.namePrefix = namePrefix;
        }

        static Rank parse(String value) {
            if (value == null) {
                return null;
            }

            for (Rank rank : values()) {
                if (rank.name().equalsIgnoreCase(value.trim())) {
                    return rank;
                }
            }

            return null;
        }
    }

    void load() {
        ranksByUuid.clear();

        if (RANKS_FILE.exists()) {
            Properties properties = new Properties();

            try (FileInputStream input = new FileInputStream(RANKS_FILE)) {
                properties.load(input);

                for (String uuid : properties.stringPropertyNames()) {
                    Rank rank = Rank.parse(properties.getProperty(uuid));

                    if (rank != null) {
                        ranksByUuid.put(uuid, rank);
                    }
                }
            } catch (Exception exception) {
                Log.err(
                    "[EvictMapGenerator] Could not read the ranks file.",
                    exception
                );
            }
        }

        loadSyncedAdmins();

        Log.info(
            "[EvictMapGenerator] Loaded @ tournament rank(s) and @ synced admin(s).",
            ranksByUuid.size(),
            syncedAdminUuids.size()
        );
    }

    private void loadSyncedAdmins() {
        syncedAdminUuids.clear();

        if (!SYNCED_ADMINS_FILE.exists()) {
            return;
        }

        Properties properties = new Properties();

        try (FileInputStream input = new FileInputStream(SYNCED_ADMINS_FILE)) {
            properties.load(input);
            syncedAdminUuids.addAll(properties.stringPropertyNames());
        } catch (Exception exception) {
            Log.err(
                "[EvictMapGenerator] Could not read the synced admins file.",
                exception
            );
        }
    }

    Rank rankOf(String uuid) {
        return uuid == null ? null : ranksByUuid.get(uuid);
    }

    /** Snapshot of the granted ranks for listing. */
    Map<String, Rank> snapshot() {
        return new HashMap<>(ranksByUuid);
    }

    boolean grant(String uuid, Rank rank) {
        if (uuid == null || uuid.isBlank() || rank == null) {
            return false;
        }

        ranksByUuid.put(uuid.trim(), rank);
        save();
        return true;
    }

    boolean revoke(String uuid) {
        if (uuid == null || ranksByUuid.remove(uuid.trim()) == null) {
            return false;
        }

        save();
        return true;
    }

    /** Commentator and above, with server admins always allowed. */
    boolean canRestartMatches(Player player) {
        return player != null
            && (player.admin || rankOf(player.uuid()) != null);
    }

    boolean isSyncedAdmin(String uuid) {
        return uuid != null && syncedAdminUuids.contains(uuid);
    }

    /**
     * Worker-side only: grants live admin to a player whose UUID the hub synced
     * into this worker, so hub admins keep their powers (and /restart) on a duel
     * server. A no-op on the hub, where nothing is synced.
     */
    void markSyncedAdmin(Player player) {
        if (player != null && !player.admin && isSyncedAdmin(player.uuid())) {
            player.admin = true;
        }
    }

    /**
     * Sets the player's name tag to match their rank (or removes a stale tag if
     * they have none). Names reset to the client's choice on reconnect, so this
     * is re-applied on every join.
     */
    void applyNameTag(Player player) {
        if (player == null) {
            return;
        }

        Rank rank = rankOf(player.uuid());
        String base = stripKnownTag(player.name);
        String desired = rank == null ? base : rank.namePrefix + base;

        if (!desired.equals(player.name)) {
            player.name = desired;
        }
    }

    private static String stripKnownTag(String name) {
        if (name == null) {
            return "";
        }

        for (Rank rank : Rank.values()) {
            if (name.startsWith(rank.namePrefix)) {
                return name.substring(rank.namePrefix.length());
            }
        }

        return name;
    }

    private void save() {
        Properties properties = new Properties();

        for (Map.Entry<String, Rank> entry : ranksByUuid.entrySet()) {
            properties.setProperty(entry.getKey(), entry.getValue().name());
        }

        File parent = RANKS_FILE.getParentFile();

        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            Log.err(
                "[EvictMapGenerator] Could not create the config directory for ranks."
            );
            return;
        }

        try (FileOutputStream output = new FileOutputStream(RANKS_FILE)) {
            properties.store(output, "Evict tournament ranks (uuid = rank)");
        } catch (Exception exception) {
            Log.err("[EvictMapGenerator] Could not write the ranks file.", exception);
        }
    }
}
