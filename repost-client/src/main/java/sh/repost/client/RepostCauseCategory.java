package sh.repost.client;

/** Closed local-cause taxonomy frozen by the JVM public-runtime plan. */
public enum RepostCauseCategory {
    /** API credential provider callback. */ API_KEY_PROVIDER,
    /** Schema default-value generator callback. */ DEFAULT_GENERATOR,
    /** Idempotency-key generator callback. */ IDEMPOTENCY_GENERATOR,
    /** Retry entropy callback. */ RETRY_ENTROPY,
    /** DNS resolver callback. */ DNS_RESOLVER,
    /** Proxy credential callback. */ PROXY_CREDENTIAL_PROVIDER,
    /** TLS material provider callback. */ TLS_PROVIDER,
    /** Custom transport callback. */ CUSTOM_TRANSPORT,
    /** Response body consumption. */ RESPONSE_BODY,
    /** Observer callback. */ OBSERVER,
    /** Built-in HTTP runtime. */ HTTP_RUNTIME,
    /** Transport close stage. */ TRANSPORT_CLOSE,
    /** Scheduler close stage. */ SCHEDULER_CLOSE,
    /** Operation executor close stage. */ OPERATION_EXECUTOR_CLOSE,
    /** DNS executor close stage. */ DNS_EXECUTOR_CLOSE,
    /** Proxy credential executor close stage. */ PROXY_CREDENTIAL_EXECUTOR_CLOSE,
    /** TLS executor close stage. */ TLS_EXECUTOR_CLOSE,
    /** Terminal settlement close stage. */ TERMINAL_SETTLEMENT_CLOSE,
    /** Observer dispatcher close stage. */ OBSERVER_CLOSE,
    /** Cause has no more specific safe category. */ UNKNOWN
}
