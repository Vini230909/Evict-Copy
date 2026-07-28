package vini.evictmap.commands;

import vini.evictmap.PlayerNameFormatter;
import vini.evictmap.data.PlayerDataManager;
import vini.evictmap.moderation.BanOrigin;
import vini.evictmap.moderation.BanRequest;

import arc.util.CommandHandler;
import mindustry.Vars;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.ui.Menus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * /ban: admin-only ban with the same UX as /info. No argument opens a picker
 * of the online players; a name searches the stored players (so it also works
 * on somebody who already left). Either way a confirmation menu names the
 * target before anything happens - a mistap in a picker must not ban anyone.
 *
 * <p>The command only hands the account over: on the hub {@code BanManager}
 * widens it to the account's known addresses, kicks it everywhere, syncs it to
 * the match servers, announces it and logs it to Discord, exactly like
 * {@code ban id} or the hammer.
 *
 * <p>It works on a match server too. There the ban takes effect locally at once
 * and is forwarded to the hub, which owns bans and applies it properly - a ban
 * that stayed on the worker would be lifted again by the next ban-list sync,
 * and the player would simply walk back onto the hub.
 */
public final class BanCommands {

    private static final int PICKER_MENU_COLUMNS = 2;

    /** Same cap as the /info picker; a tighter name reaches anyone past it. */
    private static final int MAX_PICKER_MATCHES = 20;

    /** One picker or confirm target: the account and the name shown for it. */
    private record Target(String uuid, String name) {
    }

    private final PlayerDataManager playerDataManager;

    /**
     * Where a confirmed ban goes: the hub's ban manager, or - on a match
     * server - the local ban plus the forward to the hub.
     */
    private final Consumer<BanRequest> banSeeder;

    /** Which console log the ban's line is written to. */
    private final String server;

    private final int pickerMenuId;
    private final int confirmMenuId;

    /** Admin UUID -> ordered targets shown in their picker. */
    private final Map<String, List<Target>> pickerTargetsByAdminUuid =
            new HashMap<>();

    /** Admin UUID -> the target their confirmation menu is about. */
    private final Map<String, Target> confirmTargetByAdminUuid =
            new HashMap<>();

    public BanCommands(
            PlayerDataManager playerDataManager,
            boolean hub,
            Consumer<BanRequest> banSeeder
    ) {
        this.playerDataManager = playerDataManager;
        this.banSeeder = banSeeder;
        this.server = hub ? BanOrigin.HUB : "this match server";
        this.pickerMenuId = Menus.registerMenu(this::handlePicker);
        this.confirmMenuId = Menus.registerMenu(this::handleConfirm);
    }

    public void registerClientCommands(CommandHandler handler) {
        handler.<Player>register(
                "ban",
                "[player...]",
                "Admin only: ban a player and their known addresses. No name opens a picker.",
                this::handleBan
        );
    }

    public void handlePlayerLeave(Player player) {
        if (player != null) {
            pickerTargetsByAdminUuid.remove(player.uuid());
            confirmTargetByAdminUuid.remove(player.uuid());
        }
    }

    private void handleBan(String[] args, Player player) {
        if (player == null) {
            return;
        }

        if (!player.admin) {
            player.sendMessage("[scarlet]Only admins can ban players.[]");
            return;
        }

        String query = String.join(" ", args).trim();

        if (query.isEmpty()) {
            openOnlinePicker(player);
            return;
        }

        playerDataManager.searchPlayerInfo(query, matches -> {
            if (matches.isEmpty()) {
                player.sendMessage(
                        "[scarlet]No player matches '" + query + "'.[]"
                );
                return;
            }

            if (matches.size() == 1) {
                openConfirm(player, new Target(
                        matches.get(0).uuid(),
                        matches.get(0).lastName()
                ));
                return;
            }

            openMatchesPicker(player, query, matches);
        });
    }

