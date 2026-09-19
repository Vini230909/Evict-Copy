package Extinction.moderation.vpn;

import arc.util.serialization.Jval;
import Extinction.core.io.Secrets;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * vpnapi.io: {@code GET https://vpnapi.io/api/<ip>?key=<key>}.
 *
 * <p>Answers with four flags - {@code vpn}, {@code proxy}, {@code tor} and
 * {@code relay} (Apple's iCloud Private Relay) - plus the network. Needs a
 * key, read from the secrets file and never logged; the request URL carries
 * it, so the URL is not logged either. Free tier: 1000 lookups a day.
 */
public final class VpnApiClient implements IpLookupSource {

    private static final String ENDPOINT = "https://vpnapi.io/api/";

    private final HttpClient client = LookupHttp.newClient("evict-vpnapi");

    private volatile String key = "";

    public void setKey(String key) {
        this.key = key == null ? "" : key.trim();
    }

    @Override
    public String name() {
        return "vpnapi";
    }

    @Override
    public boolean ready() {
        return !key.isEmpty();
    }

    @Override
    public String setupLine() {
        return ready()
                ? "key loaded from " + Secrets.path()
                : "NO KEY - add " + Secrets.VPNAPI_KEY + "=... to " + Secrets.path()
                + ", then 'evictvpnscan reload' (optional; ip-api works without it)";
    }

    @Override
    public long quotaPauseMillis() {
        // The allowance is per day; an hourly retry wastes one request an hour.
        return 60L * 60L * 1000L;
    }

    @Override
    public int dailyCap() {
        return 900;
    }

    @Override
    public int minuteCap() {
        return 0;
    }

    @Override
    public void lookup(String ip, Consumer<IpLookupResult> callback) {
        String currentKey = key;

        if (currentKey.isEmpty()) {
            LookupHttp.deliver(callback, IpLookupResult.failed(
                    IpLookupResult.Failure.NOT_READY,
                    "no API key loaded"
            ));
            return;
        }

        HttpRequest request;

        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(
                            ENDPOINT + ip + "?key="
                                    + URLEncoder.encode(currentKey, StandardCharsets.UTF_8)
                    ))
                    .timeout(LookupHttp.REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
        } catch (Exception exception) {
            LookupHttp.deliver(callback, IpLookupResult.failed(
                    IpLookupResult.Failure.INVALID_ADDRESS,
                    "not a usable address: " + ip
            ));
            return;
        }

        try {
            client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((response, error) -> {
                        IpLookupResult result = error != null
                                ? IpLookupResult.failed(
                                IpLookupResult.Failure.UNREACHABLE,
                                LookupHttp.rootMessage(error)
                        )
                                : parse(response.statusCode(), response.body());

                        LookupHttp.deliver(callback, result);
                    });
        } catch (Exception exception) {
            LookupHttp.deliver(callback, IpLookupResult.failed(
                    IpLookupResult.Failure.UNREACHABLE,
                    LookupHttp.rootMessage(exception)
            ));
        }
    }

    /**
     * Turns one response into a result. Package-private and pure so the
     * mapping can be read (and tried) without a network.
     */
    static IpLookupResult parse(int status, String body) {
        String text = body == null ? "" : body;

        if (status == 401 || status == 403) {
            return IpLookupResult.failed(
                    IpLookupResult.Failure.KEY_REJECTED,
                    "HTTP " + status + " " + LookupHttp.serviceMessage(text)
            );
        }

        if (status == 429) {
            return IpLookupResult.failed(
                    IpLookupResult.Failure.QUOTA,
                    "HTTP 429 " + LookupHttp.serviceMessage(text)
            );
        }

        if (status == 400 || status == 404 || status == 422) {
            return IpLookupResult.failed(
                    IpLookupResult.Failure.INVALID_ADDRESS,
                    "HTTP " + status + " " + LookupHttp.serviceMessage(text)
            );
        }

        if (status < 200 || status >= 300) {
            return IpLookupResult.failed(
                    IpLookupResult.Failure.UNREACHABLE,
                    "HTTP " + status + " " + LookupHttp.serviceMessage(text)
            );
        }

        Jval root;

        try {
            root = Jval.read(text);
        } catch (Exception exception) {
            return IpLookupResult.failed(IpLookupResult.Failure.UNREADABLE, "unreadable reply");
        }

        if (root == null || !root.isObject()) {
            return IpLookupResult.failed(IpLookupResult.Failure.UNREADABLE, "unreadable reply");
        }

        Jval security = root.get("security");

        if (security == null || !security.isObject()) {
            // A 200 without a verdict is how the service reports a private or
            // reserved address: the body carries only a message.
            String message = root.getString("message", "");
            return IpLookupResult.failed(
                    IpLookupResult.Failure.INVALID_ADDRESS,
                    message == null || message.isEmpty() ? "no verdict in the reply" : message
            );
        }

        List<String> flags = new ArrayList<>(4);

        for (String flag : new String[]{"vpn", "proxy", "tor", "relay"}) {
            if (security.getBool(flag, false)) {
                flags.add(flag);
            }
        }

        Jval network = root.get("network");
        Jval location = root.get("location");

        return IpLookupResult.of(new IpLookupResult.Answer(
                List.copyOf(flags),
                LookupHttp.string(network, "autonomous_system_number"),
                LookupHttp.string(network, "autonomous_system_organization"),
                LookupHttp.string(location, "country_code")
        ));
    }
}
