package vini.evictmap.gameplay;

import java.util.Arrays;

import arc.util.Log;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.content.UnitTypes;
import mindustry.entities.bullet.EmpBulletType;
import mindustry.type.Item;
import mindustry.type.ItemStack;
import mindustry.type.UnitType;
import mindustry.type.Weapon;
import mindustry.world.Block;
import mindustry.world.blocks.defense.OverdriveProjector;
import mindustry.world.blocks.units.Reconstructor;

/**
 * The "nerf" rebalance a /play match runs with when it was started as
 * {@code /play nerf} (see {@link vini.evictmap.duel.MatchPatch}). It is the
 * overdrive/reconstructor datapatch, applied to the game's content objects
 * instead of shipped as a client mod - the server stays the only thing that
 * has to be updated, exactly like every other Evict rule.
 * <p>
 * What it changes, one to one with the datapatch it was written from:
 * <ul>
 * <li><b>overdrive-projector</b> - boost 1.5 to 1.25, phase boost 0.75 to 0.2,
 * power 3.5 to 10/t, requirements raised, build time 352.5.</li>
 * <li><b>overdrive-dome</b> - boost 2.5 to 1.5, power 10 to 25/t, requirements
 * raised plus 20 phase fabric, build time 838.</li>
 * <li><b>phase-weaver</b> and <b>surge-smelter</b> - plastanium added to their
 * requirements, longer build time.</li>
 * <li><b>exponential-</b> and <b>tetrative-reconstructor</b> - reshuffled
 * requirements, longer build and construct times.</li>
 * <li><b>navanax</b> - EMP time boost 3 to 2; <b>quasar</b> - weaker and much
 * slower to rise/land; <b>vela</b> - slower to rise/land, less boost speed.</li>
 * </ul>
 * <p>
 * This mutates shared content objects, so it is applied exactly once per
 * process and only ever on a match worker - a worker hosts one match and then
 * exits, so nothing has to be undone. The hub never applies it.
 * <p>
 * Clients are not patched (they install nothing): a client still draws the
 * vanilla build cost and boost numbers, while the server charges and simulates
 * these. That is the same server-authoritative split every other Evict rule
 * already lives with.
 */
public final class NerfPatch {

    /**
     * Content objects are shared and this patch is not reversible, so it runs
     * once per process. A second call would re-add the extra requirement
     * stacks it appends.
     */
    private static boolean applied = false;

    private NerfPatch() {
    }

    /** Applies the rebalance to the game's content. Safe to call twice. */
    public static void apply() {
        if (applied) {
            return;
        }

        applied = true;

        patchOverdrive();
        patchCrafters();
        patchReconstructors();
        patchUnits();

        Log.info("[EvictMapGenerator] Applied the nerf rebalance patch.");
    }

    /** Whether this process is running the rebalance. */
    public static boolean isApplied() {
        return applied;
    }

    private static void patchOverdrive() {
        if (Blocks.overdriveProjector instanceof OverdriveProjector projector) {
            projector.speedBoost = 1.25f;
            projector.speedBoostPhase = 0.2f;
            amount(projector, Items.lead, 150);
            amount(projector, Items.titanium, 100);
            amount(projector, Items.silicon, 100);
            amount(projector, Items.plastanium, 60);
            power(projector, 10f);
            projector.buildTime = 352.5f;
        }

        if (Blocks.overdriveDome instanceof OverdriveProjector dome) {
            dome.speedBoost = 1.5f;
            amount(dome, Items.surgeAlloy, 150);
            amount(dome, Items.lead, 250);
            amount(dome, Items.titanium, 160);
            amount(dome, Items.silicon, 160);
            amount(dome, Items.plastanium, 100);
            require(dome, Items.phaseFabric, 20);
            power(dome, 25f);
            dome.buildTime = 838f;
            dome.description = "Consumes huge amounts of power to significantly"
                    + " increase the speed of nearby buildings. Requires phase"
                    + " fabric and silicon to operate. Does not stack.";
        }
    }

    private static void patchCrafters() {
        require(Blocks.phaseWeaver, Items.plastanium, 40);
        Blocks.phaseWeaver.buildTime = 322f;

        require(Blocks.surgeSmelter, Items.plastanium, 50);
        Blocks.surgeSmelter.buildTime = 262f;
    }

    private static void patchReconstructors() {
        Block exponential = Blocks.exponentialReconstructor;
        amount(exponential, Items.plastanium, 225);
        amount(exponential, Items.lead, 1000);
        amount(exponential, Items.silicon, 1000);
        amount(exponential, Items.titanium, 425);
        amount(exponential, Items.thorium, 500);
        amount(exponential, Items.phaseFabric, 300);
        exponential.buildTime = 3574.5f;
        constructTime(exponential, 6000f);

        Block tetrative = Blocks.tetrativeReconstructor;
        amount(tetrative, Items.lead, 2000);
        amount(tetrative, Items.silicon, 500);
        amount(tetrative, Items.thorium, 1500);
        amount(tetrative, Items.plastanium, 300);
        amount(tetrative, Items.phaseFabric, 300);
        amount(tetrative, Items.surgeAlloy, 400);
        tetrative.buildTime = 4939f;
        constructTime(tetrative, 9000f);
    }

    private static void patchUnits() {
        // Both navanax EMP mounts are mirrored copies of one weapon and share
        // the bullet instance, so every EMP bullet it carries is this one.
        for (Weapon weapon : UnitTypes.navanax.weapons) {
            if (weapon.bullet instanceof EmpBulletType emp) {
                emp.timeIncrease = 2f;
            }
        }

        UnitType quasar = UnitTypes.quasar;
        quasar.health = 500f;
        quasar.armor = 7f;
        quasar.boostMultiplier = 1.7f;
        quasar.riseSpeed = 0.00555f;
        quasar.descentSpeed = 0.00555f;

        UnitType vela = UnitTypes.vela;
        vela.boostMultiplier = 2f;
        vela.riseSpeed = 0.00555f;
        vela.descentSpeed = 0.00555f;
    }

    /**
     * Sets the amount of a requirement the block already has. A block that
     * does not ask for the item at all is a vanilla change this patch has not
     * been updated for, so say so rather than silently skipping it.
     */
    private static void amount(Block block, Item item, int amount) {
        for (ItemStack stack : block.requirements) {
            if (stack.item == item) {
                stack.amount = amount;
                return;
            }
        }

        Log.warn(
                "[EvictMapGenerator] Nerf patch: @ has no @ requirement to raise.",
                block.name,
                item.name
        );
    }

    /** Adds a requirement the block does not have yet, or sets its amount. */
    private static void require(Block block, Item item, int amount) {
        for (ItemStack stack : block.requirements) {
            if (stack.item == item) {
                stack.amount = amount;
                return;
            }
        }

        ItemStack[] grown =
                Arrays.copyOf(block.requirements, block.requirements.length + 1);
        grown[grown.length - 1] = new ItemStack(item, amount);
        block.requirements = grown;
    }

    private static void power(Block block, float usagePerTick) {
        if (block.consPower == null) {
            Log.warn(
                    "[EvictMapGenerator] Nerf patch: @ consumes no power to raise.",
                    block.name
            );
            return;
        }

        block.consPower.usage = usagePerTick;
    }

    private static void constructTime(Block block, float ticks) {
        if (block instanceof Reconstructor reconstructor) {
            reconstructor.constructTime = ticks;
        }
    }
}
