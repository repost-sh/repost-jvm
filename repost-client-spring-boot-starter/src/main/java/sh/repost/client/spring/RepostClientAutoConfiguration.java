package sh.repost.client.spring;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import java.util.Arrays;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.ClassUtils;
import sh.repost.client.ApiKeyProvider;
import sh.repost.client.ClientOptionKey;
import sh.repost.client.ClientOptions;
import sh.repost.client.ClientOptionsCustomizer;
import sh.repost.client.ConfigurationIssueCode;
import sh.repost.client.RepostRuntime;
import sh.repost.client.error.RepostConfigurationException;
import sh.repost.client.micrometer.MicrometerRepostObserver;
import sh.repost.client.opentelemetry.OpenTelemetryRepostTelemetry;

/** Spring Boot auto-configuration for one shared framework-neutral runtime. */
@AutoConfiguration
@EnableConfigurationProperties(RepostClientProperties.class)
@Import({
    RepostClientAutoConfiguration.HealthConfiguration.class,
    RepostClientAutoConfiguration.MetricsConfiguration.class
})
public final class RepostClientAutoConfiguration {
    /** Creates the Spring-managed auto-configuration instance. */
    public RepostClientAutoConfiguration() { }

    /**
     * Validates the closed configuration prefix and singleton extension points.
     *
     * @param environment application environment
     * @return startup validator
     */
    @Bean
    static BeanFactoryPostProcessor repostClientConfigurationValidator(
            ConfigurableEnvironment environment) {
        return beanFactory -> {
            RepostConfigurationKeys.validate(environment);
            requireAtMostOne(beanFactory, RepostRuntime.class);
            requireAtMostOne(beanFactory, ApiKeyProvider.class);
            requireAtMostOne(beanFactory, ClientOptionsCustomizer.class);
            validateBorrowedRuntimeObservability(beanFactory, environment);
        };
    }

