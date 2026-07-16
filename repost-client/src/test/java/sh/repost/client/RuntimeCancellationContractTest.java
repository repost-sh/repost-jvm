package sh.repost.client;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import sh.repost.client.descriptor.EventDescriptor;
import sh.repost.client.descriptor.ModelDescriptor;
import sh.repost.client.descriptor.SchemaDescriptor;
import sh.repost.client.error.RepostTransportException;

final class RuntimeCancellationContractTest {
    private static final String KEY = "cancel-key";
    private static final DefaultValueGenerators GENERATORS = DefaultValueGenerators.fixed(
            Instant.EPOCH,
            "12345678-1234-4234-9234-123456789abc",
            "abcdefghijklmnopqrstuvwx");

    @Test
    void cancellationThroughTheHardenedFuturePreventsQueuedAdmissionWork() {
        ManualExecutor executor = new ManualExecutor();
        ManualScheduler scheduler = new ManualScheduler();
        AtomicInteger transportCalls = new AtomicInteger();
        RepostRuntime runtime = runtime(executor, scheduler, request -> {
            transportCalls.incrementAndGet();
            return new CompletableFuture<>();
        });
        try {
            SendOperation operation = send(runtime, null);
            CompletableFuture<SendResult> future = operation.toCompletableFuture();

            assertSame(future, operation.toCompletableFuture());
            assertTrue(future.cancel(true));
            assertNativeCancellation(operation);
            assertOutcome(operation, DeliveryState.NOT_SENT, 0, null, null);
            executor.runNext();
            assertEquals(0, transportCalls.get());
            assertEquals(0, executor.activeTaskCount());
            assertEquals(0, scheduler.activeTaskCount());
        } finally {
            runtime.close();
        }
    }

    @Test
    void cancellationDuringBackoffPreservesDefinitiveAndUncertainEvidence() {
        assertBackoffCancellation(429, DeliveryState.NOT_SENT);
        assertBackoffCancellation(503, DeliveryState.CANCELLED_UNKNOWN);
    }

    @Test
    void cancellationPropagatesToAnInFlightCustomTransportStage() {
        ManualExecutor executor = new ManualExecutor();
        ManualScheduler scheduler = new ManualScheduler();
        CompletableFuture<TransportResponse> pending = new CompletableFuture<>();
        RepostRuntime runtime = runtime(executor, scheduler, request -> pending);
        try {
            SendOperation operation = send(runtime, KEY);
            executor.runNext();

            assertTrue(operation.toCompletableFuture().cancel(false));
            assertNativeCancellation(operation);
            assertOutcome(
                    operation, DeliveryState.CANCELLED_UNKNOWN, 1, KEY, null);
            assertTrue(pending.isCancelled());
            assertEquals(0, scheduler.activeTaskCount());
        } finally {
            runtime.close();
        }
    }

    @Test
    void cancellationOwnsAndClosesAResponseWaitingForParserHandoff() {
        ManualExecutor executor = new ManualExecutor();
        ManualScheduler scheduler = new ManualScheduler();
        TrackingInputStream body = new TrackingInputStream(acceptedBody());
        TransportResponse response = TransportResponse.of(
                202,
                Collections.singletonList(
                        TransportHeaderField.of("Content-Type", "application/json")),
                body);
        RepostRuntime runtime = runtime(
                executor,
                scheduler,
                request -> CompletableFuture.completedFuture(response));
        try {
            SendOperation operation = send(runtime, KEY);
            executor.runNext();
            assertEquals(1, executor.activeTaskCount());
            assertEquals(0, body.closeCalls.get());

            assertTrue(operation.cancel(false));
            assertNativeCancellation(operation);
            assertOutcome(
                    operation, DeliveryState.CANCELLED_UNKNOWN, 1, KEY, 202);
            assertEquals(1, body.closeCalls.get());

            executor.runNext();
            assertEquals(1, body.closeCalls.get());
            assertOutcome(
                    operation, DeliveryState.CANCELLED_UNKNOWN, 1, KEY, 202);
        } finally {
            runtime.close();
        }
    }

