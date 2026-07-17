package sh.repost.client.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import sh.repost.client.DeliveryState;
import sh.repost.client.ObserverEventKind;
import sh.repost.client.RepostErrorCode;
import sh.repost.client.RequestCommitState;
import sh.repost.client.TransportFailure;
import sh.repost.client.TransportHeaderField;
import sh.repost.client.descriptor.EventDescriptor;
import sh.repost.client.descriptor.ModelDescriptor;
import sh.repost.client.descriptor.SchemaDescriptor;

final class TestFixturesContractTest {
    @Test
    void stubTransportScriptsOrderedResponsesFailuresAndControlledCompletion() {
        StubTransport transport = new StubTransport()
                .enqueueResponse(
                        202,
                        Arrays.asList(
                                TransportHeaderField.of("Content-Type", "application/json"),
                                TransportHeaderField.of("X-Repost", "one"),
                                TransportHeaderField.of("x-repost", "two")),
                        acceptedBody("msg_first").getBytes(StandardCharsets.UTF_8))
                .enqueueFailure(TransportFailure.of(
                        RepostErrorCode.IO, RequestCommitState.NOT_COMMITTED));
        StubTransport.ControlledResponse controlled = transport.enqueuePending();

        try (RuntimeTestHarness harness = RuntimeTestHarness.builder(schema(), event())
                .transport(transport)
                .customerId("cus_harness")
                .build()) {
            assertEquals("msg_first", harness.sendEmpty().toCompletableFuture().join().getId());
            CompletionException failure = assertThrows(
                    CompletionException.class,
                    () -> harness.sendEmpty().toCompletableFuture().join());
            assertEquals(RepostErrorCode.IO,
                    ((sh.repost.client.error.RepostException) failure.getCause()).getErrorCode());
            sh.repost.client.SendOperation pending = harness.sendEmpty();
            assertTrue(!pending.toCompletableFuture().isDone());
            controlled.completeResponse(
                    202,
                    Collections.singletonList(
                            TransportHeaderField.of("Content-Type", "application/json")),
                    acceptedBody("msg_third").getBytes(StandardCharsets.UTF_8));
            assertEquals("msg_third", pending.toCompletableFuture().join().getId());

            assertEquals(3, transport.getRequests().size());
            for (RecordedRequest recorded : transport.getRequests()) {
                assertTrue(recorded.getHeaderFields().stream()
                        .noneMatch(field -> field.getName().equalsIgnoreCase("Authorization")));
            }
            assertArrayEquals(
                    transport.getRequests().get(0).getBodyBytes(),
                    transport.getRequests().get(2).getBodyBytes());
        }
    }

    @Test
    void deterministicProvidersClocksEntropyAndNetworkGuardArePublicAndStateful() {
        TestApiKeyProvider keys = TestApiKeyProvider.rotating("key-a", "key-b");
        assertEquals("key-a", keys.getApiKey());
        assertEquals("key-b", keys.getApiKey());
        assertThrows(IllegalStateException.class, keys::getApiKey);

        ManualMonotonicClock monotonic = new ManualMonotonicClock(10L);
        monotonic.advance(Duration.ofNanos(5L));
        assertEquals(15L, monotonic.nanoTime());
        ManualWallClock wall = new ManualWallClock(Instant.EPOCH);
        wall.advance(Duration.ofSeconds(2));
        assertEquals(Instant.ofEpochSecond(2), wall.now());

        DeterministicRetryEntropy entropy = DeterministicRetryEntropy.sequence(2L, 0L);
        assertEquals(2L, entropy.nextLong(3L));
        assertEquals(0L, entropy.nextLong(1L));
        assertThrows(IllegalStateException.class, () -> entropy.nextLong(2L));

        NoNetworkGuard guard = new NoNetworkGuard();
        try (RuntimeTestHarness harness = RuntimeTestHarness.builder(schema(), event()).build()) {
            assertThrows(
                    CompletionException.class,
                    () -> harness.sendEmpty().toCompletableFuture().join());
            assertEquals(1, harness.getNoNetworkGuard().getAttemptCount());
            assertThrows(
                    NoNetworkGuard.NetworkAccessAttempt.class,
                    harness.getNoNetworkGuard()::assertNoAccess);
        }
        guard.assertNoAccess();
    }

