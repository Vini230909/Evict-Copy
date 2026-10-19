// Standalone regression checks for Nerf match setup, worker handshakes and the complete rebalance.
package Extinction;

import java.lang.reflect.Field;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import Extinction.discord.ChatLogReporter;
import arc.Core;
import arc.Events;
import arc.Settings;
import arc.mock.MockApplication;
import arc.mock.MockFiles;
import arc.util.CommandHandler;
import arc.util.I18NBundle;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.content.UnitTypes;
import mindustry.core.ContentLoader;
import mindustry.core.GameState;
import mindustry.entities.bullet.EmpBulletType;
import mindustry.game.Team;
import mindustry.game.EventType.ContentPatchLoadEvent;
import mindustry.gen.Groups;
import mindustry.gen.MenuCallPacket;
import mindustry.gen.Player;
import mindustry.net.Net;
import mindustry.net.NetConnection;
import mindustry.io.SaveIO;
import mindustry.type.Item;
import mindustry.type.ItemStack;
import mindustry.type.Weapon;
import mindustry.ui.Menus;
import mindustry.world.Block;
import mindustry.world.blocks.defense.OverdriveProjector;
import mindustry.world.blocks.storage.CoreBlock;
import mindustry.world.blocks.units.Reconstructor;
import mindustry.world.consumers.ConsumePower;

public final class NerfTest {
    private static int checks;
    private static Map<Integer, MatchSlot> workers;

    public static void main(String[] args) throws Exception {
        require(!Files.exists(Path.of("config")), "tests require a fresh working directory");
        Core.app = new MockApplication();
        Core.files = new MockFiles();
        Core.settings = new Settings();
        Core.bundle = I18NBundle.createEmptyBundle();
        Vars.headless = true;
        Vars.net = new Net(null) {
            @Override public boolean server() { return true; }
            @Override public void send(Object packet, boolean reliable) {}
        };
        Groups.init();
        if (args.length > 0 && args[0].equals("client")) {
            testClient(Path.of(args[1]));
            System.out.println("PASS: " + checks + " unmodified client Nerf checks");
            return;
        }
        Events.on(ContentPatchLoadEvent.class, event -> {
            Path file = Path.of("config/patches/extinction-nerf.hjson");
            if (!Files.exists(file)) return;
            try {
                String patch = Files.readString(file);
                if (!event.patches.contains(patch)) event.patches.add(patch);
            } catch (java.io.IOException exception) {
                throw new RuntimeException(exception);
            }
        });
        testProvision();
        if (args.length > 0) {
            testPatch(MatchMode.fromId(args[0]));
            System.out.println("PASS: " + checks + " Pure worker Nerf checks");
            return;
        }
        Config.duelServerIp = "127.0.0.1";
        Matches matches = new Matches(null, ignored -> {}, new ChatLogReporter(null));
        // Reserve real slots, but never execute the background task that launches a server.
        field(Matches.class, "spawnExecutor").set(matches, new DeferredExecutor());
        workers = workerSlots(matches);
        mapCatalog().addAll(List.of(new PureMaps.PureMap("Frontier", 2),
                new PureMaps.PureMap("Crossroads", 4)));
        testMenus(new Matchmaking(matches));
        testHandshake();
        testPatch(MatchMode.TRAINING);
        System.out.println("PASS: " + checks + " Nerf checks");
    }

