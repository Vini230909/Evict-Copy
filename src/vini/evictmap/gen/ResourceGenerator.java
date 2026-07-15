package vini.evictmap.gen;

import vini.evictmap.gen.EvictSettings;

import arc.util.noise.Simplex;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.world.Block;
import mindustry.world.Tile;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;

/**
 * Evict-style resource generator.
 * Main appearance:
 * - coherent noise fields run over the complete map
 * - coordinate warping makes patch borders less round and more irregular
 * - a large-scale richness field creates naturally rich and poor areas
 * - resources may overlap visually and form mixed clusters
 * Fairness corrections remain intentionally tiny:
 * - water uses configurable placement tries per hex
 * - every normal hex receives at least a few tiles of each ore
 * - missing ores are repaired at local noise maxima, not stamped evenly
 */
final class ResourceGenerator {

    // ---------------------------------------------------------------------
    // Independent seed stream
    // ---------------------------------------------------------------------

    private static final long RESOURCE_SEED_XOR = 0x4f52452d45564943L;

    // ---------------------------------------------------------------------
    // Placement masks
    // ---------------------------------------------------------------------

    // Ore may generate anywhere inside the playable hex circle.
    private static final int ORE_MAX_RADIUS = HexGrid.HEX_RADIUS;
    private static final int ORE_MAX_RADIUS_SQUARED =
            ORE_MAX_RADIUS * ORE_MAX_RADIUS;

    private static final int GUARANTEE_ORE_RADIUS = 32;
    private static final int GUARANTEE_ORE_RADIUS_SQUARED =
            GUARANTEE_ORE_RADIUS * GUARANTEE_ORE_RADIUS;

    private static final int ORE_CORE_SAFE_RADIUS = 4;
    private static final int ORE_CORE_SAFE_RADIUS_SQUARED =
            ORE_CORE_SAFE_RADIUS * ORE_CORE_SAFE_RADIUS;

    private static final int LIQUID_MAX_RADIUS = 31;
    private static final int LIQUID_MAX_RADIUS_SQUARED =
            LIQUID_MAX_RADIUS * LIQUID_MAX_RADIUS;

    private static final int LIQUID_CORE_SAFE_RADIUS = 8;
    private static final int LIQUID_CORE_SAFE_RADIUS_SQUARED =
            LIQUID_CORE_SAFE_RADIUS * LIQUID_CORE_SAFE_RADIUS;

    // ---------------------------------------------------------------------
    // Global warped-noise presets
    // ---------------------------------------------------------------------

    /*
     * Patch size remains close to 0.4.2 / 0.4.3.
     * New:
     * - warpStrength distorts otherwise rounded blobs
     * - richnessScale and richnessStrength create large areas that are
     *   naturally resource-rich or resource-poor
     * - small tilts make some fields slightly stretched instead of circular
     */

    /**
     * Ore settings are loaded from persistent server configuration.
     * Tilt, coordinate warp and richness modifiers stay disabled for the
     * editor-style comparison workflow. Water and tar presets remain fixed.
     */
    private static NoisePreset[] createOrePresets(EvictSettings settings) {
        EvictSettings.OreSettings copper =
                settings.ore(EvictSettings.OreKind.COPPER);
        EvictSettings.OreSettings lead =
                settings.ore(EvictSettings.OreKind.LEAD);
        EvictSettings.OreSettings coal =
                settings.ore(EvictSettings.OreKind.COAL);
        EvictSettings.OreSettings titanium =
                settings.ore(EvictSettings.OreKind.TITANIUM);
        EvictSettings.OreSettings thorium =
                settings.ore(EvictSettings.OreKind.THORIUM);
        EvictSettings.OreSettings scrap =
                settings.ore(EvictSettings.OreKind.SCRAP);

        return new NoisePreset[]{
                orePreset(Blocks.oreCopper, 101, copper, 125.0f),
                orePreset(Blocks.oreLead, 211, lead, 120.0f),
                orePreset(Blocks.oreCoal, 307, coal, 110.0f),
                orePreset(Blocks.oreTitanium, 401, titanium, 105.0f),
                orePreset(Blocks.oreThorium, 503, thorium, 105.0f),
                orePreset(Blocks.oreScrap, 353, scrap, 110.0f)
        };
    }

