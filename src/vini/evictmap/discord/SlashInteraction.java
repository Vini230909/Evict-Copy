package vini.evictmap.discord;

import arc.util.serialization.Jval;

import java.util.ArrayList;
import java.util.List;

/**
 * One slash command as Discord delivered it, reduced to the parts the plugin
 * acts on.
 *
 * <p>{@code roles} and {@code administrator} are the whole reason this is a
 * record and not a raw payload: Discord's own command permissions decide who
 * <em>sees</em> the command, and a Discord server admin can re-open it to
 * everyone in Server Settings without touching the game server. So the plugin
 * checks membership itself, against what Discord signed off in this very
 * interaction, before anything is banned.
 *
 * <p>Plain data: it crosses from the gateway's thread to the command worker's.
 */
record SlashInteraction(
        String id,
        String token,
        String guildId,
        String command,
        String argument,
        List<String> roles,
        boolean administrator,
        String actor
) {

    /** Interaction type 2 - an application (slash) command was used. */
    private static final int APPLICATION_COMMAND = 2;

    /** Discord's ADMINISTRATOR permission bit. */
    private static final long ADMINISTRATOR = 1L << 3;

    /** The single option both commands take. */
    private static final String ARGUMENT = "target";

    /** Shown when Discord sends no usable name for the member. */
    private static final String UNKNOWN_ACTOR = "someone on Discord";

    /**
     * Reads an INTERACTION_CREATE payload, or returns null when it is not a
     * slash command run by a member of a Discord server - a ping, a button, or
     * a command used in a DM has no member and therefore no roles to check.
     */
    static SlashInteraction parse(Jval payload) {
        if (payload == null
                || !payload.isObject()
                || payload.getInt("type", -1) != APPLICATION_COMMAND) {
            return null;
        }

        Jval data = payload.get("data");
        Jval member = payload.get("member");

        if (data == null || !data.isObject() || member == null || !member.isObject()) {
            return null;
        }

        String id = payload.getString("id", "");
        String token = payload.getString("token", "");
        String command = data.getString("name", "");

        if (id.isEmpty() || token.isEmpty() || command.isEmpty()) {
            return null;
        }

        return new SlashInteraction(
                id,
                token,
                payload.getString("guild_id", ""),
                command,
                argument(data),
                roles(member),
                (permissions(member.getString("permissions", "0")) & ADMINISTRATOR) != 0L,
                actor(member)
        );
    }

    private static String argument(Jval data) {
        Jval options = data.get("options");

        if (options == null || !options.isArray()) {
            return "";
        }

        for (Jval option : options.asArray()) {
            if (option != null
                    && option.isObject()
                    && ARGUMENT.equals(option.getString("name", ""))) {
                return option.getString("value", "");
            }
        }

        return "";
    }

    private static List<String> roles(Jval member) {
        Jval list = member.get("roles");

        if (list == null || !list.isArray()) {
            return List.of();
        }

        List<String> roles = new ArrayList<>();

        for (Jval role : list.asArray()) {
            if (role != null && role.isString()) {
                roles.add(role.asString());
            }
        }

        return List.copyOf(roles);
    }

    /**
     * The name that ends up in the ban log's "Banned by" field, so a ban made
     * from Discord can be traced back to a person like any other.
     */
    private static String actor(Jval member) {
        Jval user = member.get("user");

        if (user == null || !user.isObject()) {
            return UNKNOWN_ACTOR;
        }

        String name = user.getString("global_name", "");

        if (name == null || name.isBlank()) {
            name = user.getString("username", "");
        }

        return name == null || name.isBlank() ? UNKNOWN_ACTOR : name;
    }

    /** Discord sends the permission bitfield as a decimal string. */
    private static long permissions(String raw) {
        try {
            return Long.parseLong(raw == null ? "0" : raw.trim());
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }
}
