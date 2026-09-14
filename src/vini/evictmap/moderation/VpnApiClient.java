package vini.evictmap.moderation;

import arc.Core;
import arc.util.serialization.Jval;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.Consumer;

/**
 * The one HTTP call behind the VPN scan:
 * {@code GET https://vpnapi.io/api/<ip>?key=<key>}.
 *
 * <p>Sent from its own daemon thread, never from the game loop, and the answer
 * is handed back on the main thread, so the caller keeps every piece of its
 * state single-threaded. A failure is a {@link Result} with a reason, never an
 * exception: nothing in a moderation lookup may stop the server that asked.
 *
 * <p>The key is read from the secrets file and never logged; the request URL
 * carries it, so the URL is not logged either.
 */
public final class VpnApiClient {

    private static final String ENDPOINT = "https://vpnapi.io/api/";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);

    /** Why a lookup produced no verdict. */
    public enum Failure {
        NONE,
        /** No key loaded - nothing was sent. */
        NO_KEY,
        /** 401/403: the key is wrong or revoked. */
        KEY_REJECTED,
        /** 429: the day's requests are used up. */
        QUOTA,
        /** The service refused the address itself (private, malformed). */
        INVALID_ADDRESS,
        /** Network trouble or an unexpected status. */
        UNREACHABLE,
        /** A 2xx whose body was not a verdict. */
        UNREADABLE
    }

    /** One lookup's outcome: a verdict, or the reason there is none. */
    public record Result(VpnVerdict verdict, Failure failure, String message) {

        static Result of(VpnVerdict verdict) {
            return new Result(verdict, Failure.NONE, "");
        }

        static Result failed(Failure failure, String message) {
            return new Result(null, failure, message == null ? "" : message);
        }

        public boolean ok() {
            return verdict != null;
        }
    }

    private final HttpClient client;

    private volatile String key = "";

    public VpnApiClient() {
        ThreadFactory threads = runnable -> {
            Thread thread = new Thread(runnable, "evict-vpnapi");
            thread.setDaemon(true);
            return thread;
        };

        this.client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .executor(Executors.newFixedThreadPool(1, threads))
                .build();
    }

    public void setKey(String key) {
        this.key = key == null ? "" : key.trim();
    }

    public boolean hasKey() {
        return !key.isEmpty();
    }

    /**
     * Looks one address up. The callback runs on the main thread, always -
     * also for the failures that never leave this method.
     */
    public void lookup(String ip, Consumer<Result> callback) {
        String currentKey = key;

        if (currentKey.isEmpty()) {
            deliver(callback, Result.failed(Failure.NO_KEY, "no API key loaded"));
            return;
        }

        HttpRequest request;

        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(
                            ENDPOINT + ip + "?key="
                                    + URLEncoder.encode(currentKey, StandardCharsets.UTF_8)
                    ))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
        } catch (Exception exception) {
            deliver(callback, Result.failed(
                    Failure.INVALID_ADDRESS,
                    "not a usable address: " + ip
            ));
            return;
        }

        try {
            client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((response, error) -> {
                        Result result = error != null
                                ? Result.failed(Failure.UNREACHABLE, rootMessage(error))
                                : parse(ip, response.statusCode(), response.body());

                        deliver(callback, result);
                    });
        } catch (Exception exception) {
            deliver(callback, Result.failed(Failure.UNREACHABLE, rootMessage(exception)));
        }
    }

    /**
     * Turns one response into a result. Package-private and pure so the
     * mapping can be read (and tried) without a network.
     */
    static Result parse(String ip, int status, String body) {
        String text = body == null ? "" : body;

        if (status == 401 || status == 403) {
            return Result.failed(Failure.KEY_REJECTED, "HTTP " + status + " " + serviceMessage(text));
        }

        if (status == 429) {
            return Result.failed(Failure.QUOTA, "HTTP 429 " + serviceMessage(text));
        }

        if (status == 400 || status == 404 || status == 422) {
            return Result.failed(Failure.INVALID_ADDRESS, "HTTP " + status + " " + serviceMessage(text));
        }

        if (status < 200 || status >= 300) {
            return Result.failed(Failure.UNREACHABLE, "HTTP " + status + " " + serviceMessage(text));
        }

        Jval root;

        try {
            root = Jval.read(text);
        } catch (Exception exception) {
            return Result.failed(Failure.UNREADABLE, "unreadable reply");
        }

        if (root == null || !root.isObject()) {
            return Result.failed(Failure.UNREADABLE, "unreadable reply");
        }

        Jval security = root.get("security");

        if (security == null || !security.isObject()) {
            // A 200 without a verdict is how the service reports a private or
            // reserved address: the body carries only a message.
            String message = root.getString("message", "");
            return Result.failed(
                    Failure.INVALID_ADDRESS,
                    message.isEmpty() ? "no verdict in the reply" : message
            );
        }

        Jval network = root.get("network");
        Jval location = root.get("location");

        return Result.of(new VpnVerdict(
                ip,
                security.getBool("vpn", false),
                security.getBool("proxy", false),
                security.getBool("tor", false),
                security.getBool("relay", false),
                string(network, "autonomous_system_number"),
                string(network, "autonomous_system_organization"),
                string(location, "country_code"),
                System.currentTimeMillis()
        ));
    }

    private static String string(Jval object, String name) {
        if (object == null || !object.isObject()) {
            return "";
        }

        String value = object.getString(name, "");
        return value == null ? "" : value.trim();
    }

    /** The service's own {@code message}, when the body carries one. */
    private static String serviceMessage(String body) {
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

    private static void deliver(Consumer<Result> callback, Result result) {
        Core.app.post(() -> callback.accept(result));
    }

    private static String rootMessage(Throwable error) {
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
