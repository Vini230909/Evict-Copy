package vini.evictmap;

import arc.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * Persistent Evict server tuning values.
 *
 * Stored relative to the server working directory so values survive terminal
 * closes, full Java restarts and normal plugin updates.
 */
final class EvictSettings {

    private static final File SETTINGS_FILE =
        new File("config/evict-map-generator.properties");

    private double attritionTier1To3Percent = 40d;
    private double attritionTier4Percent = 18d;
    private double attritionTier5Percent = 9d;

    private double fullWallPercent = 25d;
    private double smallWallPercent = 25d;
    private double openPercent = 25d;
    private double passagePercent = 25d;

    void load() {
        if (!SETTINGS_FILE.exists()) {
            save();
            Log.info(
                "[EvictMapGenerator] Created persistent settings file: @",
                SETTINGS_FILE.getPath()
            );
            return;
        }

        Properties properties = new Properties();

        try (FileInputStream input = new FileInputStream(SETTINGS_FILE)) {
            properties.load(input);

            setAttritionPercentagesWithoutSaving(
                readDouble(
                    properties,
                    "attrition.tier1To3Percent",
                    attritionTier1To3Percent
                ),
                readDouble(
                    properties,
                    "attrition.tier4Percent",
                    attritionTier4Percent
                ),
                readDouble(
                    properties,
                    "attrition.tier5Percent",
                    attritionTier5Percent
                )
            );

            setWallPercentagesWithoutSaving(
                readDouble(
                    properties,
                    "wall.fullPercent",
                    fullWallPercent
                ),
                readDouble(
                    properties,
                    "wall.smallPercent",
                    smallWallPercent
                ),
                readDouble(
                    properties,
                    "wall.openPercent",
                    openPercent
                ),
                readDouble(
                    properties,
                    "wall.passagePercent",
                    passagePercent
                )
            );

            Log.info(
                "[EvictMapGenerator] Loaded persistent settings: attrition=@; walls=@",
                compactAttritionSettings(),
                compactWallSettings()
            );
        } catch (Exception exception) {
            Log.err(
                "[EvictMapGenerator] Could not load persistent settings. Keeping defaults.",
                exception
            );
        }
    }

    void setAttritionPercentages(
        double tier1To3,
        double tier4,
        double tier5
    ) {
        setAttritionPercentagesWithoutSaving(tier1To3, tier4, tier5);
        save();
    }

    void setWallPercentages(
        double fullWall,
        double smallWall,
        double open,
        double passage
    ) {
        setWallPercentagesWithoutSaving(
            fullWall,
            smallWall,
            open,
            passage
        );
        save();
    }

    double attritionTier1To3Chance() {
        return attritionTier1To3Percent / 100d;
    }

    double attritionTier4Chance() {
        return attritionTier4Percent / 100d;
    }

    double attritionTier5Chance() {
        return attritionTier5Percent / 100d;
    }

    double fullWallChance() {
        return fullWallPercent / 100d;
    }

    double smallWallChance() {
        return smallWallPercent / 100d;
    }

    double openChance() {
        return openPercent / 100d;
    }

    double passageChance() {
        return passagePercent / 100d;
    }

    String compactAttritionSettings() {
        return "T1-T3=" + formatPercent(attritionTier1To3Percent)
            + "%, T4=" + formatPercent(attritionTier4Percent)
            + "%, T5=" + formatPercent(attritionTier5Percent) + "%";
    }

    String compactWallSettings() {
        return "full-wall=" + formatPercent(fullWallPercent)
            + "%, small-wall=" + formatPercent(smallWallPercent)
            + "%, open=" + formatPercent(openPercent)
            + "%, passage=" + formatPercent(passagePercent) + "%";
    }

    private void setAttritionPercentagesWithoutSaving(
        double tier1To3,
        double tier4,
        double tier5
    ) {
        attritionTier1To3Percent =
            validatePercentage("T1-T3 attrition", tier1To3);
        attritionTier4Percent =
            validatePercentage("T4 attrition", tier4);
        attritionTier5Percent =
            validatePercentage("T5 attrition", tier5);
    }

    private void setWallPercentagesWithoutSaving(
        double fullWall,
        double smallWall,
        double open,
        double passage
    ) {
        fullWall = validatePercentage("full-wall", fullWall);
        smallWall = validatePercentage("small-wall", smallWall);
        open = validatePercentage("open", open);
        passage = validatePercentage("passage", passage);

        double sum = fullWall + smallWall + open + passage;

        if (Math.abs(sum - 100d) > 0.0001d) {
            throw new IllegalArgumentException(
                "Wall percentages must add up to exactly 100."
            );
        }

        fullWallPercent = fullWall;
        smallWallPercent = smallWall;
        openPercent = open;
        passagePercent = passage;
    }

    private double validatePercentage(String name, double value) {
        if (
            Double.isNaN(value)
                || Double.isInfinite(value)
                || value < 0d
                || value > 100d
        ) {
            throw new IllegalArgumentException(
                name + " must be between 0 and 100."
            );
        }

        return value;
    }

    private double readDouble(
        Properties properties,
        String key,
        double fallback
    ) {
        String value = properties.getProperty(key);

        if (value == null || value.isBlank()) {
            return fallback;
        }

        return Double.parseDouble(value.trim());
    }

    private void save() {
        File parent = SETTINGS_FILE.getParentFile();

        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            Log.err(
                "[EvictMapGenerator] Could not create settings directory: @",
                parent.getPath()
            );
            return;
        }

        Properties properties = new Properties();
        properties.setProperty(
            "attrition.tier1To3Percent",
            Double.toString(attritionTier1To3Percent)
        );
        properties.setProperty(
            "attrition.tier4Percent",
            Double.toString(attritionTier4Percent)
        );
        properties.setProperty(
            "attrition.tier5Percent",
            Double.toString(attritionTier5Percent)
        );
        properties.setProperty(
            "wall.fullPercent",
            Double.toString(fullWallPercent)
        );
        properties.setProperty(
            "wall.smallPercent",
            Double.toString(smallWallPercent)
        );
        properties.setProperty(
            "wall.openPercent",
            Double.toString(openPercent)
        );
        properties.setProperty(
            "wall.passagePercent",
            Double.toString(passagePercent)
        );

        try (FileOutputStream output = new FileOutputStream(SETTINGS_FILE)) {
            properties.store(output, "EvictMapGenerator persistent settings");
        } catch (IOException exception) {
            Log.err(
                "[EvictMapGenerator] Could not save persistent settings.",
                exception
            );
        }
    }

    private String formatPercent(double value) {
        if (Math.rint(value) == value) {
            return Long.toString(Math.round(value));
        }

        return Double.toString(value);
    }
}
