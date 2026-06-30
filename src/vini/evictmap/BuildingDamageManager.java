package vini.evictmap;

import arc.struct.ObjectMap;
import arc.util.Log;
import mindustry.Vars;
import mindustry.entities.bullet.BulletType;
import mindustry.world.Block;
import mindustry.world.blocks.defense.turrets.ContinuousLiquidTurret;
import mindustry.world.blocks.defense.turrets.ContinuousTurret;
import mindustry.world.blocks.defense.turrets.ItemTurret;
import mindustry.world.blocks.defense.turrets.LiquidTurret;
import mindustry.world.blocks.defense.turrets.PowerTurret;

import java.util.HashSet;
import java.util.Set;

/**
 * Scales building-fired bullets against buildings only.
 * Unit damage is left untouched because Mindustry applies
 * buildingDamageMultiplier only when the bullet damages a building.
 */
final class BuildingDamageManager {

    private static final float BUILDING_TO_BUILDING_DAMAGE_MULTIPLIER = 0.1f;

    private boolean applied = false;

    void apply() {
        if (applied) {
            return;
        }

        Set<BulletType> visited = new HashSet<>();
        int changed = 0;

        for (Block block : Vars.content.blocks()) {
            if (block instanceof ItemTurret turret) {
                changed += scaleAmmoTypes(turret.ammoTypes, visited);
            }

            if (block instanceof LiquidTurret turret) {
                changed += scaleAmmoTypes(turret.ammoTypes, visited);
            }

            if (block instanceof ContinuousLiquidTurret turret) {
                changed += scaleAmmoTypes(turret.ammoTypes, visited);
            }

            if (block instanceof PowerTurret turret) {
                changed += scaleBulletBuildingDamage(
                        turret.shootType,
                        visited
                );
            }

            if (block instanceof ContinuousTurret turret) {
                changed += scaleBulletBuildingDamage(
                        turret.shootType,
                        visited
                );
            }
        }

        applied = true;

        Log.info(
                "[EvictMapGenerator] Scaled @ building-fired bullet types to @% building damage.",
                changed,
                Math.round(BUILDING_TO_BUILDING_DAMAGE_MULTIPLIER * 100f)
        );
    }

    private int scaleAmmoTypes(
            ObjectMap<?, BulletType> ammoTypes,
            Set<BulletType> visited
    ) {
        if (ammoTypes == null) {
            return 0;
        }

        int changed = 0;

        for (BulletType bullet : ammoTypes.values()) {
            changed += scaleBulletBuildingDamage(bullet, visited);
        }

        return changed;
    }

    private int scaleBulletBuildingDamage(
            BulletType bullet,
            Set<BulletType> visited
    ) {
        if (bullet == null || !visited.add(bullet)) {
            return 0;
        }

        bullet.buildingDamageMultiplier *=
                BUILDING_TO_BUILDING_DAMAGE_MULTIPLIER;

        return 1
                + scaleBulletBuildingDamage(bullet.fragBullet, visited)
                + scaleBulletBuildingDamage(bullet.intervalBullet, visited)
                + scaleBulletBuildingDamage(bullet.lightningType, visited);
    }
}
