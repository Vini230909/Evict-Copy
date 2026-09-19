// The one config: every setting is a field here, read from and written to the properties file.
package Extinction;

import Extinction.core.io.PropertiesFile;

import java.io.File;
import java.util.Properties;

public final class Config {

    // Shared with the old EvictSettings; both keep every key they do not know.
    private static final File FILE = new File("config/evict-map-generator.properties");
    private static final String COMMENT = "EvictMapGenerator persistent settings";

    // Whether the banned-word filter bans automatically. Copied into every worker.
    public static boolean wordFilter = true;

    private Config() {
    }

    // Reads the file, then writes it back so a new key appears in it at once.
    public static void load() {
        Properties file = PropertiesFile.load(FILE);

        wordFilter = PropertiesFile.getBool(file, "moderation.wordFilter", wordFilter);

        save();
    }

    public static void save() {
        Properties file = PropertiesFile.load(FILE);

        file.setProperty("moderation.wordFilter", Boolean.toString(wordFilter));

        PropertiesFile.save(FILE, file, COMMENT);
    }
}
