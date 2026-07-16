package sh.repost.client.opentelemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import sh.repost.client.RepostTelemetry;
import sh.repost.client.RepostErrorCode;
import sh.repost.client.RequestCommitState;
import sh.repost.client.TransportFailure;
import sh.repost.client.TransportHeaderField;
import sh.repost.client.TransportResponse;
import sh.repost.client.descriptor.EventDescriptor;
import sh.repost.client.descriptor.ModelDescriptor;
import sh.repost.client.descriptor.SchemaDescriptor;
import sh.repost.client.test.RuntimeTestHarness;
import sh.repost.client.test.StubTransport;

final class OpenTelemetryRepostTelemetryContractTest {
    @Test
    void createsOneInternalOperationAndOneInternalAttemptWithoutSensitiveAttributes() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(provider)
                .build();
        RepostTelemetry bridge = OpenTelemetryRepostTelemetry.create(openTelemetry);
        StubTransport transport = new StubTransport().enqueueResponse(
                202,
                "{\"id\":\"msg_trace\",\"type\":\"sentinel-event\","
                        + "\"customerId\":\"sentinel-customer\","
                        + "\"timestamp\":\"1970-01-01T00:00:00.000Z\"}");
        SchemaDescriptor schema = SchemaDescriptor.builder(2)
                .addModel(ModelDescriptor.of("SentinelPayload", Collections.emptyList()))
                .addEvent("events", "created", EventDescriptor.of(
                        "sentinel-event", "SentinelPayload"))
                .build();
        try {
            try (RuntimeTestHarness harness = RuntimeTestHarness.builder(
                            schema, schema.getWebhooks().get("events").get("created"))
                    .customerId("sentinel-customer")
                    .transport(transport)
                    .telemetry(bridge)
                    .build()) {
                harness.sendEmpty().toCompletableFuture().join();
            }

            List<SpanData> spans = exporter.getFinishedSpanItems();
            assertEquals(2, spans.size());
            SpanData operation = spans.stream()
                    .filter(span -> span.getName().equals("repost.send"))
                    .findFirst().orElseThrow(AssertionError::new);
            SpanData attempt = spans.stream()
                    .filter(span -> span.getName().equals("repost.send.attempt"))
                    .findFirst().orElseThrow(AssertionError::new);
            assertEquals(SpanKind.INTERNAL, operation.getKind());
            assertEquals(SpanKind.INTERNAL, attempt.getKind());
            assertEquals("sh.repost.client", operation.getInstrumentationScopeInfo().getName());
            assertEquals("1.0.0", operation.getInstrumentationScopeInfo().getVersion());
            assertEquals("sh.repost.client", attempt.getInstrumentationScopeInfo().getName());
            assertEquals("1.0.0", attempt.getInstrumentationScopeInfo().getVersion());
            assertEquals(operation.getSpanId(), attempt.getParentSpanId());
            assertNotEquals(operation.getSpanId(), attempt.getSpanId());
            assertEquals("POST", attempt.getAttributes().get(
                    AttributeKey.stringKey("http.request.method")));
            assertEquals(1L, attempt.getAttributes().get(
                    AttributeKey.longKey("repost.retry.attempt")));
            assertEquals(202L, attempt.getAttributes().get(
                    AttributeKey.longKey("http.response.status_code")));
            assertFalse(attempt.getAttributes().asMap().toString().contains("sentinel"));
            assertFalse(operation.getAttributes().asMap().toString().contains("sentinel"));
            assertEquals(
                    Set.of(
                            "http.request.method",
                            "http.response.status_code",
                            "network.protocol.name",
                            "repost.retry.attempt"),
                    attempt.getAttributes().asMap().keySet().stream()
                            .map(AttributeKey::getKey)
                            .collect(Collectors.toSet()));
            assertTrue(operation.getAttributes().isEmpty());
            assertNotSame(
                    OpenTelemetryRepostTelemetry.create(openTelemetry),
                    OpenTelemetryRepostTelemetry.create(openTelemetry));
            assertThrows(NullPointerException.class, () -> OpenTelemetryRepostTelemetry.create(null));
        } finally {
            provider.close();
        }
    }

    @Test
    void preservesCallerParentAndAllowsOneCustomTransportClientChild() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(provider)
                .build();
        Tracer applicationTracer = openTelemetry.getTracer("application-test");
        Span caller = applicationTracer.spanBuilder("caller").startSpan();
        try {
            RepostTelemetry bridge = OpenTelemetryRepostTelemetry.create(openTelemetry);
            SchemaDescriptor schema = schema();
            try (Scope ignored = caller.makeCurrent();
                    RuntimeTestHarness harness = RuntimeTestHarness.builder(
                                    schema, schema.getWebhooks().get("events").get("created"))
                            .customerId("sentinel-customer")
                            .transport(request -> {
                                Span transportSpan = applicationTracer.spanBuilder("transport.send")
                                        .setSpanKind(SpanKind.CLIENT)
                                        .startSpan();
                                transportSpan.end();
                                return java.util.concurrent.CompletableFuture.completedFuture(
                                        TransportResponse.of(
                                                202,
                                                Collections.singletonList(TransportHeaderField.of(
                                                        "Content-Type", "application/json")),
                                                new java.io.ByteArrayInputStream(acceptedBody())));
                            })
                            .telemetry(bridge)
                            .build()) {
                harness.sendEmpty().toCompletableFuture().join();
            }
        } finally {
            caller.end();
        }
        try {
            List<SpanData> spans = exporter.getFinishedSpanItems();
            SpanData callerData = named(spans, "caller");
            SpanData operation = named(spans, "repost.send");
            SpanData attempt = named(spans, "repost.send.attempt");
            SpanData transport = named(spans, "transport.send");
            assertEquals(callerData.getSpanId(), operation.getParentSpanId());
            assertEquals(operation.getSpanId(), attempt.getParentSpanId());
            assertEquals(attempt.getSpanId(), transport.getParentSpanId());
            assertEquals(SpanKind.CLIENT, transport.getKind());
        } finally {
            provider.close();
        }
    }

    @Test
    void createsExactlyOneAttemptSpanPerRetryWithOnlyClosedAttributes() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(provider)
                .build();
        try {
            StubTransport transport = new StubTransport()
                    .enqueueFailure(TransportFailure.of(
                            RepostErrorCode.IO, RequestCommitState.NOT_COMMITTED))
                    .enqueueResponse(202, new String(acceptedBody(), java.nio.charset.StandardCharsets.UTF_8));
            SchemaDescriptor schema = schema();
            try (RuntimeTestHarness harness = RuntimeTestHarness.builder(
                            schema, schema.getWebhooks().get("events").get("created"))
                    .customerId("sentinel-customer")
                    .transport(transport)
                    .maxAttempts(2)
                    .telemetry(OpenTelemetryRepostTelemetry.create(openTelemetry))
                    .build()) {
                harness.sendEmpty().toCompletableFuture().join();
            }

            List<SpanData> attempts = exporter.getFinishedSpanItems().stream()
                    .filter(span -> span.getName().equals("repost.send.attempt"))
                    .sorted(Comparator.comparingLong(span -> span.getAttributes().get(
                            AttributeKey.longKey("repost.retry.attempt"))))
                    .collect(Collectors.toList());
            assertEquals(2, attempts.size());
            assertEquals(1L, attempts.get(0).getAttributes().get(
                    AttributeKey.longKey("repost.retry.attempt")));
            assertEquals(2L, attempts.get(1).getAttributes().get(
                    AttributeKey.longKey("repost.retry.attempt")));
            assertEquals("io", attempts.get(0).getAttributes().get(
                    AttributeKey.stringKey("error.type")));
            assertEquals(202L, attempts.get(1).getAttributes().get(
                    AttributeKey.longKey("http.response.status_code")));
            assertEquals(1L, exporter.getFinishedSpanItems().stream()
                    .filter(span -> span.getName().equals("repost.send"))
                    .count());
        } finally {
            provider.close();
        }
    }

    @Test
    void tenThousandUniqueOperationsKeepSpanAttributeCardinalityFixed() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(provider)
                .build();
        try {
            SchemaDescriptor schema = schema();
            byte[] responseBody = acceptedBody();
            try (RuntimeTestHarness harness = RuntimeTestHarness.builder(
                            schema, schema.getWebhooks().get("events").get("created"))
                    .customerId("sentinel-customer")
                    .transport(request -> java.util.concurrent.CompletableFuture.completedFuture(
                            TransportResponse.of(
                                    202,
                                    Collections.singletonList(TransportHeaderField.of(
                                            "Content-Type", "application/json")),
                                    new java.io.ByteArrayInputStream(responseBody))))
                    .telemetry(OpenTelemetryRepostTelemetry.create(openTelemetry))
                    .build()) {
                for (int index = 0; index < 10_000; index++) {
                    harness.sendEmpty().toCompletableFuture().join();
                }
            }

            List<SpanData> spans = exporter.getFinishedSpanItems();
            assertEquals(20_000, spans.size());
            assertEquals(10_000L, spans.stream()
                    .filter(span -> span.getName().equals("repost.send"))
                    .map(SpanData::getSpanId)
                    .distinct()
                    .count());
            Set<Set<String>> attributeShapes = spans.stream()
                    .map(span -> span.getAttributes().asMap().keySet().stream()
                            .map(AttributeKey::getKey)
                            .collect(Collectors.toSet()))
                    .collect(Collectors.toSet());
            assertEquals(
                    Set.of(
                            Collections.emptySet(),
                            Set.of(
                                    "http.request.method",
                                    "http.response.status_code",
                                    "network.protocol.name",
                                    "repost.retry.attempt")),
                    attributeShapes);
            assertFalse(spans.stream().anyMatch(span ->
                    span.getAttributes().asMap().toString().contains("op_")));
        } finally {
            provider.close();
        }
    }

    private static SpanData named(List<SpanData> spans, String name) {
        return spans.stream()
                .filter(span -> span.getName().equals(name))
                .findFirst()
                .orElseThrow(AssertionError::new);
    }

    private static SchemaDescriptor schema() {
        return SchemaDescriptor.builder(2)
                .addModel(ModelDescriptor.of("SentinelPayload", Collections.emptyList()))
                .addEvent("events", "created", EventDescriptor.of(
                        "sentinel-event", "SentinelPayload"))
                .build();
    }

    private static byte[] acceptedBody() {
        return ("{\"id\":\"msg_trace\",\"type\":\"sentinel-event\","
                + "\"customerId\":\"sentinel-customer\","
                + "\"timestamp\":\"1970-01-01T00:00:00.000Z\"}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