    private static NoisePreset orePreset(
            Block block,
            int seedOffset,
            EvictSettings.OreSettings settings,
            float richnessScale
    ) {
        return new NoisePreset(
                block,
                seedOffset,
                (float) settings.scale(),
                (float) settings.threshold(),
                (float) settings.octaves(),
                (float) settings.falloff(),
                0.0f,
                0.0f,
                richnessScale,
                0.0f
        );
    }

    private static final NoisePreset WATER_PRESET =
            new NoisePreset(Blocks.darksandWater, 701, 24.0f, 0.885f, 2f, 0.38f, 0.10f, 5.0f, 115.0f, 0.032f);

    private static final NoisePreset TAR_PRESET =
            new NoisePreset(Blocks.tar, 809, 21.0f, 0.935f, 2f, 0.35f, -0.08f, 4.5f, 105.0f, 0.024f);

    // ---------------------------------------------------------------------
    // Liquid patch tuning
    // ---------------------------------------------------------------------

    /**
     * 90% of generated liquid patches use the normal range.
     * 10% use the rare larger range.
     */
    private static final double RARE_LIQUID_PATCH_CHANCE = 0.10;

    /**
     * Water performs a configurable number of placement tries in every
     * playable hex. Fractional values add a chance for one extra try per hex.
     * Larger patches remain progressively rarer because each normal patch has
     * a configurable chance to upgrade into a larger patch.
     */
    private static final int WATER_PATCH_LOCAL_RADIUS_PADDING = 1;

    /**
     * Oil remains substantially rarer than water, but slightly more common
     * than the previous sparse noise-only version.
     */
    private static final double TAR_PATCH_CHANCE_PER_HEX = 0.25;
    private static final int TAR_NORMAL_PATCH_MIN_SIZE = 2;
    private static final int TAR_NORMAL_PATCH_MAX_SIZE = 4;
    private static final int TAR_RARE_PATCH_MIN_SIZE = 5;
    private static final int TAR_RARE_PATCH_MAX_SIZE = 9;
    private static final int TAR_PATCH_LOCAL_RADIUS = 3;

    // ---------------------------------------------------------------------
    // Tiny fairness corrections
    // ---------------------------------------------------------------------

    /**
     * Guarantees should be nearly invisible.
     * A missing ore receives only a tiny local repair.
     */
    private static final int MIN_ORE_TILES_PER_HEX = 3;
    private static final int ORE_FALLBACK_MIN_SIZE = 3;
    private static final int ORE_FALLBACK_MAX_SIZE = 5;
    private static final int ORE_FALLBACK_MAX_LOCAL_RADIUS = 3;

    private static final int[][] DIRECTIONS_8 = new int[][]{
            {-1, -1}, {0, -1}, {1, -1},
            {-1, 0}, {1, 0},
            {-1, 1}, {0, 1}, {1, 1}
    };

    private ResourceGenerator() {
    }

    static Summary generate(
            long mapSeed,
            List<HexCenter> centers,
            EvictSettings settings
    ) {
        int seed = foldSeed(mapSeed ^ RESOURCE_SEED_XOR);
        Random correctionRandom = new Random(mapSeed ^ 0x464149522d455649L);
        CorrectionCounter corrections = new CorrectionCounter();
        NoisePreset[] orePresets = createOrePresets(settings);

        /*
         * Floors first: generate bounded liquid patches rather than
         * unrestricted global blobs. Tar is placed after water and therefore
         * never overwrites a water tile. Ores may later overlay water tiles so
         * water and resources can coexist without another setting.
         */
        generateWaterPatches(
                seed,
                correctionRandom,
                centers,
                settings.water(),
                corrections
        );
        generateTarPatches(seed, correctionRandom, centers, corrections);

        // Ores afterward. Later presets may naturally overwrite earlier ones.
        for (NoisePreset preset : orePresets) {
            generateOreNoise(seed, centers, preset);
        }

        ensureEveryHexHasEveryOre(
                seed,
                correctionRandom,
                centers,
                corrections,
                orePresets
        );

        return summarizeWorld(corrections);
    }

