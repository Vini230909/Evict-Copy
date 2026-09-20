// The one config: every setting is a field here, read from and written to the properties file.
package Extinction;

import Extinction.core.io.PropertiesFile;
import Extinction.core.util.PluginLog;

import java.io.File;
import java.util.Properties;

public final class Config {

    // Shared with the old EvictSettings; both keep every key they do not know.
    private static final File FILE = new File("config/evict-map-generator.properties");
    private static final String COMMENT = "EvictMapGenerator persistent settings";

    private static final String DEFAULT_DUEL_WORKER_MAP = "evict-map";

    // Whether the banned-word filter bans automatically. Copied into every worker.
    public static boolean wordFilter = true;

    // Match servers: the ip players are sent to (blank = /play off), first port, pool size,
    // the map a worker hosts and the server jar it runs. Workers get the ip blanked.
    public static String duelServerIp = "";
    public static int duelServerPort = 6568;
    public static int duelMaxWorkers = 4;
    public static String duelWorkerMap = DEFAULT_DUEL_WORKER_MAP;
    public static String duelWorkerJar = "server-release.jar";

    private Config() {
    }

    // Reads the file, then writes it back so a new key appears in it at once.
    public static void load() {
        Properties file = PropertiesFile.load(FILE);

        wordFilter = PropertiesFile.getBool(file, "moderation.wordFilter", wordFilter);

        duelServerIp = PropertiesFile.getString(file, "duel.server.ip", duelServerIp).trim();
        duelServerPort = range("Duel base port", PropertiesFile.getInt(file, "duel.server.port", duelServerPort), 1, 65535, duelServerPort);
        duelMaxWorkers = range("Duel max workers", PropertiesFile.getInt(file, "duel.maxWorkers", duelMaxWorkers), 1, 10, duelMaxWorkers);
        duelWorkerMap = PropertiesFile.getString(file, "duel.worker.map", duelWorkerMap).trim();
        duelWorkerMap = duelWorkerMap.isBlank() ? DEFAULT_DUEL_WORKER_MAP : duelWorkerMap;
        duelWorkerJar = PropertiesFile.getString(file, "duel.worker.jar", duelWorkerJar);

        save();
    }

    public static void save() {
        Properties file = PropertiesFile.load(FILE);

        file.setProperty("moderation.wordFilter", Boolean.toString(wordFilter));

        file.setProperty("duel.server.ip", duelServerIp);
        file.setProperty("duel.server.port", Integer.toString(duelServerPort));
        file.setProperty("duel.maxWorkers", Integer.toString(duelMaxWorkers));
        file.setProperty("duel.worker.map", duelWorkerMap);
        file.setProperty("duel.worker.jar", duelWorkerJar);

        PropertiesFile.save(FILE, file, COMMENT);
    }

    // An out-of-range value is refused with the same message as before; the field keeps its value.
    private static int range(String name, int value, int minimum, int maximum, int fallback) {
        if (value < minimum || value > maximum) {
            PluginLog.err("@ must be between @ and @.", name, minimum, maximum);
            return fallback;
        }

        return value;
    }
}
