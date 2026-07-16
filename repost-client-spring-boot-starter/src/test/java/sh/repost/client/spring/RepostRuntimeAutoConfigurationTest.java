package sh.repost.client.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import sh.repost.client.ApiKeyProvider;
import sh.repost.client.ClientOptions;
import sh.repost.client.ClientOptionsCustomizer;
import sh.repost.client.RepostRuntime;
import sh.repost.client.error.RepostConfigurationException;

final class RepostRuntimeAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RepostClientAutoConfiguration.class));

    @Test
    void disabledConfigurationCreatesNoRuntime() {
        contextRunner.withPropertyValues("repost.client.enabled=false")
                .run(context -> assertTrue(context.getBeansOfType(RepostRuntime.class).isEmpty()));
    }

    @Test
    void providerSuppressesFixedPropertyAndCustomizerRunsExactlyOnce() {
        AtomicInteger calls = new AtomicInteger();
        contextRunner.withPropertyValues("repost.client.api-key=property-key")
                .withBean(ApiKeyProvider.class, () -> () -> "provider-key")
                .withBean(ClientOptionsCustomizer.class, () -> builder -> calls.incrementAndGet())
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(1, context.getBeansOfType(RepostRuntime.class).size());
                    assertEquals(1, calls.get());
                });
    }

    @Test
    void providerAndCustomizerCredentialConflictFailsWithoutSecretValue() {
        String secret = "sentinel-customizer-secret";
        contextRunner.withBean("credentialProvider", ApiKeyProvider.class, () -> () -> "provider-key")
                .withBean(
                        "credentialCustomizer",
                        ClientOptionsCustomizer.class,
                        () -> builder -> builder.apiKey(secret))
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertNotNull(failure);
                    String messages = allMessages(failure);
                    assertTrue(!messages.contains(secret), messages);
                    assertTrue(messages.contains("ApiKeyProvider bean credentialProvider"), messages);
                    assertTrue(messages.contains(
                            "ClientOptionsCustomizer bean credentialCustomizer"), messages);
                    RepostConfigurationException configuration = findCause(
                            failure, RepostConfigurationException.class);
                    assertNotNull(configuration);
                    assertEquals(1, configuration.getConfigurationIssues().size());
                    assertEquals(
                            "ConfigurationIssue[code=CONFLICT, optionKeys=[API_KEY, API_KEY_PROVIDER]]",
                            configuration.getConfigurationIssues().get(0).toString());
                });
    }

    @Test
    void userRuntimeSuppressesDefaultConstruction() {
        RepostRuntime runtime = testRuntime();
        contextRunner.withBean("userRuntime", RepostRuntime.class, () -> runtime, bean ->
                        bean.setDestroyMethodName(""))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(1, context.getBeansOfType(RepostRuntime.class).size());
                    assertSame(runtime, context.getBean(RepostRuntime.class));
                });
        runtime.close();
    }

    @Test
    void duplicateProvidersFailWithBothBeanNames() {
        contextRunner.withBean("providerTwo", ApiKeyProvider.class, () -> () -> "two")
                .withBean("providerOne", ApiKeyProvider.class, () -> () -> "one")
                .run(context -> assertDuplicate(
                        context.getStartupFailure(), "ApiKeyProvider", "providerOne", "providerTwo"));
    }

    @Test
    void duplicateCustomizersFailWithBothBeanNames() {
        contextRunner.withBean("customizerTwo", ClientOptionsCustomizer.class, () -> builder -> { })
                .withBean("customizerOne", ClientOptionsCustomizer.class, () -> builder -> { })
                .run(context -> assertDuplicate(
                        context.getStartupFailure(),
                        "ClientOptionsCustomizer",
                        "customizerOne",
                        "customizerTwo"));
    }

    @Test
    void duplicateRuntimesFailWithBothBeanNames() {
        contextRunner.withUserConfiguration(DuplicateRuntimeConfiguration.class)
                .run(context -> assertDuplicate(
                        context.getStartupFailure(), "RepostRuntime", "runtimeOne", "runtimeTwo"));
    }

    @Test
    void userRuntimeFactoryNamedRepostRuntimeIsStillBorrowed() {
        contextRunner.withPropertyValues(
                        "repost.client.observability.micrometer-enabled=false")
                .withUserConfiguration(NamedRuntimeConfiguration.class)
                .run(context -> {
                    String messages = allMessages(context.getStartupFailure());
                    assertTrue(messages.contains("user-provided RepostRuntime"), messages);
                });
    }

    private static void assertDuplicate(
            Throwable failure, String type, String first, String second) {
        assertNotNull(failure);
        String messages = allMessages(failure);
        assertTrue(messages.contains(type), messages);
        assertTrue(messages.contains(first), messages);
        assertTrue(messages.contains(second), messages);
    }

    private static RepostRuntime testRuntime() {
        return RepostRuntime.create(ClientOptions.builder()
                .apiKey("test-key")
                .transport(request -> CompletableFuture.failedFuture(
                        new AssertionError("transport must not run")))
                .build());
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

    private static <T extends Throwable> T findCause(Throwable failure, Class<T> type) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
        }
        return null;
    }

    @Configuration(proxyBeanMethods = false)
    static class DuplicateRuntimeConfiguration {
        @Bean
        RepostRuntime runtimeOne() {
            return testRuntime();
        }

        @Bean
        RepostRuntime runtimeTwo() {
            return testRuntime();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class NamedRuntimeConfiguration {
        @Bean(destroyMethod = "")
        RepostRuntime repostRuntime() {
            return testRuntime();
        }
    }
}
