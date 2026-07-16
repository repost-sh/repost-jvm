package sh.repost.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import sh.repost.client.descriptor.EventDescriptor;
import sh.repost.client.descriptor.ModelDescriptor;
import sh.repost.client.descriptor.SchemaDescriptor;
import sh.repost.client.error.RepostTransportException;

final class RuntimeLifecycleContractTest {
    private static final DefaultValueGenerators GENERATORS = DefaultValueGenerators.fixed(
            Instant.EPOCH,
            "12345678-1234-4234-9234-123456789abc",
            "abcdefghijklmnopqrstuvwx");
    private static final String KEY = "lifecycle-key";

    @Test
    void reentrantCloseNeverWaitsForOrInterruptsItsCurrentOperation() throws Exception {
        AtomicReference<RepostRuntime> runtimeReference = new AtomicReference<>();
        CountDownLatch closeReturned = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        AtomicLong closeElapsedNanos = new AtomicLong();
        AtomicBoolean closedAtReturn = new AtomicBoolean();
        AtomicBoolean closeDoneAtReturn = new AtomicBoolean();
        AtomicBoolean interruptedAtReturn = new AtomicBoolean();
        AtomicLong bufferedAtReturn = new AtomicLong();

        RepostRuntime runtime = RepostRuntime.create(ClientOptions.builder()
                .apiKeyProvider(() -> {
                    RepostRuntime current = runtimeReference.get();
                    long started = System.nanoTime();
                    current.close();
                    closeElapsedNanos.set(System.nanoTime() - started);
                    closedAtReturn.set(current.isClosed());
                    closeDoneAtReturn.set(
                            current.closeCompletion().toCompletableFuture().isDone());
                    interruptedAtReturn.set(Thread.currentThread().isInterrupted());
                    bufferedAtReturn.set(current.diagnostics().getBufferedBytes());
                    closeReturned.countDown();
                    try {
                        releaseProvider.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return "test-key";
                })
                .defaultValueGenerators(GENERATORS)
                .transport(request -> {
                    throw new AssertionError("closed operation reached transport");
                })
                .build());
        runtimeReference.set(runtime);

        SendOperation operation = send(runtime);
        try {
            assertTrue(closeReturned.await(1, TimeUnit.SECONDS));
            assertTrue(closeElapsedNanos.get() < Duration.ofSeconds(1).toNanos());
            assertFalse(closedAtReturn.get());
            assertFalse(closeDoneAtReturn.get());
            assertFalse(interruptedAtReturn.get());
            assertTrue(bufferedAtReturn.get() > 0L);

            SendOperation rejected = send(runtime);
            RepostTransportException rejectedFailure = failure(rejected);
            assertEquals(RepostErrorCode.CLOSED, rejectedFailure.getErrorCode());
            assertEquals(DeliveryState.NOT_SENT, rejectedFailure.getDeliveryState());

            AtomicReference<RepostTransportException> callbackFailure = new AtomicReference<>();
            CompletionStage<Void> closeCallback = runtime.closeCompletion().thenRun(() ->
                    callbackFailure.set(failure(operation)));
            releaseProvider.countDown();
            closeCallback.toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(RepostErrorCode.CLOSED, callbackFailure.get().getErrorCode());
            assertTrue(runtime.isClosed());
            assertEquals(0, runtime.diagnostics().getInFlightOperations());
            assertEquals(0L, runtime.diagnostics().getBufferedBytes());
        } finally {
            releaseProvider.countDown();
        }
    }

    @Test
    void concurrentCloseAttemptsEveryOwnedStageOnceAndSharesOneAggregate() throws Exception {
        ThrowingOwnedExecutor executor = new ThrowingOwnedExecutor();
        ThrowingOwnedScheduler scheduler = new ThrowingOwnedScheduler();
        ThrowingOwnedTransport transport = new ThrowingOwnedTransport();
        RepostRuntime runtime = RepostRuntime.createForTesting(
                ClientOptions.builder().apiKey("test-key").build(),
                ignored -> null,
                executor,
                scheduler,
                transport);
        CompletionStage<Void> closeStage = runtime.closeCompletion();
        ExecutorService callers = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Throwable>> calls = new ArrayList<>();
        try {
            for (int index = 0; index < 8; index++) {
                calls.add(callers.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        runtime.close();
                        return null;
                    } catch (Throwable failure) {
                        return failure;
                    }
                }));
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();

            RepostTransportException aggregate = null;
            for (Future<Throwable> call : calls) {
                Throwable observed = call.get(2, TimeUnit.SECONDS);
                assertTrue(observed instanceof RepostTransportException);
                if (aggregate == null) {
                    aggregate = (RepostTransportException) observed;
                } else {
                    assertSame(aggregate, observed);
                }
            }
            assertEquals(RepostErrorCode.IO, aggregate.getErrorCode());
            assertEquals(DeliveryState.NOT_SENT, aggregate.getDeliveryState());
            assertEquals(Arrays.asList(
                            RepostCauseCategory.SCHEDULER_CLOSE,
                            RepostCauseCategory.TRANSPORT_CLOSE,
                            RepostCauseCategory.OPERATION_EXECUTOR_CLOSE),
                    aggregate.getCloseFailureCategories());
            assertEquals(1, scheduler.shutdownCalls.get());
            assertEquals(1, transport.closeCalls.get());
            assertEquals(1, executor.shutdownCalls.get());
            assertTrue(runtime.isClosed());
            assertTrue(runtime.diagnostics().isClosed());
            assertSame(closeStage, runtime.closeCompletion());

            RepostTransportException completionFailure;
            try {
                closeStage.toCompletableFuture().join();
                throw new AssertionError("expected aggregate close failure");
            } catch (CompletionException failure) {
                completionFailure = (RepostTransportException) failure.getCause();
            }
            assertSame(aggregate, completionFailure);
            try {
                runtime.close();
                throw new AssertionError("expected stored aggregate close failure");
            } catch (RepostTransportException later) {
                assertSame(aggregate, later);
            }
        } finally {
            start.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void closePreservesUncertainAttemptEvidenceAndCancelsTheAttempt() throws Exception {
        CountDownLatch attempted = new CountDownLatch(1);
        CompletableFuture<TransportResponse> pending = new CompletableFuture<>();
        RepostRuntime runtime = RepostRuntime.create(ClientOptions.builder()
                .apiKey("test-key")
                .defaultValueGenerators(GENERATORS)
                .idempotencyKeyGenerator(() -> KEY)
                .transport(request -> {
                    attempted.countDown();
                    return pending;
                })
                .build());
        SendOperation operation = send(runtime);
        assertTrue(attempted.await(2, TimeUnit.SECONDS));

        runtime.close();

        RepostTransportException failure = failure(operation);
        assertEquals(RepostErrorCode.CLOSED, failure.getErrorCode());
        assertEquals(DeliveryState.CANCELLED_UNKNOWN, failure.getDeliveryState());
        assertEquals(1, failure.getAttemptCount());
        assertEquals(KEY, failure.getIdempotencyKey());
        assertTrue(pending.isCancelled());
        SendOutcome outcome = operation.outcome().toCompletableFuture().join();
        assertEquals(DeliveryState.CANCELLED_UNKNOWN, outcome.getDeliveryState());
        assertEquals(1, outcome.getAttemptCount());
        assertEquals(KEY, outcome.getIdempotencyKey());
        assertTrue(runtime.isClosed());
    }

    @Test
    void cancellingADetachedCloseMirrorCannotCancelRuntimeClose() {
        RepostRuntime runtime = RepostRuntime.create(ClientOptions.builder()
                .apiKey("test-key")
                .transport(request -> new CompletableFuture<>())
                .build());
        CompletionStage<Void> closeStage = runtime.closeCompletion();
        CompletableFuture<Void> detached = closeStage.toCompletableFuture();
        assertTrue(detached.cancel(true));
        assertFalse(closeStage.toCompletableFuture().isDone());

        runtime.close();

        assertSame(closeStage, runtime.closeCompletion());
        assertTrue(closeStage.toCompletableFuture().isDone());
        assertTrue(runtime.isClosed());
    }

    @Test
    void closePreservesTheCallingThreadsInterruptState() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        RepostRuntime runtime = RepostRuntime.create(ClientOptions.builder()
                .apiKey("test-key")
                .executor(executor)
                .scheduler(scheduler)
                .transport(request -> new CompletableFuture<>())
                .build());
        try {
            Thread.currentThread().interrupt();
            runtime.close();
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(runtime.isClosed());
            assertFalse(executor.isShutdown());
            assertFalse(scheduler.isShutdown());
        } finally {
            Thread.interrupted();
            executor.shutdownNow();
            scheduler.shutdownNow();
        }
    }

