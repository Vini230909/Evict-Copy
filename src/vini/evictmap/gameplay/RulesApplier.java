package vini.evictmap.gameplay;

import arc.struct.ObjectMap;
import arc.util.Log;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.UnitTypes;
import mindustry.entities.bullet.BulletType;
import mindustry.type.UnitType;
import mindustry.type.Weapon;
import mindustry.world.Block;
import mindustry.world.blocks.defense.turrets.ContinuousLiquidTurret;
import mindustry.world.blocks.defense.turrets.ContinuousTurret;
import mindustry.world.blocks.defense.turrets.ItemTurret;
import mindustry.world.blocks.defense.turrets.LiquidTurret;
import mindustry.world.blocks.defense.turrets.PowerTurret;
import vini.evictmap.TeamManager;

import java.util.HashSet;
import java.util.Set;

/**
 * Scales building-fired bullets against buildings only.
 * Unit damage is left untouched because Mindustry applies
 * buildingDamageMultiplier only when the bullet damages a building.
 */
public final class RulesApplier {

    private static final float BVB_DAMAGE_MULTIPLIER = 0.1f;

    private static boolean rulesAppliedAtLeastOnce = false;

    public static void applyRules(boolean isGodMode) {
        applyVarsStateRulesChanges(isGodMode);
        applyBannedBlocks();
        if(rulesAppliedAtLeastOnce) return;
        applyBuildingDamageModifiers();
        applyCoreUnitDamageModifiers();
        rulesAppliedAtLeastOnce = true;

        Log.info("[EvictMapGenerator] Applied rules.");
    }

    private static void applyVarsStateRulesChanges(boolean isGodMode) {
        Vars.state.rules.allowEditRules = isGodMode;
        Vars.state.rules.infiniteResources = isGodMode;
        Vars.state.rules.waveTimer = false;
        Vars.state.rules.waveSending = false;
        Vars.state.rules.waves = false;
        Vars.state.rules.winWave = 0;
        Vars.state.rules.pvp = true;
        Vars.state.rules.pvpAutoPause = false; // (we implement our own)
        Vars.state.rules.attackMode = false;
        Vars.state.rules.editor = false;
        Vars.state.rules.reactorExplosions = false;
        Vars.state.rules.possessionAllowed = false;
        Vars.state.rules.unitDamageMultiplier = 0.5f;
        Vars.state.rules.unitCrashDamageMultiplier = 0.0f;
        Vars.state.rules.disableWorldProcessors = true;
        Vars.state.rules.blockDamageMultiplier = 0.5f;
        Vars.state.rules.buildSpeedMultiplier = 1.4f;
        Vars.state.rules.cleanupDeadTeams = false;
        Vars.state.rules.defaultTeam = TeamManager.FALLEN_TEAM;
        Vars.state.rules.loadout.clear();
    }

    private static void applyBannedBlocks() {
        Vars.state.rules.bannedBlocks.clear();

        Vars.state.rules.bannedBlocks.add(Blocks.ripple);
        Vars.state.rules.bannedBlocks.add(Blocks.foreshadow);
        Vars.state.rules.bannedBlocks.add(Blocks.scrapWall);
        Vars.state.rules.bannedBlocks.add(Blocks.scrapWallLarge);
        Vars.state.rules.bannedBlocks.add(Blocks.scrapWallHuge);
        Vars.state.rules.bannedBlocks.add(Blocks.scrapWallGigantic);
        Vars.state.rules.bannedBlocks.add(Blocks.microProcessor);
        Vars.state.rules.bannedBlocks.add(Blocks.logicProcessor);
        Vars.state.rules.bannedBlocks.add(Blocks.hyperProcessor);
        Vars.state.rules.bannedBlocks.add(Blocks.memoryCell);
        Vars.state.rules.bannedBlocks.add(Blocks.memoryBank);
        Vars.state.rules.bannedBlocks.add(Blocks.logicDisplay);
        Vars.state.rules.bannedBlocks.add(Blocks.largeLogicDisplay);
        Vars.state.rules.bannedBlocks.add(Blocks.tileLogicDisplay);
    }

    private static void applyBuildingDamageModifiers() {
        Set<BulletType> visited = new HashSet<>();
        for (Block block : Vars.content.blocks()) {
            if (block instanceof ItemTurret turret)
                scaleAmmoTypes(BVB_DAMAGE_MULTIPLIER, turret.ammoTypes, visited);
            else if (block instanceof LiquidTurret turret)
                scaleAmmoTypes(BVB_DAMAGE_MULTIPLIER, turret.ammoTypes, visited);
            else if (block instanceof ContinuousLiquidTurret turret)
                scaleAmmoTypes(BVB_DAMAGE_MULTIPLIER, turret.ammoTypes, visited);
            else if (block instanceof PowerTurret turret)
                scaleBulletDamage(BVB_DAMAGE_MULTIPLIER, turret.shootType, visited);
            else if (block instanceof ContinuousTurret turret)
                scaleBulletDamage(BVB_DAMAGE_MULTIPLIER, turret.shootType, visited);
        }
    }

    private static void applyCoreUnitDamageModifiers() {
        Set<BulletType> visited = new HashSet<>();
        scaleUnitDamage(0.0f, UnitTypes.alpha, visited);
        scaleUnitDamage(0.0f, UnitTypes.beta, visited);
        scaleUnitDamage(0.0f, UnitTypes.gamma, visited);
    }

    private static void scaleUnitDamage(float multiplier, UnitType unit, Set<BulletType> visited) {
        for(Weapon weapon : unit.weapons)
            scaleBulletDamage(multiplier, weapon.bullet, visited);
    }

    private static void scaleAmmoTypes(float multiplier, ObjectMap<?, BulletType> ammoTypes, Set<BulletType> visited) {
        for (BulletType bullet : ammoTypes.values()) scaleBulletDamage(multiplier, bullet, visited);
    }

    private static void scaleBulletDamage(float multiplier, BulletType bullet , Set<BulletType> visited) {
        if(bullet == null || !visited.add(bullet)) return;
        bullet.buildingDamageMultiplier *= multiplier;

        scaleBulletDamage(multiplier, bullet.fragBullet, visited);
        scaleBulletDamage(multiplier, bullet.intervalBullet, visited);
        scaleBulletDamage(multiplier, bullet.lightningType, visited);
    }
}
