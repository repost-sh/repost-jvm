package sh.repost.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import sh.repost.client.descriptor.EventDescriptor;
import sh.repost.client.descriptor.ModelDescriptor;
import sh.repost.client.descriptor.SchemaDescriptor;
import sh.repost.client.error.RepostConfigurationException;
import sh.repost.client.error.RepostException;

final class RuntimeRetryContractTest {
    private static final DefaultValueGenerators GENERATORS = DefaultValueGenerators.fixed(
            Instant.EPOCH,
            "12345678-1234-4234-9234-123456789abc",
            "abcdefghijklmnopqrstuvwx");

    @Test
    void reusesOneCallerKeyAndExactBodyAcrossBoundedFullJitterAttempts() {
        MutableMonotonicClock clock = new MutableMonotonicClock();
        ManualExecutor executor = new ManualExecutor();
        ManualScheduler scheduler = new ManualScheduler();
        ScriptedTransport transport = new ScriptedTransport(
                failed(TransportFailure.of(RepostErrorCode.IO, RequestCommitState.NOT_COMMITTED)),
                failed(TransportFailure.of(RepostErrorCode.IO, RequestCommitState.NOT_COMMITTED)),
                failed(TransportFailure.of(RepostErrorCode.IO, RequestCommitState.NOT_COMMITTED)));
        ArrayList<Long> entropyBounds = new ArrayList<>();
        RepostRuntime runtime = create(runtimeBuilder(clock, executor, scheduler, transport)
                .maxAttempts(3)
                .retryBaseDelay(Duration.ofMillis(100))
                .retryMaxDelay(Duration.ofSeconds(1))
                .retryEntropy(bound -> {
                    entropyBounds.add(bound);
                    return bound - 1L;
                }));
        try {
            SendOperation operation = send(runtime, "caller-key");

            executor.runNext();
            executor.runNext();
            assertEquals(Duration.ofMillis(100), scheduler.latestRetryDelay());
            scheduler.runLatestRetry();
            executor.runNext();
            executor.runNext();
            assertEquals(Duration.ofMillis(200), scheduler.latestRetryDelay());
            scheduler.runLatestRetry();
            executor.runNext();
            executor.runNext();

            RepostException failure = operationFailure(operation);
            assertEquals(RepostErrorCode.IO, failure.getErrorCode());
            assertEquals(DeliveryState.NOT_SENT, failure.getDeliveryState());
            assertEquals(3, failure.getAttemptCount());
            assertEquals("caller-key", failure.getIdempotencyKey());
            assertTrue(failure.isRetryable());
            assertEquals(Arrays.asList(101L, 201L), entropyBounds);
            assertEquals(3, transport.requests.size());
            byte[] expectedBody = bytes(transport.requests.get(0).getBody());
            for (int index = 0; index < transport.requests.size(); index++) {
                TransportRequest request = transport.requests.get(index);
                assertEquals(index + 1, request.getAttemptNumber());
                assertArrayEquals(expectedBody, bytes(request.getBody()));
                assertEquals("caller-key", header(request, "Idempotency-Key"));
                assertEquals("Bearer test-key", header(request, "Authorization"));
                assertEquals("application/json", header(request, "Content-Type"));
                assertEquals("gzip", header(request, "Accept-Encoding"));
                assertEquals("repost-jvm/1.0.0", header(request, "User-Agent"));
                assertEquals(5, request.getHeaderFields().size());
            }
            assertEquals(3, transport.executeCalls.get());
        } finally {
            runtime.close();
        }
    }

