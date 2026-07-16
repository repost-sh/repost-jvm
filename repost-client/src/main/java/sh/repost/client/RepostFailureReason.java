package sh.repost.client;

/** Stable network failure reasons with application-facing remediation keys. */
public enum RepostFailureReason {
    /** DNS returned no address. */ DNS_NOT_FOUND("network.dns.not-found"),
    /** DNS resolution timed out. */ DNS_TIMEOUT("network.dns.timeout"),
    /** The endpoint refused the connection. */ CONNECT_REFUSED("network.connect.refused"),
    /** Connection establishment timed out. */ CONNECT_TIMEOUT("network.connect.timeout"),
    /** The peer reset the connection. */ CONNECTION_RESET("network.connection.reset"),
    /** The connection closed unexpectedly. */ CONNECTION_CLOSED("network.connection.closed"),
    /** The TLS certificate chain is untrusted. */ TLS_UNTRUSTED("network.tls.untrusted"),
    /** The TLS certificate is expired. */ TLS_CERTIFICATE_EXPIRED("network.tls.certificate-expired"),
    /** The TLS certificate is not yet valid. */
    TLS_CERTIFICATE_NOT_YET_VALID("network.tls.certificate-not-yet-valid"),
    /** The TLS certificate does not match the endpoint. */
    TLS_HOSTNAME_MISMATCH("network.tls.hostname-mismatch"),
    /** TLS negotiation failed for another reason. */ TLS_NEGOTIATION("network.tls.negotiation"),
    /** The proxy requires authentication. */ PROXY_AUTH_REQUIRED("network.proxy.auth-required"),
    /** The proxy could not establish a tunnel. */ PROXY_CONNECT_FAILED("network.proxy.connect-failed"),
    /** No more specific safe reason is available. */ UNKNOWN("network.unknown");

    private final String remediationKey;

    RepostFailureReason(String remediationKey) {
        this.remediationKey = remediationKey;
    }

    /**
     * Returns the stable localization/remediation lookup key.
     *
     * @return the stable localization/remediation lookup key
     */
    public String getRemediationKey() {
        return remediationKey;
    }
}
