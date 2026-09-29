// The VPN scan: looks an address up at both sources, remembers the verdict for a day, and hands it to whoever asked.
package Extinction;

import Extinction.core.io.Secrets;
import Extinction.core.util.PluginLog;
import Extinction.data.PlayerDataManager;

import arc.util.Time;
import mindustry.Vars;
import mindustry.gen.Player;
import mindustry.net.Administration;

import java.io.File;
import java.net.InetAddress;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

// Hub only; it decides nothing - LockGate does. Cost control (one lookup per address, caps, pauses, a ceiling in flight)
// all fails open: a join that cannot be looked up is let in. All state is main-thread only (see GAMEPLAY.md, VPN scan).
public final class VpnScan {

    private static final String CACHE_FILE = "config/evict-vpn-cache.properties";

    // How long a verdict is trusted before the address is asked about again.
    private static final long CACHE_TTL_MILLIS = 24L * 60L * 60L * 1000L;

    // Lookups waiting for an answer (over all sources) before further joins are skipped.
    private static final int MAX_IN_FLIGHT = 50;

    // How long a hit waits for the account's database row before it is written without it.
    private static final float PROFILE_WAIT_TICKS = 5f * 60f;

    // An address literal; a hostname would make InetAddress resolve it.
    private static final Pattern ADDRESS_LITERAL = Pattern.compile("^[0-9a-fA-F.:%]+$");

    // Where a hit goes besides the console - the ban log.
    private final Consumer<VpnVerdict.Hit> log;

    // Where the console test's verdict goes - the same ban log, marked as a test.
    private final Consumer<VpnVerdict> testLog;

    // Whether that log is wired, for the status line.
    private final BooleanSupplier logConfigured;

    // The plugin database's row for an account, delivered on the main thread.
    private final BiConsumer<String, Consumer<PlayerDataManager.PlayerInfo>> profiles;

    private final VpnVerdict.Cache cache =
            new VpnVerdict.Cache(new File(CACHE_FILE), CACHE_TTL_MILLIS);

    // Created in start(), so a worker never opens HTTP clients for it.
    private VpnApi vpnapi;
    private final List<VpnSources.State> sources = new ArrayList<>();

    private boolean started;

    private String day = "";
    private int cachedToday;
    private int hitsToday;
    private int cleanToday;
    private int skippedToday;

    private int inFlight;

    // Addresses with a lookup in flight, and who waits for it: the prefetch and the join share one request.
    private final Map<String, List<Consumer<VpnVerdict>>> waitingByIp = new LinkedHashMap<>();

    public VpnScan(
            Consumer<VpnVerdict.Hit> log,
            Consumer<VpnVerdict> testLog,
            BooleanSupplier logConfigured,
            BiConsumer<String, Consumer<PlayerDataManager.PlayerInfo>> profiles
    ) {
        this.log = log;
        this.testLog = testLog;
        this.logConfigured = logConfigured;
        this.profiles = profiles;
    }

    // Hub-only startup: reads the key and the remembered verdicts.
    public void start() {
        if (started) {
            return;
        }

        started = true;
        openSources();
        loadKey();
        cache.load();

        if (!Config.vpnScan) {
            PluginLog.info("VPN scan is off ('vpn on' starts it).");
            return;
        }

        if (!vpnapi.ready()) {
            PluginLog.warn(
                    "VPN scan is on with ip-api only: @ is not set in @ (optional - vpnapi.io is the second opinion; 'vpn reload' after adding it).",
                    Secrets.VPNAPI_KEY,
                    Secrets.path()
            );
        }

        PluginLog.info(
                "VPN scan is on (@): new and locked accounts are looked up for the lock, a banned account's new address before it is banned. @ address(es) remembered from before.",
                sourceNames(),
                cache.size()
        );
    }

    // Re-reads the secrets file; true when a vpnapi key is now loaded.
    public boolean reloadKey() {
        if (vpnapi == null) {
            openSources();
        }

        loadKey();
        return vpnapi.ready();
    }

    public boolean hasKey() {
        return vpnapi != null && vpnapi.ready();
    }

