package sh.repost.client.micrometer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import sh.repost.client.RepostErrorCode;
import sh.repost.client.ObserverEventKind;
import sh.repost.client.RepostObserver;
import sh.repost.client.RequestCommitState;
import sh.repost.client.TransportFailure;
import sh.repost.client.TransportHeaderField;
import sh.repost.client.TransportResponse;
import sh.repost.client.descriptor.EventDescriptor;
import sh.repost.client.descriptor.ModelDescriptor;
import sh.repost.client.descriptor.SchemaDescriptor;
import sh.repost.client.test.RuntimeTestHarness;
import sh.repost.client.test.StubTransport;

final class MicrometerRepostObserverContractTest {
    @Test
    void recordsTheFrozenMetricMetadataGoldenIncludingRetryDelay() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RepostObserver bridge = MicrometerRepostObserver.create(registry);
        CountDownLatch complete = new CountDownLatch(1);
        RepostObserver observed = event -> {
            bridge.onEvent(event);
            if (event.getKind() == ObserverEventKind.OPERATION_END) {
                complete.countDown();
            }
        };
        StubTransport transport = new StubTransport()
                .enqueueFailure(TransportFailure.of(
                        RepostErrorCode.IO, RequestCommitState.NOT_COMMITTED))
                .enqueueResponse(
                202,
                "{\"id\":\"msg_metric\",\"type\":\"contract.sent\","
                        + "\"customerId\":\"sentinel-customer\","
                        + "\"timestamp\":\"1970-01-01T00:00:00.000Z\"}");
        try (RuntimeTestHarness harness = RuntimeTestHarness.builder(schema(), event())
                .customerId("sentinel-customer")
                .transport(transport)
                .observer(observed)
                .maxAttempts(2)
                .build()) {
            harness.sendEmpty().toCompletableFuture().join();
            assertTrue(complete.await(2, TimeUnit.SECONDS));
        }

        assertEquals(1.0, registry.get("repost.client.operations").counter().count());
        assertEquals(2.0, registry.get("repost.client.attempts").counters().stream()
                .mapToDouble(counter -> counter.count()).sum());
        assertEquals(1L, registry.get("repost.client.operation.duration").timer().count());
        assertEquals(2L, registry.get("repost.client.attempt.duration").timers().stream()
                .mapToLong(timer -> timer.count()).sum());
        assertEquals(1L, registry.get("repost.client.retry.delay").timer().count());

        assertEquals(List.of(
                "repost.client.attempt.duration|TIMER|seconds|"
                        + "Repost client transport-attempt duration|"
                        + "delivery.state,error.code,http.status.class,outcome",
                "repost.client.attempts|COUNTER|attempts|"
                        + "Completed Repost client transport attempts|"
                        + "delivery.state,error.code,http.status.class,outcome",
                "repost.client.operation.duration|TIMER|seconds|"
                        + "Repost client operation duration|"
                        + "delivery.state,error.code,http.status.class,outcome",
                "repost.client.operations|COUNTER|operations|"
                        + "Completed Repost client operations|"
                        + "delivery.state,error.code,http.status.class,outcome",
                "repost.client.retry.delay|TIMER|seconds|"
                        + "Scheduled Repost client retry delay|"
                        + "delivery.state,error.code,http.status.class,outcome"),
                metricMetadata(registry));

        for (Meter meter : registry.getMeters()) {
            assertNotNull(meter.getId().getTag("outcome"));
            assertNotNull(meter.getId().getTag("error.code"));
            assertNotNull(meter.getId().getTag("delivery.state"));
            assertNotNull(meter.getId().getTag("http.status.class"));
            assertNull(meter.getId().getTag("customer"));
            assertNull(meter.getId().getTag("schema"));
            assertNull(meter.getId().getTag("event"));
            assertNull(meter.getId().getTag("url"));
            assertNull(meter.getId().getTag("operation.id"));
        }
        assertNotSame(
                MicrometerRepostObserver.create(registry),
                MicrometerRepostObserver.create(registry));
        assertThrows(NullPointerException.class, () -> MicrometerRepostObserver.create(null));
    }

    @Test
    void tenThousandUniqueOperationIdsKeepMetricCardinalityFixed() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RepostObserver bridge = MicrometerRepostObserver.create(registry);
        ArrayBlockingQueue<Boolean> terminalEvents = new ArrayBlockingQueue<>(1);
        RepostObserver observed = event -> {
            bridge.onEvent(event);
            if (event.getKind() == ObserverEventKind.OPERATION_END) {
                terminalEvents.add(Boolean.TRUE);
            }
        };
        byte[] responseBody = ("{\"id\":\"msg_metric\",\"type\":\"contract.sent\","
                + "\"customerId\":\"sentinel-customer\","
                + "\"timestamp\":\"1970-01-01T00:00:00.000Z\"}")
                .getBytes(StandardCharsets.UTF_8);
        try (RuntimeTestHarness harness = RuntimeTestHarness.builder(schema(), event())
                .customerId("sentinel-customer")
                .transport(request -> java.util.concurrent.CompletableFuture.completedFuture(
                        TransportResponse.of(
                                202,
                                Collections.singletonList(TransportHeaderField.of(
                                        "Content-Type", "application/json")),
                                new ByteArrayInputStream(responseBody))))
                .observer(observed)
                .build()) {
            for (int index = 0; index < 10_000; index++) {
                harness.sendEmpty().toCompletableFuture().join();
                assertEquals(Boolean.TRUE, terminalEvents.poll(2, TimeUnit.SECONDS));
            }
        }

        assertEquals(4, registry.getMeters().size());
        assertEquals(10_000.0, registry.get("repost.client.operations").counter().count());
        assertEquals(10_000.0, registry.get("repost.client.attempts").counter().count());
    }

    private static List<String> metricMetadata(SimpleMeterRegistry registry) {
        ArrayList<String> golden = new ArrayList<>();
        for (Meter meter : registry.getMeters()) {
            Meter.Id id = meter.getId();
            ArrayList<String> keys = new ArrayList<>();
            id.getTags().forEach(tag -> keys.add(tag.getKey()));
            Collections.sort(keys);
            String metadata = id.getName() + "|" + id.getType() + "|" + id.getBaseUnit()
                    + "|" + id.getDescription() + "|" + String.join(",", keys);
            if (!golden.contains(metadata)) {
                golden.add(metadata);
            }
        }
        golden.sort(Comparator.naturalOrder());
        return golden;
    }

    private static SchemaDescriptor schema() {
        return SchemaDescriptor.builder(2)
                .addModel(ModelDescriptor.of("Payload", Collections.emptyList()))
                .addEvent("events", "created", EventDescriptor.of("contract.sent", "Payload"))
                .build();
    }

    private static EventDescriptor event() {
        return schema().getWebhooks().get("events").get("created");
    }
}