    @Test
    void generatesAndValidatesOneKeyBeforeAnyTransportAttempt() {
        MutableMonotonicClock clock = new MutableMonotonicClock();
        ManualExecutor executor = new ManualExecutor();
        ManualScheduler scheduler = new ManualScheduler();
        ScriptedTransport transport = new ScriptedTransport();
        AtomicInteger generations = new AtomicInteger();
        RepostRuntime runtime = create(runtimeBuilder(clock, executor, scheduler, transport)
                .idempotencyKeyGenerator(() -> {
                    generations.incrementAndGet();
                    throw new IllegalStateException("sentinel-generator-failure");
                }));
        try {
            SendOperation operation = send(runtime, null);
            executor.runNext();

            RepostConfigurationException failure =
                    (RepostConfigurationException) operationFailure(operation);
            assertEquals(RepostErrorCode.CONFIGURATION, failure.getErrorCode());
            assertEquals(DeliveryState.NOT_SENT, failure.getDeliveryState());
            assertEquals(0, failure.getAttemptCount());
            assertNull(failure.getIdempotencyKey());
            assertEquals(RepostCauseCategory.IDEMPOTENCY_GENERATOR, failure.getCauseCategory());
            assertEquals(
                    Collections.singletonList(ClientOptionKey.IDEMPOTENCY_KEY_GENERATOR),
                    failure.getConfigurationIssues().get(0).getOptionKeys());
            assertEquals(1, generations.get());
            assertEquals(0, transport.executeCalls.get());
            assertFalse(failure.toString().contains("sentinel-generator-failure"));
        } finally {
            runtime.close();
        }
    }

    @Test
    void arbitraryTransportDefectsAreSanitizedAndNeverRetried() {
        MutableMonotonicClock clock = new MutableMonotonicClock();
        ManualExecutor executor = new ManualExecutor();
        ManualScheduler scheduler = new ManualScheduler();
        ScriptedTransport transport = new ScriptedTransport(
                failed(new IllegalStateException("sentinel-transport-defect")));
        RepostRuntime runtime = create(runtimeBuilder(clock, executor, scheduler, transport)
                .maxAttempts(3));
        try {
            SendOperation operation = send(runtime, "defect-key");
            executor.runNext();
            executor.runNext();

            RepostException failure = operationFailure(operation);
            assertEquals(RepostErrorCode.IO, failure.getErrorCode());
            assertEquals(DeliveryState.POSSIBLY_SENT, failure.getDeliveryState());
            assertEquals(RepostFailureReason.UNKNOWN, failure.getFailureReason());
            assertEquals(RepostCauseCategory.CUSTOM_TRANSPORT, failure.getCauseCategory());
            assertEquals(1, failure.getAttemptCount());
            assertFalse(failure.isRetryable());
            assertEquals(1, transport.executeCalls.get());
            assertEquals(0, scheduler.retryCount());
            assertFalse(failure.toString().contains("sentinel-transport-defect"));
        } finally {
            runtime.close();
        }
    }

    @Test
    void generatesOneStableKeyForAllAttempts() {
        MutableMonotonicClock clock = new MutableMonotonicClock();
        ManualExecutor executor = new ManualExecutor();
        ManualScheduler scheduler = new ManualScheduler();
        ScriptedTransport transport = new ScriptedTransport(
                failed(TransportFailure.of(
                        RepostErrorCode.CONNECT, RequestCommitState.NOT_COMMITTED)),
                failed(TransportFailure.of(
                        RepostErrorCode.CONNECT, RequestCommitState.NOT_COMMITTED)));
        AtomicInteger generations = new AtomicInteger();
        RepostRuntime runtime = create(runtimeBuilder(clock, executor, scheduler, transport)
                .maxAttempts(2)
                .retryEntropy(ignored -> 0L)
                .idempotencyKeyGenerator(() -> {
                    generations.incrementAndGet();
                    return "generated-key";
                }));
        try {
            SendOperation operation = send(runtime, null);
            executor.runNext();
            executor.runNext();
            scheduler.runLatestRetry();
            executor.runNext();
            executor.runNext();

            RepostException failure = operationFailure(operation);
            assertEquals(RepostErrorCode.CONNECT, failure.getErrorCode());
            assertEquals(2, failure.getAttemptCount());
            assertEquals("generated-key", failure.getIdempotencyKey());
            assertEquals(1, generations.get());
            assertEquals("generated-key", header(transport.requests.get(0), "Idempotency-Key"));
            assertEquals("generated-key", header(transport.requests.get(1), "Idempotency-Key"));
        } finally {
            runtime.close();
        }
    }