    // A connection just opened: start the lookup now, while the client downloads the world, so the gate never waits.
    public void prefetch(String ip) {
        if (!started || !Config.vpnScan || ip == null || ip.isBlank() || isLocal(ip)) {
            return;
        }

        rollDay();

        VpnVerdict remembered = cache.get(ip);

        if (remembered != null && complete(remembered)) {
            return;
        }

        lookup(ip, verdict -> {
            if (verdict != null) {
                cache.put(verdict);
            }
        });
    }

    // One join: the decision (LockGate) gets the verdict exactly once - null when nothing could be learned - and
    // returns true when it wrote the join up itself; otherwise a hit is written here.
    public void handlePlayerJoin(Player player, Function<VpnVerdict, Boolean> decision) {
        if (player == null) {
            return;
        }

        String ip = player.con == null ? "" : player.con.address;

        if (!started || !Config.vpnScan || ip == null || ip.isBlank() || isLocal(ip)) {
            decide(decision, null);
            return;
        }

        // Captured now: the player may be gone by the time the answer lands.
        String uuid = player.uuid();
        String name = player.name;

        rollDay();

        VpnVerdict remembered = cache.get(ip);

        if (remembered != null && complete(remembered)) {
            cachedToday++;
            count(remembered);

            if (!decide(decision, remembered)) {
                report(name, uuid, ip, remembered, true);
            }

            return;
        }

        boolean asked = lookup(ip, verdict -> {
            if (verdict != null) {
                cache.put(verdict);
            }

            count(verdict);

            if (!decide(decision, verdict) && verdict != null) {
                report(name, uuid, ip, verdict, false);
            }
        });

        if (!asked) {
            skippedToday++;
            decide(decision, null);
        }
    }

    // Runs the decision, if any; true when it took the join over.
    private static boolean decide(Function<VpnVerdict, Boolean> decision, VpnVerdict verdict) {
        if (decision == null) {
            return false;
        }

        try {
            return Boolean.TRUE.equals(decision.apply(verdict));
        } catch (Exception exception) {
            PluginLog.err("VPN scan: the lock decision failed: @", exception.toString());
            return false;
        }
    }

    // One address asked about outside a join - a banned account's new address (Evasion): the remembered verdict or a
    // lookup; done runs exactly once, with null when nothing could be learned (scan off, local address, no source).
    public void check(String ip, Consumer<VpnVerdict> done) {
        if (!started || !Config.vpnScan || ip == null || ip.isBlank() || isLocal(ip)) {
            done.accept(null);
            return;
        }

        rollDay();

        VpnVerdict remembered = cache.get(ip);

        if (remembered != null && complete(remembered)) {
            cachedToday++;
            count(remembered);
            done.accept(remembered);
            return;
        }

        boolean asked = lookup(ip, verdict -> {
            if (verdict != null) {
                cache.put(verdict);
            }

            count(verdict);
            done.accept(verdict);
        });

        if (!asked) {
            skippedToday++;
            done.accept(null);
        }
    }

    // Today's tally for the checklist: flagged or clean, however the verdict was asked for.
    private void count(VpnVerdict verdict) {
        if (verdict == null) {
            return;
        }

        if (verdict.flagged()) {
            hitsToday++;
        } else {
            cleanToday++;
        }
    }

    // Console test: one address at every source right now (it counts against the day), each answer printed, and the
    // verdict posted into the ban log marked as a test - so one command proves the keys and the channel both.
    public void test(String ip, Consumer<String> out) {
        if (vpnapi == null) {
            openSources();
            loadKey();
        }

        String address = ip == null ? "" : ip.trim();

        if (address.isEmpty() || !ADDRESS_LITERAL.matcher(address).matches()) {
            out.accept("Give an IP address to try: vpn test 1.2.3.4");
            return;
        }

        rollDay();

        boolean asked = lookup(address, verdict -> {
            if (verdict == null) {
                out.accept(
                        "Lookup failed at every source - see 'vpn' for each one's last error."
                );
                return;
            }

            cache.put(verdict);
            testLog.accept(verdict);
            out.accept(
                    address + ": " + verdict.flags() + " - " + verdict.network()
                            + (verdict.flagged()
                            ? " - a new account from here would be locked, a banned account's address not banned."
                            : " - a new account from here passes, a banned account's address would be banned.")
                            + (logConfigured.getAsBoolean()
                            ? " A test line is on its way to the ban log channel."
                            : " The ban log is not set ('banlog <url>'), so the test reaches the console only.")
            );
        });

        if (!asked) {
            out.accept(
                    "No source can be asked right now (no key, paused, or over its cap) - see 'vpn'."
            );
        }
    }

