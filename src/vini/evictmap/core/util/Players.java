package vini.evictmap.core.util;

import arc.struct.Seq;
import mindustry.gen.Groups;
import mindustry.gen.Player;

import java.util.Locale;

/**
 * Player-lookup helpers.
 *
 * <p>Centralises the {@code Groups.player.find(p -> p.uuid().equals(uuid))}
 * idiom that was hand-written in a dozen places, so there is a single seam for
 * finding players (and a single thing to change if lookup ever needs caching).
 */
public final class Players {

    private Players() {
    }

    /** The online player with this UUID, or {@code null}. */
    public static Player byUuid(String uuid) {
        if (uuid == null) {
            return null;
        }
        return Groups.player.find(player -> player != null && uuid.equals(player.uuid()));
    }

    /** True when a player with this UUID is currently connected. */
    public static boolean isOnline(String uuid) {
        return byUuid(uuid) != null;
    }

    /**
     * Online players whose plain name contains {@code query} (case-insensitive).
     * An exact match, if present, is returned on its own so callers can treat it
     * as unambiguous.
     */
    public static Seq<Player> matchingName(String query) {
        Seq<Player> matches = new Seq<>();
        if (query == null || query.isBlank()) {
            return matches;
        }

        String needle = query.toLowerCase(Locale.ROOT);
        Player exact = null;

        for (Player player : Groups.player) {
            String name = player.plainName();
            if (name == null) {
                continue;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.equals(needle)) {
                exact = player;
            } else if (lower.contains(needle)) {
                matches.add(player);
            }
        }

        if (exact != null) {
            return Seq.with(exact);
        }
        return matches;
    }

    /**
     * Resolves {@code query} to a single online player: an exact or unique
     * partial name match, otherwise {@code null} (no match or ambiguous).
     */
    public static Player resolveOnline(String query) {
        Seq<Player> matches = matchingName(query);
        return matches.size == 1 ? matches.first() : null;
    }
}
