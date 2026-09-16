package vini.evictmap.commands;

import arc.util.CommandHandler;
import arc.util.Strings;
import mindustry.gen.Call;
import mindustry.gen.Player;
import mindustry.ui.Menus;
import vini.evictmap.moderation.lock.LockList;
import vini.evictmap.moderation.lock.PlayerLock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /free: admin-only, frees a locked account. Same UX as /ban: no argument
 * opens a picker of the locked accounts (there are never many), a name
 * narrows them, one match goes straight to the confirmation, and nothing
 * happens without that confirmation.
 *
 * <p>Hub only in effect: the hub owns the lock list. On a match server the
 * command answers with where to go instead.
 */
public final class FreeCommands {

    private static final int PICKER_MENU_COLUMNS = 2;
    private static final int MAX_PICKER_ENTRIES = 20;

    private final PlayerLock lock;

    private final int pickerMenuId;
    private final int confirmMenuId;

    /** Admin UUID -> ordered entries shown in their picker. */
    private final Map<String, List<LockList.Entry>> pickerByAdminUuid = new HashMap<>();

    /** Admin UUID -> the entry their confirmation menu is about. */
    private final Map<String, LockList.Entry> confirmByAdminUuid = new HashMap<>();

    public FreeCommands(PlayerLock lock) {
        this.lock = lock;
        this.pickerMenuId = Menus.registerMenu(this::handlePicker);
        this.confirmMenuId = Menus.registerMenu(this::handleConfirm);
    }

    public void registerClientCommands(CommandHandler handler) {
        handler.<Player>register(
                "free",
                "[player...]",
                "Admin only: free a locked account. No name opens a picker of the locked ones.",
                this::handleFree
        );
    }

    public void handlePlayerLeave(Player player) {
        if (player != null) {
            pickerByAdminUuid.remove(player.uuid());
            confirmByAdminUuid.remove(player.uuid());
        }
    }

    private void handleFree(String[] args, Player player) {
        if (player == null) {
            return;
        }

        if (!player.admin) {
            player.sendMessage("[scarlet]Only admins can free locked accounts.[]");
            return;
        }

        List<LockList.Entry> entries = lock.lockedEntries();

        if (entries.isEmpty()) {
            player.sendMessage("[lightgray]No account is locked right now.[]");
            return;
        }

        String query = String.join(" ", args).trim();

        if (query.isEmpty()) {
            showPicker(player, entries, "Select the account to free.");
            return;
        }

        List<LockList.Entry> matches = new ArrayList<>();
        String needle = query.toLowerCase(Locale.ROOT);

        for (LockList.Entry entry : entries) {
            if (
                    entry.uuid().equals(query)
                            || Strings.stripColors(entry.name()).toLowerCase(Locale.ROOT).contains(needle)
            ) {
                matches.add(entry);
            }
        }

        if (matches.isEmpty()) {
            player.sendMessage("[scarlet]No locked account matches '" + query + "'.[]");
            return;
        }

        if (matches.size() == 1) {
            openConfirm(player, matches.get(0));
            return;
        }

        showPicker(player, matches, "Several locked accounts match '" + query + "'.");
    }

    private void showPicker(Player admin, List<LockList.Entry> entries, String description) {
        List<LockList.Entry> shown = entries.size() > MAX_PICKER_ENTRIES
                ? entries.subList(0, MAX_PICKER_ENTRIES)
                : entries;

        List<String[]> rows = new ArrayList<>();
        List<String> currentRow = new ArrayList<>();

        for (LockList.Entry entry : shown) {
            currentRow.add(Strings.stripColors(entry.name()));

            if (currentRow.size() == PICKER_MENU_COLUMNS) {
                rows.add(currentRow.toArray(new String[0]));
                currentRow.clear();
            }
        }

        if (!currentRow.isEmpty()) {
            rows.add(currentRow.toArray(new String[0]));
        }

        rows.add(new String[]{"[red]Cancel"});
        pickerByAdminUuid.put(admin.uuid(), new ArrayList<>(shown));

        Call.menu(
                admin.con,
                pickerMenuId,
                "[accent]Free a locked account",
                entries.size() > shown.size()
                        ? "[lightgray]" + entries.size() + " accounts are locked; showing the newest "
                        + shown.size() + " - give a name for the rest.[]"
                        : description,
                rows.toArray(new String[0][])
        );
    }

    private void handlePicker(Player admin, int option) {
        if (admin == null) {
            return;
        }

        List<LockList.Entry> entries = pickerByAdminUuid.remove(admin.uuid());

        if (entries == null || option < 0 || option >= entries.size()) {
            return;
        }

        openConfirm(admin, entries.get(option));
    }

    private void openConfirm(Player admin, LockList.Entry entry) {
        confirmByAdminUuid.put(admin.uuid(), entry);

        Call.menu(
                admin.con,
                confirmMenuId,
                "[accent]Confirm",
                "Free [white]" + Strings.stripColors(entry.name()) + "[]?\n\n"
                        + "[lightgray]Locked because: " + entry.reason() + "\n"
                        + "From: " + entry.ip() + "\n\n"
                        + "A freed account is verified and never locked again.[]",
                new String[][]{{"[green]Free", "Cancel"}}
        );
    }

    private void handleConfirm(Player admin, int option) {
        if (admin == null) {
            return;
        }

        LockList.Entry entry = confirmByAdminUuid.remove(admin.uuid());

        if (entry == null || option != 0 || !admin.admin) {
            return;
        }

        PlayerLock.FreeResult result = lock.free(entry.uuid(), admin.plainName());

        admin.sendMessage(
                (result.freed() ? "[green]" : "[scarlet]") + result.line() + "[]"
        );
    }
}
