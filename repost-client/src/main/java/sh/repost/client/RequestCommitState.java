package sh.repost.client;

/** Transport evidence about whether request publication began. */
public enum RequestCommitState {
    /** Transport proves no request bytes were published. */ NOT_COMMITTED,
    /** Transport proves request publication began. */ COMMITTED,
    /** Transport cannot determine whether publication began. */ UNKNOWN
}