    /**
     * Creates the default shared runtime.
     *
     * @param properties exact Repost configuration
     * @param beanFactory application bean factory
     * @return configured runtime
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(RepostRuntime.class)
    @ConditionalOnProperty(
            prefix = "repost.client",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    RepostRuntime repostRuntime(
            RepostClientProperties properties,
            ListableBeanFactory beanFactory) {
        ClientOptions.Builder builder = ClientOptions.builder()
                .connectTimeout(properties.getConnectTimeout())
                .attemptTimeout(properties.getAttemptTimeout())
                .operationTimeout(properties.getOperationTimeout())
                .maxAttempts(properties.getMaxAttempts())
                .maxInFlightOperations(properties.getMaxInFlight())
                .maxBufferedBytes(properties.getMaxBufferedBytes())
                .retryBaseDelay(properties.getRetryBaseDelay())
                .retryMaxDelay(properties.getRetryMaxDelay());

        if (properties.getBaseUri() != null) {
            builder.baseUri(properties.getBaseUri());
        }
        if (properties.getUserAgentSuffix() != null) {
            builder.userAgentSuffix(properties.getUserAgentSuffix());
        }

        NamedBean<ApiKeyProvider> provider = getOptionalSingleton(beanFactory, ApiKeyProvider.class);
        if (provider != null) {
            builder.apiKeyProvider(provider.value());
        } else if (properties.getApiKey() != null) {
            builder.apiKey(properties.getApiKey());
        }

        NamedBean<ClientOptionsCustomizer> customizer =
                getOptionalSingleton(beanFactory, ClientOptionsCustomizer.class);
        if (customizer != null) {
            customizer.value().customize(builder);
        }
        configureMicrometer(
                builder,
                beanFactory,
                properties.getObservability().getMicrometerEnabled(),
                customizer);
        configureOpenTelemetry(
                builder,
                beanFactory,
                properties.getObservability().getOpentelemetryEnabled(),
                customizer);
        try {
            return RepostRuntime.create(builder.build());
        } catch (RepostConfigurationException invalid) {
            if (customizer != null && hasCredentialConflict(invalid)) {
                String credentialSource = provider == null
                        ? "property repost.client.api-key"
                        : "ApiKeyProvider bean " + provider.name();
                throw new IllegalStateException(
                        "Credential conflict between " + credentialSource
                                + " and ClientOptionsCustomizer bean " + customizer.name(),
                        invalid);
            }
            throw invalid;
        }
    }

    private static void validateBorrowedRuntimeObservability(
            org.springframework.beans.factory.config.ConfigurableListableBeanFactory beanFactory,
            ConfigurableEnvironment environment) {
        boolean userRuntime = Arrays.stream(
                beanFactory.getBeanNamesForType(RepostRuntime.class, false, false))
                .map(beanFactory::getBeanDefinition)
                .anyMatch(definition -> !(RepostClientAutoConfiguration.class.getName()
                                .equals(definition.getFactoryBeanName())
                        && "repostRuntime".equals(definition.getFactoryMethodName())));
        if (!userRuntime) {
            return;
        }
        for (String property : new String[]{
            "repost.client.observability.micrometer-enabled",
            "repost.client.observability.opentelemetry-enabled"
        }) {
            if (environment.containsProperty(property)) {
                throw new IllegalStateException(
                        property + " cannot configure a user-provided RepostRuntime bean");
            }
        }
    }

    private static void configureMicrometer(
            ClientOptions.Builder builder,
            ListableBeanFactory beanFactory,
            Boolean enabled,
            NamedBean<ClientOptionsCustomizer> customizer) {
        String property = "repost.client.observability.micrometer-enabled";
        if (Boolean.FALSE.equals(enabled)) {
            return;
        }
        if (builder.hasObserver()) {
            if (Boolean.TRUE.equals(enabled)) {
                throw slotConflict(property, "observer", customizer);
            }
            return;
        }
        if (!present("sh.repost.client.micrometer.MicrometerRepostObserver")
                || !present("io.micrometer.core.instrument.MeterRegistry")) {
            requireProviderWhenEnabled(property, enabled);
            return;
        }
        NamedBean<MeterRegistry> registry = getOptionalSingleton(beanFactory, MeterRegistry.class);
        if (registry == null) {
            requireProviderWhenEnabled(property, enabled);
            return;
        }
        builder.observer(MicrometerRepostObserver.create(registry.value()));
    }

    private static void configureOpenTelemetry(
            ClientOptions.Builder builder,
            ListableBeanFactory beanFactory,
            Boolean enabled,
            NamedBean<ClientOptionsCustomizer> customizer) {
        String property = "repost.client.observability.opentelemetry-enabled";
        if (Boolean.FALSE.equals(enabled)) {
            return;
        }
        if (builder.hasTelemetry()) {
            if (Boolean.TRUE.equals(enabled)) {
                throw slotConflict(property, "telemetry", customizer);
            }
            return;
        }
        if (!present("sh.repost.client.opentelemetry.OpenTelemetryRepostTelemetry")
                || !present("io.opentelemetry.api.OpenTelemetry")) {
            requireProviderWhenEnabled(property, enabled);
            return;
        }
        NamedBean<OpenTelemetry> provider = getOptionalSingleton(beanFactory, OpenTelemetry.class);
        if (provider == null) {
            requireProviderWhenEnabled(property, enabled);
            return;
        }
        builder.telemetry(OpenTelemetryRepostTelemetry.create(provider.value()));
    }

    private static boolean present(String className) {
        return ClassUtils.isPresent(className, RepostClientAutoConfiguration.class.getClassLoader());
    }

    private static void requireProviderWhenEnabled(String property, Boolean enabled) {
        if (Boolean.TRUE.equals(enabled)) {
            throw new IllegalStateException(
                    property + " requires its Repost bridge and exactly one framework provider");
        }
    }

    private static IllegalStateException slotConflict(
            String property,
            String slot,
            NamedBean<ClientOptionsCustomizer> customizer) {
        return new IllegalStateException(
                property + " conflicts with the explicit " + slot + " slot from "
                        + (customizer == null
                                ? "application configuration"
                                : "ClientOptionsCustomizer bean " + customizer.name()));
    }

    private static boolean hasCredentialConflict(RepostConfigurationException invalid) {
        return invalid.getConfigurationIssues().stream().anyMatch(issue ->
                issue.getCode() == ConfigurationIssueCode.CONFLICT
                        && issue.getOptionKeys().contains(ClientOptionKey.API_KEY)
                        && issue.getOptionKeys().contains(ClientOptionKey.API_KEY_PROVIDER));
    }

    private static <T> NamedBean<T> getOptionalSingleton(
            ListableBeanFactory beanFactory, Class<T> beanType) {
        String[] names = beanFactory.getBeanNamesForType(beanType);
        if (names.length == 0) {
            return null;
        }
        if (names.length > 1) {
            throw duplicateBeans(beanType, names);
        }
        return new NamedBean<>(names[0], beanFactory.getBean(names[0], beanType));
    }

    private static void requireAtMostOne(
            org.springframework.beans.factory.config.ConfigurableListableBeanFactory beanFactory,
            Class<?> beanType) {
        String[] names = beanFactory.getBeanNamesForType(beanType, false, false);
        if (names.length > 1) {
            throw duplicateBeans(beanType, names);
        }
    }

    private static IllegalStateException duplicateBeans(Class<?> beanType, String[] names) {
        String[] sorted = names.clone();
        Arrays.sort(sorted);
        return new IllegalStateException(
                "Expected at most one " + beanType.getSimpleName()
                        + " bean; found " + String.join(", ", sorted));
    }

    private record NamedBean<T>(String name, T value) { }

    /** Optional Actuator health integration. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.health.contributor.HealthIndicator")
    static class HealthConfiguration {
        @Bean
        @ConditionalOnMissingBean(name = "repostHealthIndicator")
        @ConditionalOnProperty(
                prefix = "repost.client",
                name = "enabled",
                havingValue = "true",
                matchIfMissing = true)
        RepostRuntimeHealthIndicator repostHealthIndicator(RepostRuntime runtime) {
            return new RepostRuntimeHealthIndicator(runtime);
        }
    }

    /** Optional Micrometer diagnostics integration. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.core.instrument.binder.MeterBinder")
    @ConditionalOnProperty(
            prefix = "repost.client.observability",
            name = "micrometer-enabled",
            havingValue = "true",
            matchIfMissing = true)
    static class MetricsConfiguration {
        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean(RepostRuntimeMetrics.class)
        @ConditionalOnProperty(
                prefix = "repost.client",
                name = "enabled",
                havingValue = "true",
                matchIfMissing = true)
        RepostRuntimeMetrics repostRuntimeMetrics(RepostRuntime runtime) {
            return new RepostRuntimeMetrics(runtime);
        }
    }
}
