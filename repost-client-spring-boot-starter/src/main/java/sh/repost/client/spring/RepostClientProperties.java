package sh.repost.client.spring;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Immutable, exact-grammar configuration for one Spring-managed Repost runtime. */
@ConfigurationProperties(prefix = "repost.client", ignoreUnknownFields = false)
public final class RepostClientProperties {
    private static final long MAX_DURATION_MILLIS = 9_223_372_036_854L;
    private static final Pattern DECIMAL = Pattern.compile("[1-9][0-9]*");
    private static final Pattern DURATION = Pattern.compile("([1-9][0-9]*)(ms|s|m)");
    private static final Pattern BYTES = Pattern.compile("([1-9][0-9]*)(B|KiB|MiB|GiB)");

    private final boolean enabled;
    private final String apiKey;
    private final String baseUri;
    private final Duration connectTimeout;
    private final Duration attemptTimeout;
    private final Duration operationTimeout;
    private final int maxAttempts;
    private final int maxInFlight;
    private final long maxBufferedBytes;
    private final Duration retryBaseDelay;
    private final Duration retryMaxDelay;
    private final String userAgentSuffix;
    private final Observability observability;

    /**
     * Creates validated configuration from Spring's raw external values.
     *
     * @param enabled exact lowercase boolean
     * @param apiKey optional fixed API key
     * @param baseUri optional raw base URI
     * @param connectTimeout exact Repost duration
     * @param attemptTimeout exact Repost duration
     * @param operationTimeout exact Repost duration
     * @param maxAttempts maximum total attempts
     * @param maxInFlight maximum concurrent operations
     * @param maxBufferedBytes aggregate runtime byte budget
     * @param retryBaseDelay initial retry delay
     * @param retryMaxDelay maximum retry delay
     * @param userAgentSuffix optional validated user-agent suffix
     * @param observability optional observability switches
     */
    public RepostClientProperties(
            @DefaultValue("true") String enabled,
            String apiKey,
            String baseUri,
            @DefaultValue("10s") String connectTimeout,
            @DefaultValue("30s") String attemptTimeout,
            @DefaultValue("120s") String operationTimeout,
            @DefaultValue("4") String maxAttempts,
            @DefaultValue("256") String maxInFlight,
            @DefaultValue("64MiB") String maxBufferedBytes,
            @DefaultValue("250ms") String retryBaseDelay,
            @DefaultValue("60s") String retryMaxDelay,
            String userAgentSuffix,
            @DefaultValue Observability observability) {
        this.enabled = parseBoolean("repost.client.enabled", enabled);
        this.apiKey = apiKey;
        this.baseUri = baseUri;
        this.connectTimeout = parseDuration("repost.client.connect-timeout", connectTimeout);
        this.attemptTimeout = parseDuration("repost.client.attempt-timeout", attemptTimeout);
        this.operationTimeout = parseDuration("repost.client.operation-timeout", operationTimeout);
        this.maxAttempts = parseInteger("repost.client.max-attempts", maxAttempts, 1, 10);
        this.maxInFlight = parseInteger(
                "repost.client.max-in-flight", maxInFlight, 1, 65_536);
        this.maxBufferedBytes = parseBytes(
                "repost.client.max-buffered-bytes",
                maxBufferedBytes,
                4_194_304L,
                1_073_741_824L);
        this.retryBaseDelay = parseDuration("repost.client.retry-base-delay", retryBaseDelay);
        this.retryMaxDelay = parseDuration("repost.client.retry-max-delay", retryMaxDelay);
        if (this.retryBaseDelay.compareTo(this.retryMaxDelay) > 0) {
            throw invalid("repost.client.retry-base-delay", "not greater than retry-max-delay");
        }
        this.userAgentSuffix = userAgentSuffix;
        this.observability = Objects.requireNonNull(observability, "observability");
    }

    /**
     * Reports whether default auto-configuration is enabled.
     *
     * @return whether default auto-configuration is enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Returns the fixed API key.
     *
     * @return fixed API key, or {@code null}
     */
    public String getApiKey() {
        return apiKey;
    }

    /**
     * Returns the raw base URI.
     *
     * @return raw base URI, or {@code null}
     */
    public String getBaseUri() {
        return baseUri;
    }

    /**
     * Returns the connection timeout.
     *
     * @return connection timeout
     */
    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    /**
     * Returns the per-attempt timeout.
     *
     * @return per-attempt timeout
     */
    public Duration getAttemptTimeout() {
        return attemptTimeout;
    }

    /**
     * Returns the operation timeout.
     *
     * @return operation timeout
     */
    public Duration getOperationTimeout() {
        return operationTimeout;
    }

    /**
     * Returns the maximum total attempts.
     *
     * @return maximum total attempts
     */
    public int getMaxAttempts() {
        return maxAttempts;
    }

    /**
     * Returns the maximum concurrent operations.
     *
     * @return maximum concurrent operations
     */
    public int getMaxInFlight() {
        return maxInFlight;
    }

    /**
     * Returns the aggregate runtime byte budget.
     *
     * @return aggregate runtime byte budget
     */
    public long getMaxBufferedBytes() {
        return maxBufferedBytes;
    }