    private static void testMenus(Matchmaking mm) throws Exception {
        TestPlayer a = new TestPlayer("Alice"), b = new TestPlayer("Bob");
        TestPlayer c = new TestPlayer("Carol"), d = new TestPlayer("Dave");
        CommandHandler commands = new CommandHandler("/");
        Extinction.commands.Player.register(commands, mm, null, null, null, null, null, null, null, null, null, null);
        commands.handleMessage("/p nerf", a);
        require(a.menu.title.contains("Nerf"), "/p accepts nerf");
        commands.handleMessage("/play NERF", a);
        require(a.menu.title.contains("Nerf"), "/play accepts case-insensitive nerf");
        MenuCallPacket previous = a.menu;
        commands.handleMessage("/p unknown", a);
        require(a.menu == previous && a.messages.get(a.messages.size() - 1).contains("none, nerf"),
                "unknown options explain valid choices without starting setup");
        click(a, "Extinction");
        click(a, "Training");
        launched(MatchMode.TRAINING, null, true);

        commands.handleMessage("/p", a);
        click(a, "Extinction");
        click(a, "Training");
        launched(MatchMode.TRAINING, null, false);
        commands.handleMessage("/p nerf", a);
        click(a, "Extinction");
        click(a, "Sandbox");
        launched(MatchMode.SANDBOX, null, true);

        begin(mm, a, "Extinction", "Unranked");
        MenuCallPacket selection = a.menu;
        mm.openGameMenu(a, "none");
        click(a, selection, "Bob");
        require(b.menu.message.contains("navanax") && b.menu.title.contains("Nerf"),
                "duel invitation warns about the full patch and freezes the player's selection");
        mm.openGameMenu(a, "none");
        click(b, "Accept");
        launched(MatchMode.ONE_VS_ONE, null, true);

        mm.openGameMenu(a, "none");
        click(a, "Extinction");
        click(a, "1v1");
        click(a, "Bob");
        require(!b.menu.title.contains("Nerf"), "plain invitations remain plain");
        mm.openGameMenu(a, "nerf");
        click(b, "Accept");
        launched(MatchMode.RANKED, null, false);

        begin(mm, a, "Extinction", "Teams");
        MenuCallPacket draft = a.menu;
        mm.openGameMenu(a, "none");
        click(a, draft, "Next team");
        click(a, "Bob");
        click(a, "Done");
        require(b.menu.title.contains("Nerf"), "Extinction team draft preserves its patch");
        click(b, "Accept");
        launched(MatchMode.TEAMS, null, true);

        begin(mm, a, "Extinction", "Random Teams");
        click(a, "2");
        click(a, "Bob");
        click(a, "Done");
        click(b, "Accept");
        launched(MatchMode.TEAMS, null, true);
        begin(mm, a, "Extinction", "FFA");
        click(a, "Bob");
        click(a, "Done");
        click(b, "Accept");
        launched(MatchMode.FFA, null, true);

        begin(mm, a, "Pure", "Training");
        MenuCallPacket maps = a.menu;
        mm.openGameMenu(a, "none");
        click(a, maps, "Frontier");
        launched(MatchMode.PURE_TRAINING, "Frontier", true);
        commands.handleMessage("/p none", a);
        click(a, "Pure");
        click(a, "Training");
        click(a, "Frontier");
        launched(MatchMode.PURE_TRAINING, "Frontier", false);

        begin(mm, a, "Pure", "1v1 PvP");
        selection = a.menu;
        mm.openGameMenu(a, "none");
        click(a, selection, "Bob");
        click(a, "Frontier");
        require(b.menu.message.contains("Frontier") && b.menu.message.contains("quasar"),
                "Pure duel invitation names the map and the full patch");
        mm.openGameMenu(a, "none");
        click(b, "Accept");
        launched(MatchMode.PURE_1V1, "Frontier", true);

        begin(mm, a, "Pure", "2 Team PvP");
        pickSlot(a, 1, "Bob");
        click(a, "Done");
        maps = a.menu;
        mm.openGameMenu(a, "none");
        click(a, maps, "Frontier");
        require(b.menu.title.contains("Nerf"), "Pure roster preserves its patch through the map picker");
        click(b, "Accept");
        launched(MatchMode.PURE_2TEAM, "Frontier", true);

        begin(mm, a, "Pure", "4 Team PvP");
        pickSlot(a, 1, "Bob");
        pickSlot(a, 2, "Carol");
        pickSlot(a, 3, "Dave");
        click(a, "Done");
        click(a, "Crossroads");
        require(List.of(b, c, d).stream().allMatch(player -> player.menu.message.contains("vela")),
                "every Pure four-team invite warns about the patch");
        click(b, "Accept");
        click(c, "Accept");
        click(d, "Accept");
        launched(MatchMode.PURE_4TEAM, "Crossroads", true);
    }

    private static void launched(MatchMode mode, String map, boolean nerf) {
        require(workers.size() == 1, "one match reserved for " + mode);
        MatchSlot slot = workers.values().iterator().next();
        require(slot.mode == mode && java.util.Objects.equals(slot.map, map), "correct game and map reserved");
        require(slot.nerf == nerf, "selected patch reaches the worker slot for " + mode);
        require(slot.modeLabel().contains("Nerf") == nerf, "mode label identifies the patch");
        require(slot.process == null, "test never launches a worker process");
        workers.clear();
    }

