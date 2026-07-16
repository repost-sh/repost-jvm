package sh.repost.client.cdi;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.inject.Instance;
import sh.repost.client.ClientOptions;
import sh.repost.client.micrometer.MicrometerRepostObserver;

final class RepostCdiMicrometer {
    private static final String KEY =
            "repost.client.observability.micrometer-enabled";

    private RepostCdiMicrometer() { }

    static void configure(Instance<Object> lookup, ClientOptions.Builder builder, Boolean enabled) {
        MeterRegistry registry = RepostRuntimeCreator.optionalBean(lookup, MeterRegistry.class);
        if (Boolean.FALSE.equals(enabled)) {
            return;
        }
        if (registry == null) {
            if (Boolean.TRUE.equals(enabled)) {
                throw new IllegalStateException(
                        KEY + " requires exactly one MeterRegistry bean");
            }
            return;
        }
        if (builder.hasObserver()) {
            if (Boolean.TRUE.equals(enabled)) {
                throw new IllegalStateException(
                        KEY + " conflicts with the configured observer slot");
            }
            return;
        }
        builder.observer(MicrometerRepostObserver.create(registry));
    }
}
