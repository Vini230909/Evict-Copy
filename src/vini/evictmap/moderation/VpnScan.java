package vini.evictmap.moderation;

import arc.util.Time;
import mindustry.Vars;
import mindustry.gen.Player;
import mindustry.net.Administration;
import vini.evictmap.core.io.Secrets;
import vini.evictmap.core.util.PluginLog;
import vini.evictmap.data.PlayerDataManager;
import vini.evictmap.gen.EvictSettings;

import java.io.File;
import java.net.InetAddress;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Looks up the address of every join and says, in the console and the ban
 * log, when it is a VPN, a proxy or a Tor exit. Nothing else - it is a
 * measurement, not a rule.
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
 * <p>Hub only: everyone arrives at the hub first, and a match server's
 * unrostered joiner is a spectator anyway.
 *
 * <p>Cost control, because the lookup service counts requests per day: one
 * request per <em>address</em>, remembered for a day in a file; a cap well
 * under the allowance; an hour's pause when the service says the day is
 * spent; a ceiling on lookups in flight so a join flood cannot queue a
 * hundred requests. Every one of those fails open - a join that cannot be
 * looked up is a join that is not written down, never a join that is
 * refused.
 *
 * <p>Every piece of state here belongs to the main thread: joins arrive on
 * it and {@link VpnApiClient} hands its answers back on it.
 */
public final class VpnScan {

    private static final String CACHE_FILE = "config/evict-vpn-cache.properties";

    /** How long a verdict is trusted before the address is asked about again. */
    private static final long CACHE_TTL_MILLIS = 24L * 60L * 60L * 1000L;

    /** Lookups sent per UTC day before the scan stops asking; the free tier allows 1000. */
    private static final int DAILY_REQUEST_CAP = 900;

    /** How long to stop asking after the service reports the day's quota spent. */
    private static final long QUOTA_PAUSE_MILLIS = 60L * 60L * 1000L;

    /** Lookups waiting for an answer before further joins are skipped. */
    private static final int MAX_IN_FLIGHT = 25;

    /** Failures of the "service unreachable" kind are logged at most this often. */
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

    /** Created in {@link #start}, so a worker never opens an HTTP client for it. */
    private VpnApiClient api;

    private boolean started;

    private String day = "";
    private int requestsToday;
    private int cachedToday;
    private int hitsToday;
    private int cleanToday;
    private int skippedToday;

    private int inFlight;
    private long quotaPausedUntilMillis;
    private boolean keyRejected;
    private String lastError = "";
    private long lastFailureLogMillis;

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
        api = new VpnApiClient();
        loadKey();
        cache.load();

        if (!settings.vpnScanEnabled()) {
            PluginLog.info("VPN scan is off ('evictvpnscan on' starts it).");
            return;
        }

        if (!api.hasKey()) {
            PluginLog.warn(
                    "VPN scan is on but @ is not set in @ - nothing is looked up until it is (then 'evictvpnscan reload').",
                    Secrets.VPNAPI_KEY,
                    Secrets.path()
            );
            return;
        }