    private static void testHandshake() throws Exception {
        Path directory = Files.createDirectory(Path.of("handshake"));
        Path file = directory.resolve(MatchHandshake.FILE_NAME);
        require(!MatchHandshake.read(file.toFile()).nerf, "missing handshake defaults to no patch");
        Files.writeString(file, "mode=training\nteam.count=1\nteam.1=Alice\n");
        require(!MatchHandshake.read(file.toFile()).nerf, "legacy handshake defaults to no patch");
        MatchSlot slot = new MatchSlot(6568);
        slot.mode = MatchMode.PURE_TRAINING;
        slot.map = "Frontier";
        slot.nerf = true;
        MatchHandshake.write(directory.toFile(), slot, List.of(List.of("Alice")));
        Properties properties = new Properties();
        try (var input = Files.newInputStream(file)) { properties.load(input); }
        require(properties.getProperty("patch").equals("nerf"), "Nerf is written explicitly to the worker");
        MatchHandshake handshake = MatchHandshake.read(file.toFile());
        require(handshake.nerf && handshake.mode == slot.mode && handshake.map.equals(slot.map),
                "Pure worker reads the selected patch without losing mode or map");
        slot.nerf = false;
        MatchHandshake.write(directory.toFile(), slot, List.of(List.of("Alice")));
        try (var input = Files.newInputStream(file)) { properties.load(input); }
        require(properties.getProperty("patch").equals("none") && !MatchHandshake.read(file.toFile()).nerf,
                "plain matches overwrite a reused folder's Nerf selection");
    }

    private static void testProvision() throws Exception {
        Path config = Files.createDirectories(Path.of("provision/config"));
        Path legacy = Files.createDirectories(config.resolve("patches"));
        Path modern = Files.createDirectories(config.resolve("assets/patches"));
        Path legacyManaged = legacy.resolve("extinction-nerf.hjson");
        Path modernManaged = modern.resolve("extinction-nerf.hjson");
        Path otherLegacy = legacy.resolve("operator.hjson");
        Path otherModern = modern.resolve("operator.hjson");
        Files.writeString(otherLegacy, "legacy operator patch");
        Files.writeString(otherModern, "modern operator patch");
        Nerf.provision(config.toFile(), false);
        require(!Files.exists(legacyManaged) && !Files.exists(modernManaged), "plain worker has no managed Nerf patch");
        Files.writeString(modernManaged, "stale migrated Nerf");
        Nerf.provision(config.toFile(), true);
        require(Files.exists(legacyManaged) && !Files.exists(modernManaged), "Nerf provisioning replaces migrated stale patch");
        String patch = Files.readString(legacyManaged);
        require(patch.startsWith("name: \"[25/50/50/100] ODnerf\""), "managed file preserves the supplied patch name");
        require(patch.contains("requirements.+: [") && patch.contains("1.bullet.timeIncrease: 2"),
                "managed file preserves indexed additions and both Navanax weapon edits");
        Files.writeString(modernManaged, patch);
        Nerf.provision(config.toFile(), false);
        require(!Files.exists(legacyManaged) && !Files.exists(modernManaged), "plain reused worker removes both managed patch paths");
        require(Files.readString(otherLegacy).equals("legacy operator patch")
                && Files.readString(otherModern).equals("modern operator patch"), "provisioning preserves other operator patches");
    }

