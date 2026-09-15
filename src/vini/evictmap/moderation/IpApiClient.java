package vini.evictmap.moderation;

import arc.util.serialization.Jval;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * ip-api.com: {@code GET http://ip-api.com/json/<ip>?fields=…}.
 *
 * <p>The second opinion. Its {@code proxy} flag overlaps vpnapi.io's; its
 * {@code hosting} flag is the one vpnapi.io has no equivalent for - an
 * address in a data-centre range, which is where every VPN exit lives
 * whether or not a database has the provider's name for it (and also where
 * a cloud-gaming box lives, which is why the line names the flag). Its
 * {@code mobile} flag is not a hit at all, only written down: a cellular
 * address is the one an address-based check can never pin, and knowing how
 * many joins are mobile is part of the week's evidence.
 *
 * <p>No key. The free tier is plain HTTP (TLS is a paid feature), limited to
 * 45 requests a minute and meant for non-commercial use; a 429 carries how
 * long to wait in {@code X-Ttl}, and the pause here is a flat minute.
 */
public final class IpApiClient implements IpLookupSource {

    private static final String ENDPOINT = "http://ip-api.com/json/";

    /** Only what the line needs - the free tier bills nothing, but bytes are bytes. */
    private static final String FIELDS =
            "status,message,proxy,hosting,mobile,as,org,countryCode";

    private final HttpClient client = LookupHttp.newClient("evict-ipapi");

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
    public void lookup(String ip, Consumer<IpLookupResult> callback) {
        HttpRequest request;

        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT + ip + "?fields=" + FIELDS))
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

    /** Package-private and pure, like {@link VpnApiClient#parse}. */
    static IpLookupResult parse(int status, String body) {
        String text = body == null ? "" : body;

        if (status == 429) {
            return IpLookupResult.failed(
                    IpLookupResult.Failure.QUOTA,
                    "HTTP 429 - more than 45 lookups in a minute"
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

        if (!"success".equals(LookupHttp.string(root, "status"))) {
            // "private range", "reserved range", "invalid query".
            String message = LookupHttp.string(root, "message");
            return IpLookupResult.failed(
                    IpLookupResult.Failure.INVALID_ADDRESS,
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
        String as = LookupHttp.string(root, "as");
        String asn = as;
        String organisation = LookupHttp.string(root, "org");
        int space = as.indexOf(' ');

        if (space > 0) {
            asn = as.substring(0, space);

            if (organisation.isEmpty()) {
                organisation = as.substring(space + 1).trim();
            }
        }

        return IpLookupResult.of(new IpLookupResult.Answer(
                List.copyOf(flags),
                asn,
                organisation,
                LookupHttp.string(root, "countryCode")
        ));
    }
}
