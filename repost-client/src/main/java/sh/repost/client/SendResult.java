package sh.repost.client;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable result returned for an accepted Repost message. */
public final class SendResult {
    private static final Pattern MESSAGE_ID = Pattern.compile("^msg_[A-Za-z0-9_-]{1,124}$");

    private final String id;
    private final String type;
    private final String customerId;
    private final Instant timestamp;

    private SendResult(Builder builder) {
        this.id = requireMessageId(builder.id);
        this.type = requireText(builder.type, "type");
        this.customerId = requireText(builder.customerId, "customerId");
        this.timestamp = Objects.requireNonNull(builder.timestamp, "timestamp");
    }

    /**
     * Returns a builder for an accepted result.
     *
     * @return a builder for an accepted result
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns accepted Repost message identifier.
     *
     * @return accepted Repost message identifier
     */
    public String getId() { return id; }
    /**
     * Returns accepted event type.
     *
     * @return accepted event type
     */
    public String getType() { return type; }
    /**
     * Returns customer identifier associated with the message.
     *
     * @return customer identifier associated with the message
     */
    public String getCustomerId() { return customerId; }
    /**
     * Returns server timestamp.
     *
     * @return server timestamp
     */
    public Instant getTimestamp() { return timestamp; }
    /**
     * Returns {@link DeliveryState#ACCEPTED}.
     *
     * @return {@link DeliveryState#ACCEPTED}
     */
    public DeliveryState getDeliveryState() { return DeliveryState.ACCEPTED; }

    @Override
    public String toString() {
        return "SendResult[delivery=ACCEPTED]";
    }

    private static String requireMessageId(String value) {
        if (value == null || !MESSAGE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("id has invalid result grammar");
        }
        return value;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        ValidationIssue.utf8Bytes(value);
        return value;
    }

    /** Builder for {@link SendResult}. */
    public static final class Builder {
        private String id;
        private String type;
        private String customerId;
        private Instant timestamp;

        private Builder() { }

        /**
         * Sets the accepted message identifier.
         *
         * @param value accepted message identifier
         * @return this builder
         */
        public Builder id(String value) { this.id = value; return this; }
        /**
         * Sets the accepted event type.
         *
         * @param value accepted event type
         * @return this builder
         */
        public Builder type(String value) { this.type = value; return this; }
        /**
         * Sets the customer identifier.
         *
         * @param value customer identifier
         * @return this builder
         */
        public Builder customerId(String value) { this.customerId = value; return this; }
        /**
         * Sets the server timestamp.
         *
         * @param value server timestamp
         * @return this builder
         */
        public Builder timestamp(Instant value) { this.timestamp = value; return this; }
        /**
         * Returns validated immutable result.
         *
         * @return validated immutable result
         */
        public SendResult build() { return new SendResult(this); }
    }
}
