package sh.repost.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import sh.repost.client.descriptor.EventDescriptor;
import sh.repost.client.descriptor.ModelDescriptor;
import sh.repost.client.descriptor.SchemaDescriptor;
import sh.repost.client.error.RepostConfigurationException;

final class RuntimeObservabilityContractTest {
    @Test
    void emitsOrderedObserverEventsAndLiveTelemetryForARealSend() throws Exception {
        ExecutorService operationExecutor = Executors.newSingleThreadExecutor();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        RecordingTelemetry telemetry = new RecordingTelemetry();
        CountDownLatch terminalEvent = new CountDownLatch(1);
        List<ObserverEvent> events = Collections.synchronizedList(new ArrayList<>());
        RepostObserver observer = event -> {
            events.add(event);
            if (event.getKind() == ObserverEventKind.OPERATION_END) {
                terminalEvent.countDown();
            }
        };
        Transport transport = request -> java.util.concurrent.CompletableFuture.completedFuture(
                TransportResponse.of(
                        202,
                        Collections.singletonList(
                                TransportHeaderField.of("Content-Type", "application/json")),
                        new ByteArrayInputStream(acceptedBody())));
        RepostRuntime runtime = RepostRuntime.create(ClientOptions.builder()
                .apiKey("test-key")
                .baseUri("https://example.com")
                .defaultValueGenerators(DefaultValueGenerators.fixed(
                        Instant.EPOCH,
                        "12345678-1234-4234-9234-123456789abc",
                        "abcdefghijklmnopqrstuvwx"))
                .wallClock(() -> Instant.EPOCH)
                .executor(operationExecutor)
                .scheduler(scheduler)
                .transport(transport)
                .observer(observer)
                .telemetry(telemetry)
                .build());
        try {
            SendResult result = send(runtime).toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertEquals("msg_observed", result.getId());
            assertTrue(terminalEvent.await(2, TimeUnit.SECONDS));
            assertEquals(
                    java.util.Arrays.asList(
                            ObserverEventKind.OPERATION_START,
                            ObserverEventKind.ATTEMPT_START,
                            ObserverEventKind.ATTEMPT_END,
                            ObserverEventKind.OPERATION_END),
                    kinds(events));
            assertNull(events.get(0).getOutcome());
            assertNull(events.get(0).getDeliveryState());
            assertNull(events.get(1).getOutcome());
            assertNull(events.get(1).getDeliveryState());
            assertEquals(ObserverOutcome.ACCEPTED, events.get(2).getOutcome());
            assertEquals(DeliveryState.ACCEPTED, events.get(2).getDeliveryState());
            assertEquals(1, events.get(3).getAttemptSummaries().size());
            assertEquals(1, telemetry.captureCalls.get());
            assertEquals(1, telemetry.operationStarts.get());
            assertEquals(1, telemetry.attemptStarts.get());
            assertEquals(1, telemetry.attemptEnds.get());
            assertEquals(1, telemetry.operationEnds.get());
            assertEquals(0, telemetry.liveHandles.get());
            assertFalse(telemetry.callbackOnCallerThread);
            assertEquals(0L, runtime.diagnostics().getDroppedObserverEvents());
            assertEquals(0L, runtime.diagnostics().getObserverFailures());
            assertEquals(0L, runtime.diagnostics().getTelemetryFailures());
        } finally {
            runtime.close();
            operationExecutor.shutdownNow();
            scheduler.shutdownNow();
        }
    }

