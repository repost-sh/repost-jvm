package sh.repost.client;

import java.util.Objects;

/** One immutable raw header field. */
public final class TransportHeaderField {
    private static final String REDACTED = "TransportHeaderField[REDACTED]";

    private final String name;
    private final String value;

    private TransportHeaderField(String name, String value) {
        this.name = Objects.requireNonNull(name, "name");
        this.value = Objects.requireNonNull(value, "value");
    }

    /**
     * Creates one raw header field.
     *
     * @param name header name
     * @param value header value
     * @return immutable field
     */
    public static TransportHeaderField of(String name, String value) {
        return new TransportHeaderField(name, value);
    }

    /**
     * Returns header name.
     *
     * @return header name
     */
    public String getName() {
        return name;
    }

    /**
     * Returns header value.
     *
     * @return header value
     */
    public String getValue() {
        return value;
    }

    @Override
    public String toString() {
        return REDACTED;
    }
}
