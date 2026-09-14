package vini.evictmap.moderation;

import vini.evictmap.core.io.PropertiesFile;
import vini.evictmap.core.util.PluginLog;

import java.io.File;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;

/**
 * Remembered verdicts, one per address, kept for a day.
 *
 * <p>The lookup service counts every request against a daily allowance, and
 * the same regulars join from the same addresses every evening; without a
 * cache each of those joins would spend a request to learn what it learned
 * yesterday. A day is long enough for that and short enough that an address
 * reclassified by the service is seen again the next day. Written to a file
 * so a restart does not start the day's spending over.
 *
 * <p>Main thread only, like the scan that owns it: verdicts arrive there and
 * joins are decided there, so there is nothing to lock.
 */
public final class VpnVerdictCache {

    private final File file;
    private final long ttlMillis;

    private final Map<String, VpnVerdict> verdicts = new HashMap<>();

    public VpnVerdictCache(File file, long ttlMillis) {
        this.file = file;
        this.ttlMillis = ttlMillis;
    }

    /** Reads the file, dropping what has expired. Safe when there is none. */
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

    /** The remembered verdict, or null when there is none or it has expired. */
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

    /** Remembers one verdict and writes the file. */
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