    @Test
    void emitsRetryAsANonterminalEventBetweenCompletedAttempts() throws Exception {
        ExecutorService operationExecutor = Executors.newSingleThreadExecutor();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch terminalEvent = new CountDownLatch(1);
        List<ObserverEvent> events = Collections.synchronizedList(new ArrayList<>());
        RepostObserver observer = event -> {
            events.add(event);
            if (event.getKind() == ObserverEventKind.OPERATION_END) {
                terminalEvent.countDown();
            }
        };
        Transport transport = request -> java.util.concurrent.CompletableFuture.completedFuture(
                TransportResponse.of(
                        attempts.incrementAndGet() == 1 ? 500 : 202,
                        Collections.singletonList(
                                TransportHeaderField.of("Content-Type", "application/json")),
                        new ByteArrayInputStream(acceptedBody())));
        RepostRuntime runtime = RepostRuntime.create(ClientOptions.builder()
                .apiKey("test-key")
                .baseUri("https://example.com")
                .defaultValueGenerators(DefaultValueGenerators.fixed(
                        Instant.EPOCH,
                        "12345678-1234-4234-9234-123456789abc",
                        "abcdefghijklmnopqrstuvwx"))
                .executor(operationExecutor)
                .scheduler(scheduler)
                .transport(transport)
                .maxAttempts(2)
                .retryEntropy(bound -> 0L)
                .observer(observer)
                .build());
        try {
            assertEquals(
                    "msg_observed",
                    send(runtime).toCompletableFuture().get(2, TimeUnit.SECONDS).getId());
            assertTrue(terminalEvent.await(2, TimeUnit.SECONDS));
            assertEquals(
                    java.util.Arrays.asList(
                            ObserverEventKind.OPERATION_START,
                            ObserverEventKind.ATTEMPT_START,
                            ObserverEventKind.ATTEMPT_END,
                            ObserverEventKind.RETRY_DELAY,
                            ObserverEventKind.ATTEMPT_START,
                            ObserverEventKind.ATTEMPT_END,
                            ObserverEventKind.OPERATION_END),
                    kinds(events));
            ObserverEvent retry = events.get(3);
            assertNull(retry.getOutcome());
            assertEquals(RepostErrorCode.SERVER_FAILURE, retry.getErrorCode());
            assertEquals(Duration.ZERO, retry.getRetryDelay());
            assertEquals(2, events.get(6).getAttemptSummaries().size());
        } finally {
            runtime.close();
            operationExecutor.shutdownNow();
            scheduler.shutdownNow();
        }
    }

    @Test
    void retrospectivelyStartsAndEndsTelemetryWhenCancelledBeforeWorkerStart() throws Exception {
        HoldingExecutor operationExecutor = new HoldingExecutor();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        RecordingTelemetry telemetry = new RecordingTelemetry();
        RepostRuntime runtime = RepostRuntime.create(ClientOptions.builder()
                .apiKey("test-key")
                .baseUri("https://example.com")
                .executor(operationExecutor)
                .scheduler(scheduler)
                .transport(request -> {
                    throw new AssertionError("cancelled operation reached transport");
                })
                .telemetry(telemetry)
                .build());
        try {
            SendOperation operation = send(runtime);

            assertTrue(operation.toCompletableFuture().cancel(false));
            assertTrue(operation.toCompletableFuture().isCancelled());
            assertEquals(1, telemetry.captureCalls.get());
            assertEquals(1, telemetry.operationStarts.get());
            assertEquals(0, telemetry.attemptStarts.get());
            assertEquals(0, telemetry.attemptEnds.get());
            assertEquals(1, telemetry.operationEnds.get());
            assertEquals(0, telemetry.liveHandles.get());
            assertFalse(telemetry.callbackOnCallerThread);
        } finally {
            runtime.close();
            operationExecutor.shutdownNow();
            scheduler.shutdownNow();
        }
    }

    @Test
    void recoverableFatalTelemetryCompletesBothStagesAndClosesTheRuntime() throws Exception {
        HoldingExecutor operationExecutor = new HoldingExecutor();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        LinkageError fatal = new LinkageError("fatal telemetry sentinel");
        UncaughtFatalRecorder uncaught = new UncaughtFatalRecorder();
        RepostTelemetry telemetry = new RepostTelemetry() {
            @Override
            public CapturedTelemetryContext captureContext() {
                return new CapturedTelemetryContext() { };
            }

            @Override
            public TelemetryOperation startOperation(
                    CapturedTelemetryContext parent,
                    TelemetryOperationStart start) {
                throw fatal;
            }
        };
        RepostRuntime runtime = RepostRuntime.create(ClientOptions.builder()
                .apiKey("test-key")
                .baseUri("https://example.com")
                .executor(operationExecutor)
                .scheduler(scheduler)
                .transport(request -> {
                    throw new AssertionError("fatal operation reached transport");
                })
                .telemetry(telemetry)
                .build());
        try {
            SendOperation operation = send(runtime);

            assertTrue(operation.toCompletableFuture().cancel(false));
            assertSame(fatal, exceptionalCause(operation));
            assertSame(fatal, exceptionalCause(operation.outcome()));
            runtime.closeCompletion().toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertTrue(runtime.isClosed());
            uncaught.assertDelivered(fatal);
        } finally {
            uncaught.close();
            runtime.close();
            operationExecutor.shutdownNow();
            scheduler.shutdownNow();
        }
    }

