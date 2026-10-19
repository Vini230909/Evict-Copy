// Serializes server dialogs so a required warning never overlaps another menu or text input.
package Extinction;

import mindustry.gen.Call;
import mindustry.gen.Player;
import mindustry.net.NetConnection;
import mindustry.ui.Menus;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

public final class Dialogs {
    private record Active(Player player, int id, boolean text, boolean warning) { }
    private record Queued(Player player, int id, boolean text, Runnable show) { }
    private static final Map<String, Active> active = new HashMap<>();
    private static final Map<String, ArrayDeque<Queued>> queued = new HashMap<>();
    private static final int INFO_MENU = Menus.registerMenu((player, option) -> { });
    private static Predicate<Player> warnings = player -> false;
    private static boolean enabled;

    private Dialogs() { }

    static void warnings(Predicate<Player> predicate) {
        warnings = predicate;
        enabled = true;
    }

    public static void menu(NetConnection connection, int id, String title, String text, String[][] options) {
        enqueue(connection, id, false, () -> Call.menu(connection, id, title, text, options));
    }

    public static void textInput(NetConnection connection, int id, String title, String text,
                                 int length, String value, boolean numeric) {
        textInput(connection, id, title, text, length, value, numeric, false);
    }

    public static void textInput(NetConnection connection, int id, String title, String text,
                                 int length, String value, boolean numeric, boolean allowEmpty) {
        enqueue(connection, id, true,
                () -> Call.textInput(connection, id, title, text, length, value, numeric, allowEmpty));
    }

    // Vanilla's information dialog has no close packet; an identical text menu supplies the acknowledgement.
    public static void infoMessage(NetConnection connection, String text) {
        menu(connection, INFO_MENU, "", text, new String[][]{{"OK"}});
    }

    private static void enqueue(NetConnection connection, int id, boolean text, Runnable show) {
        if (!enabled) { show.run(); return; }
        if (connection == null || connection.player == null || connection.kicked) return;
        Player player = connection.player;
        if (!active.containsKey(player.uuid()) && !warnings.test(player)) {
            active.put(player.uuid(), new Active(player, id, text, false));
            show.run();
            return;
        }
        ArrayDeque<Queued> waiting = queued.computeIfAbsent(player.uuid(), ignored -> new ArrayDeque<>());
        waiting.removeIf(item -> item.id == id && item.text == text);
        waiting.addLast(new Queued(player, id, text, show));
    }

    static boolean menuResult(Player player, int id) {
        return result(player, id, false);
    }

    static boolean textResult(Player player, int id) {
        return result(player, id, true);
    }

    private static boolean result(Player player, int id, boolean text) {
        if (player == null) return false;
        Active shown = active.get(player.uuid());
        if (shown == null) return !warnings.test(player);
        if (shown.player != player || shown.id != id || shown.text != text) return false;
        if (!shown.warning) active.remove(player.uuid());
        return true;
    }

    static boolean canWarn(Player player) {
        Active shown = active.get(player.uuid());
        return shown == null || (shown.player == player && shown.warning);
    }

    public static boolean busy(Player player) {
        return player != null && (active.containsKey(player.uuid()) || queued.containsKey(player.uuid()));
    }

    static void showWarning(Player player, int id, String text, String button) {
        active.put(player.uuid(), new Active(player, id, false, true));
        Call.followUpMenu(player.con, id, "[scarlet]Moderator warning", text, new String[][]{{button}});
    }

    static void finishWarning(Player player, int id) {
        Active shown = active.get(player.uuid());
        if (shown != null && shown.player == player && shown.warning) active.remove(player.uuid());
        Call.hideFollowUpMenu(player.con, id);
    }

    static void flush(Player player) {
        if (player == null || player.con == null || player.con.kicked
                || active.containsKey(player.uuid()) || warnings.test(player)) return;
        ArrayDeque<Queued> waiting = queued.get(player.uuid());
        while (waiting != null && !waiting.isEmpty()) {
            Queued next = waiting.removeFirst();
            if (next.player != player) continue;
            active.put(player.uuid(), new Active(player, next.id, next.text, false));
            next.show.run();
            break;
        }
        if (waiting != null && waiting.isEmpty()) queued.remove(player.uuid());
    }

    public static void forget(Player player) {
        if (player == null) return;
        active.remove(player.uuid());
        queued.remove(player.uuid());
    }
}