    @Test
    void oneHundredOwnedResourceCyclesTerminateRuntimeThreads() throws Exception {
        for (int cycle = 0; cycle < 100; cycle++) {
            RepostRuntime runtime = RepostRuntime.create(ClientOptions.builder()
                    .apiKey("test-key")
                    .transport(request -> new CompletableFuture<>())
                    .build());
            Thread operationThread = runtime.executor()
                    .submit(Thread::currentThread)
                    .get(2, TimeUnit.SECONDS);
            Thread timerThread = runtime.scheduler()
                    .schedule(Thread::currentThread, 0L, TimeUnit.NANOSECONDS)
                    .get(2, TimeUnit.SECONDS);
            assertEquals(null, operationThread.getContextClassLoader());
            assertEquals(null, timerThread.getContextClassLoader());

            runtime.close();

            operationThread.join(2_000L);
            timerThread.join(2_000L);
            assertFalse(operationThread.isAlive(), "operation thread at cycle " + cycle);
            assertFalse(timerThread.isAlive(), "timer thread at cycle " + cycle);
            assertTrue(runtime.isClosed());
            assertEquals(0, runtime.diagnostics().getInFlightOperations());
            assertEquals(0L, runtime.diagnostics().getBufferedBytes());
            assertTrue(runtime.closeCompletion().toCompletableFuture().isDone());
        }
    }

