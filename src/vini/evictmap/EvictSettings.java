package vini.evictmap;

import arc.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Properties;

/**
 * Persistent Evict server tuning values.
 *
 * Stored relative to the server working directory so values survive terminal
 * closes, full Java restarts and normal plugin updates.
 */
final class EvictSettings {

    enum OreKind {
        COPPER("copper", 29.94d, 0.82d, 3.10d, 0.13d),
        LEAD("lead", 27.44d, 0.83d, 3.10d, 0.16d),
        COAL("coal", 24.95d, 0.83d, 1.71d, 0.20d),
        TITANIUM("titanium", 27.44d, 0.86d, 1.98d, 0.12d),
        THORIUM("thorium", 29.94d, 0.88d, 2.20d, 0.14d),
        SCRAP("scrap", 24.95d, 0.83d, 2.34d, 0.17d);

        final String key;
        final double defaultScale;
        final double defaultThreshold;
        final double defaultOctaves;
        final double defaultFalloff;

        OreKind(
            String key,
            double defaultScale,
            double defaultThreshold,
            double defaultOctaves,
            double defaultFalloff
        ) {
            this.key = key;
            this.defaultScale = defaultScale;
            this.defaultThreshold = defaultThreshold;
            this.defaultOctaves = defaultOctaves;
            this.defaultFalloff = defaultFalloff;
        }
    }

    record OreSettings(
        double scale,
        double threshold,
        double octaves,
        double falloff
    ) {
    }

    record WaterSettings(
        double patchAttemptsPerHex,
        int normalPatchTiles,
        double largePatchChancePercent,
        int largePatchTiles
    ) {
    }

    private static final File SETTINGS_FILE =
        new File("config/evict-map-generator.properties");
    private static final int DEFAULT_EXTINCTION_TERRAIN_CHANGES_PER_TICK = 120;
    private static final int MIN_EXTINCTION_TERRAIN_CHANGES_PER_TICK = 1;
    private static final int MAX_EXTINCTION_TERRAIN_CHANGES_PER_TICK = 4096;
    private static final double DEFAULT_WATER_PATCH_ATTEMPTS_PER_HEX = 1d;
    private static final int DEFAULT_WATER_NORMAL_PATCH_TILES = 3;
    private static final double DEFAULT_WATER_LARGE_PATCH_CHANCE_PERCENT =
        13.33d;
    private static final int DEFAULT_WATER_LARGE_PATCH_TILES = 8;
    private static final double MAX_WATER_PATCH_ATTEMPTS_PER_HEX = 5d;
    private static final int MIN_WATER_PATCH_TILES = 1;
    private static final int MAX_WATER_PATCH_TILES = 64;
    private static final int DEFAULT_DUEL_SERVER_PORT = 6568;
    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65535;
    private static final int DEFAULT_DUEL_MAX_WORKERS = 4;
    private static final int MIN_DUEL_WORKERS = 1;
    private static final int MAX_DUEL_WORKERS = 10;
    private static final String DEFAULT_DUEL_WORKER_JAR = "server-release.jar";
    private static final String DEFAULT_DUEL_WORKER_MAP = "evict-map";

    /**
     * Capture attrition keeps the tier-based percentages.
     * Range attrition is intentionally one flat percentage for every unit.
     */
    private double coreAttritionTier1To3Percent = 40d;
    private double coreAttritionTier4Percent = 18d;
    private double coreAttritionTier5Percent = 9d;
    private double rangeAttritionPercent = 20d;

    private double fullWallPercent = 25d;
    private double smallWallPercent = 25d;
    private double openPercent = 25d;
    private double passagePercent = 25d;
    private int extinctionTerrainChangesPerTick =
        DEFAULT_EXTINCTION_TERRAIN_CHANGES_PER_TICK;

    /**
     * Dedicated 1v1 server that /play redirects to. A blank IP means the duel
     * feature is not configured yet, so /play stays inert instead of sending
     * players nowhere.
     */
    private String duelServerIp = "";
    private int duelServerPort = DEFAULT_DUEL_SERVER_PORT;
    private int duelMaxWorkers = DEFAULT_DUEL_MAX_WORKERS;
    private String duelWorkerJarName = DEFAULT_DUEL_WORKER_JAR;
    private String duelWorkerMap = DEFAULT_DUEL_WORKER_MAP;
    private WaterSettings waterSettings =
        new WaterSettings(
            DEFAULT_WATER_PATCH_ATTEMPTS_PER_HEX,
            DEFAULT_WATER_NORMAL_PATCH_TILES,
            DEFAULT_WATER_LARGE_PATCH_CHANCE_PERCENT,
            DEFAULT_WATER_LARGE_PATCH_TILES
        );