    @Test
    void recoverableFatalTelemetryCaptureDoesNotEscapeTheCaller() throws Exception {
        ExecutorService operationExecutor = Executors.newSingleThreadExecutor();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        AssertionError fatal = new AssertionError("fatal capture sentinel");
        UncaughtFatalRecorder uncaught = new UncaughtFatalRecorder();
        RepostRuntime runtime = RepostRuntime.create(ClientOptions.builder()
                .apiKey("test-key")
                .baseUri("https://example.com")
                .executor(operationExecutor)
                .scheduler(scheduler)
                .transport(request -> {
                    throw new AssertionError("fatal capture reached transport");
                })
                .telemetry(new RepostTelemetry() {
                    @Override
                    public CapturedTelemetryContext captureContext() {
                        throw fatal;
                    }

                    @Override
                    public TelemetryOperation startOperation(
                            CapturedTelemetryContext parent,
                            TelemetryOperationStart start) {
                        throw new AssertionError("fatal capture started telemetry");
                    }
                })
                .build());
        try {
            SendOperation operation = send(runtime);

            assertSame(fatal, exceptionalCause(operation));
            assertSame(fatal, exceptionalCause(operation.outcome()));
            runtime.closeCompletion().toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertTrue(runtime.isClosed());
            uncaught.assertDelivered(fatal);
        } finally {
            uncaught.close();
            runtime.close();
            operationExecutor.shutdownNow();
            scheduler.shutdownNow();
        }
    }

    @Test
    void recoverableFatalObserverCompletesTheOriginatingOperationAndClosesRuntime()
            throws Exception {
        ExecutorService operationExecutor = Executors.newSingleThreadExecutor();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        AssertionError fatal = new AssertionError("fatal observer sentinel");
        UncaughtFatalRecorder uncaught = new UncaughtFatalRecorder();
        RepostRuntime runtime = RepostRuntime.create(ClientOptions.builder()
                .apiKey("test-key")
                .baseUri("https://example.com")
                .executor(operationExecutor)
                .scheduler(scheduler)
                .transport(request -> new java.util.concurrent.CompletableFuture<>())
                .observer(event -> {
                    throw fatal;
                })
                .build());
        try {
            SendOperation operation = send(runtime);

            assertSame(fatal, exceptionalCause(operation));
            assertSame(fatal, exceptionalCause(operation.outcome()));
            runtime.closeCompletion().toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertTrue(runtime.isClosed());
            uncaught.assertDelivered(fatal);
            assertEquals(0L, runtime.diagnostics().getObserverFailures());
        } finally {
            uncaught.close();
            runtime.close();
            operationExecutor.shutdownNow();
            scheduler.shutdownNow();
        }
    }

