package vini.evictmap;

import arc.util.Log;
import mindustry.content.UnitTypes;
import mindustry.entities.bullet.BulletType;
import mindustry.type.UnitType;
import mindustry.type.Weapon;

import java.util.HashSet;
import java.util.Set;

/**
 * Disables combat damage for vanilla core units while keeping their building
 * and mining behavior intact.
 */
final class CoreUnitDamageManager {

    private boolean applied = false;

    void apply() {
        if (applied) {
            return;
        }

        Set<BulletType> visited = new HashSet<>();
        int changed =
            disableCoreUnitDamage(UnitTypes.alpha, visited)
                + disableCoreUnitDamage(UnitTypes.beta, visited)
                + disableCoreUnitDamage(UnitTypes.gamma, visited);

        applied = true;

        Log.info(
            "[EvictMapGenerator] Disabled damage for @ core-unit bullet types.",
            changed
        );
    }

    private int disableCoreUnitDamage(
        UnitType unitType,
        Set<BulletType> visited
    ) {
        if (unitType == null) {
            return 0;
        }

        int changed = 0;

        for (Weapon weapon : unitType.weapons) {
            if (weapon != null) {
                changed += disableBulletDamage(weapon.bullet, visited);
            }
        }

        return changed;
    }

    private int disableBulletDamage(
        BulletType bullet,
        Set<BulletType> visited
    ) {
        if (bullet == null || !visited.add(bullet)) {
            return 0;
        }

        bullet.damage = 0f;
        bullet.splashDamage = 0f;
        bullet.lightningDamage = 0f;
        bullet.buildingDamageMultiplier = 0f;

        /*
         * Some weapons create child bullets, fragments or lightning. Visit
         * those too so core-unit weapons cannot deal indirect damage.
         */
        return 1
            + disableBulletDamage(bullet.fragBullet, visited)
            + disableBulletDamage(bullet.intervalBullet, visited)
            + disableBulletDamage(bullet.lightningType, visited);
    }
}
