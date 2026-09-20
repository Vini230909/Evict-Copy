// Standalone menu and persistence regression checks; run with tests/test-pure-maps.ps1 after building.
package Extinction;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import arc.util.CommandHandler;
import mindustry.Vars;
import mindustry.gen.Groups;
import mindustry.gen.MenuCallPacket;
import mindustry.gen.Player;
import mindustry.net.Net;
import mindustry.net.NetConnection;
import mindustry.ui.Menus;

public final class PureMapsTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        require(!Files.exists(Path.of("config")), "tests require a fresh working directory");
        Vars.net = new Net(null) {
            @Override public boolean server() { return true; }
        };
        Groups.init();
        TestPlayer a = new TestPlayer("Alice"), b = new TestPlayer("Bob");
        TestPlayer c = new TestPlayer("Carol"), d = new TestPlayer("Dave");
        List<PureMaps.PureMap> maps = mapCatalog();
        maps.addAll(List.of(new PureMaps.PureMap("Alpha", 2), new PureMaps.PureMap("Beta", 2),
                new PureMaps.PureMap("Gamma", 2), new PureMaps.PureMap("Delta", 4)));

        Matchmaking mm = new Matchmaking(new Matches(null, ignored -> {}, null));
        MapVetoes vetoes = mm.mapVetoes;
        CommandHandler commands = new CommandHandler("/");
        Extinction.commands.Player.register(commands, mm, null, null);
        var help = new Extinction.commands.HelpCommands();
        var registerHelp = help.getClass().getDeclaredMethod("registerClientCommands", CommandHandler.class);
        registerHelp.setAccessible(true);
        registerHelp.invoke(help, commands);
        commands.handleMessage("/help", a);
        require(a.messages.get(a.messages.size() - 1).contains("/maps"), "help lists the maps command");
        vetoes.openMenu(a);
        require(a.menu().options.length == 5, "all maps plus Close in one menu");
        require(a.menu().options[2][0].contains("Delta (4 teams)"), "team counts displayed");
        click(a, "Alpha");
        require(a.menu().options[3][0].equals("[scarlet]Alpha (2 teams)[]"), "veto sorted last in scarlet");
        require(new MapVetoes().blocks(List.of(a.uuid()), "Alpha"), "veto survives reloading properties");
        require(!vetoes.blocks(List.of(b.uuid()), "Alpha"), "account vetoes are independent");
        require(Files.readString(Path.of("config/evict-map-vetoes.properties")).contains("Alice=Alpha"),
                "properties store UUID to map names");
        click(a, "Alpha");
        require(!new MapVetoes().blocks(List.of(a.uuid()), "Alpha"), "reenabling persists");
        toggle(vetoes, a, "Alpha");
        toggle(vetoes, b, "Beta");
        require(vetoes.combined(List.of(a.uuid(), b.uuid())).equals(Set.of("Alpha", "Beta")), "veto union");

        MapVetoes worker = new MapVetoes();
        System.setProperty("evict.duelWorker", "true");
        worker.openMenu(a);
        require(a.messages.get(a.messages.size() - 1).contains("only available on the hub"), "worker explanation");
        Field accounts = MapVetoes.class.getDeclaredField("accounts");
        accounts.setAccessible(true);
        require(accounts.get(worker) == null, "worker does not load the veto file");
        System.clearProperty("evict.duelWorker");

        mm.pure.openModeMenu(a);
        click(a, "Training");
        require(a.menu().options.length == 5 && !a.menu().options[0][0].startsWith("[scarlet]"),
                "Training includes every team count with own vetoes");
        require(a.menu().options[3][0].contains("[scarlet]Alpha"), "Training applies challenger veto");
        close(a);

        beginDuel(mm, a, b);
        require(a.menu().options[0][0].contains("Gamma"), "1v1 applies both vetoes after opponent selection");
        require(b.menu() == null || !b.menu().title.contains("Challenge"), "no invite before map selection");
        click(a, "Alpha");
        require(a.menu().message.contains("Pick the map"), "veto click redraws without an invite");
        click(a, "Gamma");
        require(b.menu().message.contains("Pure 1v1 PvP on Gamma"), "duel invite names selected map");
        click(b, "Decline");
        require(!mm.isBusy(a.uuid()) && !mm.isBusy(b.uuid()), "decline clears challenge");

        mm.pure.openModeMenu(a);
        click(a, "2 Team PvP");
        require(a.menu().message.contains("Each column"), "team mode opens roster before map");
        pickSlot(a, 1, "Bob");
        click(a, "Done");
        require(a.menu().options[0][0].contains("Gamma"), "roster vetoes apply to team picker");
        click(a, "Gamma");
        require(b.menu().message.contains("Pure 2 Team PvP on Gamma"), "team invite names selected map");
        click(b, "Decline");
        require(mm.drafts.draftOf(a) == null, "decline clears draft");

        toggle(vetoes, d, "Delta");
        mm.pure.openModeMenu(a);
        click(a, "4 Team PvP");
        pickSlot(a, 1, "Bob");
        pickSlot(a, 2, "Carol");
        pickSlot(a, 3, "Dave");
        click(a, "Done");
        require(a.menu().message.contains("No map is available after player vetoes"), "fourth player's veto cancels");
        require(mm.drafts.draftOf(a) == null, "empty map pool clears draft");
        click(a, "Delta");
        require(!mm.isBusy(d.uuid()), "cancelled picker cannot invite by clicking vetoed map");
        toggle(vetoes, d, "Delta");

        mm.pure.openModeMenu(a);
        click(a, "4 Team PvP");
        pickSlot(a, 1, "Bob");
        pickSlot(a, 2, "Carol");
        pickSlot(a, 3, "Dave");
        click(a, "Done");
        click(a, "Delta");
        require(List.of(b, c, d).stream().allMatch(p -> p.menu().message.contains("Pure 4 Team PvP on Delta")),
                "four-team invites all name the map");
        click(b, "Accept");
        click(c, "Accept");
        int teamInviteId = d.menu().menuId;
        toggle(vetoes, d, "Delta");
        Menus.menuChoose(d, teamInviteId, 0);
        require(a.messages.get(a.messages.size() - 1).contains("chosen map was vetoed"),
                "team launch rechecks every participant's vetoes");
        require(!mm.isBusy(a.uuid()) && !mm.isBusy(d.uuid()), "late veto clears team invite state");
        toggle(vetoes, d, "Delta");

        beginDuel(mm, a, b);
        toggle(vetoes, b, "Gamma");
        click(a, "Gamma");
        require(a.menu().message.contains("Match cancelled"), "live veto update cancels an exhausted picker");
        require(!mm.isBusy(b.uuid()), "no pending challenge from exhausted picker");
        toggle(vetoes, b, "Gamma");

        beginDuel(mm, a, b);
        click(a, "Gamma");
        int inviteId = b.menu().menuId;
        toggle(vetoes, b, "Gamma");
        Menus.menuChoose(b, inviteId, 0);
        require(a.messages.get(a.messages.size() - 1).contains("chosen map was vetoed"), "veto rechecked at acceptance");
        toggle(vetoes, b, "Gamma");

        mm.pure.openModeMenu(a);
        click(a, "2 Team PvP");
        pickSlot(a, 1, "Bob");
        click(a, "Done");
        close(a);
        require(mm.drafts.draftOf(a) == null, "closing map picker clears draft");

        beginDuel(mm, a, b);
        int pickerId = a.menu().menuId;
        mm.handlePlayerLeave(b);
        Groups.player.remove(b);
        Menus.menuChoose(a, pickerId, 0);
        require(!mm.isBusy(a.uuid()), "leaving invalidates the pending map picker");

        for (int index = 0; index < 30; index++) {
            maps.add(new PureMaps.PureMap("Extra " + index, 2));
        }
        vetoes.openMenu(a);
        require(a.menu().options.length == 35, "maps menu has no pagination");
        mm.pure.openModeMenu(a);
        click(a, "Training");
        require(a.menu().options.length == 35, "Pure map picker has no pagination");
        System.out.println("PASS: " + checks + " Pure map checks");
    }

    private static void beginDuel(Matchmaking mm, TestPlayer a, TestPlayer b) {
        mm.pure.openModeMenu(a);
        click(a, "1v1 PvP");
        require(a.menu().message.contains("Select a player"), "1v1 picks opponent first");
        click(a, b.name);
    }

    private static void toggle(MapVetoes vetoes, TestPlayer player, String map) {
        vetoes.openMenu(player);
        click(player, map);
    }

    private static void pickSlot(TestPlayer player, int column, String name) {
        Menus.menuChoose(player, player.menu().menuId, column);
        click(player, name);
    }

    private static void close(TestPlayer player) {
        Menus.menuChoose(player, player.menu().menuId, -1);
    }

    private static void click(TestPlayer player, String text) {
        MenuCallPacket menu = player.menu();
        int option = 0;
        for (String[] row : menu.options) {
            for (String cell : row) {
                if (cell.contains(text)) {
                    Menus.menuChoose(player, menu.menuId, option);
                    return;
                }
                option++;
            }
        }
        throw new AssertionError("No button " + text + " in " + menu.title);
    }

    @SuppressWarnings("unchecked")
    private static List<PureMaps.PureMap> mapCatalog() throws Exception {
        Field field = PureMaps.class.getDeclaredField("MAPS");
        field.setAccessible(true);
        return (List<PureMaps.PureMap>) field.get(null);
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static final class TestPlayer extends Player {
        final List<String> messages = new ArrayList<>();
        TestPlayer(String uuid) {
            name = uuid;
            con = new NetConnection("127.0.0.1") {
                @Override public void send(Object packet, boolean reliable) {
                    if (packet instanceof MenuCallPacket menu) latestMenu = menu;
                }
                @Override public void close() {}
            };
            con.uuid = uuid;
            Groups.player.add(this);
        }
        MenuCallPacket latestMenu;
        MenuCallPacket menu() { return latestMenu; }
        @Override public void sendMessage(String message) { messages.add(message); }
    }
}
