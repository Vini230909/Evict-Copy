package Extinction.moderation.vpn;

import Extinction.moderation.lock.LockGate;

import arc.util.Time;
import mindustry.Vars;
import mindustry.gen.Player;
import mindustry.net.Administration;
import Extinction.core.io.Secrets;
import Extinction.core.util.PluginLog;
import Extinction.data.PlayerDataManager;
import Extinction.gen.EvictSettings;

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

/**
 * Looks up the address of every join and says, in the console and the ban
 * log, when it is a VPN, a proxy, a Tor exit or a data-centre range. Nothing
 * else - it is a measurement, not a rule.
 *
 * <p>The bans a player evades with a fresh account and a VPN are the one
 * pattern the ban cascade cannot see: the account is new and the address is
 * new, so nothing links them to the ban. Before anything is done about that,
 * this writes down for a week who actually arrives through a VPN - the
 * evaders, and every regular who simply likes their privacy - with the
 * account's age next to each verdict, so the rule that follows (lock the new
 * ones, let the known ones through) is drawn from what happened on this
 * server, not guessed. Until that rule exists this class blocks, kicks and
 * locks nobody, and says so on every line it writes.
 *
 * <p>Two sources, asked side by side ({@link IpLookupSource}): no single
 * database knows every VPN - the first address that slipped through was a
 * proxy in a hosting range that vpnapi.io lists as clean and ip-api.com
 * flags twice over. A hit is a hit from any source, and the line says which.
 *
 * <p>Hub only: everyone arrives at the hub first, and a match server's
 * unrostered joiner is a spectator anyway.
 *
 * <p>Cost control, because the services count requests: one lookup per
 * <em>address</em>, the combined verdict remembered for a day in a file; a
 * cap per source and per day, a per-minute cap where the service has one;
 * a pause when a service says its allowance is spent; a ceiling on lookups
 * in flight so a join flood cannot queue a hundred requests. Every one of
 * those fails open - a join that cannot be looked up is a join that is not
 * written down, never a join that is refused.
 *
 * <p>Every piece of state here belongs to the main thread: joins arrive on
 * it and every source hands its answer back on it.
 */
public final class VpnScan {

    private static final String CACHE_FILE = "config/evict-vpn-cache.properties";

    /** How long a verdict is trusted before the address is asked about again. */
    private static final long CACHE_TTL_MILLIS = 24L * 60L * 60L * 1000L;

    /** Lookups waiting for an answer (over all sources) before further joins are skipped. */
    private static final int MAX_IN_FLIGHT = 50;

    /** Failures of the "service unreachable" kind are logged at most this often, per source. */
    private static final long FAILURE_LOG_MILLIS = 60L * 1000L;

    /** How long a hit waits for the account's database row before it is written without it. */
    private static final float PROFILE_WAIT_TICKS = 5f * 60f;

    /** An address literal; a hostname would make {@link InetAddress} resolve it. */
    private static final Pattern ADDRESS_LITERAL = Pattern.compile("^[0-9a-fA-F.:%]+$");

    private final EvictSettings settings;

    /** Where a hit goes besides the console - the ban log. */
    private final Consumer<VpnScanHit> log;

    /** Where the console test's verdict goes - the same ban log, marked as a test. */
    private final Consumer<VpnVerdict> testLog;

    /** Whether that log is wired, for the status line. */
    private final BooleanSupplier logConfigured;

    /** The plugin database's row for an account, delivered on the main thread. */
    private final BiConsumer<String, Consumer<PlayerDataManager.PlayerInfo>> profiles;

    private final VpnVerdictCache cache =
            new VpnVerdictCache(new File(CACHE_FILE), CACHE_TTL_MILLIS);

    /** Created in {@link #start}, so a worker never opens HTTP clients for it. */
    private VpnApiClient vpnapi;
    private final List<SourceState> sources = new ArrayList<>();

    private boolean started;

    private String day = "";
    private int cachedToday;
    private int hitsToday;
    private int cleanToday;
    private int skippedToday;

    private int inFlight;

    /**
     * Addresses with a lookup in flight, and who is waiting for it. The
     * connection prefetch and the join that follows it ask about the same
     * address seconds apart; the second asker joins the first request
     * instead of spending another.
     */
    private final Map<String, List<Consumer<VpnVerdict>>> waitingByIp = new LinkedHashMap<>();

