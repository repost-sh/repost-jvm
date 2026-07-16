package sh.repost.client.cdi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.Messages;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanBuilder;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.inject.Singleton;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigValue;
import org.junit.jupiter.api.Test;
import sh.repost.client.ApiKeyProvider;
import sh.repost.client.ClientOptions;
import sh.repost.client.ClientOptionsCustomizer;
import sh.repost.client.RepostRuntime;
import sh.repost.client.Transport;

class RepostCdiExtensionTest {
    @Test
    void registersOneApplicationLifetimeOwnedRuntimeWithPublicLifecycleFunctions() throws Exception {
        RecordingComponents components = new RecordingComponents();
        RecordingMessages messages = new RecordingMessages();
        RepostCdiExtension extension = new RepostCdiExtension(() -> config(Map.of()));

        extension.synthesize(components.proxy(), messages.proxy());

        assertTrue(messages.errors.isEmpty());
        assertEquals(RepostRuntime.class, components.beanClass);
        assertEquals(RepostRuntime.class, components.calls.get("type"));
        assertEquals(Singleton.class, components.calls.get("scope"));
        assertEquals(RepostRuntimeCreator.class, components.calls.get("createWith"));
        assertEquals(RepostRuntimeDisposer.class, components.calls.get("disposeWith"));
        assertPublicZeroArgumentConstructor(RepostRuntimeCreator.class);
        assertPublicZeroArgumentConstructor(RepostRuntimeDisposer.class);
    }

    @Test
    void suppressesTheDefaultForOneUserRuntimeAndRejectsAmbiguity() {
        RecordingComponents borrowedComponents = new RecordingComponents();
        RecordingMessages borrowedMessages = new RecordingMessages();
        RepostCdiExtension borrowed = new RepostCdiExtension(() -> config(Map.of()));
        borrowed.observeRuntime(bean("applicationRuntime"));

        borrowed.synthesize(borrowedComponents.proxy(), borrowedMessages.proxy());

        assertEquals(null, borrowedComponents.beanClass);
        assertTrue(borrowedMessages.errors.isEmpty());

        RecordingMessages ambiguousMessages = new RecordingMessages();
        RepostCdiExtension ambiguous = new RepostCdiExtension(() -> config(Map.of()));
        ambiguous.observeRuntime(bean("secondRuntime"));
        ambiguous.observeRuntime(bean("firstRuntime"));
        ambiguous.synthesize(new RecordingComponents().proxy(), ambiguousMessages.proxy());

        assertEquals(1, ambiguousMessages.errors.size());
        assertTrue(ambiguousMessages.errors.get(0).contains("firstRuntime, secondRuntime"));
    }

    @Test
    void rejectsAdapterObservabilityConfigurationForABorrowedRuntime() {
        RecordingMessages messages = new RecordingMessages();
        RepostCdiExtension extension = new RepostCdiExtension(() -> config(Map.of(
                "repost.client.observability.micrometer-enabled", "false")));
        extension.observeRuntime(bean("applicationRuntime"));

        extension.synthesize(new RecordingComponents().proxy(), messages.proxy());

        assertEquals(List.of("A user RepostRuntime cannot be combined with "
                + "repost.client.observability adapter configuration"), messages.errors);
    }

    @Test
    void freezesThePortableExtensionPhasesAndServiceRegistration() throws Exception {
        Method registration = RepostCdiExtension.class.getMethod("observeRuntime", BeanInfo.class);
        Method synthesis = RepostCdiExtension.class.getMethod(
                "synthesize", SyntheticComponents.class, Messages.class);

        assertEquals(List.of(RepostRuntime.class), List.of(
                registration.getAnnotation(Registration.class).types()));
        assertTrue(synthesis.isAnnotationPresent(Synthesis.class));
        String service = new String(
                RepostCdiExtensionTest.class.getResourceAsStream(
                                "/META-INF/services/"
                                        + "jakarta.enterprise.inject.build.compatible.spi."
                                        + "BuildCompatibleExtension")
                        .readAllBytes(),
                StandardCharsets.UTF_8);
        assertEquals("sh.repost.client.cdi.RepostCdiExtension\n", service);
    }

    @Test
    void creatorAppliesProviderPrecedenceAndCustomizerExactlyOnce() {
        AtomicInteger customizations = new AtomicInteger();
        ApiKeyProvider provider = () -> "provider-key";
        ClientOptionsCustomizer customizer = builder -> customizations.incrementAndGet();
        Map<Class<?>, Object> beans = Map.of(
                ApiKeyProvider.class, provider,
                ClientOptionsCustomizer.class, customizer);
        RepostRuntimeCreator creator = new RepostRuntimeCreator(() -> config(Map.of(
                "repost.client.api-key", "ignored-fixed-key")));

        RepostRuntime runtime = creator.create(instance(beans), parameters());

        assertEquals(1, customizations.get());
        assertFalse(runtime.toString().contains("provider-key"));
        assertFalse(runtime.toString().contains("ignored-fixed-key"));
        runtime.close();
    }

