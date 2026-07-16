package sh.repost.client.cdi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigValue;
import org.junit.jupiter.api.Test;

class RepostCdiConfigurationTest {
    @Test
    void appliesTheExactDefaultConfiguration() {
        RepostCdiConfiguration configuration = RepostCdiConfiguration.from(config(Map.of()));

        assertTrue(configuration.enabled());
        assertNull(configuration.apiKey());
        assertNull(configuration.baseUri());
        assertEquals(Duration.ofSeconds(10), configuration.connectTimeout());
        assertEquals(Duration.ofSeconds(30), configuration.attemptTimeout());
        assertEquals(Duration.ofSeconds(120), configuration.operationTimeout());
        assertEquals(4, configuration.maxAttempts());
        assertEquals(256, configuration.maxInFlight());
        assertEquals(64L * 1024L * 1024L, configuration.maxBufferedBytes());
        assertEquals(Duration.ofMillis(250), configuration.retryBaseDelay());
        assertEquals(Duration.ofSeconds(60), configuration.retryMaxDelay());
        assertNull(configuration.userAgentSuffix());
        assertNull(configuration.micrometerEnabled());
        assertNull(configuration.opentelemetryEnabled());
    }

    @Test
    void parsesOnlyTheFrozenRawLexicalGrammar() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("repost.client.enabled", "false");
        values.put("repost.client.api-key", "secret-value");
        values.put("repost.client.base-uri", "https://example.test/base");
        values.put("repost.client.connect-timeout", "11ms");
        values.put("repost.client.attempt-timeout", "12s");
        values.put("repost.client.operation-timeout", "13m");
        values.put("repost.client.max-attempts", "7");
        values.put("repost.client.max-in-flight", "1024");
        values.put("repost.client.max-buffered-bytes", "4MiB");
        values.put("repost.client.retry-base-delay", "14ms");
        values.put("repost.client.retry-max-delay", "15s");
        values.put("repost.client.user-agent-suffix", "enterprise-test");
        values.put("repost.client.observability.micrometer-enabled", "true");
        values.put("repost.client.observability.opentelemetry-enabled", "false");

        RepostCdiConfiguration configuration = RepostCdiConfiguration.from(config(values));

        assertFalse(configuration.enabled());
        assertEquals("secret-value", configuration.apiKey());
        assertEquals("https://example.test/base", configuration.baseUri());
        assertEquals(Duration.ofMillis(11), configuration.connectTimeout());
        assertEquals(Duration.ofSeconds(12), configuration.attemptTimeout());
        assertEquals(Duration.ofMinutes(13), configuration.operationTimeout());
        assertEquals(7, configuration.maxAttempts());
        assertEquals(1024, configuration.maxInFlight());
        assertEquals(4L * 1024L * 1024L, configuration.maxBufferedBytes());
        assertEquals(Duration.ofMillis(14), configuration.retryBaseDelay());
        assertEquals(Duration.ofSeconds(15), configuration.retryMaxDelay());
        assertEquals("enterprise-test", configuration.userAgentSuffix());
        assertEquals(Boolean.TRUE, configuration.micrometerEnabled());
        assertEquals(Boolean.FALSE, configuration.opentelemetryEnabled());
        assertEquals("RepostCdiConfiguration[REDACTED]", configuration.toString());
    }

    @Test
    void rejectsInvalidValuesWithoutEchoingThem() {
        Map<String, String> invalid = Map.of(
                "repost.client.enabled", "TRUE",
                "repost.client.connect-timeout", "PT10S",
                "repost.client.max-attempts", "04",
                "repost.client.max-buffered-bytes", "64MB");

        invalid.forEach((key, value) -> {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> RepostCdiConfiguration.from(config(Map.of(key, value))));
            assertTrue(failure.getMessage().contains(key));
            assertFalse(failure.getMessage().contains(value));
        });
    }

    @Test
    void rejectsUnknownOwnedKeysWithSourceButNoValue() {
        String value = "must-not-leak";
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> RepostCdiConfiguration.from(config(Map.of(
                        "repost.client.retry-delay", value))));

        assertTrue(failure.getMessage().contains("repost.client.retry-delay"));
        assertTrue(failure.getMessage().contains("test-config"));
        assertFalse(failure.getMessage().contains(value));
    }

    private static Config config(Map<String, String> values) {
        return (Config) Proxy.newProxyInstance(
                RepostCdiConfigurationTest.class.getClassLoader(),
                new Class<?>[] {Config.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getPropertyNames" -> values.keySet();
                    case "getConfigValue" -> configValue(
                            (String) arguments[0], values.get((String) arguments[0]));
                    case "getOptionalValue" -> Optional.ofNullable(values.get((String) arguments[0]));
                    case "getValue" -> values.get((String) arguments[0]);
                    case "getConfigSources" -> java.util.List.of();
                    case "getConverter" -> Optional.empty();
                    case "unwrap" -> throw new IllegalArgumentException("unsupported unwrap");
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static ConfigValue configValue(String name, String value) {
        return (ConfigValue) Proxy.newProxyInstance(
                RepostCdiConfigurationTest.class.getClassLoader(),
                new Class<?>[] {ConfigValue.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "getValue", "getRawValue" -> value;
                    case "getSourceName" -> value == null ? null : "test-config";
                    case "getSourceOrdinal" -> value == null ? 0 : 100;
                    case "getProfile" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