    // The console checklist.
    public List<String> statusLines() {
        rollDay();
        List<String> lines = new ArrayList<>();

        lines.add(
                "VPN scan: " + (Config.vpnScan
                        ? "on - looks up new and locked accounts (the lock) and banned accounts' new addresses (ban evasion)"
                        : "off ('vpn on' starts it)")
        );

        for (VpnSources.State state : sources) {
            StringBuilder line = new StringBuilder("  ")
                    .append(state.source.name()).append(": ");

            if (state.keyRejected) {
                line.append("KEY REJECTED - check ").append(Secrets.VPNAPI_KEY)
                        .append(" in ").append(Secrets.path())
                        .append(", then 'vpn reload'");
            } else {
                line.append(state.source.setupLine());
            }

            line.append("; today (UTC) ").append(state.requestsToday)
                    .append(" lookup(s) sent, cap ").append(state.source.dailyCap());

            if (state.source.minuteCap() > 0) {
                line.append(" a day / ").append(state.source.minuteCap()).append(" a minute");
            }

            long pauseLeft = state.pausedUntilMillis - System.currentTimeMillis();

            if (pauseLeft > 0L) {
                line.append("; PAUSED for another ")
                        .append(Math.max(1L, pauseLeft / 1000L)).append(" s (allowance spent)");
            }

            if (!state.lastError.isEmpty()) {
                line.append("; last error: ").append(state.lastError);
            }

            lines.add(line.toString());
        }

        lines.add(
                "  Ban log: " + (logConfigured.getAsBoolean()
                        ? "set - locks and ban evasion are written there and to the console"
                        : "not set ('banlog <url>') - locks and ban evasion reach the console only")
        );

        lines.add(
                "  Today (UTC): " + cachedToday + " answered from the cache, "
                        + hitsToday + " flagged (VPN, proxy or hosting), " + cleanToday + " clean, "
                        + skippedToday + " skipped (no source could be asked)"
        );

        lines.add(
                "  Cache: " + cache.size() + " address(es) in " + cache.path()
                        + ", each kept " + (CACHE_TTL_MILLIS / 3_600_000L) + " h"
        );

        return lines;
    }

    // True when every source that can be asked today had its say; a verdict missing one is not trusted for another day.
    private boolean complete(VpnVerdict verdict) {
        for (VpnSources.State state : sources) {
            if (
                    state.source.ready()
                            && !state.keyRejected
                            && !verdict.flagsBySource().containsKey(state.source.name())
            ) {
                return false;
            }
        }

        return true;
    }

    private void openSources() {
        vpnapi = new VpnApi();
        sources.clear();
        sources.add(new VpnSources.State(vpnapi));
        sources.add(new VpnSources.State(new IpApi()));
    }

    private void loadKey() {
        Secrets.reload();
        vpnapi.setKey(Secrets.get(Secrets.VPNAPI_KEY));

        for (VpnSources.State state : sources) {
            state.keyRejected = false;
        }
    }

    private String sourceNames() {
        List<String> names = new ArrayList<>(sources.size());

        for (VpnSources.State state : sources) {
            if (state.source.ready()) {
                names.add(state.source.name());
            }
        }

        return String.join(" + ", names);
    }