    private final EnumMap<OreKind, OreSettings> oreSettings =
        new EnumMap<>(OreKind.class);

    EvictSettings() {
        for (OreKind kind : OreKind.values()) {
            oreSettings.put(
                kind,
                new OreSettings(
                    kind.defaultScale,
                    kind.defaultThreshold,
                    kind.defaultOctaves,
                    kind.defaultFalloff
                )
            );
        }
    }

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

            setCoreAttritionPercentagesWithoutSaving(
                readDouble(
                    properties,
                    "attrition.core.tier1To3Percent",
                    readDouble(
                        properties,
                        "attrition.tier1To3Percent",
                        coreAttritionTier1To3Percent
                    )
                ),
                readDouble(
                    properties,
                    "attrition.core.tier4Percent",
                    readDouble(
                        properties,
                        "attrition.tier4Percent",
                        coreAttritionTier4Percent
                    )
                ),
                readDouble(
                    properties,
                    "attrition.core.tier5Percent",
                    readDouble(
                        properties,
                        "attrition.tier5Percent",
                        coreAttritionTier5Percent
                    )
                )
            );

            setRangeAttritionPercentWithoutSaving(
                readDouble(
                    properties,
                    "attrition.range.percent",
                    rangeAttritionPercent
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

            setExtinctionTerrainChangesPerTickWithoutSaving(
                readInt(
                    properties,
                    "extinction.terrainChangesPerTick",
                    extinctionTerrainChangesPerTick
                )
            );

            setWaterSettingsWithoutSaving(
                readDouble(
                    properties,
                    "water.patchAttemptsPerHex",
                    legacyWaterPatchAttemptsPerHex(properties)
                ),
                readInt(
                    properties,
                    "water.normalPatchTiles",
                    waterSettings.normalPatchTiles()
                ),
                readDouble(
                    properties,
                    "water.largePatchChancePercent",
                    legacyLargePatchChancePercent(properties)
                ),
                readInt(
                    properties,
                    "water.largePatchTiles",
                    waterSettings.largePatchTiles()
                )
            );

            for (OreKind kind : OreKind.values()) {
                OreSettings current = ore(kind);

                setOreSettingsWithoutSaving(
                    kind,
                    readDouble(
                        properties,
                        oreProperty(kind, "scale"),
                        current.scale()
                    ),
                    readDouble(
                        properties,
                        oreProperty(kind, "threshold"),
                        current.threshold()
                    ),
                    readDouble(
                        properties,
                        oreProperty(kind, "octaves"),
                        current.octaves()
                    ),
                    readDouble(
                        properties,
                        oreProperty(kind, "falloff"),
                        current.falloff()
                    )
                );
            }

            setDuelServerWithoutSaving(
                readString(properties, "duel.server.ip", duelServerIp),
                readInt(properties, "duel.server.port", duelServerPort),
                readInt(properties, "duel.maxWorkers", duelMaxWorkers),
                readString(properties, "duel.worker.map", duelWorkerMap)
            );
            duelWorkerJarName =
                readString(properties, "duel.worker.jar", duelWorkerJarName);

            // Backfill newly introduced properties after plugin upgrades.
            save();

            Log.info(
                "[EvictMapGenerator] Loaded persistent settings: coreAttrition=@; rangeAttrition=@; walls=@; water=@; extinctionTerrain=@; ores=@",
                compactCoreAttritionSettings(),
                compactRangeAttritionSettings(),
                compactWallSettings(),
                compactWaterSettings(),
                compactExtinctionTerrainSettings(),
                compactOreSettings()
            );
        } catch (Exception exception) {
            Log.err(
                "[EvictMapGenerator] Could not load persistent settings. Keeping defaults.",
                exception
            );
        }
    }

    void setCoreAttritionPercentages(
        double tier1To3,
        double tier4,
        double tier5
    ) {
        setCoreAttritionPercentagesWithoutSaving(tier1To3, tier4, tier5);
        save();
    }

