package Extinction.moderation.vpn;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the lookup sources said about one address, and when they said it.
 *
 * <p>One list of flags per source that answered, in the sources' own words:
 * vpnapi.io's {@code vpn}, {@code proxy}, {@code tor} and {@code relay}
 * (Apple's iCloud Private Relay, listed so it can be told apart); ip-api's
 * {@code proxy}, {@code hosting} (a data-centre range, where every VPN exit
 * lives whether or not a database knows the provider) and {@code mobile}. A
 * source that was asked and found nothing is present with an empty list; a
 * source that could not answer is absent. {@code mobile} is written down
 * but is not a hit: a cellular address is exactly what an address-based
 * check can never pin, and the point of listing it is to see how often that
 * is the case.
 *
 * <p>The network fields are the reader's second opinion: an "M247 Europe
 * SRL" is a VPN exit, a "Deutsche Telekom AG" that got flagged deserves a
 * doubt.
 */
public record VpnVerdict(
        String ip,
        Map<String, List<String>> flagsBySource,
        String asn,
        String organisation,
        String countryCode,
        long checkedAtMillis
) {

    /** Flags that make an address a hit; anything else is information only. */
    private static final Set<String> HIT_FLAGS =
            Set.of("vpn", "proxy", "tor", "relay", "hosting");

    private static final String FIELD_SEPARATOR = "\t";
    private static final String SOURCE_SEPARATOR = ";";

    public VpnVerdict {
        flagsBySource = copyOf(flagsBySource);
        asn = asn == null ? "" : asn;
        organisation = organisation == null ? "" : organisation;
        countryCode = countryCode == null ? "" : countryCode;
    }

    /** True when any source set a flag that means "hidden behind something". */
    public boolean flagged() {
        for (List<String> flags : flagsBySource.values()) {
            for (String flag : flags) {
                if (HIT_FLAGS.contains(flag)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * What each source said, e.g. {@code vpnapi: clean / ip-api: proxy, hosting};
     * {@code no answer} when no source answered at all.
     */
    public String flags() {
        if (flagsBySource.isEmpty()) {
            return "no answer";
        }

        List<String> parts = new ArrayList<>(flagsBySource.size());

        for (Map.Entry<String, List<String>> entry : flagsBySource.entrySet()) {
            parts.add(
                    entry.getKey() + ": "
                            + (entry.getValue().isEmpty()
                            ? "clean"
                            : String.join(", ", entry.getValue()))
            );
        }

        return String.join(" / ", parts);
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
     * One cache-file value: the fields in a fixed order, tab-separated, the
     * sources as {@code vpnapi=vpn,tor;ip-api=hosting}. The address is the
     * key, so it is not repeated here.
     */
    String serialize() {
        List<String> sources = new ArrayList<>(flagsBySource.size());

        for (Map.Entry<String, List<String>> entry : flagsBySource.entrySet()) {
            sources.add(entry.getKey() + "=" + String.join(",", entry.getValue()));
        }

        return checkedAtMillis
                + FIELD_SEPARATOR + String.join(SOURCE_SEPARATOR, sources)
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

        Map<String, List<String>> sources = new LinkedHashMap<>();

        for (String source : parts[1].split(SOURCE_SEPARATOR)) {
            int equals = source.indexOf('=');

            if (equals <= 0) {
                continue;
            }

            sources.put(
                    source.substring(0, equals).trim(),
                    splitFlags(source.substring(equals + 1))
            );
        }

        if (sources.isEmpty()) {
            // A line written before there were two sources holds vpnapi's
            // flags on their own; it is not worth a lookup to relearn them.
            sources.put("vpnapi", splitFlags(parts[1]));
        }

        return new VpnVerdict(ip, sources, parts[2], parts[3], parts[4], checkedAt);
    }

    private static List<String> splitFlags(String text) {
        List<String> flags = new ArrayList<>(4);

        for (String part : text.split(",")) {
            String flag = part.trim();

            if (!flag.isEmpty() && !flag.equals("clean")) {
                flags.add(flag);
            }
        }

        return List.copyOf(flags);
    }

    private static Map<String, List<String>> copyOf(Map<String, List<String>> source) {
        Map<String, List<String>> copy = new LinkedHashMap<>();

        if (source != null) {
            for (Map.Entry<String, List<String>> entry : source.entrySet()) {
                copy.put(
                        entry.getKey(),
                        entry.getValue() == null ? List.of() : List.copyOf(entry.getValue())
                );
            }
        }

        return java.util.Collections.unmodifiableMap(copy);
    }

    /** Keeps a stray tab or line break in an ASN name from breaking the line. */
    private static String clean(String text) {
        return text == null ? "" : text.replaceAll("[\\t\\r\\n]+", " ").trim();
    }
}
