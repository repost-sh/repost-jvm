package sh.repost.client;

/** Low-cardinality HTTP status classification. */
public enum HttpStatusClass {
    /** No status was safely received. */ NONE,
    /** HTTP 2xx status. */ SUCCESS,
    /** HTTP 3xx status. */ REDIRECTION,
    /** HTTP 4xx status. */ CLIENT_ERROR,
    /** HTTP 5xx status. */ SERVER_ERROR
}
