package sh.repost.client;

/** Sanitized internal DNS-isolation terminal signal. */
final class DnsLookupFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    enum Kind {
        NOT_FOUND,
        TIMEOUT,
        OVERLOADED,
        CLOSED,
        PROVIDER,
        CLOCK
    }

    private final Kind kind;

    DnsLookupFailure(Kind kind) {
        super("DNS lookup failed.", null, false, false);
        this.kind = kind;
    }

    Kind kind() {
        return kind;
    }

    @Override
    public String toString() {
        return "DnsLookupFailure[REDACTED]";
    }
}
