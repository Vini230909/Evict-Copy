package vini.evictmap;

import arc.math.Mathf;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.Vars;
import mindustry.content.UnitTypes;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.gen.Unitc;
import mindustry.type.UnitType;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Evict unit attrition:
 * - Every captured core applies a one-time tier-based core-attrition roll to
 * every normal unit inside the 40-tile capture radius, regardless of team.
 * - Every five seconds, units at least two graph hexes away from the nearest
 * owned core hex receive one flat range-attrition roll, regardless of tier.
 * - Player/core units spawned by a core are always immune.
 */
final class AttritionManager {

    private static final float RANGE_ATTRITION_INTERVAL_TICKS = 5f * 60f;
    private static final int CAPTURE_ATTRITION_RADIUS_TILES = 40;


    private static final Map<UnitType, Integer> VANILLA_TIERS =
            new IdentityHashMap<>();

    /**
     * Plugins may be instantiated before vanilla content has finished loading.
     * Resolve the UnitTypes lazily so an early class load cannot permanently
     * leave this table empty.
     */
    private static void ensureTierMap() {
        if (!VANILLA_TIERS.isEmpty() || UnitTypes.dagger == null) {
            return;
        }

        registerTier(
                1,
                UnitTypes.dagger,
                UnitTypes.crawler,
                UnitTypes.nova,
                UnitTypes.flare,
                UnitTypes.mono,
                UnitTypes.risso,
                UnitTypes.retusa,
                UnitTypes.stell,
                UnitTypes.merui,
                UnitTypes.elude
        );

        registerTier(
                2,
                UnitTypes.mace,
                UnitTypes.atrax,
                UnitTypes.pulsar,
                UnitTypes.horizon,
                UnitTypes.poly,
                UnitTypes.minke,
                UnitTypes.oxynoe,
                UnitTypes.locus,
                UnitTypes.cleroi,
                UnitTypes.avert
        );

        registerTier(
                3,
                UnitTypes.fortress,
                UnitTypes.spiroct,
                UnitTypes.quasar,
                UnitTypes.zenith,
                UnitTypes.mega,
                UnitTypes.bryde,
                UnitTypes.cyerce,
                UnitTypes.precept,
                UnitTypes.anthicus,
                UnitTypes.obviate
        );

        registerTier(
                4,
                UnitTypes.scepter,
                UnitTypes.arkyid,
                UnitTypes.vela,
                UnitTypes.antumbra,
                UnitTypes.quad,
                UnitTypes.sei,
                UnitTypes.aegires,
                UnitTypes.vanquish,
                UnitTypes.tecta,
                UnitTypes.quell
        );

        registerTier(
                5,
                UnitTypes.reign,
                UnitTypes.toxopid,
                UnitTypes.corvus,
                UnitTypes.eclipse,
                UnitTypes.oct,
                UnitTypes.omura,
                UnitTypes.navanax,
                UnitTypes.conquer,
                UnitTypes.collaris,
                UnitTypes.disrupt
        );
    }

    private final TeamManager teamManager;
    private final EvictSettings settings;
    private float rangeAttritionTimer = 0f;

    AttritionManager(
            TeamManager teamManager,
            EvictSettings settings
    ) {
        this.teamManager = teamManager;
        this.settings = settings;
    }

    void beginRound() {
        rangeAttritionTimer = 0f;
    }

    void update() {
        if (!teamManager.isRoundActiveForSystems()) {
            rangeAttritionTimer = 0f;
            return;
        }

        rangeAttritionTimer += Time.delta;

        if (rangeAttritionTimer < RANGE_ATTRITION_INTERVAL_TICKS) {
            return;
        }

        rangeAttritionTimer %= RANGE_ATTRITION_INTERVAL_TICKS;
        applyRangeAttrition();
    }

    int applyCaptureAttrition(int coreTileX, int coreTileY) {
        float centerX = coreTileX * Vars.tilesize;
        float centerY = coreTileY * Vars.tilesize;
        float radius = CAPTURE_ATTRITION_RADIUS_TILES * Vars.tilesize;

        return killMatching(
                unit -> unit.within(centerX, centerY, radius),
                unit -> coreDeathChance(unit.type)
        );
    }

    private void applyRangeAttrition() {
        killMatching(
                unit -> !teamManager.isWithinOneHexOfOwnedCore(unit),
                unit -> settings.rangeAttritionChance()
        );
    }

    private int killMatching(
            UnitFilter filter,
            ChanceProvider chanceProvider
    ) {
        Seq<Unit> toKill = new Seq<>();

        Groups.unit.each(unit -> {
            if (
                    !eligibleForAttrition(unit)
                            || !filter.accept(unit)
                            || !Mathf.chance(chanceProvider.chance(unit))
            ) {
                return;
            }

            toKill.add(unit);
        });

        toKill.each(Unitc::kill);
        return toKill.size;
    }

    private boolean eligibleForAttrition(Unit unit) {
        return unit != null
                && unit.isAdded()
                && unit.team != Team.derelict
                && !unit.spawnedByCore
                && unit.type.killable(unit);
    }

    private static void registerTier(int tier, UnitType... types) {
        for (UnitType type : types) {
            if (type != null) {
                VANILLA_TIERS.put(type, tier);
            }
        }
    }

    void setCoreDeathChancesPercent(
            double tier1To3Percent,
            double tier4Percent,
            double tier5Percent
    ) {
        settings.setCoreAttritionPercentages(
                tier1To3Percent,
                tier4Percent,
                tier5Percent
        );
    }

    void setRangeDeathChancePercent(double percent) {
        settings.setRangeAttritionPercent(percent);
    }

    String compactCoreSettings() {
        return settings.compactCoreAttritionSettings();
    }

    String compactRangeSettings() {
        return settings.compactRangeAttritionSettings();
    }

    private double coreDeathChance(UnitType type) {
        ensureTierMap();

        int tier = VANILLA_TIERS.getOrDefault(type, 1);

        return switch (tier) {
            case 4 -> settings.coreAttritionTier4Chance();
            case 5 -> settings.coreAttritionTier5Chance();
            default -> settings.coreAttritionTier1To3Chance();
        };
    }

    @FunctionalInterface
    private interface UnitFilter {
        boolean accept(Unit unit);
    }

    @FunctionalInterface
    private interface ChanceProvider {
        double chance(Unit unit);
    }
}