    @Test
    void clampsAttemptPhasesToTheSingleRemainingOperationBudget() {
        MutableMonotonicClock clock = new MutableMonotonicClock();
        ManualExecutor executor = new ManualExecutor();
        ManualScheduler scheduler = new ManualScheduler();
        ScriptedTransport transport = new ScriptedTransport(response(400));
        RepostRuntime runtime = create(runtimeBuilder(clock, executor, scheduler, transport)
                .connectTimeout(Duration.ofSeconds(2))
                .attemptTimeout(Duration.ofSeconds(3))
                .operationTimeout(Duration.ofMillis(500)));
        try {
            SendOperation operation = send(runtime, "phase-key");
            executor.runNext();
            executor.runNext();

            RepostException failure = operationFailure(operation);
            assertEquals(RepostErrorCode.HTTP_REJECTED, failure.getErrorCode());
            assertEquals(1, transport.requests.size());
            assertEquals(Duration.ofMillis(500), transport.requests.get(0).getConnectTimeout());
            assertEquals(Duration.ofMillis(500), transport.requests.get(0).getAttemptTimeout());
        } finally {
            runtime.close();
        }
    }

    @Test
    void includesCustomerIdentityInTheCanonicalRequestEnvelope() {
        MutableMonotonicClock clock = new MutableMonotonicClock();
        ManualExecutor executor = new ManualExecutor();
        ManualScheduler scheduler = new ManualScheduler();
        ScriptedTransport transport = new ScriptedTransport(response(400));
        RepostRuntime runtime = create(runtimeBuilder(clock, executor, scheduler, transport));
        try {
            SendOperation operation = send(runtime, "customer-envelope-key");
            executor.runNext();
            executor.runNext();
            operationFailure(operation);

            assertEquals(
                    "{\"type\":\"contract.sent\",\"customerId\":\"cus_contract\","
                            + "\"timestamp\":\"1970-01-01T00:00:00.000Z\",\"data\":{}}",
                    new String(
                            bytes(transport.requests.get(0).getBody()),
                            java.nio.charset.StandardCharsets.UTF_8));
        } finally {
            runtime.close();
        }
    }

    @Test
    void parsesAnIdentityMatchedSuccessfulResponseIntoSendResult() {
        MutableMonotonicClock clock = new MutableMonotonicClock();
        ManualExecutor executor = new ManualExecutor();
        ManualScheduler scheduler = new ManualScheduler();
        ScriptedTransport transport = new ScriptedTransport(acceptedResponse("msg_contract"));
        RepostRuntime runtime = create(runtimeBuilder(clock, executor, scheduler, transport)
                .maxAttempts(1));
        try {
            SendOperation operation = send(runtime, "accepted-key");
            executor.runNext();
            executor.runNext();

            SendResult result = operationResult(operation);
            assertEquals("msg_contract", result.getId());
            assertEquals("contract.sent", result.getType());
            assertEquals("cus_contract", result.getCustomerId());
            assertEquals(Instant.EPOCH, result.getTimestamp());
            SendOutcome outcome = operation.outcome().toCompletableFuture().join();
            assertTrue(outcome.isAccepted());
            assertEquals(1, outcome.getAttemptCount());
            assertEquals(Integer.valueOf(202), outcome.getHttpStatus());
        } finally {
            runtime.close();
        }
    }

    @Test
    void retriesAnIdentityMismatchWithTheSameKeyAndExactRequestBody() {
        MutableMonotonicClock clock = new MutableMonotonicClock();
        ManualExecutor executor = new ManualExecutor();
        ManualScheduler scheduler = new ManualScheduler();
        ScriptedTransport transport = new ScriptedTransport(
                acceptedResponse(
                        "msg_wrong", "contract.sent", "cus_other",
                        "1970-01-01T00:00:00.000Z"),
                acceptedResponse("msg_retried"));
        RepostRuntime runtime = create(runtimeBuilder(clock, executor, scheduler, transport)
                .maxAttempts(2)
                .retryEntropy(ignored -> 0L));
        try {
            SendOperation operation = send(runtime, "identity-retry-key");
            executor.runNext();
            executor.runNext();
            scheduler.runLatestRetry();
            executor.runNext();
            executor.runNext();

            assertEquals("msg_retried", operationResult(operation).getId());
            assertEquals(2, transport.requests.size());
            assertArrayEquals(
                    bytes(transport.requests.get(0).getBody()),
                    bytes(transport.requests.get(1).getBody()));
            assertEquals("identity-retry-key", header(
                    transport.requests.get(0), "Idempotency-Key"));
            assertEquals("identity-retry-key", header(
                    transport.requests.get(1), "Idempotency-Key"));
        } finally {
            runtime.close();
        }
    }

