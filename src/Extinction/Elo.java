// Elo rating math for ranked matches: pure arithmetic, no state; PlayerDataManager stores the results.
package Extinction;

public final class Elo {

    // Rating a player is assumed to have before their first ranked match.
    public static final int STARTING_ELO = 1000;

    // The most points one match can move a rating; the classic chess K (an even win is +16).
    private static final double K_FACTOR = 32.0;

    private Elo() {
    }

    // Chance on the standard 400-point logistic curve that `rating` beats `opponentRating`.
    public static double expectedScore(int rating, int opponentRating) {
        return 1.0 / (1.0 + Math.pow(10.0, (opponentRating - rating) / 400.0));
    }

    // Both players' new ratings after a decisive ranked match; ratings never fall below zero.
    public static Result apply(int winnerRating, int loserRating) {
        int winnerAfter = shifted(winnerRating, 1.0, expectedScore(winnerRating, loserRating));
        int loserAfter = shifted(loserRating, 0.0, expectedScore(loserRating, winnerRating));

        return new Result(winnerRating, winnerAfter, loserRating, loserAfter);
    }

    private static int shifted(int rating, double actualScore, double expected) {
        int updated = (int) Math.round(rating + K_FACTOR * (actualScore - expected));
        return Math.max(0, updated);
    }

    // Before/after ratings for both players, so the exact swing can be stored and shown in /history.
    public record Result(int winnerBefore, int winnerAfter, int loserBefore, int loserAfter) {
        public int winnerDelta() {
            return winnerAfter - winnerBefore;
        }

        public int loserDelta() {
            return loserAfter - loserBefore;
        }
    }
}
