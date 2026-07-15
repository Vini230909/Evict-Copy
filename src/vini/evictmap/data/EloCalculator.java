package vini.evictmap.data;

/**
 * Standard Elo rating math for ranked 1v1 duels.
 *
 * Every player starts at {@link #STARTING_ELO} (1000). After a decisive match
 * the winner and loser ratings move toward each other by an amount that depends
 * on how expected the result was: beating a much stronger opponent gains a lot,
 * beating a much weaker one gains almost nothing (and losing to them costs a
 * lot). The shift is capped by the {@link #K_FACTOR}.
 *
 * Pure math with no state or I/O; {@link PlayerDataManager} persists the results.
 */
public final class EloCalculator {

    /** Rating a player is assumed to have before their first ranked match. */
    public static final int STARTING_ELO = 1000;

    /**
     * The most points a single match can move a rating. 32 is the classic chess
     * K-factor: an evenly matched win is worth +16.
     */
    private static final double K_FACTOR = 32.0;

    private EloCalculator() {
    }

    /**
     * The probability, on the standard 400-point logistic curve, that a player
     * rated {@code rating} beats one rated {@code opponentRating}. Equal ratings
     * give {@code 0.5}.
     */
    public static double expectedScore(int rating, int opponentRating) {
        return 1.0 / (1.0 + Math.pow(10.0, (opponentRating - rating) / 400.0));
    }

    /**
     * The winner's and loser's new ratings after a decisive ranked match.
     * Ratings never fall below zero.
     */
    public static Result apply(int winnerRating, int loserRating) {
        int winnerAfter = shifted(
                winnerRating, 1.0, expectedScore(winnerRating, loserRating)
        );
        int loserAfter = shifted(
                loserRating, 0.0, expectedScore(loserRating, winnerRating)
        );

        return new Result(winnerRating, winnerAfter, loserRating, loserAfter);
    }

    private static int shifted(int rating, double actualScore, double expected) {
        int updated = (int) Math.round(rating + K_FACTOR * (actualScore - expected));
        return Math.max(0, updated);
    }

    /**
     * Before/after ratings for both players in a settled ranked match, so the
     * exact points swing can be stored and shown in /history.
     */
    public record Result(
            int winnerBefore,
            int winnerAfter,
            int loserBefore,
            int loserAfter
    ) {
        public int winnerDelta() {
            return winnerAfter - winnerBefore;
        }

        public int loserDelta() {
            return loserAfter - loserBefore;
        }
    }
}