    @Test
    void honorsStrictRetryAfterAndFallsBackForConflictingDuplicates() {
        MutableMonotonicClock clock = new MutableMonotonicClock();
        ManualExecutor executor = new ManualExecutor();
        ManualScheduler scheduler = new ManualScheduler();
        ScriptedTransport transport = new ScriptedTransport(
                response(
                        429,
                        header("Retry-After", "Thu, 01 Jan 2026 00:00:05 GMT"),
                        header("retry-after", "Thu, 01 Jan 2026 00:00:05 GMT")),
                response(
                        503,
                        header("Retry-After", "Thu, 01 Jan 2026 00:00:05 GMT"),
                        header("retry-after", "Thu, 01 Jan 2026 00:00:06 GMT")),
                response(400));
        Queue<Long> entropy = new ArrayDeque<>(Arrays.asList(125L, 200L));
        RepostRuntime runtime = create(runtimeBuilder(clock, executor, scheduler, transport)
                .maxAttempts(3)
                .retryBaseDelay(Duration.ofMillis(250))
                .retryMaxDelay(Duration.ofSeconds(10))
                .retryEntropy(ignored -> entropy.remove())
                .wallClock(() -> Instant.parse("2026-01-01T00:00:00Z")));
        try {
            SendOperation operation = send(runtime, "retry-after-key");

            executor.runNext();
            executor.runNext();
            assertEquals(Duration.ofSeconds(5), scheduler.latestRetryDelay());
            scheduler.runLatestRetry();
            executor.runNext();
            executor.runNext();
            assertEquals(Duration.ofMillis(200), scheduler.latestRetryDelay());
            scheduler.runLatestRetry();
            executor.runNext();
            executor.runNext();

            RepostException failure = operationFailure(operation);
            assertEquals(RepostErrorCode.HTTP_REJECTED, failure.getErrorCode());
            assertEquals(DeliveryState.POSSIBLY_SENT, failure.getDeliveryState());
            assertEquals(3, failure.getAttemptCount());
            assertEquals(Integer.valueOf(400), failure.getHttpStatus());
            assertTrue(transport.responsesClosed());
        } finally {
            runtime.close();
        }
    }

    @Test
    void honorsRetryAfterDeltaSecondsIndependentlyOfJitterCap() {
        MutableMonotonicClock clock = new MutableMonotonicClock();
        ManualExecutor executor = new ManualExecutor();
        ManualScheduler scheduler = new ManualScheduler();
        ScriptedTransport transport = new ScriptedTransport(
                response(429, header("Retry-After", "2")),
                response(400));
        RepostRuntime runtime = create(runtimeBuilder(clock, executor, scheduler, transport)
                .maxAttempts(2)
                .retryBaseDelay(Duration.ofMillis(250))
                .retryMaxDelay(Duration.ofSeconds(10))
                .retryEntropy(ignored -> 125L));
        try {
            SendOperation operation = send(runtime, "delta-key");
            executor.runNext();
            executor.runNext();
            assertEquals(Duration.ofSeconds(2), scheduler.latestRetryDelay());
            scheduler.runLatestRetry();
            executor.runNext();
            executor.runNext();
            assertEquals(RepostErrorCode.HTTP_REJECTED, operationFailure(operation).getErrorCode());
        } finally {
            runtime.close();
        }
    }

    @Test
    void doesNotStartARetryAtTheExclusiveOperationDeadline() {
        MutableMonotonicClock clock = new MutableMonotonicClock();
        ManualExecutor executor = new ManualExecutor();
        ManualScheduler scheduler = new ManualScheduler();
        ScriptedTransport transport = new ScriptedTransport(
                response(503, header("Retry-After", "1")));
        RepostRuntime runtime = create(runtimeBuilder(clock, executor, scheduler, transport)
                .maxAttempts(2)
                .operationTimeout(Duration.ofSeconds(1))
                .retryMaxDelay(Duration.ofSeconds(1))
                .retryEntropy(ignored -> 0L));
        try {
            SendOperation operation = send(runtime, "deadline-key");
            executor.runNext();
            executor.runNext();
            assertEquals(Duration.ofSeconds(1), scheduler.latestRetryDelay());

            clock.advance(Duration.ofSeconds(1));
            scheduler.runLatestRetry();
            executor.runNext();

            RepostException failure = operationFailure(operation);
            assertEquals(RepostErrorCode.OPERATION_DEADLINE, failure.getErrorCode());
            assertEquals(DeliveryState.POSSIBLY_SENT, failure.getDeliveryState());
            assertEquals(1, failure.getAttemptCount());
            assertEquals("deadline-key", failure.getIdempotencyKey());
            assertEquals(1, transport.executeCalls.get());
        } finally {
            runtime.close();
        }
    }