    @Test
    void cancellationBeforeResponseClassificationPreservesDefinitiveNotSentEvidence() {
        ManualExecutor executor = new ManualExecutor();
        ManualScheduler scheduler = new ManualScheduler();
        TrackingInputStream body = new TrackingInputStream(new byte[0]);
        TransportResponse response = TransportResponse.of(
                429,
                Collections.emptyList(),
                body);
        RepostRuntime runtime = runtime(
                executor,
                scheduler,
                request -> CompletableFuture.completedFuture(response));
        try {
            SendOperation operation = send(runtime, KEY);
            executor.runNext();
            assertEquals(1, executor.activeTaskCount());
            assertEquals(0, body.closeCalls.get());

            assertTrue(operation.cancel(false));
            assertNativeCancellation(operation);
            assertOutcome(operation, DeliveryState.NOT_SENT, 1, KEY, 429);
            assertEquals(1, body.closeCalls.get());

            executor.runNext();
            assertEquals(1, body.closeCalls.get());
            assertOutcome(operation, DeliveryState.NOT_SENT, 1, KEY, 429);
        } finally {
            runtime.close();
        }
    }

    @Test
    void settlementAttacksCannotForgeTheOperationAndDerivedStagesRemainDetached() {
        ManualExecutor executor = new ManualExecutor();
        ManualScheduler scheduler = new ManualScheduler();
        RepostRuntime runtime = runtime(
                executor, scheduler, request -> new CompletableFuture<>());
        try {
            SendOperation operation = send(runtime, KEY);
            CompletableFuture<SendResult> future = operation.toCompletableFuture();
            assertSame(future, operation.toCompletableFuture());
            assertFalse(future.complete(fakeResult()));
            assertFalse(future.completeExceptionally(new IllegalStateException("sentinel")));
            assertThrows(UnsupportedOperationException.class,
                    () -> future.obtrudeValue(fakeResult()));
            assertThrows(UnsupportedOperationException.class,
                    () -> future.obtrudeException(new IllegalStateException("sentinel")));
            AtomicInteger supplierCalls = new AtomicInteger();
            assertSame(future, future.completeAsync(() -> {
                supplierCalls.incrementAndGet();
                return fakeResult();
            }));
            assertSame(future, future.orTimeout(1, TimeUnit.NANOSECONDS));
            assertSame(future, future.completeOnTimeout(
                    fakeResult(), 1, TimeUnit.NANOSECONDS));
            assertEquals(0, supplierCalls.get());
            assertFalse(operation.isDone());

            CompletableFuture<SendResult> derived =
                    operation.thenApply(value -> value).toCompletableFuture();
            assertTrue(derived.cancel(false));
            assertFalse(operation.isCancelled());

            assertTrue(future.cancel(false));
            assertNativeCancellation(operation);
        } finally {
            runtime.close();
        }
    }

