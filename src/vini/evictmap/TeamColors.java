package vini.evictmap;

import arc.graphics.Color;
import mindustry.game.Team;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Keeps teams telling-apart-able by colour.
 * <p>
 * A team's colour is a fixed function of its id and is computed identically on
 * every (vanilla) client, so the server cannot recolour a team - the only lever
 * it has against "two teams look the same" is refusing to hand out an id whose
 * colour lands too close to one already on the map. That single decision lives
 * here so {@link TeamManager} only has to say which ids are free and which
 * colours are already in play.
 */
final class TeamColors {

    /**
     * Minimum straight-line distance, in normalized RGB space (each channel
     * 0..1, so the whole cube spans sqrt(3) ~ 1.73), that a new team's colour
     * must keep from every colour already in play. Calibrated against the real
     * id palette: genuine near-duplicates measure <= ~0.31 and clearly distinct
     * colours >= ~0.52, so 0.40 sits in the gap between them.
     */
    private static final float MIN_TEAM_COLOR_DISTANCE = 0.40f;

    private TeamColors() {
    }

    /**
     * Picks a team id from {@code availableTeamIds} whose colour is far enough
     * from every colour in {@code coloursInPlay}.
     * <p>
     * Candidates are tried in random order (via {@code random}) and the first
     * one that clears {@link #MIN_TEAM_COLOR_DISTANCE} wins; each id is tested at
     * most once. When no candidate can clear the bar - a crowded round where
     * every remaining colour is close to one already in play - the least-similar
     * candidate is returned instead so the caller still gets the roomiest colour
     * left rather than nothing. Returns {@code null} only when {@code
     * availableTeamIds} is empty.
     */
    static Integer chooseDistinctTeamId(
            List<Integer> availableTeamIds,
            List<Color> coloursInPlay,
            Random random
    ) {
        if (availableTeamIds.isEmpty()) {
            return null;
        }

        List<Integer> candidates = new ArrayList<>(availableTeamIds);
        Collections.shuffle(candidates, random);

        Integer bestFallback = null;
        double bestFallbackDistance = -1;

        for (int teamId : candidates) {
            double nearest =
                    nearestColorDistance(Team.get(teamId).color, coloursInPlay);

            if (nearest >= MIN_TEAM_COLOR_DISTANCE) {
                return teamId;
            }

            if (nearest > bestFallbackDistance) {
                bestFallbackDistance = nearest;
                bestFallback = teamId;
            }
        }

        return bestFallback;
    }

    /**
     * The distance from {@code candidate} to the nearest colour in {@code
     * others}, or {@link Double#MAX_VALUE} when {@code others} is empty (nothing
     * to clash with yet).
     */
    private static double nearestColorDistance(Color candidate, List<Color> others) {
        double nearest = Double.MAX_VALUE;

        for (Color other : others) {
            nearest = Math.min(nearest, colorDistance(candidate, other));
        }

        return nearest;
    }

    /** Straight-line distance between two colours in normalized RGB space. */
    private static double colorDistance(Color a, Color b) {
        double dr = a.r - b.r;
        double dg = a.g - b.g;
        double db = a.b - b.b;
        return Math.sqrt(dr * dr + dg * dg + db * db);
    }
}