    @Test
    void operationDeadlineCancelsTheInFlightTransportStage() {
        MutableMonotonicClock clock = new MutableMonotonicClock();
        ManualExecutor executor = new ManualExecutor();
        ManualScheduler scheduler = new ManualScheduler();
        CompletableFuture<TransportResponse> pending = new CompletableFuture<>();
        ScriptedTransport transport = new ScriptedTransport(pending);
        RepostRuntime runtime = create(runtimeBuilder(clock, executor, scheduler, transport)
                .operationTimeout(Duration.ofSeconds(1)));
        try {
            SendOperation operation = send(runtime, "in-flight-key");
            executor.runNext();
            assertFalse(pending.isDone());

            clock.advance(Duration.ofSeconds(1));
            scheduler.runDeadline();

            RepostException failure = operationFailure(operation);
            assertEquals(RepostErrorCode.OPERATION_DEADLINE, failure.getErrorCode());
            assertEquals(1, failure.getAttemptCount());
            assertEquals("in-flight-key", failure.getIdempotencyKey());
            assertTrue(pending.isCancelled());
            assertEquals(1, transport.executeCalls.get());
        } finally {
            runtime.close();
        }
    }

    @Test
    void enforcesAttemptTimeoutAgainstAPendingCustomTransportStage() {
        MutableMonotonicClock clock = new MutableMonotonicClock();
        ManualExecutor executor = new ManualExecutor();
        ManualScheduler scheduler = new ManualScheduler();
        CompletableFuture<TransportResponse> pending = new CompletableFuture<>();
        ScriptedTransport transport = new ScriptedTransport(pending);
        RepostRuntime runtime = create(runtimeBuilder(clock, executor, scheduler, transport)
                .maxAttempts(1)
                .attemptTimeout(Duration.ofSeconds(1))
                .operationTimeout(Duration.ofSeconds(30)));
        try {
            SendOperation operation = send(runtime, "attempt-timeout-key");
            executor.runNext();
            assertFalse(pending.isDone());

            clock.advance(Duration.ofSeconds(1));
            scheduler.runTaskWithDelay(Duration.ofSeconds(1));

            RepostException failure = operationFailure(operation);
            assertEquals(RepostErrorCode.ATTEMPT_TIMEOUT, failure.getErrorCode());
            assertEquals(DeliveryState.POSSIBLY_SENT, failure.getDeliveryState());
            assertEquals(1, failure.getAttemptCount());
            assertEquals("attempt-timeout-key", failure.getIdempotencyKey());
            assertTrue(pending.isCancelled());
            assertEquals(1, transport.executeCalls.get());
        } finally {
            runtime.close();
        }
    }

    @Test
    void operationDeadlineWinsAnExactTieWithTheAttemptTimeout() {
        MutableMonotonicClock clock = new MutableMonotonicClock();
        ManualExecutor executor = new ManualExecutor();
        ManualScheduler scheduler = new ManualScheduler();
        CompletableFuture<TransportResponse> pending = new CompletableFuture<>();
        ScriptedTransport transport = new ScriptedTransport(pending);
        RepostRuntime runtime = create(runtimeBuilder(clock, executor, scheduler, transport)
                .maxAttempts(1)
                .attemptTimeout(Duration.ofSeconds(1))
                .operationTimeout(Duration.ofSeconds(1)));
        try {
            SendOperation operation = send(runtime, "deadline-tie-key");
            executor.runNext();

            clock.advance(Duration.ofSeconds(1));
            scheduler.runLatestTaskWithDelay(Duration.ofSeconds(1));

            RepostException failure = operationFailure(operation);
            assertEquals(RepostErrorCode.OPERATION_DEADLINE, failure.getErrorCode());
            assertEquals(DeliveryState.POSSIBLY_SENT, failure.getDeliveryState());
            assertEquals(1, failure.getAttemptCount());
            assertEquals("deadline-tie-key", failure.getIdempotencyKey());
            assertTrue(pending.isCancelled());
        } finally {
            runtime.close();
        }
    }