    private void openOnlinePicker(Player admin) {
        List<Target> targets = new ArrayList<>();

        Groups.player.each(online -> {
            // Never offer the admin themself; a self-ban is only ever a mistap.
            if (online != null && !online.uuid().equals(admin.uuid())) {
                targets.add(new Target(
                        online.uuid(),
                        PlayerNameFormatter.displayName(online)
                ));
            }
        });

        if (targets.isEmpty()) {
            admin.sendMessage("[scarlet]No other players are online.[]");
            return;
        }

        targets.sort(
                Comparator.comparing(Target::name, String.CASE_INSENSITIVE_ORDER)
        );

        showPickerMenu(admin, targets, "Select a player to ban.");
    }

    private void openMatchesPicker(
            Player admin,
            String query,
            List<PlayerDataManager.PlayerInfo> matches
    ) {
        int shown = Math.min(matches.size(), MAX_PICKER_MATCHES);

        List<Target> targets = new ArrayList<>();

        for (int index = 0; index < shown; index++) {
            targets.add(new Target(
                    matches.get(index).uuid(),
                    matches.get(index).lastName()
            ));
        }

        String description = matches.size() > shown
                ? "[lightgray]" + matches.size() + " players match '" + query
                        + "'. Showing the " + shown
                        + " most recent - refine the name for the rest.[]"
                : "Select a player to ban.";

        showPickerMenu(admin, targets, description);
    }

    private void showPickerMenu(
            Player admin,
            List<Target> targets,
            String description
    ) {
        List<String[]> rows = new ArrayList<>();
        List<String> currentRow = new ArrayList<>();

        for (Target target : targets) {
            currentRow.add(target.name());

            if (currentRow.size() == PICKER_MENU_COLUMNS) {
                rows.add(currentRow.toArray(new String[0]));
                currentRow.clear();
            }
        }

        if (!currentRow.isEmpty()) {
            rows.add(currentRow.toArray(new String[0]));
        }

        rows.add(new String[]{"[red]Cancel"});
        pickerTargetsByAdminUuid.put(admin.uuid(), targets);

        Call.menu(
                admin.con,
                pickerMenuId,
                "[scarlet]Ban a player",
                description,
                rows.toArray(new String[0][])
        );
    }

    private void handlePicker(Player admin, int option) {
        if (admin == null) {
            return;
        }

        List<Target> targets = pickerTargetsByAdminUuid.remove(admin.uuid());

        if (targets == null || option < 0 || option >= targets.size()) {
            return;
        }

        openConfirm(admin, targets.get(option));
    }

    private void openConfirm(Player admin, Target target) {
        if (target.uuid().equals(admin.uuid())) {
            admin.sendMessage("[scarlet]You cannot ban yourself.[]");
            return;
        }

        confirmTargetByAdminUuid.put(admin.uuid(), target);

        Call.menu(
                admin.con,
                confirmMenuId,
                "[scarlet]Confirm ban",
                "Ban [white]" + target.name() + "[]?\n\n"
                        + "[lightgray]This also bans the account's known addresses "
                        + "and kicks them from the server and any match.[]",
                new String[][]{{"[scarlet]Ban", "Cancel"}}
        );
    }

    private void handleConfirm(Player admin, int option) {
        if (admin == null) {
            return;
        }

        Target target = confirmTargetByAdminUuid.remove(admin.uuid());

        if (target == null || option != 0) {
            return;
        }

        // Re-checked at confirm time: the flag may have been revoked while the
        // menu sat open.
        if (!admin.admin || Vars.netServer == null) {
            return;
        }

        if (Vars.netServer.admins.isIDBanned(target.uuid())) {
            admin.sendMessage(
                    "[lightgray]" + target.name() + " is already banned.[]"
            );
            return;
        }

        // The hub widens, kicks, syncs, announces and logs; a match server bans
        // locally and forwards the same request to the hub, which does all of
        // that there.
        banSeeder.accept(BanRequest.admin(
                target.uuid(),
                BanOrigin.now(admin.plainName(), server)
        ));

        admin.sendMessage(
                "[scarlet]Banned [white]" + target.name()
                        + "[] and the account's known addresses.[]"
        );
    }
}
