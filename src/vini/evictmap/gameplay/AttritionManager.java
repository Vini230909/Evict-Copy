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
import vini.evictmap.gen.EvictSettings;
import vini.evictmap.round.TeamManager;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Handles unit attrition (on the basis of both range and core death).
 * <p>
 * Every {@link EvictSettings#rangeAttritionInterval()} ticks, units which are not
 * {@link TeamManager#isWithinOneHexOfOwnedCore} are killed with a probability of
 * {@link EvictSettings#rangeAttritionChance()}. This is called range attrition.
 * </p>
 * <p>
 * Every time a core is taken, units which are within {@link EvictSettings#coreAttritionRadius()}
 * tiles of that core are killed with a probability of {@link EvictSettings#coreAttritionTier1To3Chance()},
 * {@link EvictSettings#coreAttritionTier4Chance()} or {@link EvictSettings#coreAttritionTier5Chance()}
 * (depending on the tier of the unit). This is called core explosion (or core) attrition.
 * </p>
 */
public final class AttritionManager implements GameplayManagerInterface {
    /**
     * Hashmap associating units with their tiers.
     */
    private static final Map<UnitType, Integer> UNIT_TIER_MAP =
            new HashMap<>();

    /**
     * Plugins may be instantiated before vanilla content has finished loading.
     * We must resolve the UnitTypes lazily so an early class load cannot permanently
     * leave this table empty.
     */
    private static void ensureUnitTierMapIsPopulated() {
        /*
         * Check that the mindustry units are initialized and that the map has not yet been initialized.
         * Conquer is actually the last mindustry unit to be initialized, so we should check if it is null
         * (not if the dagger is null alone).
         */
        if (!UNIT_TIER_MAP.isEmpty() || UnitTypes.dagger == null || UnitTypes.conquer == null) {
            return;
        }

        // Tier 1 units
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

        // Tier 2 units
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

        // Tier 3 units
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

        // Tier 4 units
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

        // Tier 5 units
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
    /**
     * Time since the last range attrition (in ticks).
     */
    private float rangeAttritionTimer = 0f;

    /**
     * Simple constructor for this class.
     *
     * @param teamManager {@link TeamManager} to be used for future operations
     * @param settings    {@link EvictSettings} to be used for future operations
     */
    public AttritionManager(
            TeamManager teamManager,
            EvictSettings settings
    ) {
        this.teamManager = teamManager;
        this.settings = settings;
    }

    @Override
    public void beginRound() {
        rangeAttritionTimer = 0f;
    }

    @Override
    public void update() {
        /*
         * We handle range attrition in the update method, but core capture attrition
         * is handled in its own method which is only called when needed.
         */
        // If the round isn't active, don't worry about attrition
        if (!teamManager.isRoundActiveForSystems()) {
            rangeAttritionTimer = 0f;
            return;
        }

        // Increment timer with modular overflow, returning early if no more work must be done
        rangeAttritionTimer += Time.delta;
        if (rangeAttritionTimer < settings.rangeAttritionInterval()) return;
        rangeAttritionTimer %= settings.rangeAttritionInterval();

        // Otherwise if we have not already returned, apply range attrition to the matching units
        killMatching(
                unit -> !teamManager.isWithinOneHexOfOwnedCore(unit),
                unit -> settings.rangeAttritionChance()
        );
    }

    @Override
    public void endRound() {
    }

    /**
     * Apply core explosion attrition to a core at a given X and Y coordinate.
     *
     * @param coreTileX X coordinate of core.
     * @param coreTileY Y coordinate of core.
     */
    public void handleCoreExplosionAttrition(int coreTileX, int coreTileY) {
        float centerX = coreTileX * Vars.tilesize;
        float centerY = coreTileY * Vars.tilesize;
        float radius = settings.coreAttritionRadius() * Vars.tilesize;

        killMatching(
                unit -> unit.within(centerX, centerY, radius),
                unit -> coreDeathChance(unit.type)
        );
    }

    /**
     * Kill units matching a given predicate with a given probability, given that
     * they also satisfy {@link AttritionManager#eligibleForAttrition}.
     *
     * @param filter         Predicate to determine whether units should be killed.
     * @param chanceProvider Probability they should be killed.
     */
    private void killMatching(
            Function<Unit, Boolean> filter,
            Function<Unit, Double> chanceProvider
    ) {
        /*
         * We shouldn't hoist the actual killing inside the iteraion, because that would invalidate the
         * iterator while we are still traversing the units.
         */
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
    }

    /**
     * Whether a given unit should be considered eligible for attrition
     * (i.e., no killing core units, among other things).
     *
     * @param unit Unit to run this predicate on.
     * @return Whether the unit is eligible for attrition.
     */
    private boolean eligibleForAttrition(Unit unit) {
        return unit != null
                && unit.isAdded()
                && unit.team != Team.derelict
                && !unit.spawnedByCore
                && unit.type.killable(unit);
    }

    /**
     * Determine the chance that a certain unit will die due to core explosion attrition
     * (based on whether it is t1-3, t4 or t5).
     *
     * @param type Type of unit to run the predicate on.
     * @return The chance that this unit will die to core explosion attrition.
     */
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
