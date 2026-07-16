package sh.repost.client.cdi;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import java.util.Objects;
import java.util.function.Supplier;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import sh.repost.client.ApiKeyProvider;
import sh.repost.client.ClientOptions;
import sh.repost.client.ClientOptionsCustomizer;
import sh.repost.client.RepostRuntime;

/** Creates the adapter-owned shared runtime without reflection or classpath scanning. */
public final class RepostRuntimeCreator implements SyntheticBeanCreator<RepostRuntime> {
    private final Supplier<Config> configSupplier;

    /** Creates the CDI-instantiated runtime creator. */
    public RepostRuntimeCreator() {
        this(ConfigProvider::getConfig);
    }

    RepostRuntimeCreator(Supplier<Config> configSupplier) {
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
    }

    @Override
    public RepostRuntime create(Instance<Object> lookup, Parameters params) {
        Objects.requireNonNull(lookup, "lookup");
        Objects.requireNonNull(params, "params");
        RepostCdiConfiguration configuration = RepostCdiConfiguration.from(configSupplier.get());
        if (!configuration.enabled()) {
            throw new IllegalStateException(
                    "repost.client.enabled is false; the default RepostRuntime is disabled");
        }
        ClientOptions.Builder builder = ClientOptions.builder()
                .connectTimeout(configuration.connectTimeout())
                .attemptTimeout(configuration.attemptTimeout())
                .operationTimeout(configuration.operationTimeout())
                .maxAttempts(configuration.maxAttempts())
                .maxInFlightOperations(configuration.maxInFlight())
                .maxBufferedBytes(configuration.maxBufferedBytes())
                .retryBaseDelay(configuration.retryBaseDelay())
                .retryMaxDelay(configuration.retryMaxDelay());
        if (configuration.baseUri() != null) {
            builder.baseUri(configuration.baseUri());
        }
        if (configuration.userAgentSuffix() != null) {
            builder.userAgentSuffix(configuration.userAgentSuffix());
        }

        ApiKeyProvider provider = optional(lookup, ApiKeyProvider.class);
        if (provider != null) {
            builder.apiKeyProvider(provider);
        } else if (configuration.apiKey() != null) {
            builder.apiKey(configuration.apiKey());
        }
        ClientOptionsCustomizer customizer = optional(lookup, ClientOptionsCustomizer.class);
        if (customizer != null) {
            customizer.customize(builder);
        }
        configureMicrometer(lookup, builder, configuration.micrometerEnabled());
        configureOpenTelemetry(lookup, builder, configuration.opentelemetryEnabled());
        return RepostRuntime.create(builder.build());
    }

    private static void configureMicrometer(
            Instance<Object> lookup, ClientOptions.Builder builder, Boolean enabled) {
        try {
            RepostCdiMicrometer.configure(lookup, builder, enabled);
        } catch (NoClassDefFoundError absentBridge) {
            if (!missing(absentBridge, "micrometer", "RepostCdiMicrometer")) {
                throw absentBridge;
            }
            requireBridgeWhenExplicit(
                    "repost.client.observability.micrometer-enabled", enabled, absentBridge);
        }
    }

    private static void configureOpenTelemetry(
            Instance<Object> lookup, ClientOptions.Builder builder, Boolean enabled) {
        try {
            RepostCdiOpenTelemetry.configure(lookup, builder, enabled);
        } catch (NoClassDefFoundError absentBridge) {
            if (!missing(absentBridge, "opentelemetry", "RepostCdiOpenTelemetry")) {
                throw absentBridge;
            }
            requireBridgeWhenExplicit(
                    "repost.client.observability.opentelemetry-enabled", enabled, absentBridge);
        }
    }

    private static void requireBridgeWhenExplicit(
            String key, Boolean enabled, NoClassDefFoundError absentBridge) {
        if (Boolean.TRUE.equals(enabled)) {
            throw new IllegalStateException(
                    key + " requires its Repost bridge and exactly one CDI provider",
                    absentBridge);
        }
    }

    private static boolean missing(
            NoClassDefFoundError failure, String dependencyPackage, String adapterClass) {
        String className = failure.getMessage();
        return className != null
                && (className.contains(dependencyPackage) || className.contains(adapterClass));
    }

    private static <T> T optional(Instance<Object> lookup, Class<T> type) {
        Instance<T> selected = lookup.select(type);
        if (selected.isAmbiguous()) {
            java.util.List<String> labels = new java.util.ArrayList<>();
            for (Instance.Handle<T> handle : selected.handles()) {
                jakarta.enterprise.inject.spi.Bean<T> bean = handle.getBean();
                labels.add(bean.getName() == null
                        ? bean.getBeanClass().getName()
                        : bean.getName());
            }
            java.util.Collections.sort(labels);
            throw new IllegalStateException("Expected at most one " + type.getSimpleName()
                    + " bean; found " + String.join(", ", labels));
        }
        return selected.isUnsatisfied() ? null : selected.get();
    }

    static <T> T optionalBean(Instance<Object> lookup, Class<T> type) {
        return optional(lookup, type);
    }
}