    @Test
    void monotonicClockFailureAfterACommittedAttemptPreservesReconciliationEvidence() {
        MutableMonotonicClock clock = new MutableMonotonicClock();
        ManualExecutor executor = new ManualExecutor();
        ManualScheduler scheduler = new ManualScheduler();
        CompletableFuture<TransportResponse> pending = new CompletableFuture<>();
        ScriptedTransport transport = new ScriptedTransport(pending);
        RepostRuntime runtime = create(runtimeBuilder(clock, executor, scheduler, transport)
                .maxAttempts(2));
        try {
            SendOperation operation = send(runtime, "clock-evidence-key");
            executor.runNext();

            clock.failNextCall();
            pending.complete(TransportResponse.of(
                    503,
                    Collections.emptyList(),
                    new ByteArrayInputStream(new byte[0])));
            executor.runNext();

            RepostConfigurationException failure =
                    (RepostConfigurationException) operationFailure(operation);
            assertEquals(RepostErrorCode.CONFIGURATION, failure.getErrorCode());
            assertEquals(DeliveryState.POSSIBLY_SENT, failure.getDeliveryState());
            assertEquals(1, failure.getAttemptCount());
            assertEquals("clock-evidence-key", failure.getIdempotencyKey());
            assertEquals(
                    Collections.singletonList(ClientOptionKey.MONOTONIC_CLOCK),
                    failure.getConfigurationIssues().get(0).getOptionKeys());
            assertEquals(0, scheduler.retryCount());
        } finally {
            runtime.close();
        }
    }

    @Test
    void sanitizesRetryEntropyFailureWithoutErasingCommittedEvidence() {
        MutableMonotonicClock clock = new MutableMonotonicClock();
        ManualExecutor executor = new ManualExecutor();
        ManualScheduler scheduler = new ManualScheduler();
        ScriptedTransport transport = new ScriptedTransport(response(500));
        RepostRuntime runtime = create(runtimeBuilder(clock, executor, scheduler, transport)
                .maxAttempts(2)
                .retryEntropy(ignored -> {
                    throw new IllegalStateException("sentinel-entropy-failure");
                }));
        try {
            SendOperation operation = send(runtime, "entropy-key");
            executor.runNext();
            executor.runNext();

            RepostConfigurationException failure =
                    (RepostConfigurationException) operationFailure(operation);
            assertEquals(DeliveryState.POSSIBLY_SENT, failure.getDeliveryState());
            assertEquals(1, failure.getAttemptCount());
            assertEquals(Integer.valueOf(500), failure.getHttpStatus());
            assertEquals("entropy-key", failure.getIdempotencyKey());
            assertEquals(RepostCauseCategory.RETRY_ENTROPY, failure.getCauseCategory());
            assertEquals(
                    Collections.singletonList(ClientOptionKey.RETRY_ENTROPY),
                    failure.getConfigurationIssues().get(0).getOptionKeys());
            assertEquals(0, scheduler.retryCount());
            assertFalse(failure.toString().contains("sentinel-entropy-failure"));
        } finally {
            runtime.close();
        }
    }

    private static ClientOptions.Builder runtimeBuilder(
            MutableMonotonicClock clock,
            ManualExecutor executor,
            ManualScheduler scheduler,
            ScriptedTransport transport) {
        return ClientOptions.builder()
                .apiKey("test-key")
                .baseUri("https://example.com")
                .defaultValueGenerators(GENERATORS)
                .monotonicClock(clock)
                .executor(executor)
                .scheduler(scheduler)
                .transport(transport)
                .operationTimeout(Duration.ofSeconds(30));
    }

    private static RepostRuntime create(ClientOptions.Builder builder) {
        return RepostRuntime.create(builder.build());
    }

