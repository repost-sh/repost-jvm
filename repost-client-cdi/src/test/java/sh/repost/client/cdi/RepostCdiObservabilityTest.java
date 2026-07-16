package sh.repost.client.cdi;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.Bean;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import sh.repost.client.ClientOptions;

class RepostCdiObservabilityTest {
    @Test
    void appliesOmittedTrueFalseAndCustomSlotRulesForBothBridges() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OpenTelemetry openTelemetry = OpenTelemetry.noop();
        Instance<Object> providers = instance(Map.of(
                MeterRegistry.class, List.of(candidate("metrics", registry)),
                OpenTelemetry.class, List.of(candidate("tracing", openTelemetry))));

        ClientOptions.Builder automatic = ClientOptions.builder();
        RepostCdiMicrometer.configure(providers, automatic, null);
        RepostCdiOpenTelemetry.configure(providers, automatic, null);
        assertTrue(automatic.hasObserver());
        assertTrue(automatic.hasTelemetry());

        ClientOptions.Builder disabled = ClientOptions.builder();
        RepostCdiMicrometer.configure(providers, disabled, false);
        RepostCdiOpenTelemetry.configure(providers, disabled, false);
        assertFalse(disabled.hasObserver());
        assertFalse(disabled.hasTelemetry());

        ClientOptions.Builder custom = ClientOptions.builder()
                .observer(event -> { })
                .telemetry(new NoopTelemetry());
        RepostCdiMicrometer.configure(providers, custom, null);
        RepostCdiOpenTelemetry.configure(providers, custom, null);
        assertDoesNotThrow(custom::build);
        assertThrows(IllegalStateException.class,
                () -> RepostCdiMicrometer.configure(providers, custom, true));
        assertThrows(IllegalStateException.class,
                () -> RepostCdiOpenTelemetry.configure(providers, custom, true));
    }

    @Test
    void explicitEnablementRequiresProvidersAndAmbiguityNamesBeanLocations() {
        Instance<Object> empty = instance(Map.of());
        assertThrows(IllegalStateException.class,
                () -> RepostCdiMicrometer.configure(empty, ClientOptions.builder(), true));
        assertThrows(IllegalStateException.class,
                () -> RepostCdiOpenTelemetry.configure(empty, ClientOptions.builder(), true));

        SimpleMeterRegistry first = new SimpleMeterRegistry();
        SimpleMeterRegistry second = new SimpleMeterRegistry();
        Instance<Object> ambiguous = instance(Map.of(
                MeterRegistry.class,
                List.of(candidate("secondRegistry", second), candidate("firstRegistry", first))));
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> RepostCdiMicrometer.configure(
                        ambiguous, ClientOptions.builder(), false));
        assertTrue(failure.getMessage().contains("firstRegistry, secondRegistry"));
    }

    private static Candidate candidate(String name, Object value) {
        return new Candidate(name, value);
    }

    @SuppressWarnings("unchecked")
    private static Instance<Object> instance(Map<Class<?>, List<Candidate>> candidates) {
        return (Instance<Object>) Proxy.newProxyInstance(
                RepostCdiObservabilityTest.class.getClassLoader(),
                new Class<?>[] {Instance.class},
                (proxy, method, arguments) -> {
                    if ("select".equals(method.getName()) && arguments[0] instanceof Class<?>) {
                        return selected(candidates.getOrDefault(arguments[0], List.of()));
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static Object selected(List<Candidate> candidates) {
        return Proxy.newProxyInstance(
                RepostCdiObservabilityTest.class.getClassLoader(),
                new Class<?>[] {Instance.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "isUnsatisfied" -> candidates.isEmpty();
                    case "isAmbiguous" -> candidates.size() > 1;
                    case "get" -> candidates.get(0).value;
                    case "handles" -> candidates.stream().map(RepostCdiObservabilityTest::handle)
                            .toList();
                    case "iterator" -> Collections.emptyIterator();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static Instance.Handle<Object> handle(Candidate candidate) {
        @SuppressWarnings("unchecked")
        Instance.Handle<Object> handle = (Instance.Handle<Object>) Proxy.newProxyInstance(
                RepostCdiObservabilityTest.class.getClassLoader(),
                new Class<?>[] {Instance.Handle.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "get" -> candidate.value;
                    case "getBean" -> bean(candidate);
                    case "close", "destroy" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return handle;
    }

    @SuppressWarnings("unchecked")
    private static Bean<Object> bean(Candidate candidate) {
        return (Bean<Object>) Proxy.newProxyInstance(
                RepostCdiObservabilityTest.class.getClassLoader(),
                new Class<?>[] {Bean.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getName" -> candidate.name;
                    case "getBeanClass" -> candidate.value.getClass();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private record Candidate(String name, Object value) { }

    private static final class NoopTelemetry implements sh.repost.client.RepostTelemetry {
        @Override
        public sh.repost.client.CapturedTelemetryContext captureContext() {
            return new sh.repost.client.CapturedTelemetryContext() { };
        }

        @Override
        public sh.repost.client.TelemetryOperation startOperation(
                sh.repost.client.CapturedTelemetryContext parent,
                sh.repost.client.TelemetryOperationStart start) {
            throw new AssertionError("not exercised");
        }
    }
}
