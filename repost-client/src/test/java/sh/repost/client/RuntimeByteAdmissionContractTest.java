package sh.repost.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import sh.repost.client.descriptor.EventDescriptor;
import sh.repost.client.descriptor.FieldDescriptor;
import sh.repost.client.descriptor.ModelDescriptor;
import sh.repost.client.descriptor.ScalarKind;
import sh.repost.client.descriptor.SchemaDescriptor;
import sh.repost.client.error.RepostException;
import sh.repost.client.error.RepostTransportException;
import sh.repost.client.error.RepostValidationException;
import sh.repost.client.internal.Serializer;

final class RuntimeByteAdmissionContractTest {
    private static final long MINIMUM_BUDGET = 4_194_304L;
    private static final long INITIAL_RESERVATION = 2_359_296L;
    private static final long RETAINED_RESPONSE_AND_PARSER = 1_310_720L;
    private static final DefaultValueGenerators GENERATORS = DefaultValueGenerators.fixed(
            Instant.EPOCH,
            "12345678-1234-4234-9234-123456789abc",
            "abcdefghijklmnopqrstuvwx");

    @Test
    void admitsExactlyOneConcurrentOperationWhenTheByteBudgetSaturates() throws Exception {
        AtomicInteger credentialReads = new AtomicInteger();
        AtomicInteger modelReads = new AtomicInteger();
        QueuedExecutor operationExecutor = new QueuedExecutor();
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        RepostRuntime runtime = RepostRuntime.create(ClientOptions.builder()
                .apiKeyProvider(() -> {
                    credentialReads.incrementAndGet();
                    return "must-not-run";
                })
                .defaultValueGenerators(GENERATORS)
                .maxInFlightOperations(2)
                .maxBufferedBytes(MINIMUM_BUDGET)
                .executor(operationExecutor)
                .scheduler(scheduler)
                .build());
        SchemaDescriptor schema = emptySchema();
        RepostModel model = countingEmptyModel(modelReads);
        CyclicBarrier start = new CyclicBarrier(2);
        try {
            Future<SendOperation> firstFuture = callers.submit(() -> {
                start.await();
                return runtime.sendAsync(
                        schema, event(schema), "customer_1", model, SendOptions.defaults());
            });
            Future<SendOperation> secondFuture = callers.submit(() -> {
                start.await();
                return runtime.sendAsync(
                        schema, event(schema), "customer_2", model, SendOptions.defaults());
            });
            SendOperation first = firstFuture.get(2, TimeUnit.SECONDS);
            SendOperation second = secondFuture.get(2, TimeUnit.SECONDS);

            assertNotEquals(first.isDone(), second.isDone());
            SendOperation rejected = first.isDone() ? first : second;
            SendOperation admitted = first.isDone() ? second : first;
            RepostTransportException rejection = transportFailure(rejected);
            assertEquals(RepostErrorCode.OVERLOADED, rejection.getErrorCode());
            assertEquals(DeliveryState.NOT_SENT, rejection.getDeliveryState());
            assertTrue(rejection.getOperationId() != null);
            assertEquals(
                    rejection.getOperationId(),
                    rejected.outcome().toCompletableFuture().join().getOperationId());

            RuntimeDiagnostics saturated = runtime.diagnostics();
            assertEquals(1, saturated.getInFlightOperations());
            assertEquals(INITIAL_RESERVATION, saturated.getBufferedBytes());
            assertEquals(0L, saturated.getConcurrencyOverloadRejections());
            assertEquals(1L, saturated.getRequestByteOverloadRejections());
            assertEquals(1, operationExecutor.submissions());
            assertEquals(0, credentialReads.get());
            assertEquals(0, modelReads.get());

            assertTrue(admitted.cancel(true));
            operationExecutor.runQueued();
            assertEquals(0, credentialReads.get());
            assertEquals(0, modelReads.get());
            assertEquals(0, runtime.diagnostics().getInFlightOperations());
            assertEquals(0L, runtime.diagnostics().getBufferedBytes());
        } finally {
            runtime.close();
            callers.shutdownNow();
            scheduler.shutdownNow();
        }
    }

