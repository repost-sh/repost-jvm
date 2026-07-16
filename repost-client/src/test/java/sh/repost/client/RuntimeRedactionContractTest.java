package sh.repost.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import sh.repost.client.descriptor.EventDescriptor;
import sh.repost.client.descriptor.FieldDescriptor;
import sh.repost.client.descriptor.ModelDescriptor;
import sh.repost.client.descriptor.ScalarKind;
import sh.repost.client.descriptor.SchemaDescriptor;
import sh.repost.client.error.RepostException;

final class RuntimeRedactionContractTest {
    private static final String API_KEY = "sentinel-api-key";
    private static final String IDEMPOTENCY_KEY = "sentinel-idempotency-key";
    private static final String REMOTE_SENTINEL =
            "sentinel-remote-header-body-host-path-and-correlation-id";

    @Test
    void mapsTerminalStatusClassesWithoutRetainingRemoteData() throws Exception {
        assertStatusFailure(
                429,
                RepostErrorCode.RATE_LIMITED,
                DeliveryState.REJECTED,
                true,
                null);
        assertStatusFailure(
                400,
                RepostErrorCode.HTTP_REJECTED,
                DeliveryState.REJECTED,
                false,
                null);
        assertStatusFailure(
                500,
                RepostErrorCode.SERVER_FAILURE,
                DeliveryState.POSSIBLY_SENT,
                true,
                null);
        assertStatusFailure(
                407,
                RepostErrorCode.PROXY,
                DeliveryState.NOT_SENT,
                false,
                RepostFailureReason.PROXY_AUTH_REQUIRED);
    }

    @Test
    void serializationFailureIsLocalRedactedAndNeverCallsTransport() throws Exception {
        AtomicInteger transportCalls = new AtomicInteger();
        Transport transport = request -> {
            transportCalls.incrementAndGet();
            return CompletableFuture.completedFuture(response(202));
        };
        SchemaDescriptor schema = schemaWithOneStringField();
        EventDescriptor event = schema.getWebhooks().get("events").get("created");
        RepostModel model = new RepostModel() {
            @Override
            public boolean __repostIsPresent(int fieldIndex) {
                throw new SentinelModelFailure("sentinel-payload-and-class-name");
            }

            @Override
            public Object __repostValue(int fieldIndex) {
                throw new AssertionError("value must not be read after presence failure");
            }
        };
        try (RepostRuntime runtime = runtime(transport)) {
            SendOperation operation = runtime.sendAsync(
                    schema,
                    event,
                    "sentinel-customer",
                    model,
                    SendOptions.builder().idempotencyKey(IDEMPOTENCY_KEY).build());

            RepostException failure = failure(operation);
            assertEquals(RepostErrorCode.SERIALIZATION, failure.getErrorCode());
            assertEquals(DeliveryState.NOT_SENT, failure.getDeliveryState());
            assertEquals(0, failure.getAttemptCount());
            assertNull(failure.getHttpStatus());
            assertEquals(0, transportCalls.get());
            SendOutcome outcome = operation.outcome().toCompletableFuture().get();
            assertOperationEvidence(failure, outcome);
            assertEquals(IDEMPOTENCY_KEY, failure.getIdempotencyKey());
            assertEquals(IDEMPOTENCY_KEY, outcome.getIdempotencyKey());
            assertRedacted(failure, "sentinel-payload-and-class-name");
            assertRedacted(failure, SentinelModelFailure.class.getName());
            assertRedacted(failure, IDEMPOTENCY_KEY);
        }
    }

    @Test
    void transportFailureCauseDistinguishesOwnedProductionAndBorrowedCustomSlots()
            throws Exception {
        StructuredFailureTransport customTransport = new StructuredFailureTransport();
        try (RepostRuntime runtime = runtime(customTransport)) {
            assertStructuredTransportFailure(
                    runtime, RepostCauseCategory.CUSTOM_TRANSPORT);
        }
        assertEquals(0, customTransport.closeCalls.get());

        StructuredFailureTransport ownedTransport = new StructuredFailureTransport();
        ExecutorService ownedExecutor = Executors.newSingleThreadExecutor();
        ScheduledThreadPoolExecutor ownedScheduler = new ScheduledThreadPoolExecutor(1);
        RepostRuntime ownedRuntime = RepostRuntime.createForTesting(
                ClientOptions.builder()
                        .apiKey(API_KEY)
                        .baseUri("https://sentinel-remote.invalid")
                        .maxAttempts(1)
                        .build(),
                ignored -> null,
                ownedExecutor,
                ownedScheduler,
                ownedTransport);
        try (RepostRuntime runtime = ownedRuntime) {
            assertStructuredTransportFailure(
                    runtime, RepostCauseCategory.HTTP_RUNTIME);
        }
        assertEquals(1, ownedTransport.closeCalls.get());
        assertTrue(ownedExecutor.isShutdown());
        assertTrue(ownedScheduler.isShutdown());
    }