    /**
     * Returns the initial retry delay.
     *
     * @return initial retry delay
     */
    public Duration getRetryBaseDelay() {
        return retryBaseDelay;
    }

    /**
     * Returns the maximum retry delay.
     *
     * @return maximum retry delay
     */
    public Duration getRetryMaxDelay() {
        return retryMaxDelay;
    }

    /**
     * Returns the user-agent suffix.
     *
     * @return user-agent suffix, or {@code null}
     */
    public String getUserAgentSuffix() {
        return userAgentSuffix;
    }

    /**
     * Returns the observability switches.
     *
     * @return observability switches
     */
    public Observability getObservability() {
        return observability;
    }

    @Override
    public String toString() {
        return "RepostClientProperties[REDACTED]";
    }

    private static boolean parseBoolean(String key, String raw) {
        if ("true".equals(raw)) {
            return true;
        }
        if ("false".equals(raw)) {
            return false;
        }
        throw invalid(key, "lowercase true or false");
    }

    private static int parseInteger(String key, String raw, int minimum, int maximum) {
        if (!DECIMAL.matcher(raw).matches()) {
            throw invalid(key, "a decimal integer without signs, whitespace, or leading zeroes");
        }
        try {
            int value = Integer.parseInt(raw);
            if (value < minimum || value > maximum) {
                throw invalid(key, "an integer from " + minimum + " through " + maximum);
            }
            return value;
        } catch (NumberFormatException overflow) {
            throw invalid(key, "an integer from " + minimum + " through " + maximum);
        }
    }

    private static Duration parseDuration(String key, String raw) {
        Matcher matcher = DURATION.matcher(raw);
        if (!matcher.matches()) {
            throw invalid(key, "a positive duration using ms, s, or m");
        }
        try {
            long magnitude = Long.parseLong(matcher.group(1));
            long factor = switch (matcher.group(2)) {
                case "ms" -> 1L;
                case "s" -> 1_000L;
                case "m" -> 60_000L;
                default -> throw new AssertionError("unreachable duration unit");
            };
            long millis = Math.multiplyExact(magnitude, factor);
            if (millis > MAX_DURATION_MILLIS) {
                throw invalid(key, "a whole-millisecond duration within the supported range");
            }
            return Duration.ofMillis(millis);
        } catch (ArithmeticException invalidNumber) {
            throw invalid(key, "a whole-millisecond duration within the supported range");
        }
    }

    private static long parseBytes(
            String key, String raw, long minimum, long maximum) {
        Matcher matcher = BYTES.matcher(raw);
        if (!matcher.matches()) {
            throw invalid(key, "a positive byte size using B, KiB, MiB, or GiB");
        }
        try {
            long magnitude = Long.parseLong(matcher.group(1));
            long factor = switch (matcher.group(2)) {
                case "B" -> 1L;
                case "KiB" -> 1_024L;
                case "MiB" -> 1_048_576L;
                case "GiB" -> 1_073_741_824L;
                default -> throw new AssertionError("unreachable byte-size unit");
            };
            long bytes = Math.multiplyExact(magnitude, factor);
            if (bytes < minimum || bytes > maximum) {
                throw invalid(key, "a byte size from 4MiB through 1GiB");
            }
            return bytes;
        } catch (ArithmeticException invalidNumber) {
            throw invalid(key, "a byte size from 4MiB through 1GiB");
        }
    }

    private static IllegalArgumentException invalid(String key, String expected) {
        return new IllegalArgumentException("Invalid " + key + "; expected " + expected);
    }

    /** Immutable tri-state observability auto-wiring switches. */
    public static final class Observability {
        private final Boolean micrometerEnabled;
        private final Boolean opentelemetryEnabled;

        /**
         * Creates exact observability switches.
         *
         * @param micrometerEnabled exact lowercase boolean, or {@code null} for auto
         * @param opentelemetryEnabled exact lowercase boolean, or {@code null} for auto
         */
        public Observability(String micrometerEnabled, String opentelemetryEnabled) {
            this.micrometerEnabled = micrometerEnabled == null
                    ? null
                    : parseBoolean(
                            "repost.client.observability.micrometer-enabled", micrometerEnabled);
            this.opentelemetryEnabled = opentelemetryEnabled == null
                    ? null
                    : parseBoolean(
                            "repost.client.observability.opentelemetry-enabled", opentelemetryEnabled);
        }

        /**
         * Returns the explicit Micrometer switch.
         *
         * @return explicit Micrometer switch, or {@code null} for auto
         */
        public Boolean getMicrometerEnabled() {
            return micrometerEnabled;
        }

        /**
         * Returns the explicit OpenTelemetry switch.
         *
         * @return explicit OpenTelemetry switch, or {@code null} for auto
         */
        public Boolean getOpentelemetryEnabled() {
            return opentelemetryEnabled;
        }

        @Override
        public String toString() {
            return "Observability[micrometerEnabled=" + micrometerEnabled
                    + ", opentelemetryEnabled=" + opentelemetryEnabled + "]";
        }
    }
}