    @Test
    void releasesTheFullReservationWhenCloseSettlesQueuedWork() {
        AtomicInteger credentialReads = new AtomicInteger();
        AtomicInteger modelReads = new AtomicInteger();
        QueuedExecutor operationExecutor = new QueuedExecutor();
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        RepostRuntime runtime = RepostRuntime.create(ClientOptions.builder()
                .apiKeyProvider(() -> {
                    credentialReads.incrementAndGet();
                    return "must-not-run";
                })
                .defaultValueGenerators(GENERATORS)
                .maxBufferedBytes(MINIMUM_BUDGET)
                .executor(operationExecutor)
                .scheduler(scheduler)
                .build());
        SchemaDescriptor schema = emptySchema();
        SendOperation operation = runtime.sendAsync(
                schema,
                event(schema),
                "customer_1",
                countingEmptyModel(modelReads),
                SendOptions.defaults());
        try {
            assertEquals(INITIAL_RESERVATION, runtime.diagnostics().getBufferedBytes());
            runtime.close();

            RepostTransportException failure = transportFailure(operation);
            assertEquals(RepostErrorCode.CLOSED, failure.getErrorCode());
            assertEquals(0, runtime.diagnostics().getInFlightOperations());
            assertEquals(0L, runtime.diagnostics().getBufferedBytes());
            assertTrue(runtime.diagnostics().isClosed());
            operationExecutor.runQueued();
            assertEquals(0, credentialReads.get());
            assertEquals(0, modelReads.get());
        } finally {
            runtime.close();
            scheduler.shutdownNow();
        }
    }

    @Test
    void retainsOnlyTheSerializedRequestBytesAfterWriting() throws Exception {
        BlockingSampleClock clock = new BlockingSampleClock(5);
        ExecutorService operationExecutor = Executors.newSingleThreadExecutor();
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        RepostRuntime runtime = RepostRuntime.create(ClientOptions.builder()
                .apiKey("key")
                .defaultValueGenerators(GENERATORS)
                .maxBufferedBytes(MINIMUM_BUDGET)
                .monotonicClock(clock)
                .executor(operationExecutor)
                .scheduler(scheduler)
                .transport(request -> {
                    throw new IllegalStateException("deterministic test transport failure");
                })
                .build());
        SchemaDescriptor schema = emptySchema();
        RepostModel model = countingEmptyModel(new AtomicInteger());
        int serializedBytes = Serializer.serializeOperationEnvelope(
                schema,
                event(schema).getModelId(),
                event(schema).getType(),
                "customer_1",
                model,
                GENERATORS).getBytes().length;
        try {
            SendOperation operation = runtime.sendAsync(
                    schema, event(schema), "customer_1", model, SendOptions.defaults());
            assertTrue(clock.blocked.await(2, TimeUnit.SECONDS));

            RuntimeDiagnostics retained = runtime.diagnostics();
            assertEquals(1, retained.getInFlightOperations());
            assertEquals(
                    RETAINED_RESPONSE_AND_PARSER + serializedBytes,
                    retained.getBufferedBytes());

            clock.release.countDown();
            RepostTransportException failure = transportFailure(operation);
            assertEquals(RepostErrorCode.IO, failure.getErrorCode());
            assertEquals(0L, runtime.diagnostics().getBufferedBytes());
        } finally {
            clock.release.countDown();
            runtime.close();
            operationExecutor.shutdownNow();
            scheduler.shutdownNow();
        }
    }

    @Test
    void releasesReservationWhenTheRealSerializerRejectsTheModel() {
        QueuedExecutor operationExecutor = new QueuedExecutor();
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        RepostRuntime runtime = RepostRuntime.create(ClientOptions.builder()
                .apiKey("key")
                .defaultValueGenerators(GENERATORS)
                .maxBufferedBytes(MINIMUM_BUDGET)
                .executor(operationExecutor)
                .scheduler(scheduler)
                .build());
        FieldDescriptor required = FieldDescriptor.builder(
                        0, "requiredValue", "requiredValue", ScalarKind.STRING)
                .requiredInInput(true)
                .build();
        SchemaDescriptor schema = schema(Collections.singletonList(required));
        SendOperation operation = runtime.sendAsync(
                schema,
                event(schema),
                "customer_1",
                countingEmptyModel(new AtomicInteger()),
                SendOptions.defaults());
        try {
            assertEquals(INITIAL_RESERVATION, runtime.diagnostics().getBufferedBytes());

            operationExecutor.runQueued();

            RepostException failure = operationFailure(operation);
            assertInstanceOf(RepostValidationException.class, failure);
            assertEquals(RepostErrorCode.VALIDATION, failure.getErrorCode());
            assertTrue(failure.getOperationId() != null);
            assertEquals("$.requiredValue", failure.getValidationIssues().get(0).getPath());
            assertEquals(0, runtime.diagnostics().getInFlightOperations());
            assertEquals(0L, runtime.diagnostics().getBufferedBytes());
        } finally {
            runtime.close();
            scheduler.shutdownNow();
        }
    }