    /** One source's spending and health. Main thread only, like everything here. */
    private static final class SourceState {

        final IpLookupSource source;
        int requestsToday;
        int requestsThisMinute;
        long minuteStartMillis;
        long pausedUntilMillis;
        boolean keyRejected;
        String lastError = "";
        long lastFailureLogMillis;

        SourceState(IpLookupSource source) {
            this.source = source;
        }

        /** True when a lookup may be sent now; counts it when it may. */
        boolean take(long now) {
            if (keyRejected || !source.ready() || now < pausedUntilMillis) {
                return false;
            }

            if (requestsToday >= source.dailyCap()) {
                return false;
            }

            if (source.minuteCap() > 0) {
                if (now - minuteStartMillis >= 60_000L) {
                    minuteStartMillis = now;
                    requestsThisMinute = 0;
                }

                if (requestsThisMinute >= source.minuteCap()) {
                    return false;
                }

                requestsThisMinute++;
            }

            requestsToday++;
            return true;
        }
    }

    public VpnScan(
            EvictSettings settings,
            Consumer<VpnScanHit> log,
            Consumer<VpnVerdict> testLog,
            BooleanSupplier logConfigured,
            BiConsumer<String, Consumer<PlayerDataManager.PlayerInfo>> profiles
    ) {
        this.settings = settings;
        this.log = log;
        this.testLog = testLog;
        this.logConfigured = logConfigured;
        this.profiles = profiles;
    }

    /** Hub-only startup: reads the key and the remembered verdicts. */
    public void start() {
        if (started) {
            return;
        }

        started = true;
        openSources();
        loadKey();
        cache.load();

        if (!settings.vpnScanEnabled()) {
            PluginLog.info("VPN scan is off ('evictvpnscan on' starts it).");
            return;
        }

        if (!vpnapi.ready()) {
            PluginLog.warn(
                    "VPN scan is on with ip-api only: @ is not set in @ (optional - vpnapi.io is the second opinion; 'evictvpnscan reload' after adding it).",
                    Secrets.VPNAPI_KEY,
                    Secrets.path()
            );
        }

        PluginLog.info(
                "VPN scan is on, log only: every join's address is looked up (@) and a VPN, proxy or hosting range is written to the console and the ban log. Nothing is blocked. @ address(es) remembered from before.",
                sourceNames(),
                cache.size()
        );
    }

    /** Re-reads the secrets file; true when a vpnapi key is now loaded. */
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