    static String presetDescription(EvictSettings settings) {
        return "persistent editor-style ores: "
                + settings.compactOreSettings()
                + " + water patches: "
                + settings.compactWaterSettings()
                + " + bounded oil patches with 10% rare large patches"
                + " + tiny ore-only per-hex fairness repairs";
    }

    // ---------------------------------------------------------------------
    // Global noise generation
    // ---------------------------------------------------------------------

    private static void generateWaterPatches(
            int seed,
            Random random,
            List<HexCenter> centers,
            EvictSettings.WaterSettings water,
            CorrectionCounter corrections
    ) {
        if (centers.isEmpty()) {
            return;
        }

        /*
         * Each playable hex receives a configured number of placement tries.
         * Decimal values use a fractional extra try: 4.3 means 4 guaranteed
         * tries and a 30% chance for one more in that hex.
         */
        for (HexCenter center : centers) {
            int patchAttempts = waterPatchAttemptsForHex(
                    random,
                    water.patchAttemptsPerHex()
            );

            for (int patch = 0; patch < patchAttempts; patch++) {
                TilePoint start = bestLiquidPatchStart(
                        seed,
                        center,
                        WATER_PRESET
                );

                if (start == null) {
                    continue;
                }

                int targetSize = chooseWaterPatchSize(random, water);
                int placed = growFloorPatch(
                        seed,
                        WATER_PRESET,
                        center,
                        start,
                        Blocks.darksandWater,
                        targetSize,
                        waterPatchLocalRadius(targetSize)
                );

                if (placed > 0) {
                    corrections.waterGeneratedPatches++;
                }
            }
        }
    }

    private static int waterPatchAttemptsForHex(
            Random random,
            double patchAttemptsPerHex
    ) {
        int attempts = (int) Math.floor(patchAttemptsPerHex);
        double fractionalAttempt = patchAttemptsPerHex - attempts;

        if (
                fractionalAttempt > 0d
                        && random.nextDouble() < fractionalAttempt
        ) {
            attempts++;
        }

        return attempts;
    }

    private static int chooseWaterPatchSize(
            Random random,
            EvictSettings.WaterSettings water
    ) {
        return random.nextDouble() * 100d < water.largePatchChancePercent()
                ? water.largePatchTiles()
                : water.normalPatchTiles();
    }

    private static int waterPatchLocalRadius(int targetSize) {
        return Math.max(
                1,
                (int) Math.ceil(Math.sqrt(targetSize / Math.PI))
                        + WATER_PATCH_LOCAL_RADIUS_PADDING
        );
    }

    private static void generateTarPatches(
            int seed,
            Random random,
            List<HexCenter> centers,
            CorrectionCounter corrections
    ) {
        for (HexCenter center : centers) {
            if (random.nextDouble() >= TAR_PATCH_CHANCE_PER_HEX) {
                continue;
            }

            TilePoint start = bestLiquidPatchStart(seed, center, TAR_PRESET);

            if (start == null) {
                continue;
            }

            int targetSize = chooseTarPatchSize(
                    random
            );

            int placed = growFloorPatch(
                    seed,
                    TAR_PRESET,
                    center,
                    start,
                    Blocks.tar,
                    targetSize,
                    TAR_PATCH_LOCAL_RADIUS
            );

            if (placed > 0) {
                corrections.tarGeneratedPatches++;
            }
        }
    }

