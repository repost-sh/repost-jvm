package sh.repost.client.spring;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

final class RepostConfigurationKeys {
    private static final String PREFIX = "repost.client.";
    private static final Set<String> CANONICAL = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
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
            "repost.client.observability.opentelemetry-enabled")));
    private static final Set<String> ENVIRONMENT = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
            "REPOST_CLIENT_ENABLED",
            "REPOST_CLIENT_API_KEY",
            "REPOST_CLIENT_BASE_URI",
            "REPOST_CLIENT_CONNECT_TIMEOUT",
            "REPOST_CLIENT_ATTEMPT_TIMEOUT",
            "REPOST_CLIENT_OPERATION_TIMEOUT",
            "REPOST_CLIENT_MAX_ATTEMPTS",
            "REPOST_CLIENT_MAX_IN_FLIGHT",
            "REPOST_CLIENT_MAX_BUFFERED_BYTES",
            "REPOST_CLIENT_RETRY_BASE_DELAY",
            "REPOST_CLIENT_RETRY_MAX_DELAY",
            "REPOST_CLIENT_USER_AGENT_SUFFIX",
            "REPOST_CLIENT_OBSERVABILITY_MICROMETER_ENABLED",
            "REPOST_CLIENT_OBSERVABILITY_OPENTELEMETRY_ENABLED")));

    private RepostConfigurationKeys() { }

    static void validate(ConfigurableEnvironment environment) {
        Set<String> checked = new LinkedHashSet<>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            for (String name : enumerable.getPropertyNames()) {
                if (!checked.add(name)) {
                    continue;
                }
                if (name.toLowerCase(Locale.ROOT).startsWith(PREFIX)
                        && !CANONICAL.contains(name)) {
                    throw unknown(name, source, CANONICAL);
                }
                if (name.toUpperCase(Locale.ROOT).startsWith("REPOST_CLIENT_")
                        && !ENVIRONMENT.contains(name)) {
                    throw unknown(name, source, ENVIRONMENT);
                }
            }
        }
    }

    private static IllegalArgumentException unknown(
            String name, PropertySource<?> source, Set<String> canonical) {
        return new IllegalArgumentException(
                "Unknown Repost property " + name + " in config source "
                        + source.getName() + "; canonical keys are " + canonical);
    }
}
