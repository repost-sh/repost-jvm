package sh.repost.client;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import sh.repost.client.error.RepostTransportException;
import sh.repost.client.descriptor.EventDescriptor;
import sh.repost.client.descriptor.ModelDescriptor;
import sh.repost.client.descriptor.SchemaDescriptor;

final class BoundedScaleContractTest {
    private static final int SENDS = 1_000;
    private static final int ADMISSION_CAP = 32;
    private static final DefaultValueGenerators GENERATORS = DefaultValueGenerators.fixed(
            Instant.EPOCH,
            "12345678-1234-4234-9234-123456789abc",
            "abcdefghijklmnopqrstuvwx");
    private static final SchemaDescriptor SCHEMA = SchemaDescriptor.builder(2)
            .addModel(ModelDescriptor.of("Payload", Collections.emptyList()))
            .addEvent("scale", "created", EventDescriptor.of("scale.created", "Payload"))
            .build();
    private static final EventDescriptor EVENT = SCHEMA.getWebhooks().get("scale").get("created");
    private static final RepostModel PAYLOAD = new RepostModel() {
        @Override public boolean __repostIsPresent(int fieldIndex) { return false; }
        @Override public Object __repostValue(int fieldIndex) { return null; }
    };

    @Test
    void oneThousandOutstandingSendsRespectAdmissionAndReturnToBaseline() throws Exception {
        PendingTransport transport = new PendingTransport();
        RepostRuntime runtime = RepostRuntime.create(ClientOptions.builder()
                .apiKey("test-key")
                .defaultValueGenerators(GENERATORS)
                .maxInFlightOperations(ADMISSION_CAP)
                .maxBufferedBytes(1_073_741_824L)
                .transport(transport)
                .build());
        List<SendOperation> operations = new ArrayList<>(SENDS);
        try {
            for (int index = 0; index < SENDS; index++) {
                operations.add(runtime.sendAsync(
                        SCHEMA,
                        EVENT,
                        "cus_scale",
                        PAYLOAD,
                        SendOptions.builder().idempotencyKey("scale-key-" + index).build()));
            }

            assertTrue(transport.awaitCalls(ADMISSION_CAP, 5, TimeUnit.SECONDS));
            RuntimeDiagnostics saturated = runtime.diagnostics();
            assertEquals(ADMISSION_CAP, saturated.getInFlightOperations());
            assertTrue(saturated.getBufferedBytes() > 0L);
            assertTrue(saturated.getBufferedBytes() <= 1_073_741_824L);
            assertEquals(SENDS - ADMISSION_CAP, saturated.getConcurrencyOverloadRejections());
            assertEquals(ADMISSION_CAP, transport.callCount());

            transport.acceptAll();
            int accepted = 0;
            int overloaded = 0;
            for (SendOperation operation : operations) {
                try {
                    operation.toCompletableFuture().join();
                    accepted++;
                } catch (CompletionException failure) {
                    RepostTransportException rejection =
                            (RepostTransportException) failure.getCause();
                    assertEquals(RepostErrorCode.OVERLOADED, rejection.getErrorCode());
                    assertEquals(DeliveryState.NOT_SENT, rejection.getDeliveryState());
                    overloaded++;
                }
            }
            assertEquals(ADMISSION_CAP, accepted);
            assertEquals(SENDS - ADMISSION_CAP, overloaded);
            assertEquals(ADMISSION_CAP, transport.callCount());
            assertEquals(0, runtime.diagnostics().getInFlightOperations());
            assertEquals(0L, runtime.diagnostics().getBufferedBytes());
        } finally {
            transport.acceptAll();
            runtime.close();
        }
    }

    @Test
    void oneHundredOwnedSendCloseCyclesReleaseEveryResource() throws Exception {
        for (int cycle = 0; cycle < 100; cycle++) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
            OwnedTransport transport = new OwnedTransport();
            RepostRuntime runtime = RepostRuntime.createForTesting(
                    ClientOptions.builder()
                            .apiKey("test-key")
                            .defaultValueGenerators(GENERATORS)
                            .build(),
                    ignored -> null,
                    executor,
                    scheduler,
                    transport);

            SendResult result = runtime.send(
                    SCHEMA, EVENT, "cus_scale", PAYLOAD, SendOptions.defaults());
            assertEquals("msg_scale", result.getId());
            runtime.close();

            assertTrue(runtime.isClosed());
            assertEquals(0, runtime.diagnostics().getInFlightOperations());
            assertEquals(0L, runtime.diagnostics().getBufferedBytes());
            assertEquals(1, transport.closeCalls.get());
            assertTrue(executor.isShutdown());
            assertTrue(scheduler.isShutdown());
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(scheduler.awaitTermination(2, TimeUnit.SECONDS));
            assertFalse(transport.executedAfterClose);
        }
    }

    private static TransportResponse acceptedResponse() {
        byte[] body = ("{\"id\":\"msg_scale\",\"type\":\"scale.created\","
                + "\"customerId\":\"cus_scale\","
                + "\"timestamp\":\"1970-01-01T00:00:00.000Z\"}").getBytes(UTF_8);
        return TransportResponse.of(
                202,
                Collections.singletonList(
                        TransportHeaderField.of("Content-Type", "application/json")),
                new ByteArrayInputStream(body));
    }

    private static final class PendingTransport implements Transport {
        private final List<CompletableFuture<TransportResponse>> responses =
                Collections.synchronizedList(new ArrayList<>());

        @Override public CompletableFuture<TransportResponse> execute(TransportRequest request) {
            CompletableFuture<TransportResponse> response = new CompletableFuture<>();
            responses.add(response);
            return response;
        }

        int callCount() { return responses.size(); }

        boolean awaitCalls(int count, long timeout, TimeUnit unit) throws InterruptedException {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            while (responses.size() < count && System.nanoTime() < deadline) {
                Thread.sleep(1L);
            }
            return responses.size() == count;
        }

        void acceptAll() {
            synchronized (responses) {
                for (CompletableFuture<TransportResponse> response : responses) {
                    if (!response.isDone()) response.complete(acceptedResponse());
                }
            }
        }
    }

    private static final class OwnedTransport implements Transport, AutoCloseable {
        private final AtomicInteger closeCalls = new AtomicInteger();
        private volatile boolean closed;
        private volatile boolean executedAfterClose;

        @Override public CompletableFuture<TransportResponse> execute(TransportRequest request) {
            if (closed) executedAfterClose = true;
            return CompletableFuture.completedFuture(acceptedResponse());
        }

        @Override public void close() {
            closed = true;
            closeCalls.incrementAndGet();
        }
    }
}
