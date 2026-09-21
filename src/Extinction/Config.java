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

    // Attrition, in percent: once on core capture (per unit tier) and every 5 s far from an owned core.
    public static double attritionCoreTier1To3Percent = 40d;
    public static double attritionCoreTier4Percent = 18d;
    public static double attritionCoreTier5Percent = 9d;
    public static double attritionRangePercent = 20d;

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

        // Older files spelled the core keys without "core"; those still count.
        attritionCoreTier1To3Percent = percent("T1-T3 core attrition",
                PropertiesFile.getDouble(file, "attrition.core.tier1To3Percent",
                        PropertiesFile.getDouble(file, "attrition.tier1To3Percent", attritionCoreTier1To3Percent)),
                attritionCoreTier1To3Percent);
        attritionCoreTier4Percent = percent("T4 core attrition",
                PropertiesFile.getDouble(file, "attrition.core.tier4Percent",
                        PropertiesFile.getDouble(file, "attrition.tier4Percent", attritionCoreTier4Percent)),
                attritionCoreTier4Percent);
        attritionCoreTier5Percent = percent("T5 core attrition",
                PropertiesFile.getDouble(file, "attrition.core.tier5Percent",
                        PropertiesFile.getDouble(file, "attrition.tier5Percent", attritionCoreTier5Percent)),
                attritionCoreTier5Percent);
        attritionRangePercent = percent("Range attrition",
                PropertiesFile.getDouble(file, "attrition.range.percent", attritionRangePercent),
                attritionRangePercent);

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

        file.setProperty("attrition.core.tier1To3Percent", Double.toString(attritionCoreTier1To3Percent));
        file.setProperty("attrition.core.tier4Percent", Double.toString(attritionCoreTier4Percent));
        file.setProperty("attrition.core.tier5Percent", Double.toString(attritionCoreTier5Percent));
        file.setProperty("attrition.range.percent", Double.toString(attritionRangePercent));

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

    private static double percent(String name, double value, double fallback) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value < 0d || value > 100d) {
            PluginLog.err("@ must be between 0 and 100.", name);
            return fallback;
        }

        return value;
    }
}
