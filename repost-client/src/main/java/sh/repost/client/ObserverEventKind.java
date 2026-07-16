package sh.repost.client;

/** Kinds emitted by the bounded runtime observer stream. */
public enum ObserverEventKind {
    /** Operation entered the runtime. */ OPERATION_START,
    /** Transport attempt started. */ ATTEMPT_START,
    /** Transport attempt ended. */ ATTEMPT_END,
    /** Operation entered retry backoff. */ RETRY_DELAY,
    /** Cancellation was observed. */ OPERATION_CANCEL,
    /** Operation reached its terminal outcome. */ OPERATION_END
}
