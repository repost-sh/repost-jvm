package sh.repost.client.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import sh.repost.client.ClientOptionsCustomizer;
import sh.repost.client.RepostModel;
import sh.repost.client.RepostObserver;
import sh.repost.client.RepostRuntime;
import sh.repost.client.SendOptions;
import sh.repost.client.descriptor.EventDescriptor;
import sh.repost.client.descriptor.ModelDescriptor;
import sh.repost.client.descriptor.SchemaDescriptor;
import sh.repost.client.test.StubTransport;

final class RepostObservabilityAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RepostClientAutoConfiguration.class))
            .withPropertyValues("repost.client.api-key=test-key");

    @Test
    void omittedBridgesCoexistAndExposeOnlyBoundedDiagnostics() {
        String payloadSentinel = "sentinel-payload";
        StubTransport transport = new StubTransport().enqueueResponse(
                202,
                ("{\"id\":\"msg_spring\",\"type\":\"contract.sent\","
                        + "\"customerId\":\"cus_spring\","
                        + "\"timestamp\":\"1970-01-01T00:00:00.000Z\"}"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();

        runner.withBean(SimpleMeterRegistry.class, () -> registry)
                .withBean(OpenTelemetry.class, () -> openTelemetry)
                .withBean(ClientOptionsCustomizer.class, () -> builder ->
                        builder.transport(transport).maxAttempts(1))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    send(context.getBean(RepostRuntime.class), payloadSentinel);
                    context.getBean(RepostRuntimeMetrics.class).bindTo(registry);

                    awaitOperationMetric(registry);
                    assertNotNull(registry.find("repost.client.operations").counter());
                    assertNotNull(registry.find("repost.client.runtime.in.flight").gauge());
                    assertEquals(1, exporter.getFinishedSpanItems().stream()
                            .filter(span -> span.getName().equals("repost.send"))
                            .count());
                    assertEquals(1, exporter.getFinishedSpanItems().stream()
                            .filter(span -> span.getName().equals("repost.send.attempt"))
                            .count());
                    assertTrue(exporter.getFinishedSpanItems().stream()
                            .allMatch(span -> span.getKind() == SpanKind.INTERNAL));

                    String exposed = registry.getMeters() + " "
                            + exporter.getFinishedSpanItems() + " "
                            + context.getBean(RepostRuntimeHealthIndicator.class).health();
                    assertFalse(exposed.contains("test-key"), exposed);
                    assertFalse(exposed.contains(payloadSentinel), exposed);
                });
        tracerProvider.close();
        registry.close();
    }

    @Test
    void omittedBridgePreservesExplicitCustomSlot() {
        RepostObserver observer = event -> { };
        runner.withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
                .withBean("slotCustomizer", ClientOptionsCustomizer.class,
                        () -> builder -> builder.observer(observer))
                .run(context -> assertNull(context.getStartupFailure()));
    }

    @Test
    void explicitTrueConflictsWithCustomSlotAndNamesBothSources() {
        runner.withPropertyValues("repost.client.observability.micrometer-enabled=true")
                .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
                .withBean("slotCustomizer", ClientOptionsCustomizer.class,
                        () -> builder -> builder.observer(event -> { }))
                .run(context -> {
                    String messages = allMessages(context.getStartupFailure());
                    assertTrue(messages.contains(
                            "repost.client.observability.micrometer-enabled"), messages);
                    assertTrue(messages.contains(
                            "ClientOptionsCustomizer bean slotCustomizer"), messages);
                });
    }

    @Test
    void explicitFalseLeavesCustomSlotAlone() {
        runner.withPropertyValues("repost.client.observability.micrometer-enabled=false")
                .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
                .withBean(ClientOptionsCustomizer.class,
                        () -> builder -> builder.observer(event -> { }))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBeansOfType(RepostRuntimeMetrics.class).isEmpty());
                });
    }

    @Test
    void multipleRegistryBeansFailWithBothNamesEvenForOneInstance() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        runner.withBean("registryOne", SimpleMeterRegistry.class, () -> registry)
                .withBean("registryTwo", SimpleMeterRegistry.class, () -> registry)
                .run(context -> {
                    String messages = allMessages(context.getStartupFailure());
                    assertTrue(messages.contains("MeterRegistry"), messages);
                    assertTrue(messages.contains("registryOne"), messages);
                    assertTrue(messages.contains("registryTwo"), messages);
                });
        registry.close();
    }

    @Test
    void explicitObservabilityCannotMutateBorrowedRuntime() {
        RepostRuntime runtime = RepostRuntime.create(sh.repost.client.ClientOptions.builder()
                .apiKey("borrowed-key")
                .transport(request -> java.util.concurrent.CompletableFuture.failedFuture(
                        new AssertionError("transport must not run")))
                .build());
        runner.withPropertyValues("repost.client.observability.opentelemetry-enabled=false")
                .withBean("borrowedRuntime", RepostRuntime.class, () -> runtime,
                        bean -> bean.setDestroyMethodName(""))
                .run(context -> {
                    String messages = allMessages(context.getStartupFailure());
                    assertTrue(messages.contains(
                            "repost.client.observability.opentelemetry-enabled"), messages);
                    assertTrue(messages.contains("user-provided RepostRuntime"), messages);
                });
        assertFalse(runtime.isClosed());
        runtime.close();
    }

    private static void send(RepostRuntime runtime, String payloadSentinel) {
        SchemaDescriptor schema = SchemaDescriptor.builder(2)
                .addModel(ModelDescriptor.of("Payload", Collections.emptyList()))
                .addEvent("events", "created", EventDescriptor.of("contract.sent", "Payload"))
                .build();
        RepostModel model = new RepostModel() {
            @Override public boolean __repostIsPresent(int fieldIndex) { return false; }
            @Override public Object __repostValue(int fieldIndex) { return null; }
            @Override public String toString() { return payloadSentinel; }
        };
        try {
            runtime.sendAsync(
                            schema,
                            schema.getWebhooks().get("events").get("created"),
                            "cus_spring",
                            model,
                            SendOptions.builder().idempotencyKey("spring-key").build())
                    .toCompletableFuture()
                    .get(2, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException expectedProtocolFailure) {
            assertTrue(expectedProtocolFailure.getCause()
                    instanceof sh.repost.client.error.RepostPublishException);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static void awaitOperationMetric(SimpleMeterRegistry registry) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (registry.find("repost.client.operations").counter() == null
                && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
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
