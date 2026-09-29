// ip-api.com, the VPN scan's second source: GET http://ip-api.com/json/<ip>?fields=...
package Extinction;

import arc.util.serialization.Jval;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

// Flags proxy, hosting (a data-centre range, where every VPN exit lives) and mobile (written down, never a hit).
// No key; the free tier is plain HTTP, 45 lookups a minute, non-commercial; the pause after a 429 is a flat minute.
public final class IpApi implements VpnSources.Source {

    private static final String ENDPOINT = "http://ip-api.com/json/";

    // Only what the line needs - the free tier bills nothing, but bytes are bytes.
    private static final String FIELDS =
            "status,message,proxy,hosting,mobile,as,org,countryCode";

    private final HttpClient client = VpnSources.newClient("evict-ipapi");

    @Override
    public String name() {
        return "ip-api";
    }

    @Override
    public boolean ready() {
        return true;
    }

    @Override
    public String setupLine() {
        return "no key needed (free tier, 45 lookups a minute)";
    }

    @Override
    public long quotaPauseMillis() {
        return 60L * 1000L;
    }

    @Override
    public int dailyCap() {
        return 20_000;
    }

    @Override
    public int minuteCap() {
        return 40;
    }

    @Override
    public void lookup(String ip, Consumer<VpnSources.Result> callback) {
        HttpRequest request;

        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT + ip + "?fields=" + FIELDS))
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

    // Pure, like VpnApi.parse.
    static VpnSources.Result parse(int status, String body) {
        String text = body == null ? "" : body;

        if (status == 429) {
            return VpnSources.Result.failed(
                    VpnSources.Result.Failure.QUOTA,
                    "HTTP 429 - more than 45 lookups in a minute"
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

        if (!"success".equals(VpnSources.string(root, "status"))) {
            // "private range", "reserved range", "invalid query".
            String message = VpnSources.string(root, "message");
            return VpnSources.Result.failed(
                    VpnSources.Result.Failure.INVALID_ADDRESS,
                    message.isEmpty() ? "no answer in the reply" : message
            );
        }

        List<String> flags = new ArrayList<>(3);

        for (String flag : new String[]{"proxy", "hosting", "mobile"}) {
            if (root.getBool(flag, false)) {
                flags.add(flag);
            }
        }

        // "AS44559 IT HOSTLINE LTD" - the number, then the name.
        String as = VpnSources.string(root, "as");
        String asn = as;
        String organisation = VpnSources.string(root, "org");
        int space = as.indexOf(' ');

        if (space > 0) {
            asn = as.substring(0, space);

            if (organisation.isEmpty()) {
                organisation = as.substring(space + 1).trim();
            }
        }

        return VpnSources.Result.of(new VpnSources.Result.Answer(
                List.copyOf(flags),
                asn,
                organisation,
                VpnSources.string(root, "countryCode")
        ));
    }
}