    /**
     * A connection just opened: start the lookup now, while the client is
     * still downloading the world, so the verdict is in the cache by the time
     * the join event fires and the lock gate never has to hold anyone. Costs
     * nothing extra - the join would have asked about the same address, and
     * asks the same request instead.
     */
    public void prefetch(String ip) {
        if (!started || !settings.vpnScanEnabled() || ip == null || ip.isBlank() || isLocal(ip)) {
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

    /**
     * One join. Writes a hit down - and hands the verdict to {@code decision}
     * when there is one to hand: the {@link LockGate} decides on it. The
     * decision is called exactly once, with null when nothing could be
     * learned (scan off, local address, no source to ask, every source
     * failed), so a caller holding a player for the answer is never left
     * holding. When the decision returns true it has written the join up
     * itself (the lock's own line) and the scan's hit line is left out.
     */
    public void handlePlayerJoin(Player player, Function<VpnVerdict, Boolean> decision) {
        if (player == null) {
            return;
        }

        String ip = player.con == null ? "" : player.con.address;

        if (!started || !settings.vpnScanEnabled() || ip == null || ip.isBlank() || isLocal(ip)) {
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

            if (!decide(decision, remembered)) {
                report(name, uuid, ip, remembered, true);
            }

            return;
        }

        boolean asked = lookup(ip, verdict -> {
            if (verdict != null) {
                cache.put(verdict);
            }

            if (!decide(decision, verdict) && verdict != null) {
                report(name, uuid, ip, verdict, false);
            }
        });

        if (!asked) {
            skippedToday++;
            decide(decision, null);
        }
    }

    /** Runs the decision, if any; true when it took the join over. */
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

    /**
     * Console test: looks one address up right now at every source (it counts
     * against the day), says what each one answered and what a join from it
     * would do, and posts the verdict into the ban log marked as a test - so
     * one command proves the keys and the channel both.
     */
    public void test(String ip, Consumer<String> out) {
        if (vpnapi == null) {
            openSources();
            loadKey();
        }

        String address = ip == null ? "" : ip.trim();

        if (address.isEmpty() || !ADDRESS_LITERAL.matcher(address).matches()) {
            out.accept("Give an IP address to try: evictvpnscan test 1.2.3.4");
            return;
        }

        rollDay();

        boolean asked = lookup(address, verdict -> {
            if (verdict == null) {
                out.accept(
                        "Lookup failed at every source - see 'evictvpnscan' for each one's last error."
                );
                return;
            }

            cache.put(verdict);
            testLog.accept(verdict);
            out.accept(
                    address + ": " + verdict.flags() + " - " + verdict.network()
                            + (verdict.flagged()
                            ? " - a join from here is written to the ban log."
                            : " - a join from here passes without a line.")
                            + (logConfigured.getAsBoolean()
                            ? " A test line is on its way to the ban log channel."
                            : " The ban log is not set ('evictbanlog <url>'), so hits reach the console only.")
            );
        });

        if (!asked) {
            out.accept(
                    "No source can be asked right now (no key, paused, or over its cap) - see 'evictvpnscan'."
            );
        }
    }

    /** The console checklist. */
    public List<String> statusLines() {
        rollDay();
        List<String> lines = new ArrayList<>();

        lines.add(
                "VPN scan: " + (settings.vpnScanEnabled()
                        ? "on, log only - nothing is blocked, kicked or locked"
                        : "off ('evictvpnscan on' starts it)")
        );

        for (SourceState state : sources) {
            StringBuilder line = new StringBuilder("  ")
                    .append(state.source.name()).append(": ");

            if (state.keyRejected) {
                line.append("KEY REJECTED - check ").append(Secrets.VPNAPI_KEY)
                        .append(" in ").append(Secrets.path())
                        .append(", then 'evictvpnscan reload'");
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
                "  Hits go to: " + (logConfigured.getAsBoolean()
                        ? "the console and the ban log"
                        : "the console only - the ban log is not set ('evictbanlog <url>')")
        );

        lines.add(
                "  Today (UTC): " + cachedToday + " join(s) answered from the cache, "
                        + hitsToday + " hit(s), " + cleanToday + " clean, "
                        + skippedToday + " skipped (no source could be asked)"
        );

        lines.add(
                "  Cache: " + cache.size() + " address(es) in " + cache.path()
                        + ", each kept " + (CACHE_TTL_MILLIS / 3_600_000L) + " h"
        );

        return lines;
    }

    /**
     * True when every source that can be asked today has had its say in this
     * verdict. A verdict remembered from before a source existed - or from a
     * day the vpnapi key was missing - is not worth trusting for another day
     * when the missing source could answer now.
     */
    private boolean complete(VpnVerdict verdict) {
        for (SourceState state : sources) {
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
        vpnapi = new VpnApiClient();
        sources.clear();
        sources.add(new SourceState(vpnapi));
        sources.add(new SourceState(new IpApiClient()));
    }

    private void loadKey() {
        Secrets.reload();
        vpnapi.setKey(Secrets.get(Secrets.VPNAPI_KEY));

        for (SourceState state : sources) {
            state.keyRejected = false;
        }
    }

    private String sourceNames() {
        List<String> names = new ArrayList<>(sources.size());

        for (SourceState state : sources) {
            if (state.source.ready()) {
                names.add(state.source.name());
            }
        }

        return String.join(" + ", names);
    }

    /**
     * Asks every source that can be asked and hands the combined verdict to
     * {@code done} once the last answer is in - null when no source answered.
     * Returns false, without calling {@code done}, when no source could be
     * asked at all.
     */
    private boolean lookup(String ip, Consumer<VpnVerdict> done) {
        List<Consumer<VpnVerdict>> waiting = waitingByIp.get(ip);

        if (waiting != null) {
            // Already being asked about - the prefetch, or another join from
            // the same address a moment ago. One request, every asker told.
            waiting.add(done);
            return true;
        }

        long now = System.currentTimeMillis();

        if (inFlight >= MAX_IN_FLIGHT) {
            return false;
        }

        List<SourceState> asked = new ArrayList<>(sources.size());

        for (SourceState state : sources) {
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

        Map<String, IpLookupResult> answers = new LinkedHashMap<>();
        int[] pending = {asked.size()};
        inFlight += asked.size();

        for (SourceState state : asked) {
            state.source.lookup(ip, result -> {
                inFlight--;
                rollDay();
                answers.put(state.source.name(), result);

                if (result.ok()) {
                    state.lastError = "";
                } else {
                    noteFailure(state, ip, result);
                }

                if (--pending[0] == 0) {
                    waitingByIp.remove(ip);
                    VpnVerdict verdict = combine(ip, answers);

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

    /** The verdict from whatever answered; null when nothing did. */
    private static VpnVerdict combine(String ip, Map<String, IpLookupResult> answers) {
        Map<String, List<String>> flags = new LinkedHashMap<>();
        String asn = "";
        String organisation = "";
        String countryCode = "";

        for (Map.Entry<String, IpLookupResult> entry : answers.entrySet()) {
            IpLookupResult result = entry.getValue();

            if (!result.ok()) {
                continue;
            }

            flags.put(entry.getKey(), result.answer().flags());

            if (asn.isEmpty()) {
                asn = result.answer().asn();
            }

            if (organisation.isEmpty()) {
                organisation = result.answer().organisation();
            }

            if (countryCode.isEmpty()) {
                countryCode = result.answer().countryCode();
            }
        }

        if (flags.isEmpty()) {
            return null;
        }

        return new VpnVerdict(
                ip,
                flags,
                asn,
                organisation,
                countryCode,
                System.currentTimeMillis()
        );
    }

    /**
     * Writes a flagged join down. Every join, also the tenth of the same
     * account from the same address: the server sees a couple of hundred joins
     * a day at most, and a player who keeps reconnecting is itself something
     * worth seeing.
     */
    private void report(
            String name,
            String uuid,
            String ip,
            VpnVerdict verdict,
            boolean cached
    ) {
        if (!verdict.flagged()) {
            cleanToday++;
            return;
        }

        hitsToday++;

        int joins = timesJoined(uuid);

        // Written exactly once: by the database's answer, or by the fallback
        // below if that answer never comes. The line matters more than the
        // account's age on it.
        boolean[] written = new boolean[1];

        Consumer<PlayerDataManager.PlayerInfo> write = profile -> {
            if (written[0]) {
                return;
            }

            written[0] = true;

            VpnScanHit hit = new VpnScanHit(
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

    private static void noteFailure(SourceState state, String ip, IpLookupResult result) {
        state.lastError = result.message();
        long now = System.currentTimeMillis();
        String source = state.source.name();

        switch (result.failure()) {
            case QUOTA -> {
                state.pausedUntilMillis = now + state.source.quotaPauseMillis();
                PluginLog.warn(
                        "VPN scan: @ says its allowance is spent (@). Not asking it for @ s.",
                        source,
                        result.message(),
                        state.source.quotaPauseMillis() / 1000L
                );
            }
            case KEY_REJECTED -> {
                state.keyRejected = true;
                PluginLog.err(
                        "VPN scan: @ rejected the API key (@). Not asking it until a working @ is in @ and 'evictvpnscan reload' ran.",
                        source,
                        result.message(),
                        Secrets.VPNAPI_KEY,
                        Secrets.path()
                );
            }
            case INVALID_ADDRESS -> PluginLog.warn(
                    "VPN scan: @ could not look up @: @",
                    source,
                    ip,
                    result.message()
            );
            case NOT_READY -> {
                // Asked while not ready cannot happen through take(); quiet.
            }
            default -> {
                // A service outage would otherwise write a warning per join.
                if (now - state.lastFailureLogMillis >= FAILURE_LOG_MILLIS) {
                    state.lastFailureLogMillis = now;
                    PluginLog.warn(
                            "VPN scan: @ lookup failed (@). Its answers are missing while it keeps failing.",
                            source,
                            result.message()
                    );
                }
            }
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

        for (SourceState state : sources) {
            state.requestsToday = 0;
        }
    }

    /**
     * Loopback, LAN and link-local addresses: no service can say anything
     * about them and a request would be wasted. Anything that is not an
     * address literal is treated the same way rather than resolved.
     */
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
