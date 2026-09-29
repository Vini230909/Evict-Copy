// vpnapi.io, one of the VPN scan's two sources: GET https://vpnapi.io/api/<ip>?key=<key>.
package Extinction;

import Extinction.core.io.Secrets;

import arc.util.serialization.Jval;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

// Flags vpn, proxy, tor and relay (Apple's iCloud Private Relay), plus the network. Needs the key from the secrets file,
// never logged - the request URL carries it, so neither is the URL. Free tier: 1000 lookups a day.
public final class VpnApi implements VpnSources.Source {

    private static final String ENDPOINT = "https://vpnapi.io/api/";

    private final HttpClient client = VpnSources.newClient("evict-vpnapi");

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
                + ", then 'vpn reload' (optional; ip-api works without it)";
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
    public void lookup(String ip, Consumer<VpnSources.Result> callback) {
        String currentKey = key;

        if (currentKey.isEmpty()) {
            VpnSources.deliver(callback, VpnSources.Result.failed(
                    VpnSources.Result.Failure.NOT_READY,
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
                    .timeout(VpnSources.REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
        } catch (Exception exception) {
            VpnSources.deliver(callback, VpnSources.Result.failed(
                    VpnSources.Result.Failure.INVALID_ADDRESS,
                    "not a usable address: " + ip
            ));
            return;
        }

        try {
            client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((response, error) -> {
                        VpnSources.Result result = error != null
                                ? VpnSources.Result.failed(
                                VpnSources.Result.Failure.UNREACHABLE,
                                VpnSources.rootMessage(error)
                        )
                                : parse(response.statusCode(), response.body());

                        VpnSources.deliver(callback, result);
                    });
        } catch (Exception exception) {
            VpnSources.deliver(callback, VpnSources.Result.failed(
                    VpnSources.Result.Failure.UNREACHABLE,
                    VpnSources.rootMessage(exception)
            ));
        }
    }

    // One response as a result; pure, so the mapping can be read and tried without a network.
    static VpnSources.Result parse(int status, String body) {
        String text = body == null ? "" : body;

        if (status == 401 || status == 403) {
            return VpnSources.Result.failed(
                    VpnSources.Result.Failure.KEY_REJECTED,
                    "HTTP " + status + " " + VpnSources.serviceMessage(text)
            );
        }

        if (status == 429) {
            return VpnSources.Result.failed(
                    VpnSources.Result.Failure.QUOTA,
                    "HTTP 429 " + VpnSources.serviceMessage(text)
            );
        }

        if (status == 400 || status == 404 || status == 422) {
            return VpnSources.Result.failed(
                    VpnSources.Result.Failure.INVALID_ADDRESS,
                    "HTTP " + status + " " + VpnSources.serviceMessage(text)
            );
        }

        if (status < 200 || status >= 300) {
            return VpnSources.Result.failed(
                    VpnSources.Result.Failure.UNREACHABLE,
                    "HTTP " + status + " " + VpnSources.serviceMessage(text)
            );
        }

        Jval root;

        try {
            root = Jval.read(text);
        } catch (Exception exception) {
            return VpnSources.Result.failed(VpnSources.Result.Failure.UNREADABLE, "unreadable reply");
        }

        if (root == null || !root.isObject()) {
            return VpnSources.Result.failed(VpnSources.Result.Failure.UNREADABLE, "unreadable reply");
        }

        Jval security = root.get("security");

        if (security == null || !security.isObject()) {
            // A 200 without a verdict is how the service reports a private or reserved address.
            String message = root.getString("message", "");
            return VpnSources.Result.failed(
                    VpnSources.Result.Failure.INVALID_ADDRESS,
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

        return VpnSources.Result.of(new VpnSources.Result.Answer(
                List.copyOf(flags),
                VpnSources.string(network, "autonomous_system_number"),
                VpnSources.string(network, "autonomous_system_organization"),
                VpnSources.string(location, "country_code")
        ));
    }
}