    @Test
    void blockedObserverCannotDelaySendAndCloseInterruptsItsDispatcher() throws Exception {
        ExecutorService operationExecutor = Executors.newSingleThreadExecutor();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        RepostObserver observer = event -> {
            entered.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException expected) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
        };
        Transport transport = request -> java.util.concurrent.CompletableFuture.completedFuture(
                TransportResponse.of(
                        202,
                        Collections.singletonList(
                                TransportHeaderField.of("Content-Type", "application/json")),
                        new ByteArrayInputStream(acceptedBody())));
        RepostRuntime runtime = RepostRuntime.create(ClientOptions.builder()
                .apiKey("test-key")
                .baseUri("https://example.com")
                .defaultValueGenerators(DefaultValueGenerators.fixed(
                        Instant.EPOCH,
                        "12345678-1234-4234-9234-123456789abc",
                        "abcdefghijklmnopqrstuvwx"))
                .executor(operationExecutor)
                .scheduler(scheduler)
                .transport(transport)
                .observer(observer)
                .build());
        try {
            assertEquals(
                    "msg_observed",
                    send(runtime).toCompletableFuture().get(2, TimeUnit.SECONDS).getId());
            assertTrue(entered.await(2, TimeUnit.SECONDS));

            runtime.close();

            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            assertTrue(runtime.diagnostics().getDroppedObserverEvents() > 0L);
        } finally {
            runtime.close();
            operationExecutor.shutdownNow();
            scheduler.shutdownNow();
        }
    }

    @Test
    void fullObserverQueueDropsEventsWithoutBackpressuringSends() throws Exception {
        ExecutorService operationExecutor = Executors.newFixedThreadPool(4);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        CountDownLatch observerEntered = new CountDownLatch(1);
        CountDownLatch releaseObserver = new CountDownLatch(1);
        RepostObserver observer = event -> {
            observerEntered.countDown();
            try {
                releaseObserver.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        };
        Transport transport = request -> java.util.concurrent.CompletableFuture.completedFuture(
                TransportResponse.of(
                        202,
                        Collections.singletonList(
                                TransportHeaderField.of("Content-Type", "application/json")),
                        new ByteArrayInputStream(acceptedBody())));
        RepostRuntime runtime = RepostRuntime.create(ClientOptions.builder()
                .apiKey("test-key")
                .baseUri("https://example.com")
                .defaultValueGenerators(DefaultValueGenerators.fixed(
                        Instant.EPOCH,
                        "12345678-1234-4234-9234-123456789abc",
                        "abcdefghijklmnopqrstuvwx"))
                .executor(operationExecutor)
                .scheduler(scheduler)
                .transport(transport)
                .observer(observer)
                .build());
        try {
            assertEquals(
                    "msg_observed",
                    send(runtime).toCompletableFuture().get(2, TimeUnit.SECONDS).getId());
            assertTrue(observerEntered.await(2, TimeUnit.SECONDS));
            for (int index = 0; index < 300; index++) {
                assertEquals(
                        "msg_observed",
                        send(runtime).toCompletableFuture().get(2, TimeUnit.SECONDS).getId());
            }
            assertTrue(runtime.diagnostics().getDroppedObserverEvents() > 0L);
        } finally {
            releaseObserver.countDown();
            runtime.close();
            operationExecutor.shutdownNow();
            scheduler.shutdownNow();
        }
    }

    @Test
    void observerExecutorRejectionDropsCallbacksAndRemainsBorrowed() throws Exception {
        ExecutorService operationExecutor = Executors.newSingleThreadExecutor();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        RejectingExecutor observerExecutor = new RejectingExecutor();
        RepostRuntime runtime = RepostRuntime.create(ClientOptions.builder()
                .apiKey("test-key")
                .baseUri("https://example.com")
                .defaultValueGenerators(DefaultValueGenerators.fixed(
                        Instant.EPOCH,
                        "12345678-1234-4234-9234-123456789abc",
                        "abcdefghijklmnopqrstuvwx"))
                .executor(operationExecutor)
                .scheduler(scheduler)
                .transport(request -> java.util.concurrent.CompletableFuture.completedFuture(
                        TransportResponse.of(
                                202,
                                Collections.singletonList(
                                        TransportHeaderField.of("Content-Type", "application/json")),
                                new ByteArrayInputStream(acceptedBody()))))
                .observer(event -> { })
                .observerExecutor(observerExecutor)
                .build());
        try {
            assertEquals(
                    "msg_observed",
                    send(runtime).toCompletableFuture().get(2, TimeUnit.SECONDS).getId());
            assertTrue(observerExecutor.rejection.await(2, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (runtime.diagnostics().getDroppedObserverEvents() == 0L
                    && System.nanoTime() < deadline) {
                Thread.sleep(1L);
            }
            assertTrue(runtime.diagnostics().getDroppedObserverEvents() > 0L);
        } finally {
            runtime.close();
            operationExecutor.shutdownNow();
            scheduler.shutdownNow();
        }
        assertFalse(observerExecutor.isShutdown());
    }

    @Test
    void observerRuntimeExceptionsAreCountedWithoutChangingTheSend() throws Exception {
        ExecutorService operationExecutor = Executors.newSingleThreadExecutor();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        CountDownLatch callbacks = new CountDownLatch(4);
        RepostRuntime runtime = RepostRuntime.create(ClientOptions.builder()
                .apiKey("test-key")
                .baseUri("https://example.com")
                .defaultValueGenerators(DefaultValueGenerators.fixed(
                        Instant.EPOCH,
                        "12345678-1234-4234-9234-123456789abc",
                        "abcdefghijklmnopqrstuvwx"))
                .executor(operationExecutor)
                .scheduler(scheduler)
                .transport(request -> java.util.concurrent.CompletableFuture.completedFuture(
                        TransportResponse.of(
                                202,
                                Collections.singletonList(
                                        TransportHeaderField.of("Content-Type", "application/json")),
                                new ByteArrayInputStream(acceptedBody()))))
                .observer(event -> {
                    callbacks.countDown();
                    throw new IllegalStateException("observer sentinel");
                })
                .build());
        try {
            assertEquals(
                    "msg_observed",
                    send(runtime).toCompletableFuture().get(2, TimeUnit.SECONDS).getId());
            assertTrue(callbacks.await(2, TimeUnit.SECONDS));
            runtime.close();
            assertEquals(4L, runtime.diagnostics().getObserverFailures());
        } finally {
            runtime.close();
            operationExecutor.shutdownNow();
            scheduler.shutdownNow();
        }
    }

    @Test
    void observerAndTelemetrySlotsAreIdentitySingleAssignment() {
        RepostObserver observer = event -> { };
        RepostTelemetry telemetry = new RecordingTelemetry();
        ClientOptions.Builder empty = ClientOptions.builder();
        assertFalse(empty.hasObserver());
        assertFalse(empty.hasTelemetry());

        ClientOptions.Builder configured = ClientOptions.builder()
                .apiKey("test-key")
                .observer(observer)
                .observer(observer)
                .telemetry(telemetry)
                .telemetry(telemetry);
        assertTrue(configured.hasObserver());
        assertTrue(configured.hasTelemetry());
        ClientOptions sameIdentity = configured.build();
        assertSame(observer, sameIdentity.observer());
        assertSame(telemetry, sameIdentity.telemetry());
        assertThrows(
                RepostConfigurationException.class,
                () -> ClientOptions.builder()
                        .apiKey("test-key")
                        .observer(observer)
                        .observer(event -> { })
                        .build());
        assertThrows(
                RepostConfigurationException.class,
                () -> ClientOptions.builder()
                        .apiKey("test-key")
                        .telemetry(telemetry)
                        .telemetry(new RecordingTelemetry())
                        .build());
    }

    @Test
    void everyNonfatalTelemetryHookFailureIsCountedWithoutChangingTheSend() throws Exception {
        for (TelemetryFailurePoint point : TelemetryFailurePoint.values()) {
            ExecutorService operationExecutor = Executors.newSingleThreadExecutor();
            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
            FailingTelemetry telemetry = new FailingTelemetry(point);
            RepostRuntime runtime = RepostRuntime.create(ClientOptions.builder()
                    .apiKey("test-key")
                    .baseUri("https://example.com")
                    .defaultValueGenerators(DefaultValueGenerators.fixed(
                            Instant.EPOCH,
                            "12345678-1234-4234-9234-123456789abc",
                            "abcdefghijklmnopqrstuvwx"))
                    .executor(operationExecutor)
                    .scheduler(scheduler)
                    .transport(request -> java.util.concurrent.CompletableFuture.completedFuture(
                            TransportResponse.of(
                                    202,
                                    Collections.singletonList(TransportHeaderField.of(
                                            "Content-Type", "application/json")),
                                    new ByteArrayInputStream(acceptedBody()))))
                    .telemetry(telemetry)
                    .build());
            try {
                assertEquals(
                        "msg_observed",
                        send(runtime).toCompletableFuture().get(2, TimeUnit.SECONDS).getId(),
                        point.name());
                assertEquals(
                        point == TelemetryFailurePoint.ATTEMPT_SCOPE ? 2L : 1L,
                        runtime.diagnostics().getTelemetryFailures(),
                        point.name());
                assertEquals(0, telemetry.liveHandles.get(), point.name());
            } finally {
                runtime.close();
                operationExecutor.shutdownNow();
                scheduler.shutdownNow();
            }
        }
    }

    @Test
    void lateContinuationCannotReenterTelemetryAfterTerminalCleanup() {
        ScopeCountingTelemetry telemetry = new ScopeCountingTelemetry();
        RuntimeObservability observability = new RuntimeObservability(
                null,
                null,
                telemetry,
                () -> 0L,
                () -> Instant.EPOCH,
                (operationId, fatal) -> { throw fatal; });
        String operationId = "op_00000000-0000-4000-8000-000000000000";
        RuntimeObservability.Operation operation = observability.begin(operationId, 0L);
        operation.startWorker();
        operation.startAttempt(1);
        operation.finish(SendOutcome.accepted(operationId, 1, "idem_test", 202), false);
        int scopesAtTerminal = telemetry.scopeCalls.get();

        operation.makeAttemptCurrent().close();
        operation.makeOperationCurrent().close();

        assertEquals(scopesAtTerminal, telemetry.scopeCalls.get());
        assertEquals(0, telemetry.liveHandles.get());
    }

    private static SendOperation send(RepostRuntime runtime) {
        SchemaDescriptor schema = SchemaDescriptor.builder(2)
                .addModel(ModelDescriptor.of("Payload", Collections.emptyList()))
                .addEvent("events", "created", EventDescriptor.of("contract.sent", "Payload"))
                .build();
        return runtime.sendAsync(
                schema,
                schema.getWebhooks().get("events").get("created"),
                "cus_contract",
                new RepostModel() {
                    @Override public boolean __repostIsPresent(int fieldIndex) { return false; }
                    @Override public Object __repostValue(int fieldIndex) { return null; }
                },
                SendOptions.builder().idempotencyKey("observability-key").build());
    }

    private static byte[] acceptedBody() {
        return ("{\"id\":\"msg_observed\",\"type\":\"contract.sent\","
                + "\"customerId\":\"cus_contract\","
                + "\"timestamp\":\"1970-01-01T00:00:00.000Z\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static Throwable exceptionalCause(java.util.concurrent.CompletionStage<?> stage)
            throws Exception {
        try {
            stage.toCompletableFuture().get(2, TimeUnit.SECONDS);
            throw new AssertionError("expected exceptional completion");
        } catch (ExecutionException failure) {
            return failure.getCause();
        }
    }

    private static List<ObserverEventKind> kinds(List<ObserverEvent> events) {
        ArrayList<ObserverEventKind> result = new ArrayList<>();
        synchronized (events) {
            for (ObserverEvent event : events) {
                result.add(event.getKind());
            }
        }
        return result;
    }

    private static final class RecordingTelemetry implements RepostTelemetry {
        private final Thread caller = Thread.currentThread();
        private final AtomicInteger captureCalls = new AtomicInteger();
        private final AtomicInteger operationStarts = new AtomicInteger();
        private final AtomicInteger attemptStarts = new AtomicInteger();
        private final AtomicInteger attemptEnds = new AtomicInteger();
        private final AtomicInteger operationEnds = new AtomicInteger();
        private final AtomicInteger liveHandles = new AtomicInteger();
        private volatile boolean callbackOnCallerThread;

        @Override
        public CapturedTelemetryContext captureContext() {
            captureCalls.incrementAndGet();
            return new CapturedTelemetryContext() { };
        }

        @Override
        public TelemetryOperation startOperation(
                CapturedTelemetryContext parent,
                TelemetryOperationStart start) {
            callbackOnCallerThread |= Thread.currentThread() == caller;
            operationStarts.incrementAndGet();
            liveHandles.incrementAndGet();
            return new TelemetryOperation() {
                @Override
                public TelemetryScope makeCurrent() {
                    return () -> { };
                }

                @Override
                public TelemetryAttempt startAttempt(TelemetryAttemptStart attemptStart) {
                    attemptStarts.incrementAndGet();
                    liveHandles.incrementAndGet();
                    return new TelemetryAttempt() {
                        @Override
                        public TelemetryScope makeCurrent() {
                            return () -> { };
                        }

                        @Override
                        public void end(TelemetryAttemptEnd end) {
                            attemptEnds.incrementAndGet();
                            liveHandles.decrementAndGet();
                        }
                    };
                }

                @Override
                public void end(TelemetryOperationEnd end) {
                    operationEnds.incrementAndGet();
                    liveHandles.decrementAndGet();
                }
            };
        }
    }

    private enum TelemetryFailurePoint {
        CAPTURE,
        OPERATION_START,
        OPERATION_SCOPE,
        ATTEMPT_START,
        ATTEMPT_SCOPE,
        ATTEMPT_END,
        OPERATION_END
    }

    private static final class FailingTelemetry implements RepostTelemetry {
        private final TelemetryFailurePoint point;
        private final AtomicInteger liveHandles = new AtomicInteger();

        private FailingTelemetry(TelemetryFailurePoint point) {
            this.point = point;
        }

        @Override
        public CapturedTelemetryContext captureContext() {
            failAt(TelemetryFailurePoint.CAPTURE);
            return new CapturedTelemetryContext() { };
        }

        @Override
        public TelemetryOperation startOperation(
                CapturedTelemetryContext parent,
                TelemetryOperationStart start) {
            failAt(TelemetryFailurePoint.OPERATION_START);
            liveHandles.incrementAndGet();
            return new TelemetryOperation() {
                @Override
                public TelemetryScope makeCurrent() {
                    failAt(TelemetryFailurePoint.OPERATION_SCOPE);
                    return () -> { };
                }

                @Override
                public TelemetryAttempt startAttempt(TelemetryAttemptStart attemptStart) {
                    failAt(TelemetryFailurePoint.ATTEMPT_START);
                    liveHandles.incrementAndGet();
                    return new TelemetryAttempt() {
                        @Override
                        public TelemetryScope makeCurrent() {
                            failAt(TelemetryFailurePoint.ATTEMPT_SCOPE);
                            return () -> { };
                        }

                        @Override
                        public void end(TelemetryAttemptEnd end) {
                            liveHandles.decrementAndGet();
                            failAt(TelemetryFailurePoint.ATTEMPT_END);
                        }
                    };
                }

                @Override
                public void end(TelemetryOperationEnd end) {
                    liveHandles.decrementAndGet();
                    failAt(TelemetryFailurePoint.OPERATION_END);
                }
            };
        }

        private void failAt(TelemetryFailurePoint target) {
            if (point == target) {
                throw new IllegalStateException("telemetry sentinel");
            }
        }
    }

    private static final class ScopeCountingTelemetry implements RepostTelemetry {
        private final AtomicInteger scopeCalls = new AtomicInteger();
        private final AtomicInteger liveHandles = new AtomicInteger();

        @Override
        public CapturedTelemetryContext captureContext() {
            return new CapturedTelemetryContext() { };
        }

        @Override
        public TelemetryOperation startOperation(
                CapturedTelemetryContext parent,
                TelemetryOperationStart start) {
            liveHandles.incrementAndGet();
            return new TelemetryOperation() {
                @Override
                public TelemetryScope makeCurrent() {
                    scopeCalls.incrementAndGet();
                    return () -> { };
                }

                @Override
                public TelemetryAttempt startAttempt(TelemetryAttemptStart attemptStart) {
                    liveHandles.incrementAndGet();
                    return new TelemetryAttempt() {
                        @Override
                        public TelemetryScope makeCurrent() {
                            scopeCalls.incrementAndGet();
                            return () -> { };
                        }

                        @Override
                        public void end(TelemetryAttemptEnd end) {
                            liveHandles.decrementAndGet();
                        }
                    };
                }

                @Override
                public void end(TelemetryOperationEnd end) {
                    liveHandles.decrementAndGet();
                }
            };
        }
    }

    private static final class HoldingExecutor extends AbstractExecutorService {
        private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
        private volatile boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            ArrayList<Runnable> remaining = new ArrayList<>();
            Runnable task;
            while ((task = tasks.poll()) != null) {
                remaining.add(task);
            }
            return remaining;
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown && tasks.isEmpty();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return isTerminated();
        }

        @Override
        public void execute(Runnable command) {
            if (shutdown) {
                throw new java.util.concurrent.RejectedExecutionException();
            }
            tasks.add(command);
        }
    }

    private static final class RejectingExecutor extends AbstractExecutorService {
        private final CountDownLatch rejection = new CountDownLatch(1);
        private volatile boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }

        @Override
        public void execute(Runnable command) {
            rejection.countDown();
            throw new java.util.concurrent.RejectedExecutionException("rejected for test");
        }
    }

    private static final class UncaughtFatalRecorder implements AutoCloseable {
        private final Thread.UncaughtExceptionHandler prior =
                Thread.getDefaultUncaughtExceptionHandler();
        private final CountDownLatch delivered = new CountDownLatch(1);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        private UncaughtFatalRecorder() {
            Thread.setDefaultUncaughtExceptionHandler((thread, value) -> {
                if (thread.getName().equals("repost-fatal-shutdown")) {
                    failure.set(value);
                    delivered.countDown();
                } else if (prior != null) {
                    prior.uncaughtException(thread, value);
                }
            });
        }

        private void assertDelivered(Error expected) throws InterruptedException {
            assertTrue(delivered.await(2, TimeUnit.SECONDS));
            assertSame(expected, failure.get());
        }

        @Override
        public void close() {
            Thread.setDefaultUncaughtExceptionHandler(prior);
        }
    }
}