    private static SendOperation send(RepostRuntime runtime, String idempotencyKey) {
        SchemaDescriptor schema = SchemaDescriptor.builder(2)
                .addModel(ModelDescriptor.of("Payload", Collections.emptyList()))
                .addEvent("events", "created", EventDescriptor.of("contract.sent", "Payload"))
                .build();
        SendOptions options = idempotencyKey == null
                ? SendOptions.defaults()
                : SendOptions.builder().idempotencyKey(idempotencyKey).build();
        return runtime.sendAsync(
                schema,
                schema.getWebhooks().get("events").get("created"),
                "cus_contract",
                new RepostModel() {
                    @Override public boolean __repostIsPresent(int fieldIndex) { return false; }
                    @Override public Object __repostValue(int fieldIndex) { return null; }
                },
                options);
    }

    private static CompletableFuture<TransportResponse> failed(Throwable failure) {
        CompletableFuture<TransportResponse> stage = new CompletableFuture<>();
        stage.completeExceptionally(failure);
        return stage;
    }

    private static ResponseStep response(int status, TransportHeaderField... headers) {
        return new ResponseStep(status, Arrays.asList(headers), new byte[0]);
    }

    private static ResponseStep acceptedResponse(String id) {
        return acceptedResponse(
                id, "contract.sent", "cus_contract", "1970-01-01T00:00:00.000Z");
    }

