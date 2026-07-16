package sh.repost.client;

import java.net.URI;
import java.util.Objects;
import sh.repost.client.internal.UriValidator;

/** Immutable explicit HTTP proxy configuration. */
public final class HttpProxyOptions {
    private final URI uri;
    private final ProxyCredentialsProvider credentialsProvider;

    private HttpProxyOptions(Builder builder) {
        this.uri = UriValidator.canonicalProxy(builder.rawUri);
        this.credentialsProvider = builder.credentialsProvider;
    }

    /**
     * Returns a new proxy options builder.
     *
     * @return proxy options builder
     */
    public static Builder builder() {
        return new Builder();
    }

    URI uri() {
        return uri;
    }

    ProxyCredentialsProvider credentialsProvider() {
        return credentialsProvider;
    }

    @Override
    public String toString() {
        return "HttpProxyOptions[REDACTED]";
    }

    /** Builder for explicit proxy configuration. */
    public static final class Builder {
        private String rawUri;
        private ProxyCredentialsProvider credentialsProvider;

        private Builder() { }

        /**
         * Sets the canonical HTTP proxy URI.
         *
         * @param rawValue URI with explicit port
         * @return this builder
         */
        public Builder uri(String rawValue) {
            this.rawUri = Objects.requireNonNull(rawValue, "rawValue");
            return this;
        }

        /**
         * Sets a borrowed proxy credential provider.
         *
         * @param value provider
         * @return this builder
         */
        public Builder credentialsProvider(ProxyCredentialsProvider value) {
            this.credentialsProvider = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Builds validated immutable proxy options.
         *
         * @return proxy options
         */
        public HttpProxyOptions build() {
            if (rawUri == null) {
                throw new IllegalArgumentException("proxy uri is required");
            }
            return new HttpProxyOptions(this);
        }
    }
}
