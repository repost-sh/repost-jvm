package sh.repost.client;

/** Stable, low-cardinality failure codes emitted by the Repost runtime. */
public enum RepostErrorCode {
    /** Client configuration is invalid. */ CONFIGURATION,
    /** Runtime is closed. */ CLOSED,
    /** Model or payload validation failed. */ VALIDATION,
    /** Validated data could not be serialized. */ SERIALIZATION,
    /** Request exceeds the configured size limit. */ REQUEST_TOO_LARGE,
    /** DNS resolution failed. */ DNS,
    /** Endpoint connection failed. */ CONNECT,
    /** Proxy negotiation failed. */ PROXY,
    /** TLS negotiation or validation failed. */ TLS,
    /** Transport I/O failed. */ IO,
    /** A transport attempt timed out. */ ATTEMPT_TIMEOUT,
    /** The operation-wide deadline elapsed. */ OPERATION_DEADLINE,
    /** The caller cancelled the operation. */ CANCELLED,
    /** A bounded runtime resource rejected admission. */ OVERLOADED,
    /** The server rate-limited the operation. */ RATE_LIMITED,
    /** The server rejected the request. */ HTTP_REJECTED,
    /** The server returned a retryable failure. */ SERVER_FAILURE,
    /** Response metadata or body exceeded a configured limit. */ RESPONSE_TOO_LARGE,
    /** Response framing or content violated the protocol. */ RESPONSE_PROTOCOL,
    /** Generated descriptor version is unsupported. */ DESCRIPTOR_VERSION
}