    private static ResponseStep acceptedResponse(
            String id,
            String type,
            String customerId,
            String timestamp) {
        String body = "{\"id\":\"" + id + "\",\"type\":\"" + type + "\","
                + "\"customerId\":\"" + customerId + "\","
                + "\"timestamp\":\"" + timestamp + "\"}";
        return new ResponseStep(
                202,
                Collections.singletonList(
                        TransportHeaderField.of("Content-Type", "application/json")),
                body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static TransportHeaderField header(String name, String value) {
        return TransportHeaderField.of(name, value);
    }

    private static String header(TransportRequest request, String name) {
        for (TransportHeaderField field : request.getHeaderFields()) {
            if (field.getName().equalsIgnoreCase(name)) {
                return field.getValue();
            }
        }
        throw new AssertionError("missing header " + name);
    }

    private static byte[] bytes(ByteBuffer source) {
        ByteBuffer copy = source.asReadOnlyBuffer();
        byte[] result = new byte[copy.remaining()];
        copy.get(result);
        return result;
    }

    private static RepostException operationFailure(SendOperation operation) {
        try {
            operation.get();
            throw new AssertionError("expected operation failure");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        } catch (ExecutionException exception) {
            assertNotNull(exception.getCause());
            return (RepostException) exception.getCause();
        }
    }

    private static SendResult operationResult(SendOperation operation) {
        try {
            return operation.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        } catch (ExecutionException exception) {
            throw new AssertionError("expected operation success", exception.getCause());
        }
    }

    private static final class ScriptedTransport implements Transport {
        private final Queue<Object> steps = new ArrayDeque<>();
        private final List<TransportRequest> requests = new ArrayList<>();
        private final List<TrackingInputStream> responseBodies = new ArrayList<>();
        private final AtomicInteger executeCalls = new AtomicInteger();

        private ScriptedTransport(Object... steps) {
            this.steps.addAll(Arrays.asList(steps));
        }

        @Override
        @SuppressWarnings("unchecked")
        public CompletionStage<TransportResponse> execute(TransportRequest request) {
            executeCalls.incrementAndGet();
            requests.add(request);
            Object step = steps.remove();
            if (step instanceof CompletionStage<?>) {
                return (CompletionStage<TransportResponse>) step;
            }
            ResponseStep response = (ResponseStep) step;
            TrackingInputStream body = new TrackingInputStream(response.body);
            responseBodies.add(body);
            return CompletableFuture.completedFuture(TransportResponse.of(
                    response.status, response.headers, body));
        }

        private boolean responsesClosed() {
            for (TrackingInputStream body : responseBodies) {
                if (!body.closed) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final class ResponseStep {
        private final int status;
        private final List<TransportHeaderField> headers;
        private final byte[] body;

        private ResponseStep(
                int status,
                List<TransportHeaderField> headers,
                byte[] body) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }
    }

    private static final class TrackingInputStream extends ByteArrayInputStream {
        private boolean closed;

        private TrackingInputStream(byte[] body) {
            super(body);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class MutableMonotonicClock implements MonotonicClock {
        private long now;
        private boolean failNextCall;

        @Override public long nanoTime() {
            if (failNextCall) {
                failNextCall = false;
                throw new IllegalStateException("sentinel-clock-failure");
            }
            return now;
        }

        private void advance(Duration duration) {
            now += duration.toNanos();
        }

        private void failNextCall() {
            failNextCall = true;
        }
    }

    private static final class ManualExecutor extends AbstractExecutorService {
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        private boolean shutdown;

        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() {
            shutdown = true;
            ArrayList<Runnable> pending = new ArrayList<>(tasks);
            tasks.clear();
            return pending;
        }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown && tasks.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) {
            return isTerminated();
        }
        @Override public void execute(Runnable command) { tasks.add(command); }

        private void runNext() {
            Runnable next = tasks.poll();
            if (next == null) {
                throw new AssertionError("expected one executor task");
            }
            next.run();
        }
    }

    private static final class ManualScheduler extends AbstractExecutorService
            implements ScheduledExecutorService {
        private final List<ManualTask<?>> tasks = new ArrayList<>();
        private boolean shutdown;

        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() {
            shutdown = true;
            ArrayList<Runnable> pending = new ArrayList<>(tasks);
            tasks.clear();
            return pending;
        }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown && tasks.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) {
            return isTerminated();
        }
        @Override public void execute(Runnable command) {
            schedule(command, 0L, TimeUnit.NANOSECONDS);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            ManualTask<Void> task = new ManualTask<>(command, unit.toNanos(delay));
            tasks.add(task);
            return task;
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            ManualTask<V> task = new ManualTask<>(callable, unit.toNanos(delay));
            tasks.add(task);
            return task;
        }

        @Override public ScheduledFuture<?> scheduleAtFixedRate(
                Runnable command, long initialDelay, long period, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }
        @Override public ScheduledFuture<?> scheduleWithFixedDelay(
                Runnable command, long initialDelay, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        private int retryCount() {
            int count = 0;
            for (ManualTask<?> task : tasks) {
                if (!task.cancelled && task.delayNanos < Duration.ofSeconds(30).toNanos()) {
                    count++;
                }
            }
            return count;
        }

        private Duration latestRetryDelay() {
            return Duration.ofNanos(latestRetry().delayNanos);
        }

        private void runLatestRetry() {
            latestRetry().run();
        }

        private void runDeadline() {
            for (ManualTask<?> task : tasks) {
                if (!task.cancelled && task.delayNanos >= Duration.ofSeconds(1).toNanos()) {
                    task.run();
                    return;
                }
            }
            throw new AssertionError("expected one deadline task");
        }

        private void runTaskWithDelay(Duration delay) {
            long expectedNanos = delay.toNanos();
            for (ManualTask<?> task : tasks) {
                if (!task.cancelled && task.delayNanos == expectedNanos) {
                    task.run();
                    return;
                }
            }
            throw new AssertionError("expected one task delayed by " + delay);
        }

        private void runLatestTaskWithDelay(Duration delay) {
            long expectedNanos = delay.toNanos();
            for (int index = tasks.size() - 1; index >= 0; index--) {
                ManualTask<?> task = tasks.get(index);
                if (!task.cancelled && task.delayNanos == expectedNanos) {
                    task.run();
                    return;
                }
            }
            throw new AssertionError("expected one task delayed by " + delay);
        }

        private ManualTask<?> latestRetry() {
            for (int index = tasks.size() - 1; index >= 0; index--) {
                ManualTask<?> task = tasks.get(index);
                if (!task.cancelled && task.delayNanos < Duration.ofSeconds(30).toNanos()) {
                    return task;
                }
            }
            throw new AssertionError("expected one retry task");
        }
    }

    private static final class ManualTask<V> extends FutureTask<V>
            implements ScheduledFuture<V> {
        private final long delayNanos;
        private boolean cancelled;

        private ManualTask(Runnable runnable, long delayNanos) {
            super(runnable, null);
            this.delayNanos = delayNanos;
        }

        private ManualTask(Callable<V> callable, long delayNanos) {
            super(callable);
            this.delayNanos = delayNanos;
        }

        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled = true;
            return super.cancel(mayInterruptIfRunning);
        }
        @Override public long getDelay(TimeUnit unit) {
            return unit.convert(delayNanos, TimeUnit.NANOSECONDS);
        }
        @Override public int compareTo(Delayed other) { return this == other ? 0 : -1; }
    }
}