    @Test
    void runtimeHarnessSendsThroughTheRealRuntimeAndCapturesExactEnvelope() throws Exception {
        SchemaDescriptor schema = schema();
        StubTransport transport = new StubTransport().enqueueResponse(
                202,
                "{\"id\":\"msg_harness\",\"type\":\"contract.sent\","
                        + "\"customerId\":\"cus_harness\","
                        + "\"timestamp\":\"1970-01-01T00:00:00.000Z\"}");
        try (RuntimeTestHarness harness = RuntimeTestHarness.builder(
                        schema, schema.getWebhooks().get("events").get("created"))
                .transport(transport)
                .customerId("cus_harness")
                .build()) {
            assertEquals("msg_harness", harness.sendEmpty().toCompletableFuture().join().getId());
            assertEquals(DeliveryState.ACCEPTED,
                    harness.getLastOperation().outcome().toCompletableFuture().join()
                            .getDeliveryState());
            assertEquals(1, harness.getCapturedRequests().size());
            String body = new String(
                    harness.getCapturedRequests().get(0).getBodyBytes(), StandardCharsets.UTF_8);
            assertTrue(body.contains("\"type\":\"contract.sent\""));
            assertTrue(body.contains("\"customerId\":\"cus_harness\""));
        }
    }

    @Test
    void runtimeHarnessAcceptsCredentialAndObserverControlsWithoutBypassingCore() {
        TestApiKeyProvider keys = TestApiKeyProvider.rotating("key-a", "key-b");
        RecordingObserver observer = new RecordingObserver();
        StubTransport transport = new StubTransport()
                .enqueueResponse(202, acceptedBody("msg_first"))
                .enqueueResponse(202, acceptedBody("msg_second"));
        try (RuntimeTestHarness harness = RuntimeTestHarness.builder(schema(), event())
                .customerId("cus_harness")
                .apiKeyProvider(keys)
                .baseUri("https://example.com")
                .retryBaseDelay(Duration.ofMillis(1))
                .retryMaxDelay(Duration.ofMillis(2))
                .transport(transport)
                .observer(observer)
                .build()) {
            assertEquals("msg_first", harness.sendEmpty().toCompletableFuture().join().getId());
            assertEquals(ObserverEventKind.OPERATION_START,
                    observer.awaitNext(Duration.ofSeconds(2)).getKind());
            assertEquals(ObserverEventKind.ATTEMPT_START,
                    observer.awaitNext(Duration.ofSeconds(2)).getKind());
            assertEquals(ObserverEventKind.ATTEMPT_END,
                    observer.awaitNext(Duration.ofSeconds(2)).getKind());
            assertEquals(ObserverEventKind.OPERATION_END,
                    observer.awaitNext(Duration.ofSeconds(2)).getKind());
            assertEquals("msg_second", harness.sendEmpty().toCompletableFuture().join().getId());
            CompletionException exhausted = assertThrows(
                    CompletionException.class,
                    () -> harness.sendEmpty().toCompletableFuture().join());
            assertEquals(RepostErrorCode.CONFIGURATION,
                    ((sh.repost.client.error.RepostException) exhausted.getCause()).getErrorCode());
            assertEquals(2, transport.getRequests().size());
        }
    }

