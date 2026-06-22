package vini.evictmap;

import arc.util.Log;
import mindustry.Vars;

/**
 * Applies the fixed rules for every Evict round.
 *
 * This class intentionally owns only rules. It does not know anything about
 * generation, players, captures or commands.
 */
final class EvictRules {

    private EvictRules() {
    }

    static void apply(float unitBuildSpeedMultiplier) {
        Vars.state.rules.pvp = true;
        Vars.state.rules.pvpAutoPause = false;

        Vars.state.rules.waves = false;
        Vars.state.rules.waveTimer = false;
        Vars.state.rules.waveSending = false;
        Vars.state.rules.winWave = 0;

        Vars.state.rules.infiniteResources = false;
        Vars.state.rules.attackMode = false;

        // Vanilla PvP hosting changes this to 2x. Evict uses the persisted,
        // duel-synced multiplier from EvictSettings (default 1.4x).
        Vars.state.rules.unitBuildSpeedMultiplier = unitBuildSpeedMultiplier;

        // /corecap is based on the variable per-core cap system.
        Vars.state.rules.unitCapVariable = true;

        Vars.state.rules.canGameOver = false;
        Vars.state.rules.cleanupDeadTeams = false;
        Vars.state.rules.coreCapture = false;
        Vars.state.rules.defaultTeam = TeamManager.FALLEN_TEAM;

        // Personal starting items are placed once by StartLoadout.
        Vars.state.rules.loadout.clear();

        Log.info(
            "[EvictMapGenerator] Applied Evict rules: pvp=ON, pvpAutoPause=OFF, waves=OFF, vanillaGameOver=OFF, unitFactorySpeed=@x, defaultTeam=Fallen #@.",
            unitBuildSpeedMultiplier,
            TeamManager.FALLEN_TEAM_ID
        );
    }
}