    @Test
    void keepsBytesChargedUntilACancelledSerializerWorkerExits() throws Exception {
        BlockingModel model = new BlockingModel();
        TrackingExecutor operationExecutor = new TrackingExecutor();
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        RepostRuntime runtime = RepostRuntime.create(ClientOptions.builder()
                .apiKey("key")
                .defaultValueGenerators(GENERATORS)
                .maxInFlightOperations(2)
                .maxBufferedBytes(MINIMUM_BUDGET)
                .executor(operationExecutor)
                .scheduler(scheduler)
                .build());
        SchemaDescriptor schema = optionalFieldSchema();
        try {
            SendOperation operation = runtime.sendAsync(
                    schema, event(schema), "customer_1", model, SendOptions.defaults());
            assertTrue(model.entered.await(2, TimeUnit.SECONDS));

            assertTrue(operation.cancel(true));
            assertTrue(operation.isCancelled());
            assertEquals(RepostErrorCode.CANCELLED,
                    operation.outcome().toCompletableFuture().join().getErrorCode());
            assertEquals(0, runtime.diagnostics().getInFlightOperations());
            assertEquals(INITIAL_RESERVATION, runtime.diagnostics().getBufferedBytes());

            SendOperation rejected = runtime.sendAsync(
                    schema,
                    event(schema),
                    "customer_2",
                    countingEmptyModel(new AtomicInteger()),
                    SendOptions.defaults());
            assertEquals(RepostErrorCode.OVERLOADED, transportFailure(rejected).getErrorCode());
            assertEquals(1L, runtime.diagnostics().getRequestByteOverloadRejections());
            assertEquals(INITIAL_RESERVATION, runtime.diagnostics().getBufferedBytes());

            model.release.countDown();
            assertTrue(operationExecutor.finished.await(2, TimeUnit.SECONDS));
            assertEquals(0L, runtime.diagnostics().getBufferedBytes());
        } finally {
            model.release.countDown();
            runtime.close();
            operationExecutor.shutdownNow();
            scheduler.shutdownNow();
        }
    }

    @Test
    void closeWaitsHonestlyForAResidualSerializerWorker() throws Exception {
        BlockingModel model = new BlockingModel();
        TrackingExecutor operationExecutor = new TrackingExecutor();
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        ExecutorService closer = Executors.newSingleThreadExecutor();
        RepostRuntime runtime = RepostRuntime.create(ClientOptions.builder()
                .apiKey("key")
                .defaultValueGenerators(GENERATORS)
                .maxBufferedBytes(MINIMUM_BUDGET)
                .executor(operationExecutor)
                .scheduler(scheduler)
                .build());
        SchemaDescriptor schema = optionalFieldSchema();
        try {
            SendOperation operation = runtime.sendAsync(
                    schema, event(schema), "customer_1", model, SendOptions.defaults());
            assertTrue(model.entered.await(2, TimeUnit.SECONDS));

            Future<?> closeCall = closer.submit(runtime::close);
            RepostTransportException failure = transportFailure(operation);
            assertEquals(RepostErrorCode.CLOSED, failure.getErrorCode());
            assertEquals(0, runtime.diagnostics().getInFlightOperations());
            assertEquals(INITIAL_RESERVATION, runtime.diagnostics().getBufferedBytes());
            assertFalse(runtime.diagnostics().isClosed());
            assertFalse(runtime.closeCompletion().toCompletableFuture().isDone());
            assertFalse(closeCall.isDone());

            model.release.countDown();
            assertTrue(operationExecutor.finished.await(2, TimeUnit.SECONDS));
            closeCall.get(2, TimeUnit.SECONDS);
            assertEquals(0L, runtime.diagnostics().getBufferedBytes());
            assertTrue(runtime.diagnostics().isClosed());
            assertTrue(runtime.closeCompletion().toCompletableFuture().isDone());
        } finally {
            model.release.countDown();
            runtime.close();
            closer.shutdownNow();
            operationExecutor.shutdownNow();
            scheduler.shutdownNow();
        }
    }

