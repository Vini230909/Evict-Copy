package vini.evictmap.moderation.vpn;

import java.util.function.Consumer;

/**
 * One service that says what an address is.
 *
 * <p>Two of them, because no single database knows every VPN: an address
 * one lists as clean the other lists as a proxy in a hosting range. The scan
 * asks every source it has and counts a hit when any of them says so, and
 * writes down which one did.
 */
public interface IpLookupSource {

    /** Short name for lines and the checklist: {@code vpnapi}, {@code ip-api}. */
    String name();

    /** False while the source cannot be asked - typically no key loaded. */
    boolean ready();

    /** For the checklist: how the source is set up, or what it is missing. */
    String setupLine();

    /** How long to stop asking after the service reports the allowance spent. */
    long quotaPauseMillis();

    /** Lookups sent per UTC day before the scan stops asking this source. */
    int dailyCap();

    /** Lookups sent per minute before the scan waits; 0 for no such limit. */
    int minuteCap();

    /** Looks one address up. The callback runs on the main thread, always. */
    void lookup(String ip, Consumer<IpLookupResult> callback);
}