    @Test
    void disposerClosesOnlyItsSyntheticRuntime() {
        Transport transport = request -> CompletableFuture.failedFuture(
                new AssertionError("network must not run"));
        RepostRuntime runtime = RepostRuntime.create(ClientOptions.builder()
                .apiKey("test-key")
                .transport(transport)
                .build());

        new RepostRuntimeDisposer().dispose(runtime, instance(Map.of()), parameters());

        assertTrue(runtime.closeCompletion().toCompletableFuture().isDone());
    }

    private static void assertPublicZeroArgumentConstructor(Class<?> type) throws Exception {
        assertTrue(Modifier.isPublic(type.getModifiers()));
        assertTrue(Modifier.isPublic(type.getConstructor().getModifiers()));
        assertEquals(1, type.getConstructors().length);
    }

    private static BeanInfo bean(String name) {
        return (BeanInfo) Proxy.newProxyInstance(
                RepostCdiExtensionTest.class.getClassLoader(),
                new Class<?>[] {BeanInfo.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "name" -> name;
                    case "isSynthetic", "isAlternative", "isClassBean",
                            "isProducerMethod", "isProducerField" -> false;
                    case "types", "qualifiers", "stereotypes", "injectionPoints" -> List.of();
                    case "priority", "declaringClass", "producerMethod", "producerField",
                            "disposer", "scope" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @SuppressWarnings("unchecked")
    private static Instance<Object> instance(Map<Class<?>, Object> beans) {
        return (Instance<Object>) Proxy.newProxyInstance(
                RepostCdiExtensionTest.class.getClassLoader(),
                new Class<?>[] {Instance.class},
                (proxy, method, arguments) -> {
                    if ("select".equals(method.getName()) && arguments[0] instanceof Class<?>) {
                        Object bean = beans.get(arguments[0]);
                        return selectedInstance(bean);
                    }
                    if ("isUnsatisfied".equals(method.getName())) {
                        return beans.isEmpty();
                    }
                    if ("isAmbiguous".equals(method.getName())) {
                        return false;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static Object selectedInstance(Object bean) {
        return Proxy.newProxyInstance(
                RepostCdiExtensionTest.class.getClassLoader(),
                new Class<?>[] {Instance.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "isUnsatisfied" -> bean == null;
                    case "isAmbiguous" -> false;
                    case "get" -> bean;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static jakarta.enterprise.inject.build.compatible.spi.Parameters parameters() {
        return (jakarta.enterprise.inject.build.compatible.spi.Parameters) Proxy.newProxyInstance(
                RepostCdiExtensionTest.class.getClassLoader(),
                new Class<?>[] {jakarta.enterprise.inject.build.compatible.spi.Parameters.class},
                (proxy, method, arguments) -> {
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static Config config(Map<String, String> values) {
        return (Config) Proxy.newProxyInstance(
                RepostCdiExtensionTest.class.getClassLoader(),
                new Class<?>[] {Config.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getPropertyNames" -> values.keySet();
                    case "getConfigValue" -> configValue(
                            (String) arguments[0], values.get((String) arguments[0]));
                    case "getOptionalValue" -> Optional.ofNullable(values.get((String) arguments[0]));
                    case "getValue" -> values.get((String) arguments[0]);
                    case "getConfigSources" -> List.of();
                    case "getConverter" -> Optional.empty();
                    case "unwrap" -> throw new IllegalArgumentException("unsupported unwrap");
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static ConfigValue configValue(String name, String value) {
        return (ConfigValue) Proxy.newProxyInstance(
                RepostCdiExtensionTest.class.getClassLoader(),
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

    private static final class RecordingComponents {
        private Class<?> beanClass;
        private final Map<String, Object> calls = new LinkedHashMap<>();

        SyntheticComponents proxy() {
            return (SyntheticComponents) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {SyntheticComponents.class},
                    (proxy, method, arguments) -> {
                        if ("addBean".equals(method.getName())) {
                            beanClass = (Class<?>) arguments[0];
                            return builder();
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
        }

        private SyntheticBeanBuilder<?> builder() {
            return (SyntheticBeanBuilder<?>) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {SyntheticBeanBuilder.class},
                    (proxy, method, arguments) -> {
                        calls.put(method.getName(), arguments[0]);
                        return proxy;
                    });
        }
    }

    private static final class RecordingMessages {
        private final List<String> errors = new ArrayList<>();

        Messages proxy() {
            return (Messages) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {Messages.class},
                    (proxy, method, arguments) -> {
                        if ("error".equals(method.getName())) {
                            errors.add(String.valueOf(arguments[0]));
                            return null;
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}
