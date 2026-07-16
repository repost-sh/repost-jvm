package sh.repost.client;

/** Best-known request delivery state for an operation. */
public enum DeliveryState {
    /** Request was definitely not published. */ NOT_SENT,
    /** Request may have been published but no definitive response was received. */ POSSIBLY_SENT,
    /** Server accepted the request. */ ACCEPTED,
    /** Server definitively rejected the request. */ REJECTED,
    /** Cancellation occurred after publication may have started. */ CANCELLED_UNKNOWN
}
