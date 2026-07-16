package sh.repost.client;

/** Stable identifiers for client configuration fields used in structured diagnostics. */
public enum ClientOptionKey {
    /** Fixed API credential. */ API_KEY,
    /** Operation-time API credential provider. */ API_KEY_PROVIDER,
    /** Repost API base URI. */ BASE_URI,
    /** Connection establishment timeout. */ CONNECT_TIMEOUT,
    /** Per-attempt timeout. */ ATTEMPT_TIMEOUT,
    /** Whole-operation timeout. */ OPERATION_TIMEOUT,
    /** Maximum transport attempts. */ MAX_ATTEMPTS,
    /** Maximum admitted operations. */ MAX_IN_FLIGHT_OPERATIONS,
    /** Maximum buffered request and response bytes. */ MAX_BUFFERED_BYTES,
    /** Initial retry delay. */ RETRY_BASE_DELAY,
    /** Maximum retry delay. */ RETRY_MAX_DELAY,
    /** Built-in HTTP transport configuration. */ HTTP_TRANSPORT_OPTIONS,
    /** Custom one-attempt transport. */ TRANSPORT,
    /** Operation executor. */ EXECUTOR,
    /** Retry scheduler. */ SCHEDULER,
    /** Terminal operation observer. */ OBSERVER,
    /** Observer callback executor. */ OBSERVER_EXECUTOR,
    /** Live telemetry integration. */ TELEMETRY,
    /** Schema default-value generators. */ DEFAULT_VALUE_GENERATORS,
    /** Idempotency-key generator. */ IDEMPOTENCY_KEY_GENERATOR,
    /** Monotonic time source. */ MONOTONIC_CLOCK,
    /** Wall-clock time source. */ WALL_CLOCK,
    /** Retry jitter entropy source. */ RETRY_ENTROPY,
    /** User-Agent suffix. */ USER_AGENT_SUFFIX
}
