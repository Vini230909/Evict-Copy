package vini.evictmap.gameplay;

import arc.struct.ObjectMap;
import arc.util.Log;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.UnitTypes;
import mindustry.entities.bullet.BulletType;
import mindustry.game.Team;
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
 * Apply static rules to the map.
 * <p>
 * Included are all changes to Vars.state.rules, banned blocks, building damage modifiers,
 * and core unit damage modifiers. The last two are only applied once per-process, but the first two
 * must be reapplied every time the map is reloaded (at each PlayEvent).
 * </p>
 */
public final class RulesApplier {
    /**
     * BvB damage multiplier to be used.
     */
    private static final float BVB_DAMAGE_MULTIPLIER = 0.1f;
    /**
     * If the rules have been applied at least once before (for building and core unit damage modifiers).
     */
    private static boolean rulesAppliedAtLeastOnce = false;

    /**
     * Apply the rules to the current game state.
     */
    public static void applyRules() {
        // Set the parameter to true to enable god mode (hacks) for development.
        applyVarsStateRulesChanges();
        applyBannedBlocks();
        if (rulesAppliedAtLeastOnce) return;
        applyBuildingDamageModifiers();
        applyCoreUnitDamageModifiers();
        rulesAppliedAtLeastOnce = true;

        Log.info("[EvictMapGenerator] Applied rules.");
    }

    /**
     * Apply changes to {@link Vars#state#rules}.
     */
    private static void applyVarsStateRulesChanges() {
        boolean isGodMode = System.getProperty("god-mode", "false").equals("true");
        if (isGodMode) Log.info("God Mode enabled");
        Vars.state.rules.allowEditRules = isGodMode;
        Vars.state.rules.infiniteResources = isGodMode;
        Vars.state.rules.waveTimer = false;
        Vars.state.rules.waveSending = false;
        Vars.state.rules.waves = false;
        Vars.state.rules.winWave = 0;
        Vars.state.rules.pvp = true;
        Vars.state.rules.pvpAutoPause = false; // (we implement our own)
        Vars.state.rules.canGameOver = false;
        Vars.state.rules.reactorExplosions = false;
        Vars.state.rules.possessionAllowed = false;
        Vars.state.rules.unitDamageMultiplier = 0.5f;
        Vars.state.rules.unitCrashDamageMultiplier = 0.0f;
        Vars.state.rules.disableWorldProcessors = true;
        Vars.state.rules.blockDamageMultiplier = 0.5f;
        Vars.state.rules.buildSpeedMultiplier = 1.4f;
        Vars.state.rules.cleanupDeadTeams = false;

        for (Team team : Team.all) {
            Vars.state.rules.teams.get(team).cheat = isGodMode;
            Vars.state.rules.teams.get(team).fillItems = isGodMode;
            Vars.state.rules.teams.get(team).infiniteResources = isGodMode;
        }

        Vars.state.rules.defaultTeam = TeamManager.FALLEN_TEAM;
        Vars.state.rules.loadout.clear();
    }

    /**
     * Apply banned blocks settings.
     */
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

    /**
     * Apply building damage modifiers.
     */
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

    /**
     * Apply core unit damage modifiers.
     */
    private static void applyCoreUnitDamageModifiers() {
        Set<BulletType> visited = new HashSet<>();
        scaleUnitDamage(0.0f, UnitTypes.alpha, visited);
        scaleUnitDamage(0.0f, UnitTypes.beta, visited);
        scaleUnitDamage(0.0f, UnitTypes.gamma, visited);
    }

    private static void scaleUnitDamage(float multiplier, UnitType unit, Set<BulletType> visited) {
        for (Weapon weapon : unit.weapons)
            scaleBulletDamage(multiplier, weapon.bullet, visited);
    }

    private static void scaleAmmoTypes(float multiplier, ObjectMap<?, BulletType> ammoTypes, Set<BulletType> visited) {
        for (BulletType bullet : ammoTypes.values()) scaleBulletDamage(multiplier, bullet, visited);
    }

    private static void scaleBulletDamage(float multiplier, BulletType bullet, Set<BulletType> visited) {
        if (bullet == null || !visited.add(bullet)) return;
        bullet.buildingDamageMultiplier *= multiplier;

        scaleBulletDamage(multiplier, bullet.fragBullet, visited);
        scaleBulletDamage(multiplier, bullet.intervalBullet, visited);
        scaleBulletDamage(multiplier, bullet.lightningType, visited);
    }
}