        PluginLog.info(
                "VPN scan is on, log only: every join's address is looked up and a VPN or proxy is written to the console and the ban log. Nothing is blocked. @ address(es) remembered from before.",
                cache.size()
        );
    }

    /** Re-reads the secrets file; true when a key is now loaded. */
    public boolean reloadKey() {
        if (api == null) {
            api = new VpnApiClient();
        }

        loadKey();
        return api.hasKey();
    }

    public boolean hasKey() {
        return api != null && api.hasKey();
    }

    /** One join. Decides nothing; at most it writes a line, and that later. */
    public void handlePlayerJoin(Player player) {
        if (!started || player == null || !settings.vpnScanEnabled()) {
            return;
        }

        String ip = player.con == null ? "" : player.con.address;

        if (ip == null || ip.isBlank() || isLocal(ip)) {
            return;
        }

        // Captured now: the player may be gone by the time the answer lands.
        String uuid = player.uuid();
        String name = player.name;

        rollDay();

        VpnVerdict remembered = cache.get(ip);

        if (remembered != null) {
            cachedToday++;
            report(name, uuid, ip, remembered, true);
            return;
        }

        if (!api.hasKey() || keyRejected) {
            return;
        }

        if (
                System.currentTimeMillis() < quotaPausedUntilMillis
                        || requestsToday >= DAILY_REQUEST_CAP
                        || inFlight >= MAX_IN_FLIGHT
        ) {
            skippedToday++;
            return;
        }

        requestsToday++;
        inFlight++;

        api.lookup(ip, result -> {
            inFlight--;
            rollDay();

            if (!result.ok()) {
                noteFailure(ip, result);
                return;
            }

            lastError = "";
            cache.put(result.verdict());
            report(name, uuid, ip, result.verdict(), false);
        });
    }

    /**
     * Console test: looks one address up right now (it counts against the
     * day), says what a join from it would do, and posts the verdict into the
     * ban log marked as a test - so one command proves the key and the
     * channel both.
     */
    public void test(String ip, Consumer<String> out) {
        if (api == null || !api.hasKey()) {
            out.accept(
                    "No API key loaded - add " + Secrets.VPNAPI_KEY + "=... to "
                            + Secrets.path() + ", then 'evictvpnscan reload'."
            );
            return;
        }

        String address = ip == null ? "" : ip.trim();

        if (address.isEmpty() || !ADDRESS_LITERAL.matcher(address).matches()) {
            out.accept("Give an IP address to try: evictvpnscan test 1.2.3.4");
            return;
        }

        rollDay();
        requestsToday++;

        api.lookup(address, result -> {
            rollDay();

            if (!result.ok()) {
                noteFailure(address, result);
                out.accept(
                        "Lookup failed (" + result.failure().name().toLowerCase()
                                + "): " + result.message()
                );
                return;
            }

            lastError = "";
            VpnVerdict verdict = result.verdict();
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

        if (keyRejected) {
            lines.add(
                    "  API key: REJECTED by vpnapi.io - check " + Secrets.VPNAPI_KEY
                            + " in " + Secrets.path() + ", then 'evictvpnscan reload'"
            );
        } else if (hasKey()) {
            lines.add("  API key: loaded from " + Secrets.path());
        } else {
            lines.add(
                    "  API key: NOT SET - add " + Secrets.VPNAPI_KEY + "=... to "
                            + Secrets.path() + ", then 'evictvpnscan reload'"
            );
        }

        lines.add(
                "  Hits go to: " + (logConfigured.getAsBoolean()
                        ? "the console and the ban log"
                        : "the console only - the ban log is not set ('evictbanlog <url>')")
        );

        lines.add(
                "  Today (UTC): " + requestsToday + " lookup(s) sent (cap " + DAILY_REQUEST_CAP
                        + "), " + cachedToday + " answered from the cache, "
                        + hitsToday + " hit(s), " + cleanToday + " clean, "
                        + skippedToday + " skipped"
        );

        lines.add(
                "  Cache: " + cache.size() + " address(es) in " + cache.path()
                        + ", each kept " + (CACHE_TTL_MILLIS / 3_600_000L) + " h"
        );

        long pauseLeft = quotaPausedUntilMillis - System.currentTimeMillis();

        if (pauseLeft > 0L) {
            lines.add(
                    "  Quota: the day's lookups are used up - paused for another "
                            + Math.max(1L, pauseLeft / 60_000L) + " min"
            );
        }

        if (!lastError.isEmpty()) {
            lines.add("  Last error: " + lastError);
        }

        return lines;
    }

    private void loadKey() {
        Secrets.reload();
        api.setKey(Secrets.get(Secrets.VPNAPI_KEY));
        keyRejected = false;
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

    private void noteFailure(String ip, VpnApiClient.Result result) {
        lastError = result.message();
        long now = System.currentTimeMillis();

        switch (result.failure()) {
            case QUOTA -> {
                quotaPausedUntilMillis = now + QUOTA_PAUSE_MILLIS;
                PluginLog.warn(
                        "VPN scan: the day's lookups are used up (@). Pausing for an hour; joins are not looked up meanwhile.",
                        result.message()
                );
            }
            case KEY_REJECTED -> {
                keyRejected = true;
                PluginLog.err(
                        "VPN scan: vpnapi.io rejected the API key (@). Nothing is looked up until a working @ is in @ and 'evictvpnscan reload' ran.",
                        result.message(),
                        Secrets.VPNAPI_KEY,
                        Secrets.path()
                );
            }
            case INVALID_ADDRESS -> PluginLog.warn(
                    "VPN scan: @ could not be looked up: @",
                    ip,
                    result.message()
            );
            default -> {
                // A service outage would otherwise write a warning per join.
                if (now - lastFailureLogMillis >= FAILURE_LOG_MILLIS) {
                    lastFailureLogMillis = now;
                    PluginLog.warn(
                            "VPN scan: lookup failed (@). Joins are not looked up while it keeps failing.",
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
        requestsToday = 0;
        cachedToday = 0;
        hitsToday = 0;
        cleanToday = 0;
        skippedToday = 0;
    }

    /**
     * Loopback, LAN and link-local addresses: the service cannot say anything
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