    private static SendOperation send(RepostRuntime runtime) {
        SchemaDescriptor schema = SchemaDescriptor.builder(2)
                .addModel(ModelDescriptor.of("Payload", Collections.emptyList()))
                .addEvent(
                        "events",
                        "created",
                        EventDescriptor.of("contract.lifecycle", "Payload"))
                .build();
        return runtime.sendAsync(
                schema,
                schema.getWebhooks().get("events").get("created"),
                "cus_lifecycle",
                new RepostModel() {
                    @Override public boolean __repostIsPresent(int fieldIndex) { return false; }
                    @Override public Object __repostValue(int fieldIndex) { return null; }
                },
                SendOptions.defaults());
    }

    private static RepostTransportException failure(SendOperation operation) {
        try {
            operation.toCompletableFuture().join();
            throw new AssertionError("expected structured runtime failure");
        } catch (java.util.concurrent.CompletionException failure) {
            return (RepostTransportException) failure.getCause();
        }
    }

    private static final class ThrowingOwnedExecutor extends AbstractExecutorService {
        private final AtomicInteger shutdownCalls = new AtomicInteger();

        @Override public void shutdown() {
            shutdownCalls.incrementAndGet();
            throw new IllegalStateException("sentinel operation shutdown");
        }

        @Override public List<Runnable> shutdownNow() {
            throw new IllegalStateException("sentinel operation shutdownNow");
        }

        @Override public boolean isShutdown() { return false; }

        @Override public boolean isTerminated() { return false; }

        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return false; }

        @Override public void execute(Runnable command) {
            throw new RejectedExecutionException();
        }
    }

    private static final class ThrowingOwnedScheduler extends ScheduledThreadPoolExecutor {
        private final AtomicInteger shutdownCalls = new AtomicInteger();

        private ThrowingOwnedScheduler() {
            super(1);
        }

        @Override public void shutdown() {
            shutdownCalls.incrementAndGet();
            throw new IllegalStateException("sentinel scheduler shutdown");
        }
    }

    private static final class ThrowingOwnedTransport implements Transport, AutoCloseable {
        private final AtomicInteger closeCalls = new AtomicInteger();

        @Override public CompletionStage<TransportResponse> execute(TransportRequest request) {
            throw new AssertionError("not used");
        }

        @Override public void close() {
            closeCalls.incrementAndGet();
            throw new IllegalStateException("sentinel transport close");
        }
    }
}
