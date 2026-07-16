package sh.repost.client.cdi;

import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Messages;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import sh.repost.client.ApiKeyProvider;
import sh.repost.client.ClientOptionsCustomizer;
import sh.repost.client.RepostRuntime;

/** Portable CDI build-compatible extension for the shared Repost runtime. */
public final class RepostCdiExtension implements BuildCompatibleExtension {
    private final Supplier<Config> configSupplier;
    private final List<String> runtimes = new ArrayList<>();
    private final List<String> providers = new ArrayList<>();
    private final List<String> customizers = new ArrayList<>();

    /** Creates the service-loaded extension. */
    public RepostCdiExtension() {
        this(ConfigProvider::getConfig);
    }

    RepostCdiExtension(Supplier<Config> configSupplier) {
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
    }

    /**
     * Records application runtime producers before synthesis.
     *
     * @param bean discovered runtime bean
     */
    @Registration(types = RepostRuntime.class)
    public void observeRuntime(BeanInfo bean) {
        if (!bean.isSynthetic()) {
            runtimes.add(label(bean));
        }
    }

    /**
     * Records application credential providers before synthesis.
     *
     * @param bean discovered provider bean
     */
    @Registration(types = ApiKeyProvider.class)
    public void observeApiKeyProvider(BeanInfo bean) {
        if (!bean.isSynthetic()) {
            providers.add(label(bean));
        }
    }

    /**
     * Records application option customizers before synthesis.
     *
     * @param bean discovered customizer bean
     */
    @Registration(types = ClientOptionsCustomizer.class)
    public void observeClientOptionsCustomizer(BeanInfo bean) {
        if (!bean.isSynthetic()) {
            customizers.add(label(bean));
        }
    }

    /**
     * Registers one owned runtime only when enabled and no application runtime exists.
     *
     * @param components synthetic component registry
     * @param messages deployment diagnostics
     */
    @Synthesis
    public void synthesize(SyntheticComponents components, Messages messages) {
        RepostCdiConfiguration configuration = RepostCdiConfiguration.from(configSupplier.get());
        if (!requireAtMostOne("RepostRuntime", runtimes, messages)
                | !requireAtMostOne("ApiKeyProvider", providers, messages)
                | !requireAtMostOne("ClientOptionsCustomizer", customizers, messages)) {
            return;
        }
        if (runtimes.size() == 1
                && (configuration.micrometerEnabled() != null
                        || configuration.opentelemetryEnabled() != null)) {
            messages.error("A user RepostRuntime cannot be combined with "
                    + "repost.client.observability adapter configuration");
            return;
        }
        if (!configuration.enabled() || runtimes.size() == 1) {
            return;
        }
        components.addBean(RepostRuntime.class)
                .type(RepostRuntime.class)
                .scope(Singleton.class)
                .createWith(RepostRuntimeCreator.class)
                .disposeWith(RepostRuntimeDisposer.class);
    }

    private static boolean requireAtMostOne(
            String type, List<String> beans, Messages messages) {
        if (beans.size() <= 1) {
            return true;
        }
        List<String> sorted = new ArrayList<>(beans);
        Collections.sort(sorted);
        messages.error("Expected at most one " + type + " bean; found "
                + String.join(", ", sorted));
        return false;
    }

    private static String label(BeanInfo bean) {
        if (bean.name() != null) {
            return bean.name();
        }
        return bean.declaringClass() == null
                ? "synthetic-or-unknown-location"
                : bean.declaringClass().name();
    }
}