    private static void testPatch(MatchMode mode) throws Exception {
        initializeContent();
        String vanilla = fingerprint();
        MatchSlot slot = new MatchSlot(6568);
        slot.mode = mode;
        slot.nerf = true;
        if (mode.pure()) slot.map = "Frontier";
        MatchHandshake.write(Path.of(".").toFile(), slot, List.of(List.of("Alice")));
        readPatches(new byte[]{0});
        new Referee().begin();
        require(fingerprint().equals(vanilla) && Vars.state.patcher.patches.isEmpty(),
                "hub never applies a worker handshake's patch");
        System.setProperty("evict.duelWorker", "true");
        Nerf.provision(Path.of("config").toFile(), false);
        slot.nerf = false;
        MatchHandshake.write(Path.of(".").toFile(), slot, List.of(List.of("Alice")));
        readPatches(new byte[]{0});
        Referee plainWorker = new Referee();
        plainWorker.begin();
        require(plainWorker.handshakeLoaded(), "plain worker loads its handshake");
        require(fingerprint().equals(vanilla) && Vars.state.patcher.patches.isEmpty(),
                "plain worker keeps vanilla content and sends no patch");
        slot.nerf = true;
        MatchHandshake.write(Path.of(".").toFile(), slot, List.of(List.of("Alice")));
        Nerf.provision(Path.of("config").toFile(), true);
        Blocks.overdriveDome.checkStats();
        Object vanillaStats = Blocks.overdriveDome.stats;
        readPatches(new byte[]{0});
        require(Vars.state.patcher.patches.size == 1, "worker map load installs a native data patch");
        require(Vars.state.patcher.patches.first().name.equals("[25/50/50/100] ODnerf"),
                "native patch keeps the supplied ODnerf name");
        require(Blocks.overdriveDome.stats != vanillaStats, "native patch invalidates cached dome stats");
        assertPatchHealthy();
        Referee nerfWorker = new Referee();
        nerfWorker.begin();
        require(nerfWorker.handshakeLoaded() && nerfWorker.matchMode() == mode,
                "Nerf worker starts the requested game mode");
        assertNerfValues();

        byte[] worldPatches = writePatches();
        require(worldPatches.length > 1 && worldPatches[0] == 1,
                "world snapshot contains the native patch for joining clients");
        Files.write(Path.of(mode.id() + "-nerf.patches"), worldPatches);
        resetContent(vanilla);
        readPatches(worldPatches);
        require(Vars.state.patcher.patches.size == 1, "loading a patched save never duplicates Nerf");
        assertPatchHealthy();
        assertNerfValues();

        resetContent(vanilla);
        // Existing maps predate native patch chunks; this is their real load event path.
        SaveIO.getSaveWriter(10).readContentHeader(new DataInputStream(new ByteArrayInputStream(new byte[]{0})));
        nerfWorker.begin();
        require(Vars.state.patcher.patches.size == 1,
                "Training reload restores the patch without restarting the referee");
        assertPatchHealthy();
        assertNerfValues();
        resetContent(vanilla);
        testMapPatch(slot, vanilla);
        System.clearProperty("evict.duelWorker");
    }

    private static void testMapPatch(MatchSlot slot, String vanilla) throws Exception {
        OverdriveProjector dome = (OverdriveProjector) Blocks.overdriveDome;
        float vanillaRange = dome.range, vanillaBoost = dome.speedBoost;
        byte[] mapPatch = "{\"name\":\"Map\",\"block\":{\"overdrive-dome\":{\"range\":222}}}"
                .getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeByte(1);
        output.writeInt(mapPatch.length);
        output.write(mapPatch);
        readPatches(bytes.toByteArray());
        require(Vars.state.patcher.patches.size == 2, "Nerf preserves the map's existing data patch");
        number(dome.range, 222f, "map patch remains effective alongside Nerf");
        assertPatchHealthy();
        assertNerfValues();
        resetContent(vanilla);
        number(dome.range, vanillaRange, "reset also restores the map patch");

        slot.nerf = false;
        MatchHandshake.write(Path.of(".").toFile(), slot, List.of(List.of("Alice")));
        Nerf.provision(Path.of("config").toFile(), false);
        readPatches(bytes.toByteArray());
        require(Vars.state.patcher.patches.size == 1, "plain worker preserves only the map's existing patch");
        number(dome.range, 222f, "plain worker retains its map patch");
        number(dome.speedBoost, vanillaBoost, "plain worker never adds Nerf to a patched map");
        assertPatchHealthy();
        resetContent(vanilla);
        number(dome.range, vanillaRange, "plain map patch resets normally");
    }

    private static void testClient(Path worldPatches) throws Exception {
        initializeContent();
        String vanilla = fingerprint();
        require(!Boolean.getBoolean("evict.duelWorker"), "joining client has no worker setting");
        readPatches(Files.readAllBytes(worldPatches));
        require(Vars.state.patcher.patches.size == 1, "unmodified client receives the worker's native patch");
        assertPatchHealthy();
        assertNerfValues();
        resetContent(vanilla);
        readPatches(new byte[]{0});
        require(fingerprint().equals(vanilla) && Vars.state.patcher.patches.isEmpty(),
                "returning to a normal server keeps vanilla content");
    }