    @Test
    void synchronousInterruptionPreservesTheSameCancellationEvidence() throws Exception {
        CountDownLatch transportStarted = new CountDownLatch(1);
        CompletableFuture<TransportResponse> pending = new CompletableFuture<>();
        RepostRuntime runtime = RepostRuntime.create(ClientOptions.builder()
                .apiKey("test-key")
                .baseUri("https://example.com")
                .defaultValueGenerators(GENERATORS)
                .idempotencyKeyGenerator(() -> KEY)
                .transport(request -> {
                    transportStarted.countDown();
                    return pending;
                })
                .build());
        AtomicReference<Throwable> observed = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                sendSynchronously(runtime, KEY);
            } catch (Throwable failure) {
                observed.set(failure);
            }
        });
        try {
            caller.start();
            assertTrue(transportStarted.await(2, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (pending.getNumberOfDependents() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(1L);
            }
            assertTrue(pending.getNumberOfDependents() > 0);
            caller.interrupt();
            caller.join(2_000L);

            assertFalse(caller.isAlive());
            RepostTransportException failure =
                    (RepostTransportException) observed.get();
            assertEquals(RepostErrorCode.CANCELLED, failure.getErrorCode());
            assertEquals(DeliveryState.CANCELLED_UNKNOWN, failure.getDeliveryState());
            assertEquals(1, failure.getAttemptCount());
            assertEquals(KEY, failure.getIdempotencyKey());
            assertTrue(pending.isCancelled());
        } finally {
            caller.interrupt();
            runtime.close();
        }
    }

    private static void assertBackoffCancellation(
            int status,
            DeliveryState expectedDelivery) {
        ManualExecutor executor = new ManualExecutor();
        ManualScheduler scheduler = new ManualScheduler();
        Transport transport = request -> CompletableFuture.completedFuture(
                TransportResponse.of(
                        status,
                        Collections.emptyList(),
                        new ByteArrayInputStream(new byte[0])));
        RepostRuntime runtime = runtime(executor, scheduler, transport);
        try {
            SendOperation operation = send(runtime, KEY);
            executor.runNext();
            executor.runNext();
            assertTrue(scheduler.activeTaskCount() > 1);

            assertTrue(operation.cancel(false));
            assertNativeCancellation(operation);
            assertOutcome(operation, expectedDelivery, 1, KEY, status);
            assertEquals(0, scheduler.activeTaskCount());
            assertFalse(operation.cancel(false));
        } finally {
            runtime.close();
        }
    }

    private static RepostRuntime runtime(
            ManualExecutor executor,
            ManualScheduler scheduler,
            Transport transport) {
        return RepostRuntime.create(ClientOptions.builder()
                .apiKey("test-key")
                .baseUri("https://example.com")
                .defaultValueGenerators(GENERATORS)
                .idempotencyKeyGenerator(() -> KEY)
                .retryEntropy(ignored -> 0L)
                .operationTimeout(Duration.ofSeconds(30))
                .executor(executor)
                .scheduler(scheduler)
                .transport(transport)
                .build());
    }

    private static SendOperation send(RepostRuntime runtime, String key) {
        SchemaDescriptor schema = SchemaDescriptor.builder(2)
                .addModel(ModelDescriptor.of("Payload", Collections.emptyList()))
                .addEvent(
                        "events",
                        "created",
                        EventDescriptor.of("contract.sent", "Payload"))
                .build();
        SendOptions options = key == null
                ? SendOptions.defaults()
                : SendOptions.builder().idempotencyKey(key).build();
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

    private static SendResult sendSynchronously(RepostRuntime runtime, String key) {
        SchemaDescriptor schema = SchemaDescriptor.builder(2)
                .addModel(ModelDescriptor.of("Payload", Collections.emptyList()))
                .addEvent(
                        "events",
                        "created",
                        EventDescriptor.of("contract.sent", "Payload"))
                .build();
        return runtime.send(
                schema,
                schema.getWebhooks().get("events").get("created"),
                "cus_contract",
                new RepostModel() {
                    @Override public boolean __repostIsPresent(int fieldIndex) { return false; }
                    @Override public Object __repostValue(int fieldIndex) { return null; }
                },
                SendOptions.builder().idempotencyKey(key).build());
    }

    private static void assertNativeCancellation(SendOperation operation) {
        assertTrue(operation.isDone());
        assertTrue(operation.isCancelled());
        assertThrows(CancellationException.class, operation::get);
        assertThrows(
                CancellationException.class,
                () -> operation.toCompletableFuture().join());
    }

    private static void assertOutcome(
            SendOperation operation,
            DeliveryState delivery,
            int attempts,
            String key,
            Integer status) {
        CompletionStage<SendOutcome> stage = operation.outcome();
        assertSame(stage, operation.outcome());
        SendOutcome outcome = stage.toCompletableFuture().join();
        assertEquals(RepostErrorCode.CANCELLED, outcome.getErrorCode());
        assertEquals(delivery, outcome.getDeliveryState());
        assertEquals(attempts, outcome.getAttemptCount());
        assertEquals(key, outcome.getIdempotencyKey());
        assertEquals(status, outcome.getHttpStatus());
    }

    private static SendResult fakeResult() {
        return SendResult.builder()
                .id("msg_fake")
                .type("fake")
                .customerId("fake")
                .timestamp(Instant.EPOCH)
                .build();
    }

    private static byte[] acceptedBody() {
        return ("{\"id\":\"msg_cancel\",\"type\":\"contract.sent\","
                + "\"customerId\":\"cus_contract\","
                + "\"timestamp\":\"1970-01-01T00:00:00.000Z\"}").getBytes(UTF_8);
    }

    private static final class TrackingInputStream extends ByteArrayInputStream {
        private final AtomicInteger closeCalls = new AtomicInteger();

        private TrackingInputStream(byte[] body) { super(body); }

        @Override public void close() {
            closeCalls.incrementAndGet();
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
            Runnable task = tasks.poll();
            if (task == null) { throw new AssertionError("expected one executor task"); }
            task.run();
        }

        private int activeTaskCount() { return tasks.size(); }
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
        @Override public boolean isTerminated() { return shutdown; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }
        @Override public void execute(Runnable command) {
            schedule(command, 0L, TimeUnit.NANOSECONDS);
        }
        @Override public ScheduledFuture<?> schedule(
                Runnable command, long delay, TimeUnit unit) {
            ManualTask<Void> task = new ManualTask<>(command, unit.toNanos(delay));
            tasks.add(task);
            return task;
        }
        @Override public <V> ScheduledFuture<V> schedule(
                Callable<V> callable, long delay, TimeUnit unit) {
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

        private int activeTaskCount() {
            int active = 0;
            for (ManualTask<?> task : tasks) {
                if (!task.cancelled) { active++; }
            }
            return active;
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
