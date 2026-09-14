package vini.evictmap.moderation;

import java.util.ArrayList;
import java.util.List;

/**
 * What the VPN lookup said about one address, and when it said it.
 *
 * <p>Four flags, straight from vpnapi.io: {@code vpn} and {@code proxy} are
 * what ban evasion hides behind, {@code tor} is the same thing on a different
 * network, and {@code relay} is Apple's iCloud Private Relay - kept so an
 * admin reading the log can tell it apart, because a game client does not
 * come through it on purpose. The network fields are the reader's second
 * opinion: an "M247 Europe SRL" is a VPN exit, a "Deutsche Telekom AG" that
 * got flagged deserves a doubt.
 */
public record VpnVerdict(
        String ip,
        boolean vpn,
        boolean proxy,
        boolean tor,
        boolean relay,
        String asn,
        String organisation,
        String countryCode,
        long checkedAtMillis
) {

    private static final String FIELD_SEPARATOR = "\t";

    /** True when the address is something a client hides behind. */
    public boolean flagged() {
        return vpn || proxy || tor || relay;
    }

    /** The flags that are set, e.g. {@code vpn, proxy}; {@code clean} when none. */
    public String flags() {
        List<String> set = new ArrayList<>(4);

        if (vpn) {
            set.add("vpn");
        }

        if (proxy) {
            set.add("proxy");
        }

        if (tor) {
            set.add("tor");
        }

        if (relay) {
            set.add("relay");
        }

        return set.isEmpty() ? "clean" : String.join(", ", set);
    }

    /** {@code AS9009 M247 Europe SRL (RO)}, from whichever parts are known. */
    public String network() {
        StringBuilder text = new StringBuilder();

        if (!asn.isBlank()) {
            text.append(asn);
        }

        if (!organisation.isBlank()) {
            if (text.length() > 0) {
                text.append(' ');
            }

            text.append(organisation);
        }

        if (!countryCode.isBlank()) {
            if (text.length() > 0) {
                text.append(' ');
            }

            text.append('(').append(countryCode).append(')');
        }

        return text.length() == 0 ? "unknown network" : text.toString();
    }

    /**
     * One cache-file value: the fields in a fixed order, tab-separated. The
     * address is the key, so it is not repeated here.
     */
    String serialize() {
        return checkedAtMillis
                + FIELD_SEPARATOR + flags()
                + FIELD_SEPARATOR + clean(asn)
                + FIELD_SEPARATOR + clean(organisation)
                + FIELD_SEPARATOR + clean(countryCode);
    }

    /** The inverse of {@link #serialize}; null for a line that does not parse. */
    static VpnVerdict deserialize(String ip, String value) {
        if (ip == null || ip.isBlank() || value == null) {
            return null;
        }

        String[] parts = value.split(FIELD_SEPARATOR, -1);

        if (parts.length < 5) {
            return null;
        }

        long checkedAt;

        try {
            checkedAt = Long.parseLong(parts[0].trim());
        } catch (NumberFormatException exception) {
            return null;
        }

        String flags = parts[1];

        return new VpnVerdict(
                ip,
                hasFlag(flags, "vpn"),
                hasFlag(flags, "proxy"),
                hasFlag(flags, "tor"),
                hasFlag(flags, "relay"),
                parts[2],
                parts[3],
                parts[4],
                checkedAt
        );
    }

    private static boolean hasFlag(String flags, String flag) {
        for (String part : flags.split(",")) {
            if (part.trim().equals(flag)) {
                return true;
            }
        }

        return false;
    }

    /** Keeps a stray tab or line break in an ASN name from breaking the line. */
    private static String clean(String text) {
        return text == null ? "" : text.replaceAll("[\\t\\r\\n]+", " ").trim();
    }
}
