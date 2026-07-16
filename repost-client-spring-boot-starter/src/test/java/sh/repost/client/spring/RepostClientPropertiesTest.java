package sh.repost.client.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.ResourcePropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;

final class RepostClientPropertiesTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RepostClientAutoConfiguration.class));

    @Test
    void bindsEveryExactPropertyAsImmutableTypedConfiguration() {
        contextRunner.withPropertyValues(
                        "repost.client.enabled=true",
                        "repost.client.api-key=sentinel-secret",
                        "repost.client.base-uri=https://example.com",
                        "repost.client.connect-timeout=11s",
                        "repost.client.attempt-timeout=31s",
                        "repost.client.operation-timeout=121s",
                        "repost.client.max-attempts=5",
                        "repost.client.max-in-flight=257",
                        "repost.client.max-buffered-bytes=65MiB",
                        "repost.client.retry-base-delay=251ms",
                        "repost.client.retry-max-delay=61s",
                        "repost.client.user-agent-suffix=example/1",
                        "repost.client.observability.micrometer-enabled=false",
                        "repost.client.observability.opentelemetry-enabled=false")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    RepostClientProperties properties =
                            context.getBean(RepostClientProperties.class);
                    assertTrue(properties.isEnabled());
                    assertEquals("sentinel-secret", properties.getApiKey());
                    assertEquals("https://example.com", properties.getBaseUri());
                    assertEquals(Duration.ofSeconds(11), properties.getConnectTimeout());
                    assertEquals(Duration.ofSeconds(31), properties.getAttemptTimeout());
                    assertEquals(Duration.ofSeconds(121), properties.getOperationTimeout());
                    assertEquals(5, properties.getMaxAttempts());
                    assertEquals(257, properties.getMaxInFlight());
                    assertEquals(65L * 1_048_576L, properties.getMaxBufferedBytes());
                    assertEquals(Duration.ofMillis(251), properties.getRetryBaseDelay());
                    assertEquals(Duration.ofSeconds(61), properties.getRetryMaxDelay());
                    assertEquals("example/1", properties.getUserAgentSuffix());
                    assertEquals(Boolean.FALSE,
                            properties.getObservability().getMicrometerEnabled());
                    assertEquals(Boolean.FALSE,
                            properties.getObservability().getOpentelemetryEnabled());
                    assertFalse(properties.toString().contains("sentinel-secret"));
                    assertEquals("RepostClientProperties[REDACTED]", properties.toString());
                });
    }

    @Test
    void appliesFrozenDefaultsThroughTheExactParser() {
        contextRunner.withPropertyValues("repost.client.api-key=test-key").run(context -> {
            RepostClientProperties properties = context.getBean(RepostClientProperties.class);
            assertTrue(properties.isEnabled());
            assertEquals(Duration.ofSeconds(10), properties.getConnectTimeout());
            assertEquals(Duration.ofSeconds(30), properties.getAttemptTimeout());
            assertEquals(Duration.ofSeconds(120), properties.getOperationTimeout());
            assertEquals(4, properties.getMaxAttempts());
            assertEquals(256, properties.getMaxInFlight());
            assertEquals(64L * 1_048_576L, properties.getMaxBufferedBytes());
            assertEquals(Duration.ofMillis(250), properties.getRetryBaseDelay());
            assertEquals(Duration.ofSeconds(60), properties.getRetryMaxDelay());
            assertNull(properties.getObservability().getMicrometerEnabled());
            assertNull(properties.getObservability().getOpentelemetryEnabled());
        });
    }

    @ParameterizedTest(name = "rejects {0}")
    @MethodSource("invalidValues")
    void rejectsBroadenedOrOutOfRangeSyntax(String property, String value) {
        contextRunner.withPropertyValues(property + "=" + value).run(context -> {
            Throwable failure = context.getStartupFailure();
            assertNotNull(failure);
            assertTrue(allMessages(failure).contains(property), allMessages(failure));
        });
    }

    @Test
    void rejectsUnknownAndWronglyCasedKeysWithoutReportingValues() {
        String secret = "sentinel-unknown-value";
        contextRunner.withPropertyValues(
                        "repost.client.maxAttempts=" + secret,
                        "repost.client.api-key=test-key")
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertNotNull(failure);
                    String messages = allMessages(failure);
                    assertTrue(messages.contains("repost.client.maxAttempts"), messages);
                    assertTrue(messages.contains("canonical keys"), messages);
                    assertFalse(messages.contains(secret), messages);
                });
    }

    @Test
    void acceptsOnlyCanonicalEnvironmentSpellings() {
        contextRunner.withInitializer(context -> context.getEnvironment().getPropertySources()
                        .addFirst(new SystemEnvironmentPropertySource(
                                "test-env",
                                Map.of(
                                        "REPOST_CLIENT_API_KEY", "test-key",
                                        "REPOST_CLIENT_MAX_ATTEMPTS", "5"))))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(5, context.getBean(RepostClientProperties.class).getMaxAttempts());
                });
    }

    @Test
    void bindsRealYamlAndPropertiesResources() {
        contextRunner.withInitializer(context -> {
                    try {
                        new YamlPropertySourceLoader()
                                .load("yaml", new ClassPathResource("repost-client-test.yml"))
                                .forEach(source -> context.getEnvironment()
                                        .getPropertySources().addLast(source));
                        context.getEnvironment().getPropertySources().addFirst(
                                new ResourcePropertySource("classpath:repost-client-test.properties"));
                    } catch (java.io.IOException failure) {
                        throw new IllegalStateException(failure);
                    }
                })
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    RepostClientProperties properties =
                            context.getBean(RepostClientProperties.class);
                    assertEquals(7, properties.getMaxAttempts());
                    assertEquals(Duration.ofSeconds(17), properties.getConnectTimeout());
                });
    }

    @Test
    void rejectsMisspelledEnvironmentKeysWithoutReportingValues() {
        String secret = "sentinel-environment-value";
        contextRunner.withInitializer(context -> context.getEnvironment().getPropertySources()
                        .addFirst(new SystemEnvironmentPropertySource(
                                "test-env",
                                Map.of("REPOST_CLIENT_MAX_ATTEMPTZ", secret))))
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertNotNull(failure);
                    String messages = allMessages(failure);
                    assertTrue(messages.contains("REPOST_CLIENT_MAX_ATTEMPTZ"), messages);
                    assertTrue(messages.contains("test-env"), messages);
                    assertFalse(messages.contains(secret), messages);
                });
    }

    @Test
    void exactTrueWithoutRequiredBridgeFailsWithPropertyNameOnly() {
        contextRunner.withPropertyValues(
                        "repost.client.api-key=test-key",
                        "repost.client.observability.micrometer-enabled=true")
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertNotNull(failure);
                    assertTrue(allMessages(failure).contains(
                            "repost.client.observability.micrometer-enabled"));
                });
    }

    private static Stream<Arguments> invalidValues() {
        return Stream.of(
                Arguments.of("repost.client.enabled", "TRUE"),
                Arguments.of("repost.client.max-attempts", "01"),
                Arguments.of("repost.client.max-attempts", "11"),
                Arguments.of("repost.client.max-in-flight", "+1"),
                Arguments.of("repost.client.connect-timeout", "PT10S"),
                Arguments.of("repost.client.attempt-timeout", "1.5s"),
                Arguments.of("repost.client.operation-timeout", "10S"),
                Arguments.of("repost.client.retry-base-delay", "0ms"),
                Arguments.of("repost.client.retry-max-delay", "9223372036855s"),
                Arguments.of("repost.client.max-buffered-bytes", "64MB"),
                Arguments.of("repost.client.max-buffered-bytes", "3MiB"),
                Arguments.of("repost.client.observability.opentelemetry-enabled", "TRUE"));
    }

    private static String allMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null) {
                messages.append(current.getMessage()).append('\n');
            }
        }
        return messages.toString();
    }
}
