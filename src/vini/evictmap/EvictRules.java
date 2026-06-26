package vini.evictmap;

import arc.util.Log;
import mindustry.Vars;
import mindustry.world.Block;

import java.util.Set;

/**
 * Applies the fixed rules for every Evict round.
 *
 * This class intentionally owns only rules. It does not know anything about
 * generation, players, captures or commands.
 */
final class EvictRules {

    private EvictRules() {
    }

    static void apply(
        float unitBuildSpeedMultiplier,
        Set<String> bannedBlockNames
    ) {
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

        String bannedBlocks = applyBannedBlocks(bannedBlockNames);

        Log.info(
            "[EvictMapGenerator] Applied Evict rules: pvp=ON, pvpAutoPause=OFF, waves=OFF, vanillaGameOver=OFF, unitFactorySpeed=@x, bannedBlocks=@, defaultTeam=Fallen #@.",
            unitBuildSpeedMultiplier,
            bannedBlocks,
            TeamManager.FALLEN_TEAM_ID
        );
    }

    /**
     * Forces {@code state.rules.bannedBlocks} to exactly the given block ids, so
     * a duel worker bans the same blocks as the hub it was spawned from - no more,
     * no less. A {@code null} list means "do not manage banned blocks here" and
     * leaves the live rules untouched; this is what the hub passes, where the bans
     * are owned by the map / admin actions rather than this plugin. Unknown ids
     * are logged and skipped rather than aborting the round.
     *
     * @return a short description of what was applied, for the rules log line
     */
    private static String applyBannedBlocks(Set<String> bannedBlockNames) {
        if (bannedBlockNames == null) {
            return "unmanaged";
        }

        Vars.state.rules.bannedBlocks.clear();

        int applied = 0;

        for (String name : bannedBlockNames) {
            Block block = Vars.content.block(name);

            if (block == null) {
                Log.warn(
                    "[EvictMapGenerator] Synced banned block id '@' is not a known block; skipping.",
                    name
                );
                continue;
            }

            Vars.state.rules.bannedBlocks.add(block);
            applied++;
        }

        return Integer.toString(applied);
    }
}
