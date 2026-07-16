package sh.repost.client;

/** Low-cardinality outcome reported to observers and telemetry. */
public enum ObserverOutcome {
    /** Operation was accepted. */ ACCEPTED,
    /** Attempt failed and remains retry-eligible. */ RETRYABLE_FAILURE,
    /** Server rejected the operation. */ REJECTED,
    /** Operation failed terminally. */ FAILED,
    /** Caller cancelled the operation. */ CANCELLED,
    /** Runtime closed before completion. */ CLOSED
}
