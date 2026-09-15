package vini.evictmap.moderation;

import arc.Core;
import arc.util.serialization.Jval;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.Consumer;

/**
 * The bits every lookup source shares: an HTTP client on its own daemon
 * thread, delivery of the answer on the main thread, and reading the
 * service's own error message out of a body.
 */
final class LookupHttp {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);

    private LookupHttp() {
    }

    /** One single-threaded daemon client, so a slow service never holds the game loop. */
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

    static void deliver(Consumer<IpLookupResult> callback, IpLookupResult result) {
        Core.app.post(() -> callback.accept(result));
    }

    /** The service's own {@code message}, when the body carries one. */
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
