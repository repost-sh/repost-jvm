package sh.repost.client.cdi;

import java.time.Duration;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigValue;

final class RepostCdiConfiguration {
    private static final long MAX_DURATION_MILLIS = 9_223_372_036_854L;
    private static final Pattern DECIMAL = Pattern.compile("[1-9][0-9]*");
    private static final Pattern DURATION = Pattern.compile("([1-9][0-9]*)(ms|s|m)");
    private static final Pattern BYTES = Pattern.compile("([1-9][0-9]*)(B|KiB|MiB|GiB)");
    private static final Set<String> KEYS = Set.of(
            "repost.client.enabled",
            "repost.client.api-key",
            "repost.client.base-uri",
            "repost.client.connect-timeout",
            "repost.client.attempt-timeout",
            "repost.client.operation-timeout",
            "repost.client.max-attempts",
            "repost.client.max-in-flight",
            "repost.client.max-buffered-bytes",
            "repost.client.retry-base-delay",
            "repost.client.retry-max-delay",
            "repost.client.user-agent-suffix",
            "repost.client.observability.micrometer-enabled",
            "repost.client.observability.opentelemetry-enabled");

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
    private final Boolean micrometerEnabled;
    private final Boolean opentelemetryEnabled;

    private RepostCdiConfiguration(Config config) {
        rejectUnknownKeys(config);
        enabled = parseBoolean(
                "repost.client.enabled", raw(config, "repost.client.enabled", "true"));
        apiKey = raw(config, "repost.client.api-key", null);
        baseUri = raw(config, "repost.client.base-uri", null);
        connectTimeout = parseDuration(
                "repost.client.connect-timeout",
                raw(config, "repost.client.connect-timeout", "10s"));
        attemptTimeout = parseDuration(
                "repost.client.attempt-timeout",
                raw(config, "repost.client.attempt-timeout", "30s"));
        operationTimeout = parseDuration(
                "repost.client.operation-timeout",
                raw(config, "repost.client.operation-timeout", "120s"));
        maxAttempts = parseInteger(
                "repost.client.max-attempts",
                raw(config, "repost.client.max-attempts", "4"),
                1,
                10);
        maxInFlight = parseInteger(
                "repost.client.max-in-flight",
                raw(config, "repost.client.max-in-flight", "256"),
                1,
                65_536);
        maxBufferedBytes = parseBytes(
                "repost.client.max-buffered-bytes",
                raw(config, "repost.client.max-buffered-bytes", "64MiB"));
        retryBaseDelay = parseDuration(
                "repost.client.retry-base-delay",
                raw(config, "repost.client.retry-base-delay", "250ms"));
        retryMaxDelay = parseDuration(
                "repost.client.retry-max-delay",
                raw(config, "repost.client.retry-max-delay", "60s"));
        if (retryBaseDelay.compareTo(retryMaxDelay) > 0) {
            throw invalid(
                    "repost.client.retry-base-delay", "not greater than retry-max-delay");
        }
        userAgentSuffix = raw(config, "repost.client.user-agent-suffix", null);
        micrometerEnabled = parseOptionalBoolean(
                "repost.client.observability.micrometer-enabled",
                raw(config, "repost.client.observability.micrometer-enabled", null));
        opentelemetryEnabled = parseOptionalBoolean(
                "repost.client.observability.opentelemetry-enabled",
                raw(config, "repost.client.observability.opentelemetry-enabled", null));
    }

    static RepostCdiConfiguration from(Config config) {
        return new RepostCdiConfiguration(java.util.Objects.requireNonNull(config, "config"));
    }

    boolean enabled() {
        return enabled;
    }

    String apiKey() {
        return apiKey;
    }

    String baseUri() {
        return baseUri;
    }

    Duration connectTimeout() {
        return connectTimeout;
    }

    Duration attemptTimeout() {
        return attemptTimeout;
    }

    Duration operationTimeout() {
        return operationTimeout;
    }

    int maxAttempts() {
        return maxAttempts;
    }

    int maxInFlight() {
        return maxInFlight;
    }

    long maxBufferedBytes() {
        return maxBufferedBytes;
    }

    Duration retryBaseDelay() {
        return retryBaseDelay;
    }

    Duration retryMaxDelay() {
        return retryMaxDelay;
    }

    String userAgentSuffix() {
        return userAgentSuffix;
    }

    Boolean micrometerEnabled() {
        return micrometerEnabled;
    }

    Boolean opentelemetryEnabled() {
        return opentelemetryEnabled;
    }

    @Override
    public String toString() {
        return "RepostCdiConfiguration[REDACTED]";
    }

    private static void rejectUnknownKeys(Config config) {
        for (String key : config.getPropertyNames()) {
            if (key.startsWith("repost.client.") && !KEYS.contains(key)) {
                ConfigValue value = config.getConfigValue(key);
                String source = value.getSourceName();
                throw new IllegalArgumentException(
                        "Unknown Repost configuration key " + key + " from "
                                + (source == null ? "unknown config source" : source));
            }
        }
    }

    private static String raw(Config config, String key, String defaultValue) {
        String value = config.getConfigValue(key).getRawValue();
        return value == null ? defaultValue : value;
    }

    private static Boolean parseOptionalBoolean(String key, String raw) {
        return raw == null ? null : parseBoolean(key, raw);
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

    private static long parseBytes(String key, String raw) {
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
            if (bytes < 4_194_304L || bytes > 1_073_741_824L) {
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
}
