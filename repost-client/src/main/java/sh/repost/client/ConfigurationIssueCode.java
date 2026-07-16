package sh.repost.client;

/** Stable configuration issue codes. */
public enum ConfigurationIssueCode {
    /** A required option is absent. */ MISSING,
    /** Mutually exclusive options were supplied. */ CONFLICT,
    /** An option value violates its grammar. */ INVALID_VALUE,
    /** A numeric or duration option is outside its allowed range. */ OUT_OF_RANGE,
    /** An option or combination is unsupported. */ UNSUPPORTED,
    /** Supplied resources cannot be used together safely. */ RESOURCE_MISMATCH
}