    void setRangeAttritionPercent(double percent) {
        setRangeAttritionPercentWithoutSaving(percent);
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

    void setExtinctionTerrainChangesPerTick(int amount) {
        setExtinctionTerrainChangesPerTickWithoutSaving(amount);
        save();
    }

    void setDuelServer(
        String ip,
        int basePort,
        int maxWorkers,
        String map
    ) {
        setDuelServerWithoutSaving(ip, basePort, maxWorkers, map);
        save();
    }

    String duelServerIp() {
        return duelServerIp;
    }

    int duelServerPort() {
        return duelServerPort;
    }

    int duelMaxWorkers() {
        return duelMaxWorkers;
    }

    String duelWorkerJarName() {
        return duelWorkerJarName;
    }

    String duelWorkerMap() {
        return duelWorkerMap;
    }

    boolean duelServerConfigured() {
        return duelServerIp != null && !duelServerIp.isBlank();
    }

    String compactDuelServerSettings() {
        if (!duelServerConfigured()) {
            return "not set";
        }

        int lastPort = duelServerPort + duelMaxWorkers - 1;

        return duelServerIp
            + " ports " + duelServerPort + "-" + lastPort
            + " (" + duelMaxWorkers + " workers, map=" + duelWorkerMap + ")";
    }

    private void setDuelServerWithoutSaving(
        String ip,
        int basePort,
        int maxWorkers,
        String map
    ) {
        duelServerIp = ip == null ? "" : ip.trim();
        duelServerPort = validateIntRange(
            "Duel base port",
            basePort,
            MIN_PORT,
            MAX_PORT
        );
        duelMaxWorkers = validateIntRange(
            "Duel max workers",
            maxWorkers,
            MIN_DUEL_WORKERS,
            MAX_DUEL_WORKERS
        );
        duelWorkerMap = map == null || map.isBlank()
            ? DEFAULT_DUEL_WORKER_MAP
            : map.trim();
    }

    void setWaterSettings(
        double patchAttemptsPerHex,
        int normalPatchTiles,
        double largePatchChancePercent,
        int largePatchTiles
    ) {
        setWaterSettingsWithoutSaving(
            patchAttemptsPerHex,
            normalPatchTiles,
            largePatchChancePercent,
            largePatchTiles
        );
        save();
    }

    void setOreSettings(
        OreKind kind,
        double scale,
        double threshold,
        double octaves,
        double falloff
    ) {
        setOreSettingsWithoutSaving(
            kind,
            scale,
            threshold,
            octaves,
            falloff
        );
        save();
    }

    OreSettings ore(OreKind kind) {
        return oreSettings.get(kind);
    }

    WaterSettings water() {
        return waterSettings;
    }

    double coreAttritionTier1To3Chance() {
        return coreAttritionTier1To3Percent / 100d;
    }

    double coreAttritionTier4Chance() {
        return coreAttritionTier4Percent / 100d;
    }

    double coreAttritionTier5Chance() {
        return coreAttritionTier5Percent / 100d;
    }

    double rangeAttritionChance() {
        return rangeAttritionPercent / 100d;
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

    int extinctionTerrainChangesPerTick() {
        return extinctionTerrainChangesPerTick;
    }

    String compactCoreAttritionSettings() {
        return "T1-T3=" + formatPercent(coreAttritionTier1To3Percent)
            + "%, T4=" + formatPercent(coreAttritionTier4Percent)
            + "%, T5=" + formatPercent(coreAttritionTier5Percent) + "%";
    }

    String compactRangeAttritionSettings() {
        return formatPercent(rangeAttritionPercent) + "%";
    }

    String compactWallSettings() {
        return "full-wall=" + formatPercent(fullWallPercent)
            + "%, small-wall=" + formatPercent(smallWallPercent)
            + "%, open=" + formatPercent(openPercent)
            + "%, passage=" + formatPercent(passagePercent) + "%";
    }

    String compactExtinctionTerrainSettings() {
        return Integer.toString(extinctionTerrainChangesPerTick);
    }

    String compactWaterSettings() {
        return "tries-per-hex="
            + formatNumber(waterSettings.patchAttemptsPerHex())
            + ", normal="
            + waterSettings.normalPatchTiles()
            + " tiles, large="
            + formatPercent(waterSettings.largePatchChancePercent())
            + "% at "
            + waterSettings.largePatchTiles()
            + " tiles";
    }

    String compactOreSettings() {
        StringBuilder result = new StringBuilder();

        for (OreKind kind : OreKind.values()) {
            if (result.length() > 0) {
                result.append("; ");
            }

            result.append(compactOreSettings(kind));
        }

        return result.toString();
    }

    String compactOreSettings(OreKind kind) {
        OreSettings ore = ore(kind);

        return kind.key
            + "(scale=" + formatNumber(ore.scale())
            + ", threshold=" + formatNumber(ore.threshold())
            + ", octaves=" + formatNumber(ore.octaves())
            + ", falloff=" + formatNumber(ore.falloff())
            + ")";
    }

    private void setCoreAttritionPercentagesWithoutSaving(
        double tier1To3,
        double tier4,
        double tier5
    ) {
        coreAttritionTier1To3Percent =
            validatePercentage("T1-T3 core attrition", tier1To3);
        coreAttritionTier4Percent =
            validatePercentage("T4 core attrition", tier4);
        coreAttritionTier5Percent =
            validatePercentage("T5 core attrition", tier5);
    }

    private void setRangeAttritionPercentWithoutSaving(double percent) {
        rangeAttritionPercent =
            validatePercentage("Range attrition", percent);
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

    private void setExtinctionTerrainChangesPerTickWithoutSaving(int amount) {
        if (
            amount < MIN_EXTINCTION_TERRAIN_CHANGES_PER_TICK
                || amount > MAX_EXTINCTION_TERRAIN_CHANGES_PER_TICK
        ) {
            throw new IllegalArgumentException(
                "Extinction terrain changes per tick must be between "
                    + MIN_EXTINCTION_TERRAIN_CHANGES_PER_TICK
                    + " and "
                    + MAX_EXTINCTION_TERRAIN_CHANGES_PER_TICK
                    + "."
            );
        }

        extinctionTerrainChangesPerTick = amount;
    }

    private void setWaterSettingsWithoutSaving(
        double patchAttemptsPerHex,
        int normalPatchTiles,
        double largePatchChancePercent,
        int largePatchTiles
    ) {
        patchAttemptsPerHex = validateRange(
            "Water patch tries per hex",
            patchAttemptsPerHex,
            0d,
            MAX_WATER_PATCH_ATTEMPTS_PER_HEX
        );
        normalPatchTiles = validateIntRange(
            "Water normal patch tiles",
            normalPatchTiles,
            MIN_WATER_PATCH_TILES,
            MAX_WATER_PATCH_TILES
        );
        largePatchChancePercent = validatePercentage(
            "Water large patch chance",
            largePatchChancePercent
        );
        largePatchTiles = validateIntRange(
            "Water large patch tiles",
            largePatchTiles,
            MIN_WATER_PATCH_TILES,
            MAX_WATER_PATCH_TILES
        );

        waterSettings = new WaterSettings(
            patchAttemptsPerHex,
            normalPatchTiles,
            largePatchChancePercent,
            largePatchTiles
        );
    }

    private void setOreSettingsWithoutSaving(
        OreKind kind,
        double scale,
        double threshold,
        double octaves,
        double falloff
    ) {
        if (kind == null) {
            throw new IllegalArgumentException("Ore kind is required.");
        }

        scale = validatePositiveFinite(kind.key + " scale", scale);
        threshold = validateRange(kind.key + " threshold", threshold, 0d, 1d);
        octaves = validatePositiveFinite(kind.key + " octaves", octaves);
        falloff = validateRange(kind.key + " falloff", falloff, 0d, 1d);

        oreSettings.put(
            kind,
            new OreSettings(scale, threshold, octaves, falloff)
        );
    }

    private double validatePositiveFinite(String name, double value) {
        if (
            Double.isNaN(value)
                || Double.isInfinite(value)
                || value <= 0d
        ) {
            throw new IllegalArgumentException(name + " must be greater than 0.");
        }

        return value;
    }

    private double validateRange(
        String name,
        double value,
        double minimum,
        double maximum
    ) {
        if (
            Double.isNaN(value)
                || Double.isInfinite(value)
                || value < minimum
                || value > maximum
        ) {
            throw new IllegalArgumentException(
                name + " must be between " + minimum + " and " + maximum + "."
            );
        }

        return value;
    }

    private int validateIntRange(
        String name,
        int value,
        int minimum,
        int maximum
    ) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                name + " must be between " + minimum + " and " + maximum + "."
            );
        }

        return value;
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

    private int readInt(
        Properties properties,
        String key,
        int fallback
    ) {
        String value = properties.getProperty(key);

        if (value == null || value.isBlank()) {
            return fallback;
        }

        return Integer.parseInt(value.trim());
    }

    private String readString(
        Properties properties,
        String key,
        String fallback
    ) {
        String value = properties.getProperty(key);

        return value == null ? fallback : value.trim();
    }

    private double legacyWaterPatchAttemptsPerHex(Properties properties) {
        String legacyPercent = properties.getProperty(
            "water.patchAttemptsPercentPerHex"
        );

        if (legacyPercent == null || legacyPercent.isBlank()) {
            legacyPercent = properties.getProperty("water.patchAttemptsPercent");
        }

        if (legacyPercent == null || legacyPercent.isBlank()) {
            return waterSettings.patchAttemptsPerHex();
        }

        return Double.parseDouble(legacyPercent.trim()) / 100d;
    }

    private double legacyLargePatchChancePercent(Properties properties) {
        String largeValue = properties.getProperty("water.largePatchWeight");

        if (largeValue == null || largeValue.isBlank()) {
            return waterSettings.largePatchChancePercent();
        }

        double smallWeight = readDouble(
            properties,
            "water.smallPatchWeight",
            0d
        );
        double mediumWeight = readDouble(
            properties,
            "water.mediumPatchWeight",
            0d
        );
        double largeWeight = Double.parseDouble(largeValue.trim());
        double totalWeight = smallWeight + mediumWeight + largeWeight;

        if (totalWeight <= 0d) {
            return waterSettings.largePatchChancePercent();
        }

        return largeWeight * 100d / totalWeight;
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
            "attrition.core.tier1To3Percent",
            Double.toString(coreAttritionTier1To3Percent)
        );
        properties.setProperty(
            "attrition.core.tier4Percent",
            Double.toString(coreAttritionTier4Percent)
        );
        properties.setProperty(
            "attrition.core.tier5Percent",
            Double.toString(coreAttritionTier5Percent)
        );
        properties.setProperty(
            "attrition.range.percent",
            Double.toString(rangeAttritionPercent)
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
        properties.setProperty(
            "extinction.terrainChangesPerTick",
            Integer.toString(extinctionTerrainChangesPerTick)
        );
        properties.setProperty(
            "duel.server.ip",
            duelServerIp == null ? "" : duelServerIp
        );
        properties.setProperty(
            "duel.server.port",
            Integer.toString(duelServerPort)
        );
        properties.setProperty(
            "duel.maxWorkers",
            Integer.toString(duelMaxWorkers)
        );
        properties.setProperty("duel.worker.map", duelWorkerMap);
        properties.setProperty("duel.worker.jar", duelWorkerJarName);
        properties.setProperty(
            "water.patchAttemptsPerHex",
            Double.toString(waterSettings.patchAttemptsPerHex())
        );
        properties.setProperty(
            "water.normalPatchTiles",
            Integer.toString(waterSettings.normalPatchTiles())
        );
        properties.setProperty(
            "water.largePatchChancePercent",
            Double.toString(waterSettings.largePatchChancePercent())
        );
        properties.setProperty(
            "water.largePatchTiles",
            Integer.toString(waterSettings.largePatchTiles())
        );

        for (OreKind kind : OreKind.values()) {
            OreSettings ore = ore(kind);

            properties.setProperty(
                oreProperty(kind, "scale"),
                Double.toString(ore.scale())
            );
            properties.setProperty(
                oreProperty(kind, "threshold"),
                Double.toString(ore.threshold())
            );
            properties.setProperty(
                oreProperty(kind, "octaves"),
                Double.toString(ore.octaves())
            );
            properties.setProperty(
                oreProperty(kind, "falloff"),
                Double.toString(ore.falloff())
            );
        }

        try (FileOutputStream output = new FileOutputStream(SETTINGS_FILE)) {
            properties.store(output, "EvictMapGenerator persistent settings");
        } catch (IOException exception) {
            Log.err(
                "[EvictMapGenerator] Could not save persistent settings.",
                exception
            );
        }
    }

    private String oreProperty(OreKind kind, String field) {
        return "ore." + kind.key + "." + field;
    }

    private String formatPercent(double value) {
        return formatNumber(value);
    }

    private String formatNumber(double value) {
        if (Math.rint(value) == value) {
            return Long.toString(Math.round(value));
        }

        return Double.toString(value);
    }
}