    private static int chooseTarPatchSize(
            Random random
    ) {
        boolean rare = random.nextDouble() < RARE_LIQUID_PATCH_CHANCE;

        return rare
                ? inclusiveRandom(random, ResourceGenerator.TAR_RARE_PATCH_MIN_SIZE, ResourceGenerator.TAR_RARE_PATCH_MAX_SIZE)
                : inclusiveRandom(random, ResourceGenerator.TAR_NORMAL_PATCH_MIN_SIZE, ResourceGenerator.TAR_NORMAL_PATCH_MAX_SIZE);
    }

    private static TilePoint bestLiquidPatchStart(
            int seed,
            HexCenter center,
            NoisePreset preset
    ) {
        TilePoint best = null;
        float bestNoise = -Float.MAX_VALUE;

        for (
                int y = center.y - LIQUID_MAX_RADIUS;
                y <= center.y + LIQUID_MAX_RADIUS;
                y++
        ) {
            for (
                    int x = center.x - LIQUID_MAX_RADIUS;
                    x <= center.x + LIQUID_MAX_RADIUS;
                    x++
            ) {
                Tile tile = Vars.world.tile(x, y);

                if (
                        tile == null
                                || tile.block() != Blocks.air
                                || tile.floor() != Blocks.darksand
                                || !insideLiquidMaskForCenter(x, y, center)
                ) {
                    continue;
                }

                float noise = sample(seed, preset, x, y);

                if (noise > bestNoise) {
                    bestNoise = noise;
                    best = new TilePoint(x, y);
                }
            }
        }

        return best;
    }

