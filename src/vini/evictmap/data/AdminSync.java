package vini.evictmap.data;

import arc.util.Log;
import mindustry.gen.Player;
import vini.evictmap.duel.DuelServerManager;

import java.io.File;
import java.io.FileInputStream;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

/**
 * Worker-side admin recognition. A spawned duel worker has no access to the
 * hub's own admin list, so {@link DuelServerManager} writes the hub admins into
 * the worker's config at spawn and this reads them back: a hub admin joining a
 * match server keeps their powers there.
 * The hub is the only writer; a worker only reads. On the hub the set stays
 * empty and every method here is a no-op.
 */
public final class AdminSync {

    private static final File SYNCED_ADMINS_FILE =
            new File("config/evict-admins.properties");

    private final Set<String> syncedAdminUuids = new HashSet<>();

    public void load() {
        syncedAdminUuids.clear();

        if (SYNCED_ADMINS_FILE.exists()) {
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

        Log.info(
                "[EvictMapGenerator] Loaded @ synced admin(s).",
                syncedAdminUuids.size()
        );
    }

    boolean isSyncedAdmin(String uuid) {
        return uuid != null && syncedAdminUuids.contains(uuid);
    }

    /**
     * Worker-side only: grants live admin to a player whose UUID the hub synced
     * into this worker. A no-op on the hub, where nothing is synced.
     */
    public void markSyncedAdmin(Player player) {
        if (player != null && !player.admin && isSyncedAdmin(player.uuid())) {
            player.admin = true;
        }
    }
}
