package sh.repost.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.net.ssl.SSLContext;

/** Immutable built-in HTTP transport configuration. */
public final class HttpTransportOptions {
    private static final Set<String> PROTOCOL_ALLOWLIST = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("TLSv1.3", "TLSv1.2")));
    private static final Set<String> CIPHER_ALLOWLIST = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "TLS_AES_128_GCM_SHA256",
                    "TLS_AES_256_GCM_SHA384",
                    "TLS_CHACHA20_POLY1305_SHA256",
                    "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
                    "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
                    "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                    "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
                    "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
                    "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256")));

    private final HttpProxyOptions proxy;
    private final SSLContext sslContext;
    private final List<String> tlsProtocols;
    private final List<String> tlsCipherSuites;
    private final DnsResolver dnsResolver;

    private HttpTransportOptions(Builder builder) {
        this.proxy = builder.proxy;
        this.sslContext = builder.sslContext;
        this.tlsProtocols = copyAllowlist(builder.tlsProtocols, PROTOCOL_ALLOWLIST, "tlsProtocols");
        this.tlsCipherSuites = copyAllowlist(
                builder.tlsCipherSuites, CIPHER_ALLOWLIST, "tlsCipherSuites");
        this.dnsResolver = builder.dnsResolver;
    }

    /**
     * Returns production HTTP defaults.
     *
     * @return immutable default options
     */
    public static HttpTransportOptions defaults() {
        return builder().build();
    }

    /**
     * Returns a new HTTP options builder.
     *
     * @return HTTP options builder
     */
    public static Builder builder() {
        return new Builder();
    }

    HttpProxyOptions proxy() {
        return proxy;
    }

    SSLContext sslContext() {
        return sslContext;
    }

    List<String> tlsProtocols() {
        return tlsProtocols;
    }

    List<String> tlsCipherSuites() {
        return tlsCipherSuites;
    }

    DnsResolver dnsResolver() {
        return dnsResolver;
    }

    @Override
    public String toString() {
        return "HttpTransportOptions[configured]";
    }

    private static List<String> copyAllowlist(
            List<String> source,
            Set<String> allowlist,
            String name) {
        if (source == null) {
            return null;
        }
        if (source.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        ArrayList<String> copy = new ArrayList<>(source.size());
        HashSet<String> seen = new HashSet<>();
        for (String value : source) {
            Objects.requireNonNull(value, name + " contains null");
            if (!allowlist.contains(value)) {
                throw new IllegalArgumentException(name + " contains an unsupported value");
            }
            if (!seen.add(value)) {
                throw new IllegalArgumentException(name + " contains a duplicate");
            }
            copy.add(value);
        }
        return Collections.unmodifiableList(copy);
    }

    /** Builder for built-in HTTP transport configuration. */
    public static final class Builder {
        private HttpProxyOptions proxy;
        private SSLContext sslContext;
        private List<String> tlsProtocols;
        private List<String> tlsCipherSuites;
        private DnsResolver dnsResolver;

        private Builder() { }

        /**
         * Sets an explicit HTTP proxy.
         *
         * @param value proxy options
         * @return this builder
         */
        public Builder proxy(HttpProxyOptions value) {
            this.proxy = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Sets a borrowed truststore or mTLS context.
         *
         * @param value SSL context
         * @return this builder
         */
        public Builder sslContext(SSLContext value) {
            this.sslContext = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Sets an ordered TLS protocol allowlist.
         *
         * @param values protocol names
         * @return this builder
         */
        public Builder tlsProtocols(List<String> values) {
            this.tlsProtocols = new ArrayList<>(Objects.requireNonNull(values, "values"));
            return this;
        }

        /**
         * Sets an ordered TLS cipher-suite allowlist.
         *
         * @param values cipher-suite names
         * @return this builder
         */
        public Builder tlsCipherSuites(List<String> values) {
            this.tlsCipherSuites = new ArrayList<>(Objects.requireNonNull(values, "values"));
            return this;
        }

        /**
         * Sets a borrowed DNS resolver.
         *
         * @param value resolver
         * @return this builder
         */
        public Builder dnsResolver(DnsResolver value) {
            this.dnsResolver = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Builds validated immutable HTTP options.
         *
         * @return HTTP transport options
         */
        public HttpTransportOptions build() {
            return new HttpTransportOptions(this);
        }
    }
}
