package sh.repost.client;

import java.util.Objects;

/** Immutable per-send options. */
public final class SendOptions {
    private static final SendOptions DEFAULTS = new SendOptions(null);

    private final String idempotencyKey;

    private SendOptions(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey == null ? null : validateIdempotencyKey(idempotencyKey);
    }

    /**
     * Returns a new per-send options builder.
     *
     * @return a new per-send options builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns shared immutable default options.
     *
     * @return shared immutable default options
     */
    public static SendOptions defaults() {
        return DEFAULTS;
    }

    String getIdempotencyKey() {
        return idempotencyKey;
    }

    @Override
    public String toString() {
        return "SendOptions[REDACTED]";
    }

    static String validateIdempotencyKey(String value) {
        Objects.requireNonNull(value, "idempotencyKey");
        if (value.isEmpty() || value.length() > 255
                || value.charAt(0) == ' ' || value.charAt(value.length() - 1) == ' ') {
            throw new IllegalArgumentException("idempotencyKey has invalid length or spacing");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x20 || character > 0x7e) {
                throw new IllegalArgumentException("idempotencyKey must be printable ASCII");
            }
        }
        return value;
    }

    /** Builder for per-send options. */
    public static final class Builder {
        private String idempotencyKey;

        private Builder() { }

        /**
         * Sets a caller-controlled key reused across retries.
         *
         * @param value printable ASCII idempotency key
         * @return this builder
         */
        public Builder idempotencyKey(String value) {
            this.idempotencyKey = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Returns validated immutable options.
         *
         * @return validated immutable options
         */
        public SendOptions build() {
            return new SendOptions(idempotencyKey);
        }
    }
}
