// What the VPN scan's two sources (VpnApi, IpApi) share: the interface, the result, each one's spending, and the merge.
package Extinction;

import Extinction.core.io.Secrets;
import Extinction.core.util.PluginLog;

import arc.Core;
import arc.util.serialization.Jval;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.Consumer;

// Two because no single database knows every VPN (see GAMEPLAY.md, VPN scan). Each runs on its own daemon thread and
// hands its answer back on the main thread; a failure is a value, never an exception on the game loop.
public final class VpnSources {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);

    // Failures of the "service unreachable" kind are logged at most this often, per source.
    private static final long FAILURE_LOG_MILLIS = 60L * 1000L;

    private VpnSources() {
    }

    // One service that says what an address is.
    public interface Source {

        // Short name for lines and the checklist: vpnapi, ip-api.
        String name();

        // False while the source cannot be asked - typically no key loaded.
        boolean ready();

        // For the checklist: how the source is set up, or what it is missing.
        String setupLine();

        // How long to stop asking after the service reports the allowance spent.
        long quotaPauseMillis();

        // Lookups sent per UTC day before the scan stops asking this source.
        int dailyCap();

        // Lookups sent per minute before the scan waits; 0 for no such limit.
        int minuteCap();

        // Looks one address up. The callback runs on the main thread, always.
        void lookup(String ip, Consumer<Result> callback);
    }

    // What one service answered about one address - or why it did not, so the scan can react to each kind.
    public record Result(Answer answer, Failure failure, String message) {

        // Why a lookup produced no answer.
        public enum Failure {
            NONE,
            // The source cannot be asked right now (no key loaded) - nothing was sent.
            NOT_READY,
            // 401/403: the key is wrong or revoked.
            KEY_REJECTED,
            // 429: the allowance is used up.
            QUOTA,
            // The service refused the address itself (private, malformed).
            INVALID_ADDRESS,
            // Network trouble or an unexpected status.
            UNREACHABLE,
            // A 2xx whose body was not an answer.
            UNREADABLE
        }

        // One service's answer: its flags in its own words (vpn, proxy, tor, relay, hosting, mobile) and the network.
        // An empty flag list is a clean address.
        public record Answer(
                List<String> flags,
                String asn,
                String organisation,
                String countryCode
        ) {
        }

        static Result of(Answer answer) {
            return new Result(answer, Failure.NONE, "");
        }

        static Result failed(Failure failure, String message) {
            return new Result(null, failure, message == null ? "" : message);
        }

        public boolean ok() {
            return answer != null;
        }
    }

    // One source's spending and health. Main thread only, like the scan.
    static final class State {

        final Source source;
        int requestsToday;
        int requestsThisMinute;
        long minuteStartMillis;
        long pausedUntilMillis;
        boolean keyRejected;
        String lastError = "";
        long lastFailureLogMillis;

        State(Source source) {
            this.source = source;
        }

        // True when a lookup may be sent now; counts it when it may.
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

    // The verdict from whatever answered; null when nothing did.
    static VpnVerdict combine(String ip, Map<String, Result> answers) {
        Map<String, List<String>> flags = new LinkedHashMap<>();
        String asn = "";
        String organisation = "";
        String countryCode = "";

        for (Map.Entry<String, Result> entry : answers.entrySet()) {
            Result result = entry.getValue();

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

    // Reacts to one failed lookup: pause on a spent allowance, stop on a rejected key, one warning a minute otherwise.
    static void noteFailure(State state, String ip, Result result) {
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
                        "VPN scan: @ rejected the API key (@). Not asking it until a working @ is in @ and 'vpn reload' ran.",
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

    // One single-threaded daemon client, so a slow service never holds the game loop.
    static HttpClient newClient(String threadName) {
        ThreadFactory threads = runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        };

        return HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .executor(Executors.newFixedThreadPool(1, threads))
                .build();
    }

    static void deliver(Consumer<Result> callback, Result result) {
        Core.app.post(() -> callback.accept(result));
    }

    // The service's own "message", when the body carries one.
    static String serviceMessage(String body) {
        try {
            Jval root = Jval.read(body);

            if (root != null && root.isObject()) {
                String message = root.getString("message", "");

                if (message != null && !message.isBlank()) {
                    return message.trim();
                }
            }
        } catch (Exception ignored) {
            // A non-JSON error page is described by its status alone.
        }

        return "";
    }

    static String string(Jval object, String name) {
        if (object == null || !object.isObject()) {
            return "";
        }

        String value = object.getString(name, "");
        return value == null ? "" : value.trim();
    }

    static String rootMessage(Throwable error) {
        Throwable cause = error;

        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }

        String message = cause.getMessage();
        return message == null || message.isBlank()
                ? cause.getClass().getSimpleName()
                : message;
    }
}
