package sh.repost.client;

/** Stable validation issue codes used with safe schema-derived paths. */
public enum ValidationIssueCode {
    /** A required field is absent. */ REQUIRED,
    /** Explicit null is not allowed. */ NULL_NOT_ALLOWED,
    /** Runtime value has the wrong schema type. */ TYPE_MISMATCH,
    /** Numeric or temporal value is outside the supported range. */ OUT_OF_RANGE,
    /** Floating-point value is not finite. */ NON_FINITE,
    /** Date-time value is invalid. */ INVALID_DATETIME,
    /** Enum member is not declared by the descriptor. */ INVALID_ENUM,
    /** Raw JSON value is outside the accepted domain. */ INVALID_JSON,
    /** Text contains an invalid Unicode scalar sequence. */ INVALID_UNICODE,
    /** Collection depth or aggregate node limit was reached. */ COLLECTION_LIMIT,
    /** A cycle was detected. */ CYCLE
}