    private static SchemaDescriptor emptySchema() {
        return schema(Collections.emptyList());
    }

    private static SchemaDescriptor optionalFieldSchema() {
        return schema(Collections.singletonList(FieldDescriptor.builder(
                0, "value", "value", ScalarKind.STRING).build()));
    }

    private static SchemaDescriptor schema(List<FieldDescriptor> fields) {
        return SchemaDescriptor.builder(2)
                .addModel(ModelDescriptor.of("Payload", fields))
                .addEvent("events", "created", EventDescriptor.of("event.created", "Payload"))
                .build();
    }

    private static EventDescriptor event(SchemaDescriptor schema) {
        return schema.getWebhooks().get("events").get("created");
    }

    private static RepostModel countingEmptyModel(AtomicInteger reads) {
        return new RepostModel() {
            @Override
            public boolean __repostIsPresent(int fieldIndex) {
                reads.incrementAndGet();
                return false;
            }

            @Override
            public Object __repostValue(int fieldIndex) {
                reads.incrementAndGet();
                return null;
            }
        };
    }

    private static RepostTransportException transportFailure(SendOperation operation) {
        return (RepostTransportException) operationFailure(operation);
    }

    private static RepostException operationFailure(SendOperation operation) {
        try {
            operation.toCompletableFuture().join();
            throw new AssertionError("expected operation failure");
        } catch (java.util.concurrent.CompletionException failure) {
            return (RepostException) failure.getCause();
        }
    }

    private static final class QueuedExecutor extends AbstractExecutorService {
        private Runnable queued;
        private int submissions;

        @Override public void shutdown() { }
        @Override public List<Runnable> shutdownNow() { return Collections.emptyList(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return false; }

        @Override
        public synchronized void execute(Runnable command) {
            if (queued != null) {
                throw new AssertionError("expected at most one queued operation");
            }
            submissions++;
            queued = command;
        }

        private synchronized int submissions() {
            return submissions;
        }

        private void runQueued() {
            Runnable command;
            synchronized (this) {
                if (queued == null) {
                    throw new AssertionError("expected one queued operation");
                }
                command = queued;
                queued = null;
            }
            command.run();
        }
    }

    private static final class BlockingSampleClock implements MonotonicClock {
        private final int blockingSample;
        private final AtomicInteger samples = new AtomicInteger();
        private final CountDownLatch blocked = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private BlockingSampleClock(int blockingSample) {
            this.blockingSample = blockingSample;
        }

        @Override
        public long nanoTime() {
            if (samples.incrementAndGet() == blockingSample) {
                blocked.countDown();
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            return 0L;
        }
    }

    private static final class BlockingModel implements RepostModel {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public boolean __repostIsPresent(int fieldIndex) {
            entered.countDown();
            boolean interrupted = false;
            while (true) {
                try {
                    release.await();
                    break;
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            return false;
        }

        @Override
        public Object __repostValue(int fieldIndex) {
            throw new AssertionError("absent field value must not be read");
        }
    }

    private static final class TrackingExecutor extends AbstractExecutorService {
        private final ExecutorService delegate = Executors.newSingleThreadExecutor();
        private final CountDownLatch finished = new CountDownLatch(1);

        @Override public void shutdown() { delegate.shutdown(); }
        @Override public List<Runnable> shutdownNow() { return delegate.shutdownNow(); }
        @Override public boolean isShutdown() { return delegate.isShutdown(); }
        @Override public boolean isTerminated() { return delegate.isTerminated(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit)
                throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }

        @Override
        public void execute(Runnable command) {
            delegate.execute(new FutureTask<Void>(() -> {
                try {
                    command.run();
                } finally {
                    finished.countDown();
                }
                return null;
            }));
        }
    }
}
