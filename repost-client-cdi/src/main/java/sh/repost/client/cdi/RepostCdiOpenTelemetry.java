package sh.repost.client.cdi;

import io.opentelemetry.api.OpenTelemetry;
import jakarta.enterprise.inject.Instance;
import sh.repost.client.ClientOptions;
import sh.repost.client.opentelemetry.OpenTelemetryRepostTelemetry;

final class RepostCdiOpenTelemetry {
    private static final String KEY =
            "repost.client.observability.opentelemetry-enabled";

    private RepostCdiOpenTelemetry() { }

    static void configure(Instance<Object> lookup, ClientOptions.Builder builder, Boolean enabled) {
        OpenTelemetry provider = RepostRuntimeCreator.optionalBean(lookup, OpenTelemetry.class);
        if (Boolean.FALSE.equals(enabled)) {
            return;
        }
        if (provider == null) {
            if (Boolean.TRUE.equals(enabled)) {
                throw new IllegalStateException(
                        KEY + " requires exactly one OpenTelemetry bean");
            }
            return;
        }
        if (builder.hasTelemetry()) {
            if (Boolean.TRUE.equals(enabled)) {
                throw new IllegalStateException(
                        KEY + " conflicts with the configured telemetry slot");
            }
            return;
        }
        builder.telemetry(OpenTelemetryRepostTelemetry.create(provider));
    }
}
