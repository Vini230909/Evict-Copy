// /ban and the player list's ban button: an admin picks a player and types the reason; nothing happens without one.
package Extinction;

import Extinction.data.PlayerDataManager;

import mindustry.Vars;
import mindustry.gen.AdminRequestCallPacket;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.net.Packets.AdminAction;
import mindustry.ui.Menus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

// Only hands the account over: the hub's Bans widens, kicks, syncs, announces and logs it; a match server bans
// locally and forwards it (WorkerBans), because a ban that stayed on the worker is lifted by the next sync.
public final class BanMenu {

    private static final int PICKER_MENU_COLUMNS = 2;

    // Same cap as the /info picker; a tighter name reaches anyone past it.
    private static final int MAX_PICKER_MATCHES = 20;

    // Longest reason the pop-up takes: one line in the ban log.
    private static final int MAX_REASON_LENGTH = 120;

    // One picker or reason target: the account and the name shown for it.
    private record Target(String uuid, String name) {
    }

    private final PlayerDataManager playerDataManager;

    // Where a ban goes: the hub's Bans, or on a match server the local ban plus the forward.
    private final Consumer<Bans.Request> banSeeder;

    // Which console log the ban's line is written to.
    private final String server;

    private final int pickerMenuId;
    private final int reasonInputId;

    private boolean installed;

    // Admin UUID -> ordered targets shown in their picker.
    private final Map<String, List<Target>> pickerTargetsByAdminUuid =
            new HashMap<>();

    // Admin UUID -> the target their reason pop-up is about.
    private final Map<String, Target> reasonTargetByAdminUuid =
            new HashMap<>();

    public BanMenu(
            PlayerDataManager playerDataManager,
            boolean hub,
            Consumer<Bans.Request> banSeeder
    ) {
        this.playerDataManager = playerDataManager;
        this.banSeeder = banSeeder;
        this.server = hub ? Bans.Origin.HUB : "this match server";
        this.pickerMenuId = Menus.registerMenu(this::handlePicker);
        this.reasonInputId = Menus.registerTextInput(this::handleReason);
    }

    // Both roles: the player list's ban button asks for the reason too, instead of vanilla's instant ban. Safe to call once.
    // Every other admin action - and a refused ban request - goes on to vanilla untouched.
    public void install() {
        if (installed || Vars.net == null) {
            return;
        }

        installed = true;

        Vars.net.handleServer(AdminRequestCallPacket.class, (con, packet) -> {
            Player admin = con.player;

            if (
                    packet.action != AdminAction.ban
                            || admin == null
                            || con.kicked
                            || !admin.admin
                            || packet.other == null
                            || (packet.other.admin && packet.other != admin)
            ) {
                packet.handleServer(con);
                return;
            }

            askReason(admin, new Target(packet.other.uuid(), PlayerNames.displayName(packet.other)), "");
        });
    }

    public void handlePlayerLeave(Player player) {
        if (player != null) {
            pickerTargetsByAdminUuid.remove(player.uuid());
            reasonTargetByAdminUuid.remove(player.uuid());
        }
    }

    // No argument opens a picker of the online players; a name searches the stored ones, so it reaches leavers too.
    public void handleBan(String[] args, Player player) {
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
                askReason(player, new Target(
                        matches.get(0).uuid(),
                        matches.get(0).lastName()
                ), "");
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
                        PlayerNames.displayName(online)
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

        askReason(admin, targets.get(option), "");
    }

    // The reason pop-up is the confirmation too: cancelled, nobody is banned; empty, it asks again.
    private void askReason(Player admin, Target target, String warning) {
        if (target.uuid().equals(admin.uuid())) {
            admin.sendMessage("[scarlet]You cannot ban yourself.[]");
            return;
        }

        reasonTargetByAdminUuid.put(admin.uuid(), target);

        Call.textInput(
                admin.con,
                reasonInputId,
                "Ban " + target.name(),
                warning + "Why? The reason goes into the ban log. Cancel bans nobody.\n\n"
                        + "[lightgray]This also bans the account's known addresses "
                        + "and kicks them from the server and any match.[]",
                MAX_REASON_LENGTH,
                "",
                false,
                false
        );
    }

    private void handleReason(Player admin, String text) {
        if (admin == null) {
            return;
        }

        Target target = reasonTargetByAdminUuid.remove(admin.uuid());

        if (target == null) {
            return;
        }

        if (text == null) {
            admin.sendMessage("[lightgray]Ban cancelled - " + target.name() + "[lightgray] was not banned.[]");
            return;
        }

        String reason = text.replaceAll("\\s+", " ").trim();

        if (reason.isEmpty()) {
            askReason(admin, target, "[scarlet]A ban needs a reason.[]\n\n");
            return;
        }

        // Re-checked now: the flag may have been revoked while the pop-up sat open.
        if (!admin.admin || Vars.netServer == null) {
            return;
        }

        if (Vars.netServer.admins.isIDBanned(target.uuid())) {
            admin.sendMessage(
                    "[lightgray]" + target.name() + " is already banned.[]"
            );
            return;
        }

        banSeeder.accept(Bans.Request.admin(
                target.uuid(),
                Bans.Origin.now(admin.plainName(), server, reason)
        ));

        admin.sendMessage(
                "[scarlet]Banned [white]" + target.name()
                        + "[] and the account's known addresses.[]"
        );
    }
}