    private static void initializeContent() {
        Vars.content = new ContentLoader();
        Vars.content.createBaseContent();
        Vars.content.init();
        Vars.state = new GameState();
        Team.sharded.data().cores.add(((CoreBlock) Blocks.coreShard).new CoreBuild());
    }

    private static void readPatches(byte[] bytes) throws Exception {
        SaveIO.getSaveWriter().readContentPatches(new DataInputStream(new ByteArrayInputStream(bytes)));
    }

    private static byte[] writePatches() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        SaveIO.getSaveWriter().writeContentPatches(new DataOutputStream(bytes));
        return bytes.toByteArray();
    }

    private static void resetContent(String vanilla) {
        Vars.state.patcher.unapply();
        require(fingerprint().equals(vanilla), "native reset restores all changed stats, costs and consumers");
        Vars.state = new GameState();
        Team.sharded.data().cores.add(((CoreBlock) Blocks.coreShard).new CoreBuild());
    }

    private static void assertPatchHealthy() {
        for (var patch : Vars.state.patcher.patches) {
            require(!patch.error && patch.warnings.isEmpty(), "native patch loads without errors or warnings: "
                    + patch.warnings);
        }
    }

    private static String fingerprint() {
        StringBuilder result = new StringBuilder();
        for (Block block : List.of(Blocks.overdriveProjector, Blocks.overdriveDome, Blocks.phaseWeaver,
                Blocks.surgeSmelter, Blocks.exponentialReconstructor, Blocks.tetrativeReconstructor)) {
            result.append(block.name).append(':').append(Arrays.toString(block.requirements))
                    .append(':').append(block.buildTime).append(':').append(block.consPower.usage);
            if (block instanceof OverdriveProjector overdrive) {
                result.append(':').append(overdrive.speedBoost).append(':').append(overdrive.speedBoostPhase);
            }
            if (block instanceof Reconstructor reconstructor) result.append(':').append(reconstructor.constructTime);
        }
        for (var unit : List.of(UnitTypes.quasar, UnitTypes.vela)) {
            result.append(':').append(unit.health).append(':').append(unit.armor)
                    .append(':').append(unit.boostMultiplier).append(':').append(unit.riseSpeed)
                    .append(':').append(unit.descentSpeed);
        }
        for (Weapon weapon : UnitTypes.navanax.weapons) {
            if (weapon.bullet instanceof EmpBulletType emp) result.append(':').append(emp.timeIncrease);
        }
        return result.toString();
    }

    private static void assertNerfValues() {
        OverdriveProjector projector = (OverdriveProjector) Blocks.overdriveProjector;
        number(projector.speedBoost, 1.25f, "projector boost");
        number(projector.speedBoostPhase, 0.2f, "projector phase boost");
        requirements(projector, Items.lead, 150, Items.titanium, 100, Items.silicon, 100, Items.plastanium, 60);
        number(projector.consPower.usage, 10f, "projector power");
        number(projector.buildTime, 352.5f, "projector build time");
        OverdriveProjector dome = (OverdriveProjector) Blocks.overdriveDome;
        number(dome.speedBoost, 1.5f, "dome boost");
        requirements(dome, Items.surgeAlloy, 150, Items.lead, 250, Items.titanium, 160,
                Items.silicon, 160, Items.plastanium, 100, Items.phaseFabric, 20);
        number(dome.consPower.usage, 25f, "dome power");
        number(dome.buildTime, 838f, "dome build time");
        requirements(Blocks.phaseWeaver, Items.plastanium, 40);
        number(Blocks.phaseWeaver.buildTime, 322f, "phase weaver build time");
        requirements(Blocks.surgeSmelter, Items.plastanium, 50);
        number(Blocks.surgeSmelter.buildTime, 262f, "surge smelter build time");
        Reconstructor exponential = (Reconstructor) Blocks.exponentialReconstructor;
        requirements(exponential, Items.plastanium, 225, Items.lead, 1000, Items.silicon, 500,
                Items.titanium, 1000, Items.thorium, 425, Items.phaseFabric, 300);
        number(exponential.buildTime, 3574.5f, "exponential build time");
        number(exponential.constructTime, 6000f, "exponential construct time");
        Reconstructor tetrative = (Reconstructor) Blocks.tetrativeReconstructor;
        requirements(tetrative, Items.lead, 2000, Items.silicon, 1500, Items.thorium, 500,
                Items.plastanium, 300, Items.phaseFabric, 300, Items.surgeAlloy, 400);
        number(tetrative.buildTime, 4939f, "tetrative build time");
        number(tetrative.constructTime, 9000f, "tetrative construct time");
        int empMounts = 0;
        for (Weapon weapon : UnitTypes.navanax.weapons) {
            if (weapon.bullet instanceof EmpBulletType emp) {
                empMounts++;
                number(emp.timeIncrease, 2f, "navanax EMP time increase");
            }
        }
        require(empMounts == 2, "both Navanax EMP mounts are patched");
        require(UnitTypes.navanax.weapons.get(0).bullet instanceof EmpBulletType
                && UnitTypes.navanax.weapons.get(1).bullet instanceof EmpBulletType,
                "supplied weapon indices identify both Navanax EMP mounts");
        number(UnitTypes.quasar.health, 500f, "quasar health");
        number(UnitTypes.quasar.armor, 7f, "quasar armor");
        number(UnitTypes.quasar.boostMultiplier, 1.7f, "quasar boost speed");
        number(UnitTypes.quasar.riseSpeed, 0.00555f, "quasar rise speed");
        number(UnitTypes.quasar.descentSpeed, 0.00555f, "quasar descent speed");
        number(UnitTypes.vela.boostMultiplier, 2f, "vela boost speed");
        number(UnitTypes.vela.riseSpeed, 0.00555f, "vela rise speed");
        number(UnitTypes.vela.descentSpeed, 0.00555f, "vela descent speed");
        for (Block block : List.of(projector, dome)) {
            long powerConsumers = Arrays.stream(block.consumers).filter(consume -> consume instanceof ConsumePower).count();
            require(powerConsumers == 1, block.name + " has exactly one power consumer");
        }
        require(dome.consumesItem(Items.phaseFabric) && dome.consumesItem(Items.silicon),
                "dome keeps both input-item consumers after power replacement");
    }

    private static void requirements(Block block, Object... expected) {
        for (int index = 0; index < expected.length; index += 2) {
            Item item = (Item) expected[index];
            int count = 0, amount = -1;
            for (ItemStack stack : block.requirements) {
                if (stack.item == item) { count++; amount = stack.amount; }
            }
            require(count == 1 && amount == (int) expected[index + 1], block.name + " cost for " + item.name);
        }
    }

    private static void number(float actual, float expected, String message) {
        require(Math.abs(actual - expected) < 0.00001f, message + ": " + actual + " != " + expected);
    }

    private static void begin(Matchmaking mm, TestPlayer player, String game, String mode) {
        mm.openGameMenu(player, "nerf");
        click(player, game);
        click(player, mode);
    }

    private static void pickSlot(TestPlayer player, int column, String name) {
        Menus.menuChoose(player, player.menu.menuId, column);
        click(player, name);
    }

    private static void click(TestPlayer player, String text) { click(player, player.menu, text); }

    private static void click(TestPlayer player, MenuCallPacket menu, String text) {
        int option = 0;
        for (String[] row : menu.options) {
            for (String cell : row) {
                if (cell.contains(text)) { Menus.menuChoose(player, menu.menuId, option); return; }
                option++;
            }
        }
        throw new AssertionError("No button " + text + " in " + menu.title);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, MatchSlot> workerSlots(Matches matches) throws Exception {
        return (Map<Integer, MatchSlot>) field(Matches.class, "workers").get(matches);
    }

    @SuppressWarnings("unchecked")
    private static List<PureMaps.PureMap> mapCatalog() throws Exception {
        return (List<PureMaps.PureMap>) field(PureMaps.class, "MAPS").get(null);
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static final class DeferredExecutor extends AbstractExecutorService {
        @Override public void execute(Runnable task) {}
        @Override public void shutdown() {}
        @Override public List<Runnable> shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return false; }
    }

    private static final class TestPlayer extends Player {
        final List<String> messages = new ArrayList<>();
        MenuCallPacket menu;
        TestPlayer(String uuid) {
            name = uuid;
            con = new NetConnection("127.0.0.1") {
                @Override public void send(Object packet, boolean reliable) {
                    if (packet instanceof MenuCallPacket sent) menu = sent;
                }
                @Override public void close() {}
            };
            con.uuid = uuid;
            Groups.player.add(this);
        }
        @Override public void sendMessage(String message) { messages.add(message); }
    }
}