    @Test
    void asynchronousFactoryFailureClosesTransferredBodyExactlyOnceAndDoesNotRetry() {
        CloseCountingInputStream body = new CloseCountingInputStream();
        StubTransport transport = new StubTransport().enqueueAsyncFactoryResponse(
                199,
                Collections.emptyList(),
                body);
        try (RuntimeTestHarness harness = RuntimeTestHarness.builder(schema(), event())
                .customerId("cus_harness")
                .transport(transport)
                .maxAttempts(2)
                .build()) {
            assertThrows(
                    CompletionException.class,
                    () -> harness.sendEmpty().toCompletableFuture().join());
        }
        assertEquals(1, body.closeCount.get());
        assertEquals(1, transport.getRequests().size());

        CloseCountingInputStream nullHeadersBody = new CloseCountingInputStream();
        StubTransport nullHeaders = new StubTransport()
                .enqueueAsyncNullHeadersFactoryResponse(202, nullHeadersBody);
        try (RuntimeTestHarness harness = RuntimeTestHarness.builder(schema(), event())
                .customerId("cus_harness")
                .transport(nullHeaders)
                .maxAttempts(2)
                .build()) {
            assertThrows(
                    CompletionException.class,
                    () -> harness.sendEmpty().toCompletableFuture().join());
        }
        assertEquals(1, nullHeadersBody.closeCount.get());
        assertEquals(1, nullHeaders.getRequests().size());
    }

    @Test
    void rawNullAndCancellationScriptsRemainDeterministicAndNonretryable() throws Exception {
        StubTransport nullStage = new StubTransport().enqueueNullStage();
        try (RuntimeTestHarness harness = RuntimeTestHarness.builder(schema(), event())
                .transport(nullStage)
                .maxAttempts(2)
                .build()) {
            assertThrows(CompletionException.class,
                    () -> harness.sendEmpty().toCompletableFuture().join());
        }
        assertEquals(1, nullStage.getRequests().size());

        StubTransport nullResponse = new StubTransport().enqueueNullResponse();
        try (RuntimeTestHarness harness = RuntimeTestHarness.builder(schema(), event())
                .transport(nullResponse)
                .maxAttempts(2)
                .build()) {
            assertThrows(CompletionException.class,
                    () -> harness.sendEmpty().toCompletableFuture().join());
        }
        assertEquals(1, nullResponse.getRequests().size());

        StubTransport pending = new StubTransport();
        StubTransport.ControlledResponse controlled = pending.enqueuePending();
        try (RuntimeTestHarness harness = RuntimeTestHarness.builder(schema(), event())
                .transport(pending)
                .build()) {
            sh.repost.client.SendOperation operation = harness.sendEmpty();
            assertTrue(pending.awaitRequestCount(1, Duration.ofSeconds(2)));
            assertTrue(operation.toCompletableFuture().cancel(false));
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while (!controlled.isDone() && System.nanoTime() < deadline) {
                Thread.sleep(1L);
            }
            assertTrue(controlled.isDone());
            assertTrue(controlled.isCancelled());
        }
    }

    @Test
    void streamReadFailureUsesTheRealResponsePathAndClosesExactlyOnce() {
        FailingInputStream body = new FailingInputStream(
                "{".getBytes(StandardCharsets.UTF_8),
                new java.io.IOException("stream sentinel"));
        StubTransport transport = new StubTransport().enqueueFactoryResponse(
                202,
                Collections.singletonList(
                        TransportHeaderField.of("Content-Type", "application/json")),
                body);
        try (RuntimeTestHarness harness = RuntimeTestHarness.builder(schema(), event())
                .customerId("cus_harness")
                .transport(transport)
                .build()) {
            assertThrows(
                    CompletionException.class,
                    () -> harness.sendEmpty().toCompletableFuture().join());
        }
        assertEquals(1, body.getCloseCount());
        assertEquals(1, transport.getRequests().size());
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

    private static String acceptedBody(String id) {
        return "{\"id\":\"" + id + "\",\"type\":\"contract.sent\","
                + "\"customerId\":\"cus_harness\","
                + "\"timestamp\":\"1970-01-01T00:00:00.000Z\"}";
    }

    private static final class CloseCountingInputStream extends java.io.InputStream {
        private final AtomicInteger closeCount = new AtomicInteger();

        @Override
        public int read() {
            return -1;
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
        }
    }
}
