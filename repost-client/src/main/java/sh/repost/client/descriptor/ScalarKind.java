package sh.repost.client.descriptor;

/** Runtime scalar categories represented by a field descriptor. */
public enum ScalarKind {
    /** Unicode text. */ STRING,
    /** Boolean value. */ BOOLEAN,
    /** Signed 64-bit integer. */ INT64,
    /** Finite IEEE-754 double. */ FLOAT64,
    /** UTC instant serialized at millisecond precision. */ DATETIME,
    /** Value in the accepted raw-JSON domain. */ JSON,
    /** Descriptor-defined enum member. */ ENUM,
    /** Nested generated model. */ MODEL
}
