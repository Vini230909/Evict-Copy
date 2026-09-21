// Attrition: units die by chance when far from an owned core (every 5 s) or when a core is captured.
package Extinction;

import Extinction.round.TeamManager;

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

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public final class Attrition {

    private static final float CORE_RADIUS_TILES = 40f;
    private static final float RANGE_INTERVAL_TICKS = 5f * 60f;

    // Unit type -> tier; filled lazily because vanilla content may not be loaded when this class is.
    private static final Map<UnitType, Integer> UNIT_TIERS = new HashMap<>();

    private final TeamManager teamManager;

    // Ticks since the last range attrition.
    private float rangeTimer = 0f;

    public Attrition(TeamManager teamManager) {
        this.teamManager = teamManager;
    }

    public void beginRound() {
        rangeTimer = 0f;
    }

    // Range attrition: every 5 s, every unit not within one hex of an owned core rolls the same chance.
    public void update() {
        if (!teamManager.isRoundActiveForSystems()) {
            rangeTimer = 0f;
            return;
        }

        rangeTimer += Time.delta;
        if (rangeTimer < RANGE_INTERVAL_TICKS) return;
        rangeTimer %= RANGE_INTERVAL_TICKS;

        killMatching(
                unit -> !teamManager.isWithinOneHexOfOwnedCore(unit),
                unit -> Config.attritionRangePercent / 100d
        );
    }

    // Capture attrition: once, every unit within the capture radius of the core rolls its tier's chance.
    public void onCoreCaptured(int coreTileX, int coreTileY) {
        float centerX = coreTileX * Vars.tilesize;
        float centerY = coreTileY * Vars.tilesize;
        float radius = CORE_RADIUS_TILES * Vars.tilesize;

        killMatching(
                unit -> unit.within(centerX, centerY, radius),
                unit -> coreDeathChance(unit.type)
        );
    }

    // For the startup log: "T1-T3=40%, T4=18%, T5=9%".
    public static String coreSummary() {
        return "T1-T3=" + formatPercent(Config.attritionCoreTier1To3Percent)
                + "%, T4=" + formatPercent(Config.attritionCoreTier4Percent)
                + "%, T5=" + formatPercent(Config.attritionCoreTier5Percent) + "%";
    }

    public static String rangeSummary() {
        return formatPercent(Config.attritionRangePercent) + "%";
    }

    // Collects first, kills after: killing while iterating would invalidate the unit iterator.
    private void killMatching(Function<Unit, Boolean> filter, Function<Unit, Double> chance) {
        Seq<Unit> toKill = new Seq<>();

        Groups.unit.each(unit -> {
            if (!eligible(unit) || !filter.apply(unit) || !Mathf.chance(chance.apply(unit))) {
                return;
            }

            toKill.add(unit);
        });

        toKill.each(Unitc::kill);
    }

    // Core-spawned player units and derelicts never take attrition.
    private static boolean eligible(Unit unit) {
        return unit != null
                && unit.isAdded()
                && unit.team != Team.derelict
                && !unit.spawnedByCore
                && unit.type.killable(unit);
    }

    private static double coreDeathChance(UnitType type) {
        fillUnitTiers();

        return switch (UNIT_TIERS.getOrDefault(type, 1)) {
            case 4 -> Config.attritionCoreTier4Percent / 100d;
            case 5 -> Config.attritionCoreTier5Percent / 100d;
            default -> Config.attritionCoreTier1To3Percent / 100d;
        };
    }

    private static String formatPercent(double value) {
        return Math.rint(value) == value ? Long.toString(Math.round(value)) : Double.toString(value);
    }

    // Conquer is the last vanilla unit to load, so it is the one to check for, not dagger alone.
    private static void fillUnitTiers() {
        if (!UNIT_TIERS.isEmpty() || UnitTypes.dagger == null || UnitTypes.conquer == null) {
            return;
        }

        tier(1, UnitTypes.dagger, UnitTypes.crawler, UnitTypes.nova, UnitTypes.flare, UnitTypes.mono,
                UnitTypes.risso, UnitTypes.retusa, UnitTypes.stell, UnitTypes.merui, UnitTypes.elude);
        tier(2, UnitTypes.mace, UnitTypes.atrax, UnitTypes.pulsar, UnitTypes.horizon, UnitTypes.poly,
                UnitTypes.minke, UnitTypes.oxynoe, UnitTypes.locus, UnitTypes.cleroi, UnitTypes.avert);
        tier(3, UnitTypes.fortress, UnitTypes.spiroct, UnitTypes.quasar, UnitTypes.zenith, UnitTypes.mega,
                UnitTypes.bryde, UnitTypes.cyerce, UnitTypes.precept, UnitTypes.anthicus, UnitTypes.obviate);
        tier(4, UnitTypes.scepter, UnitTypes.arkyid, UnitTypes.vela, UnitTypes.antumbra, UnitTypes.quad,
                UnitTypes.sei, UnitTypes.aegires, UnitTypes.vanquish, UnitTypes.tecta, UnitTypes.quell);
        tier(5, UnitTypes.reign, UnitTypes.toxopid, UnitTypes.corvus, UnitTypes.eclipse, UnitTypes.oct,
                UnitTypes.omura, UnitTypes.navanax, UnitTypes.conquer, UnitTypes.collaris, UnitTypes.disrupt);
    }

    private static void tier(int tier, UnitType... types) {
        for (UnitType type : types) {
            UNIT_TIERS.put(type, tier);
        }
    }
}
