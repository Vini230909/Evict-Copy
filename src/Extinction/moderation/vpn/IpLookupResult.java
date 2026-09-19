package Extinction.moderation.vpn;

import java.util.List;

/**
 * What one lookup service answered about one address - or why it did not.
 *
 * <p>Failures are values, never exceptions: nothing in a moderation lookup
 * may stop the server that asked, and the scan wants to know the kind of
 * failure (spent quota, rejected key, unreachable) to react to each one
 * differently.
 */
public record IpLookupResult(Answer answer, Failure failure, String message) {

    /** Why a lookup produced no answer. */
    public enum Failure {
        NONE,
        /** The source cannot be asked right now (no key loaded) - nothing was sent. */
        NOT_READY,
        /** 401/403: the key is wrong or revoked. */
        KEY_REJECTED,
        /** 429: the allowance is used up. */
        QUOTA,
        /** The service refused the address itself (private, malformed). */
        INVALID_ADDRESS,
        /** Network trouble or an unexpected status. */
        UNREACHABLE,
        /** A 2xx whose body was not an answer. */
        UNREADABLE
    }

    /**
     * One service's answer: the flags it set (in that service's own words,
     * lower-case: {@code vpn}, {@code proxy}, {@code tor}, {@code relay},
     * {@code hosting}, {@code mobile}) and what it knows about the network.
     * An empty flag list is a clean address.
     */
    public record Answer(
            List<String> flags,
            String asn,
            String organisation,
            String countryCode
    ) {
    }

    static IpLookupResult of(Answer answer) {
        return new IpLookupResult(answer, Failure.NONE, "");
    }

    static IpLookupResult failed(Failure failure, String message) {
        return new IpLookupResult(null, failure, message == null ? "" : message);
    }

    public boolean ok() {
        return answer != null;
    }
}
