package vini.evictmap.gameplay;

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
import vini.evictmap.EvictSettings;
import vini.evictmap.TeamManager;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Evict unit attrition:
 * - Every captured core applies a one-time tier-based core-attrition roll to
 * every normal unit inside the 40-tile capture radius, regardless of team.
 * - Every five seconds, units at least two graph hexes away from the nearest
 * owned core hex receive one flat range-attrition roll, regardless of tier.
 * - Player/core units spawned by a core are always immune.
 */
public final class AttritionManager implements GameplayManagerInterface {

    private static final float RANGE_ATTRITION_INTERVAL_TICKS = 5f * 60f;
    private static final int CAPTURE_ATTRITION_RADIUS_TILES = 40;


    private static final Map<UnitType, Integer> UNIT_TIER_MAP =
            new HashMap<>();

    /**
     * Plugins may be instantiated before vanilla content has finished loading.
     * Resolve the UnitTypes lazily so an early class load cannot permanently
     * leave this table empty.
     */
    private static void ensureUnitTierMapIsPopulated() {
        if (!UNIT_TIER_MAP.isEmpty() || UnitTypes.dagger == null) {
            return;
        }

        UNIT_TIER_MAP.put(UnitTypes.dagger, 1);
        UNIT_TIER_MAP.put(UnitTypes.crawler, 1);
        UNIT_TIER_MAP.put(UnitTypes.nova, 1);
        UNIT_TIER_MAP.put(UnitTypes.flare, 1);
        UNIT_TIER_MAP.put(UnitTypes.mono, 1);
        UNIT_TIER_MAP.put(UnitTypes.risso, 1);
        UNIT_TIER_MAP.put(UnitTypes.retusa, 1);
        UNIT_TIER_MAP.put(UnitTypes.stell, 1);
        UNIT_TIER_MAP.put(UnitTypes.merui, 1);
        UNIT_TIER_MAP.put(UnitTypes.elude, 1);

        UNIT_TIER_MAP.put(UnitTypes.mace, 2);
        UNIT_TIER_MAP.put(UnitTypes.atrax, 2);
        UNIT_TIER_MAP.put(UnitTypes.pulsar, 2);
        UNIT_TIER_MAP.put(UnitTypes.horizon, 2);
        UNIT_TIER_MAP.put(UnitTypes.poly, 2);
        UNIT_TIER_MAP.put(UnitTypes.minke, 2);
        UNIT_TIER_MAP.put(UnitTypes.oxynoe, 2);
        UNIT_TIER_MAP.put(UnitTypes.locus, 2);
        UNIT_TIER_MAP.put(UnitTypes.cleroi, 2);
        UNIT_TIER_MAP.put(UnitTypes.avert, 2);

        UNIT_TIER_MAP.put(UnitTypes.fortress, 3);
        UNIT_TIER_MAP.put(UnitTypes.spiroct, 3);
        UNIT_TIER_MAP.put(UnitTypes.quasar, 3);
        UNIT_TIER_MAP.put(UnitTypes.zenith, 3);
        UNIT_TIER_MAP.put(UnitTypes.mega, 3);
        UNIT_TIER_MAP.put(UnitTypes.bryde, 3);
        UNIT_TIER_MAP.put(UnitTypes.cyerce, 3);
        UNIT_TIER_MAP.put(UnitTypes.precept, 3);
        UNIT_TIER_MAP.put(UnitTypes.anthicus, 3);
        UNIT_TIER_MAP.put(UnitTypes.obviate, 3);

        UNIT_TIER_MAP.put(UnitTypes.scepter, 4);
        UNIT_TIER_MAP.put(UnitTypes.arkyid, 4);
        UNIT_TIER_MAP.put(UnitTypes.vela, 4);
        UNIT_TIER_MAP.put(UnitTypes.antumbra, 4);
        UNIT_TIER_MAP.put(UnitTypes.quad, 4);
        UNIT_TIER_MAP.put(UnitTypes.sei, 4);
        UNIT_TIER_MAP.put(UnitTypes.aegires, 4);
        UNIT_TIER_MAP.put(UnitTypes.vanquish, 4);
        UNIT_TIER_MAP.put(UnitTypes.tecta, 4);
        UNIT_TIER_MAP.put(UnitTypes.quell, 4);

        UNIT_TIER_MAP.put(UnitTypes.reign, 5);
        UNIT_TIER_MAP.put(UnitTypes.toxopid, 5);
        UNIT_TIER_MAP.put(UnitTypes.corvus, 5);
        UNIT_TIER_MAP.put(UnitTypes.eclipse, 5);
        UNIT_TIER_MAP.put(UnitTypes.oct, 5);
        UNIT_TIER_MAP.put(UnitTypes.omura, 5);
        UNIT_TIER_MAP.put(UnitTypes.navanax, 5);
        UNIT_TIER_MAP.put(UnitTypes.conquer, 5);
        UNIT_TIER_MAP.put(UnitTypes.collaris, 5);
        UNIT_TIER_MAP.put(UnitTypes.disrupt, 5);
    }

    private final TeamManager teamManager;
    private final EvictSettings settings;
    private float rangeAttritionTimer = 0f;

    public AttritionManager(
            TeamManager teamManager,
            EvictSettings settings
    ) {
        this.teamManager = teamManager;
        this.settings = settings;
    }

    public void beginRound() {
        rangeAttritionTimer = 0f;
    }

    public void update() {
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

    public void endRound() {
    }

    public int applyCaptureAttrition(int coreTileX, int coreTileY) {
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
            Function<Unit, Boolean> filter,
            Function<Unit, Double> chanceProvider
    ) {
        Seq<Unit> toKill = new Seq<>();

        Groups.unit.each(unit -> {
            if (
                    !eligibleForAttrition(unit)
                            || !filter.apply(unit)
                            || !Mathf.chance(chanceProvider.apply(unit))
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

    public void setCoreDeathChancesPercent(
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

    public void setRangeDeathChancePercent(double percent) {
        settings.setRangeAttritionPercent(percent);
    }

    public String compactCoreSettings() {
        return settings.compactCoreAttritionSettings();
    }

    public String compactRangeSettings() {
        return settings.compactRangeAttritionSettings();
    }

    private double coreDeathChance(UnitType type) {
        ensureUnitTierMapIsPopulated();

        int tier = UNIT_TIER_MAP.getOrDefault(type, 1);

        return switch (tier) {
            case 4 -> settings.coreAttritionTier4Chance();
            case 5 -> settings.coreAttritionTier5Chance();
            default -> settings.coreAttritionTier1To3Chance();
        };
    }
}