    private static void generateOreNoise(
            int seed,
            List<HexCenter> centers,
            NoisePreset preset
    ) {
        int width = Vars.world.width();
        int height = Vars.world.height();

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                Tile tile = Vars.world.tile(x, y);

                if (
                        tile == null
                                || tile.block() != Blocks.air
                                || !canCarryOreOverlay(tile)
                                || !insideOreMask(x, y, centers)
                ) {
                    continue;
                }

                if (passesThreshold(seed, preset, x, y)) {
                    tile.setOverlay(preset.block);
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Ore guarantees: tiny fallback only
    // ---------------------------------------------------------------------

    private static void ensureEveryHexHasEveryOre(
            int seed,
            Random random,
            List<HexCenter> centers,
            CorrectionCounter corrections,
            NoisePreset[] orePresets
    ) {
        for (HexCenter center : centers) {
            for (NoisePreset preset : orePresets) {
                int existingTiles = countOreTiles(center, preset.block);

                if (existingTiles >= MIN_ORE_TILES_PER_HEX) {
                    continue;
                }

                TilePoint start = bestOreFallbackStart(
                        seed,
                        center,
                        preset,
                        false
                );

                if (start == null) {
                    start = bestOreFallbackStart(
                            seed,
                            center,
                            preset,
                            true
                    );
                }

                if (start == null) {
                    continue;
                }

                int targetSize = Math.max(
                        MIN_ORE_TILES_PER_HEX - existingTiles,
                        inclusiveRandom(
                                random,
                                ORE_FALLBACK_MIN_SIZE,
                                ORE_FALLBACK_MAX_SIZE
                        )
                );

                int placed = growOrePatch(
                        seed,
                        preset,
                        center,
                        start,
                        targetSize,
                        ORE_FALLBACK_MAX_LOCAL_RADIUS
                );

                if (placed > 0) {
                    corrections.oreFallbackRepairs++;
                }
            }
        }
    }

    private static int countOreTiles(
            HexCenter center,
            Block oreOverlay
    ) {
        int count = 0;

        for (
                int y = center.y - GUARANTEE_ORE_RADIUS;
                y <= center.y + GUARANTEE_ORE_RADIUS;
                y++
        ) {
            for (
                    int x = center.x - GUARANTEE_ORE_RADIUS;
                    x <= center.x + GUARANTEE_ORE_RADIUS;
                    x++
            ) {
                if (!insideGuaranteeOreMaskForCenter(x, y, center)) {
                    continue;
                }

                Tile tile = Vars.world.tile(x, y);

                if (tile != null && tile.overlay() == oreOverlay) {
                    count++;
                }
            }
        }

        return count;
    }

    private static TilePoint bestOreFallbackStart(
            int seed,
            HexCenter center,
            NoisePreset preset,
            boolean allowOverwrite
    ) {
        TilePoint best = null;
        float bestNoise = -Float.MAX_VALUE;

        for (
                int y = center.y - GUARANTEE_ORE_RADIUS;
                y <= center.y + GUARANTEE_ORE_RADIUS;
                y++
        ) {
            for (
                    int x = center.x - GUARANTEE_ORE_RADIUS;
                    x <= center.x + GUARANTEE_ORE_RADIUS;
                    x++
            ) {
                TilePoint point = new TilePoint(x, y);
                Tile tile = Vars.world.tile(x, y);

                if (
                        tile == null
                                || tile.block() != Blocks.air
                                || !canCarryOreOverlay(tile)
                                || !insideGuaranteeOreMaskForCenter(x, y, center)
                                || (!allowOverwrite && tile.overlay() != Blocks.air)
                ) {
                    continue;
                }

                float noise = sample(seed, preset, x, y);

                if (noise > bestNoise) {
                    bestNoise = noise;
                    best = point;
                }
            }
        }

        return best;
    }

    // ---------------------------------------------------------------------
    // Noise-guided fallback patch growth
    // ---------------------------------------------------------------------

    private static int growFloorPatch(
            int seed,
            NoisePreset preset,
            HexCenter center,
            TilePoint start,
            Block floor,
            int targetSize,
            int localRadius
    ) {
        PriorityQueue<Candidate> queue = candidateQueue();
        Set<TilePoint> queued = new HashSet<>();
        Set<TilePoint> placed = new HashSet<>();

        queue.add(candidate(seed, preset, start));
        queued.add(start);

        while (!queue.isEmpty() && placed.size() < targetSize) {
            TilePoint point = queue.remove().point;

            if (
                    placed.contains(point)
                            || !insideLocalRadius(point, start, localRadius)
            ) {
                continue;
            }

            Tile tile = Vars.world.tile(point.x, point.y);

            if (
                    tile == null
                            || tile.block() != Blocks.air
                            || tile.floor() != Blocks.darksand
                            || !insideLiquidMaskForCenter(point.x, point.y, center)
            ) {
                continue;
            }

            Tile.setFloor(tile, floor, Blocks.air);
            placed.add(point);

            enqueueNeighbours(seed, preset, point, queue, queued);
        }

        return placed.size();
    }

    private static int growOrePatch(
            int seed,
            NoisePreset preset,
            HexCenter center,
            TilePoint start,
            int targetSize,
            @SuppressWarnings("SameParameterValue") int localRadius
    ) {
        PriorityQueue<Candidate> queue = candidateQueue();
        Set<TilePoint> queued = new HashSet<>();
        Set<TilePoint> placed = new HashSet<>();

        queue.add(candidate(seed, preset, start));
        queued.add(start);

        while (!queue.isEmpty() && placed.size() < targetSize) {
            TilePoint point = queue.remove().point;

            if (
                    placed.contains(point)
                            || !insideLocalRadius(point, start, localRadius)
            ) {
                continue;
            }

            Tile tile = Vars.world.tile(point.x, point.y);

            if (
                    tile == null
                            || tile.block() != Blocks.air
                            || !canCarryOreOverlay(tile)
                            || !insideGuaranteeOreMaskForCenter(point.x, point.y, center)
            ) {
                continue;
            }

            tile.setOverlay(preset.block);
            placed.add(point);

            enqueueNeighbours(seed, preset, point, queue, queued);
        }

        return placed.size();
    }

    private static PriorityQueue<Candidate> candidateQueue() {
        return new PriorityQueue<>(
                Comparator.comparingDouble((Candidate candidate) -> candidate.noise)
                        .reversed()
        );
    }

    private static Candidate candidate(
            int seed,
            NoisePreset preset,
            TilePoint point
    ) {
        return new Candidate(
                point,
                sample(seed, preset, point.x, point.y)
        );
    }

    private static void enqueueNeighbours(
            int seed,
            NoisePreset preset,
            TilePoint point,
            PriorityQueue<Candidate> queue,
            Set<TilePoint> queued
    ) {
        for (int[] direction : DIRECTIONS_8) {
            TilePoint neighbour = new TilePoint(
                    point.x + direction[0],
                    point.y + direction[1]
            );

            if (queued.add(neighbour)) {
                queue.add(candidate(seed, preset, neighbour));
            }
        }
    }

    // ---------------------------------------------------------------------
    // Placement masks
    // ---------------------------------------------------------------------

    private static boolean insideOreMask(
            int x,
            int y,
            List<HexCenter> centers
    ) {
        int closestDistanceSquared =
                closestCenterDistanceSquared(x, y, centers);

        return closestDistanceSquared >= ORE_CORE_SAFE_RADIUS_SQUARED
                && closestDistanceSquared <= ORE_MAX_RADIUS_SQUARED;
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private static boolean canCarryOreOverlay(Tile tile) {
        return tile.floor() == Blocks.darksand
                || tile.floor() == Blocks.darksandWater;
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private static boolean insideGuaranteeOreMaskForCenter(
            int x,
            int y,
            HexCenter center
    ) {
        int distanceSquared = squaredDistance(x, y, center.x, center.y);

        return distanceSquared >= ORE_CORE_SAFE_RADIUS_SQUARED
                && distanceSquared <= GUARANTEE_ORE_RADIUS_SQUARED;
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private static boolean insideLiquidMaskForCenter(
            int x,
            int y,
            HexCenter center
    ) {
        int distanceSquared = squaredDistance(x, y, center.x, center.y);

        return distanceSquared >= LIQUID_CORE_SAFE_RADIUS_SQUARED
                && distanceSquared <= LIQUID_MAX_RADIUS_SQUARED;
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private static boolean insideLocalRadius(
            TilePoint point,
            TilePoint start,
            int radius
    ) {
        return squaredDistance(point.x, point.y, start.x, start.y)
                <= radius * radius;
    }

    private static int closestCenterDistanceSquared(
            int x,
            int y,
            List<HexCenter> centers
    ) {
        int closest = Integer.MAX_VALUE;

        for (HexCenter center : centers) {
            int distanceSquared = squaredDistance(x, y, center.x, center.y);

            if (distanceSquared < closest) {
                closest = distanceSquared;
            }
        }

        return closest;
    }

    // ---------------------------------------------------------------------
    // Warped global noise
    // ---------------------------------------------------------------------

    private static boolean passesThreshold(
            int baseSeed,
            NoisePreset preset,
            int x,
            int y
    ) {
        float richness = Simplex.noise2d(
                baseSeed + preset.seedOffset + 30000,
                2f,
                0.5f,
                1f / preset.richnessScale,
                x + 41f,
                y - 37f
        );

        float centeredRichness = (richness - 0.5f) * 2f;
        float effectiveThreshold =
                preset.threshold - centeredRichness * preset.richnessStrength;

        return sample(baseSeed, preset, x, y) > effectiveThreshold;
    }

    private static float sample(
            int baseSeed,
            NoisePreset preset,
            int x,
            int y
    ) {
        float warpX = (
                Simplex.noise2d(
                        baseSeed + preset.seedOffset + 10000,
                        2f,
                        0.5f,
                        1f / 90f,
                        x,
                        y
                ) - 0.5f
        ) * 2f * preset.warpStrength;

        float warpY = (
                Simplex.noise2d(
                        baseSeed + preset.seedOffset + 20000,
                        2f,
                        0.5f,
                        1f / 90f,
                        x + 17f,
                        y - 23f
                ) - 0.5f
        ) * 2f * preset.warpStrength;

        return Simplex.noise2d(
                baseSeed + preset.seedOffset,
                preset.octaves,
                preset.falloff,
                1f / preset.scale,
                x + 10f + warpX,
                y + x * preset.tilt + 10f + warpY
        );
    }

    private static int foldSeed(long seed) {
        return (int) (seed ^ (seed >>> 32));
    }

    private static int inclusiveRandom(
            Random random,
            int minimum,
            int maximum
    ) {
        return minimum + random.nextInt(maximum - minimum + 1);
    }

    private static int squaredDistance(
            int x1,
            int y1,
            int x2,
            int y2
    ) {
        int deltaX = x1 - x2;
        int deltaY = y1 - y2;

        return deltaX * deltaX + deltaY * deltaY;
    }

    // ---------------------------------------------------------------------
    // Reporting
    // ---------------------------------------------------------------------

    private static Summary summarizeWorld(CorrectionCounter corrections) {
        int waterTiles = 0;
        int tarTiles = 0;
        int copperTiles = 0;
        int leadTiles = 0;
        int coalTiles = 0;
        int scrapTiles = 0;
        int titaniumTiles = 0;
        int thoriumTiles = 0;

        int width = Vars.world.width();
        int height = Vars.world.height();

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                Tile tile = Vars.world.tile(x, y);

                if (tile == null) {
                    continue;
                }

                if (tile.floor() == Blocks.darksandWater) {
                    waterTiles++;
                } else if (tile.floor() == Blocks.tar) {
                    tarTiles++;
                }

                Block overlay = tile.overlay();

                if (overlay == Blocks.oreCopper) {
                    copperTiles++;
                } else if (overlay == Blocks.oreLead) {
                    leadTiles++;
                } else if (overlay == Blocks.oreCoal) {
                    coalTiles++;
                } else if (overlay == Blocks.oreScrap) {
                    scrapTiles++;
                } else if (overlay == Blocks.oreTitanium) {
                    titaniumTiles++;
                } else if (overlay == Blocks.oreThorium) {
                    thoriumTiles++;
                }
            }
        }

        return new Summary(
                waterTiles,
                tarTiles,
                copperTiles,
                leadTiles,
                coalTiles,
                scrapTiles,
                titaniumTiles,
                thoriumTiles,
                corrections.waterGeneratedPatches,
                corrections.tarGeneratedPatches,
                corrections.oreFallbackRepairs
        );
    }

    record HexCenter(int x, int y) {
    }

    public record Summary(
            int waterTiles,
            int tarTiles,
            int copperTiles,
            int leadTiles,
            int coalTiles,
            int scrapTiles,
            int titaniumTiles,
            int thoriumTiles,
            int waterGeneratedPatches,
            int tarGeneratedPatches,
            int oreFallbackRepairs
    ) {
        public String compact() {
            return "water=" + waterTiles
                    + ", tar=" + tarTiles
                    + ", copper=" + copperTiles
                    + ", lead=" + leadTiles
                    + ", coal=" + coalTiles
                    + ", scrap=" + scrapTiles
                    + ", titanium=" + titaniumTiles
                    + ", thorium=" + thoriumTiles
                    + ", waterPatches=" + waterGeneratedPatches
                    + ", tarPatches=" + tarGeneratedPatches
                    + ", oreFallbackRepairs=" + oreFallbackRepairs;
        }
    }

    private record NoisePreset(
            Block block,
            int seedOffset,
            float scale,
            float threshold,
            float octaves,
            float falloff,
            float tilt,
            float warpStrength,
            float richnessScale,
            float richnessStrength
    ) {
    }

    private record TilePoint(int x, int y) {
    }

    private record Candidate(TilePoint point, float noise) {
    }

    private static final class CorrectionCounter {
        private int waterGeneratedPatches;
        private int tarGeneratedPatches;
        private int oreFallbackRepairs;
    }
}
