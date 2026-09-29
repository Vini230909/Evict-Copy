// What the lookup sources said about one address, and when - plus the file that remembers it for a day.
package Extinction;

import Extinction.core.io.PropertiesFile;
import Extinction.core.util.PluginLog;

import arc.util.Strings;

import java.io.File;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

// One flag list per source that answered, in its own words; a source asked that found nothing has an empty list, one
// that could not answer is absent. "mobile" is written down but is not a hit: no address check can pin a cellular one.
public record VpnVerdict(
        String ip,
        Map<String, List<String>> flagsBySource,
        String asn,
        String organisation,
        String countryCode,
        long checkedAtMillis
) {

    // Flags that make an address a hit; anything else is information only.
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

    // True when any source set a flag that means "hidden behind something".
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

    // What each source said, e.g. "vpnapi: clean / ip-api: proxy, hosting"; "no answer" when none answered.
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

    // "AS9009 M247 Europe SRL (RO)", from whichever parts are known - the reader's second opinion.
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

    // One cache-file value: fields in a fixed order, tab-separated, sources as "vpnapi=vpn,tor;ip-api=hosting".
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

    // The inverse of serialize; null for a line that does not parse.
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
            // A line from before there were two sources holds vpnapi's flags alone; not worth a lookup to relearn.
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

    // Keeps a stray tab or line break in an ASN name from breaking the line.
    private static String clean(String text) {
        return text == null ? "" : text.replaceAll("[\\t\\r\\n]+", " ").trim();
    }

    // One join that came through a VPN: who, from where, what the lookup said, and how old the account is.
    public record Hit(
            String name,
            String uuid,
            String ip,
            VpnVerdict verdict,
            boolean cached,
            long firstSeenMillis,
            long playtimeMillis,
            int timesJoined
    ) {

        private static final DateTimeFormatter CONSOLE_TIME =
                DateTimeFormatter.ofPattern("MM-dd-yyyy HH:mm");

        // The plain name, colour tags stripped, as the console shows names.
        public String plainName() {
            String plain = Strings.stripColors(name == null ? "" : name).trim();
            return plain.isEmpty() ? "(unnamed)" : plain;
        }

        // True when the plugin's database has never stored this account.
        public boolean unknownAccount() {
            return firstSeenMillis <= 0L;
        }

        // "1 join" / "12 joins".
        public String joins() {
            return timesJoined + (timesJoined == 1 ? " join" : " joins");
        }

        // The console's one line, everything in it plain text.
        public String consoleLine() {
            return "VPN scan: " + plainName()
                    + " (" + uuid + ") from " + ip
                    + " - " + verdict.flags()
                    + " - " + verdict.network()
                    + " - account: " + consoleAccount()
                    + (cached ? " - cached verdict" : "")
                    + " - log only, nothing was done";
        }

        private String consoleAccount() {
            if (unknownAccount()) {
                return "not stored yet, " + joins();
            }

            return "first seen "
                    + CONSOLE_TIME.format(LocalDateTime.ofInstant(
                            Instant.ofEpochMilli(firstSeenMillis),
                            ZoneId.systemDefault()
                    ))
                    + ", played " + playtime()
                    + ", " + joins();
        }

        // "0 min" / "45 min" / "12 h 3 min".
        public String playtime() {
            long minutes = Math.max(0L, playtimeMillis) / 60_000L;

            if (minutes < 60L) {
                return minutes + " min";
            }

            return (minutes / 60L) + " h " + (minutes % 60L) + " min";
        }
    }

    // Remembered verdicts, one per address, kept for a day and in a file, so the regulars' evenings cost no lookups.
    // Main thread only, like the scan that owns it.
    public static final class Cache {

        private final File file;
        private final long ttlMillis;

        private final Map<String, VpnVerdict> verdicts = new HashMap<>();

        public Cache(File file, long ttlMillis) {
            this.file = file;
            this.ttlMillis = ttlMillis;
        }

        // Reads the file, dropping what has expired. Safe when there is none.
        public void load() {
            verdicts.clear();

            Properties properties = PropertiesFile.load(file);
            long now = System.currentTimeMillis();

            for (String ip : properties.stringPropertyNames()) {
                VpnVerdict verdict = VpnVerdict.deserialize(ip, properties.getProperty(ip));

                if (verdict != null && !expired(verdict, now)) {
                    verdicts.put(ip, verdict);
                }
            }
        }

        // The remembered verdict, or null when there is none or it has expired.
        public VpnVerdict get(String ip) {
            VpnVerdict verdict = verdicts.get(ip);

            if (verdict == null) {
                return null;
            }

            if (expired(verdict, System.currentTimeMillis())) {
                verdicts.remove(ip);
                return null;
            }

            return verdict;
        }

        // Remembers one verdict and writes the file.
        public void put(VpnVerdict verdict) {
            if (verdict == null || verdict.ip().isBlank()) {
                return;
            }

            verdicts.put(verdict.ip(), verdict);
            save();
        }

        public int size() {
            return verdicts.size();
        }

        public String path() {
            return file.getPath();
        }

        private boolean expired(VpnVerdict verdict, long now) {
            return now - verdict.checkedAtMillis() > ttlMillis;
        }

        private void save() {
            Properties properties = new Properties();
            long now = System.currentTimeMillis();

            Iterator<Map.Entry<String, VpnVerdict>> entries = verdicts.entrySet().iterator();

            while (entries.hasNext()) {
                Map.Entry<String, VpnVerdict> entry = entries.next();

                if (expired(entry.getValue(), now)) {
                    entries.remove();
                    continue;
                }

                properties.setProperty(entry.getKey(), entry.getValue().serialize());
            }

            if (!PropertiesFile.save(
                    file,
                    properties,
                    "Evict VPN scan: remembered lookups, one address per line (checked-at, flags, ASN, organisation, country). Safe to delete."
            )) {
                PluginLog.warn("VPN scan could not write its cache @.", file.getPath());
            }
        }
    }
}