    @Test
    void failedTransportStagePreservesRecoverableFatalIdentityAndShutsDown() throws Exception {
        LinkageError fatal = new LinkageError("failed-stage fatal sentinel");
        CompletableFuture<TransportResponse> failed = new CompletableFuture<>();
        failed.completeExceptionally(fatal);
        CountDownLatch uncaught = new CountDownLatch(1);
        AtomicReference<Throwable> delivered = new AtomicReference<>();
        Thread.UncaughtExceptionHandler prior = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            if (thread.getName().equals("repost-fatal-shutdown")) {
                delivered.set(failure);
                uncaught.countDown();
            } else if (prior != null) {
                prior.uncaughtException(thread, failure);
            }
        });
        RepostRuntime runtime = runtime(request -> failed);
        try {
            SchemaDescriptor schema = emptySchema();
            SendOperation operation = runtime.sendAsync(
                    schema,
                    schema.getWebhooks().get("events").get("created"),
                    "sentinel-customer",
                    emptyModel(),
                    SendOptions.builder().idempotencyKey(IDEMPOTENCY_KEY).build());

            assertEquals(fatal, exceptionalCause(operation));
            assertEquals(fatal, exceptionalCause(operation.outcome()));
            runtime.closeCompletion().toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertTrue(uncaught.await(2, TimeUnit.SECONDS));
            assertEquals(fatal, delivered.get());
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(prior);
            runtime.close();
        }
    }

    private static void assertStatusFailure(
            int status,
            RepostErrorCode code,
            DeliveryState delivery,
            boolean retryable,
            RepostFailureReason reason) throws Exception {
        try (RepostRuntime runtime = runtime(new SentinelStatusTransport(status))) {
            SchemaDescriptor schema = emptySchema();
            SendOperation operation = runtime.sendAsync(
                    schema,
                    schema.getWebhooks().get("events").get("created"),
                    "sentinel-customer",
                    emptyModel(),
                    SendOptions.builder().idempotencyKey(IDEMPOTENCY_KEY).build());

            RepostException failure = failure(operation);
            assertEquals(code, failure.getErrorCode());
            assertEquals(delivery, failure.getDeliveryState());
            assertEquals(Integer.valueOf(status), failure.getHttpStatus());
            assertEquals(1, failure.getAttemptCount());
            assertEquals(retryable, failure.isRetryable());
            assertEquals(reason, failure.getFailureReason());
            SendOutcome outcome = operation.outcome().toCompletableFuture().get();
            assertOperationEvidence(failure, outcome);
            assertEquals(IDEMPOTENCY_KEY, failure.getIdempotencyKey());
            assertEquals(IDEMPOTENCY_KEY, outcome.getIdempotencyKey());
            assertRedacted(failure, API_KEY);
            assertRedacted(failure, REMOTE_SENTINEL);
            assertRedacted(failure, SentinelStatusTransport.class.getName());
        }
    }

    private static void assertOperationEvidence(
            RepostException failure,
            SendOutcome outcome) {
        assertTrue(failure.getOperationId().matches(
                "^op_[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"));
        assertEquals(failure.getOperationId(), outcome.getOperationId());
        assertEquals(failure.getErrorCode(), outcome.getErrorCode());
        assertEquals(failure.getFailureReason(), outcome.getFailureReason());
        assertEquals(failure.getCauseCategory(), outcome.getCauseCategory());
        assertEquals(failure.getDeliveryState(), outcome.getDeliveryState());
        assertEquals(failure.getAttemptCount(), outcome.getAttemptCount());
        assertEquals(failure.getIdempotencyKey(), outcome.getIdempotencyKey());
        assertEquals(failure.getHttpStatus(), outcome.getHttpStatus());
    }

    private static void assertStructuredTransportFailure(
            RepostRuntime runtime,
            RepostCauseCategory expectedCause) throws Exception {
        SchemaDescriptor schema = emptySchema();
        SendOperation operation = runtime.sendAsync(
                schema,
                schema.getWebhooks().get("events").get("created"),
                "sentinel-customer",
                emptyModel(),
                SendOptions.builder().idempotencyKey(IDEMPOTENCY_KEY).build());

        RepostException failure = failure(operation);
        assertEquals(RepostErrorCode.DNS, failure.getErrorCode());
        assertEquals(DeliveryState.NOT_SENT, failure.getDeliveryState());
        assertEquals(RepostFailureReason.DNS_NOT_FOUND, failure.getFailureReason());
        assertEquals(expectedCause, failure.getCauseCategory());
        assertEquals(1, failure.getAttemptCount());
        assertEquals(IDEMPOTENCY_KEY, failure.getIdempotencyKey());
        assertNull(failure.getHttpStatus());
        assertTrue(failure.isRetryable());
        SendOutcome outcome = operation.outcome().toCompletableFuture().get();
        assertOperationEvidence(failure, outcome);
        assertRedacted(failure, StructuredFailureTransport.class.getName());
        assertRedacted(failure, IDEMPOTENCY_KEY);
    }

    private static void assertRedacted(RepostException failure, String sentinel) {
        StringWriter rendered = new StringWriter();
        failure.printStackTrace(new PrintWriter(rendered));
        assertFalse(failure.getMessage().contains(sentinel));
        assertFalse(failure.toString().contains(sentinel));
        assertFalse(rendered.toString().contains(sentinel));
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
    }

    private static RepostRuntime runtime(Transport transport) {
        return RepostRuntime.create(ClientOptions.builder()
                .apiKey(API_KEY)
                .baseUri("https://sentinel-remote.invalid")
                .transport(transport)
                .maxAttempts(1)
                .build());
    }

    private static SchemaDescriptor emptySchema() {
        return SchemaDescriptor.builder(2)
                .addModel(ModelDescriptor.of("Payload", Collections.emptyList()))
                .addEvent("events", "created", EventDescriptor.of("contract.sent", "Payload"))
                .build();
    }

    private static RepostModel emptyModel() {
        return new RepostModel() {
            @Override public boolean __repostIsPresent(int fieldIndex) { return false; }
            @Override public Object __repostValue(int fieldIndex) { return null; }
        };
    }

    private static SchemaDescriptor schemaWithOneStringField() {
        FieldDescriptor field = FieldDescriptor.builder(
                        0, "value", "value", ScalarKind.STRING)
                .build();
        return SchemaDescriptor.builder(2)
                .addModel(ModelDescriptor.of("Payload", Collections.singletonList(field)))
                .addEvent("events", "created", EventDescriptor.of("contract.sent", "Payload"))
                .build();
    }

    private static TransportResponse response(int status) {
        return TransportResponse.of(
                status,
                Collections.singletonList(
                        TransportHeaderField.of("X-Sentinel-Remote", REMOTE_SENTINEL)),
                new ByteArrayInputStream(
                        REMOTE_SENTINEL.getBytes(StandardCharsets.UTF_8)));
    }

    private static RepostException failure(SendOperation operation) {
        try {
            operation.get();
            throw new AssertionError("expected operation failure");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        } catch (ExecutionException exception) {
            return (RepostException) exception.getCause();
        }
    }

    private static Throwable exceptionalCause(CompletionStage<?> stage) throws Exception {
        try {
            stage.toCompletableFuture().get(2, TimeUnit.SECONDS);
            throw new AssertionError("expected exceptional completion");
        } catch (ExecutionException failure) {
            return failure.getCause();
        }
    }

    private static final class SentinelStatusTransport implements Transport {
        private final int status;

        private SentinelStatusTransport(int status) {
            this.status = status;
        }

        @Override
        public CompletionStage<TransportResponse> execute(TransportRequest request) {
            return CompletableFuture.completedFuture(response(status));
        }
    }

    private static final class SentinelModelFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private SentinelModelFailure(String message) {
            super(message);
        }
    }

    private static final class StructuredFailureTransport
            implements Transport, AutoCloseable {
        private final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public CompletionStage<TransportResponse> execute(TransportRequest request) {
            CompletableFuture<TransportResponse> failed = new CompletableFuture<>();
            failed.completeExceptionally(TransportFailure.of(
                    RepostErrorCode.DNS,
                    RequestCommitState.NOT_COMMITTED,
                    RepostFailureReason.DNS_NOT_FOUND));
            return failed;
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }
    }
}