    // Asks every source that can be asked; done gets the combined verdict - null when none answered. False, without
    // calling done, when no source could be asked at all.
    private boolean lookup(String ip, Consumer<VpnVerdict> done) {
        List<Consumer<VpnVerdict>> waiting = waitingByIp.get(ip);

        if (waiting != null) {
            // Already being asked about - the prefetch, or another join a moment ago. One request, every asker told.
            waiting.add(done);
            return true;
        }

        long now = System.currentTimeMillis();

        if (inFlight >= MAX_IN_FLIGHT) {
            return false;
        }

        List<VpnSources.State> asked = new ArrayList<>(sources.size());

        for (VpnSources.State state : sources) {
            if (state.take(now)) {
                asked.add(state);
            }
        }

        if (asked.isEmpty()) {
            return false;
        }

        List<Consumer<VpnVerdict>> askers = new ArrayList<>(2);
        askers.add(done);
        waitingByIp.put(ip, askers);

        Map<String, VpnSources.Result> answers = new LinkedHashMap<>();
        int[] pending = {asked.size()};
        inFlight += asked.size();

        for (VpnSources.State state : asked) {
            state.source.lookup(ip, result -> {
                inFlight--;
                rollDay();
                answers.put(state.source.name(), result);

                if (result.ok()) {
                    state.lastError = "";
                } else {
                    VpnSources.noteFailure(state, ip, result);
                }

                if (--pending[0] == 0) {
                    waitingByIp.remove(ip);
                    VpnVerdict verdict = VpnSources.combine(ip, answers);

                    for (Consumer<VpnVerdict> asker : askers) {
                        try {
                            asker.accept(verdict);
                        } catch (Exception exception) {
                            PluginLog.err("VPN scan: a verdict handler failed: @", exception.toString());
                        }
                    }
                }
            });
        }

        return true;
    }

    // Writes a flagged join down - every one, also the tenth of the same account: a player who keeps reconnecting is
    // itself worth seeing.
    private void report(
            String name,
            String uuid,
            String ip,
            VpnVerdict verdict,
            boolean cached
    ) {
        if (!verdict.flagged()) {
            return;
        }

        int joins = timesJoined(uuid);

        // Written exactly once: by the database's answer, or by the fallback if it never comes.
        boolean[] written = new boolean[1];

        Consumer<PlayerDataManager.PlayerInfo> write = profile -> {
            if (written[0]) {
                return;
            }

            written[0] = true;

            VpnVerdict.Hit hit = new VpnVerdict.Hit(
                    name,
                    uuid,
                    ip,
                    verdict,
                    cached,
                    profile == null ? 0L : profile.firstSeenMillis(),
                    profile == null ? 0L : profile.totalPlaytimeMillis(),
                    joins
            );

            PluginLog.info("@", hit.consoleLine());
            log.accept(hit);
        };

        if (profiles == null) {
            write.accept(null);
            return;
        }

        try {
            profiles.accept(uuid, write);
            Time.run(PROFILE_WAIT_TICKS, () -> write.accept(null));
        } catch (Exception exception) {
            write.accept(null);
        }
    }

    private static int timesJoined(String uuid) {
        if (Vars.netServer == null) {
            return 0;
        }

        Administration.PlayerInfo info = Vars.netServer.admins.getInfoOptional(uuid);
        return info == null ? 0 : info.timesJoined;
    }

    private void rollDay() {
        String today = LocalDate.now(ZoneOffset.UTC).toString();

        if (today.equals(day)) {
            return;
        }

        day = today;
        cachedToday = 0;
        hitsToday = 0;
        cleanToday = 0;
        skippedToday = 0;

        for (VpnSources.State state : sources) {
            state.requestsToday = 0;
        }
    }

    // Loopback, LAN and link-local addresses - no service knows them - and anything not an address literal.
    static boolean isLocal(String ip) {
        if (!ADDRESS_LITERAL.matcher(ip).matches()) {
            return true;
        }

        try {
            InetAddress address = InetAddress.getByName(ip);
            return address.isLoopbackAddress()
                    || address.isSiteLocalAddress()
                    || address.isLinkLocalAddress()
                    || address.isAnyLocalAddress();
        } catch (Exception exception) {
            return true;
        }
    }
}
