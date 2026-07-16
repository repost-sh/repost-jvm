package sh.repost.client;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/** Internal isolation for the lossy observer and non-droppable live telemetry seams. */
final class RuntimeObservability {
    private static final int OBSERVER_QUEUE_CAPACITY = 1_024;
    private static final TelemetryScope NOOP_SCOPE = () -> { };

    private final RepostObserver observer;
    private final ExecutorService observerExecutor;
    private final RepostTelemetry telemetry;
    private final MonotonicClock monotonicClock;
    private final WallClock wallClock;
    private final BiConsumer<String, Error> fatalAction;
    private final ObserverDispatcher dispatcher;
    private final ExecutorService terminalExecutor;
    private volatile Thread terminalThread;
    private final AtomicLong droppedObserverEvents = new AtomicLong();
    private final AtomicLong observerFailures = new AtomicLong();
    private final AtomicLong telemetryFailures = new AtomicLong();

    RuntimeObservability(
            RepostObserver observer,
            ExecutorService observerExecutor,
            RepostTelemetry telemetry,
            MonotonicClock monotonicClock,
            WallClock wallClock,
            BiConsumer<String, Error> fatalAction) {
        this.observer = observer;
        this.observerExecutor = observerExecutor;
        this.telemetry = telemetry;
        this.monotonicClock = Objects.requireNonNull(monotonicClock, "monotonicClock");
        this.wallClock = Objects.requireNonNull(wallClock, "wallClock");
        this.fatalAction = Objects.requireNonNull(fatalAction, "fatalAction");
        this.dispatcher = observer == null ? null : new ObserverDispatcher();
        this.terminalExecutor = telemetry == null ? null : Executors.newSingleThreadExecutor(task -> {
            Thread created = new Thread(task, "repost-terminal-telemetry");
            created.setDaemon(true);
            created.setContextClassLoader(null);
            terminalThread = created;
            return created;
        });
    }

    boolean isEnabled() {
        return observer != null || telemetry != null;
    }

    Operation begin(String operationId, long startNanos) {
        Instant startedAt = safeWallClock();
        CapturedTelemetryContext parent = null;
        if (telemetry != null) {
            try {
                parent = Objects.requireNonNull(
                        telemetry.captureContext(), "telemetry.captureContext returned null");
            } catch (RuntimeException failure) {
                increment(telemetryFailures);
            }
        }
        Operation operation = new Operation(operationId, startNanos, startedAt, parent);
        operation.emit(
                ObserverEventKind.OPERATION_START,
                null,
                null,
                null,
                null,
                null,
                HttpStatusClass.NONE,
                null,
                Collections.emptyList());
        return operation;
    }

    long droppedObserverEvents() {
        return capped(droppedObserverEvents);
    }

    long observerFailures() {
        return capped(observerFailures);
    }

    long telemetryFailures() {
        return capped(telemetryFailures);
    }

    void close(long deadlineNanos) {
        if (dispatcher != null) {
            dispatcher.close(deadlineNanos);
        }
        if (terminalExecutor != null) {
            terminalExecutor.shutdown();
            if (Thread.currentThread() == terminalThread) {
                return;
            }
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0L) {
                terminalExecutor.shutdownNow();
                return;
            }
            try {
                if (!terminalExecutor.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
                    terminalExecutor.shutdownNow();
                }
            } catch (InterruptedException interrupted) {
                terminalExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private void runOnTerminalThread(Runnable action) {
        if (Thread.currentThread() == terminalThread) {
            action.run();
            return;
        }
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Error> fatal = new AtomicReference<>();
        Runnable owned = () -> {
            try {
                action.run();
            } catch (Error failure) {
                fatal.set(failure);
            } finally {
                completed.countDown();
            }
        };
        try {
            terminalExecutor.execute(owned);
        } catch (RejectedExecutionException rejection) {
            Thread fallback = new Thread(owned, "repost-terminal-telemetry-fallback");
            fallback.setDaemon(true);
            fallback.setContextClassLoader(null);
            fallback.start();
        }
        boolean interrupted = false;
        while (true) {
            try {
                completed.await();
                break;
            } catch (InterruptedException waitInterrupted) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        Error failure = fatal.get();
        if (failure != null) {
            throw failure;
        }
    }

    final class Operation {
        private final String operationId;
        private final long startNanos;
        private final Instant startedAt;
        private final CapturedTelemetryContext parent;
        private final ArrayList<AttemptSummary> attempts = new ArrayList<>(10);
        private TelemetryOperation telemetryOperation;
        private TelemetryAttempt telemetryAttempt;
        private int activeAttempt;
        private long activeAttemptStartNanos;
        private boolean telemetryOperationEnded;
        private boolean telemetryAttemptEnded;
        private boolean telemetryStartAttempted;
        private boolean terminalClaimed;
        private boolean terminal;
        private long lastElapsedNanos;

        private Operation(
                String operationId,
                long startNanos,
                Instant startedAt,
                CapturedTelemetryContext parent) {
            this.operationId = Objects.requireNonNull(operationId, "operationId");
            this.startNanos = startNanos;
            this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
            this.parent = parent;
        }

        synchronized void startWorker() {
            if (terminal) {
                return;
            }
            startTelemetryOperation();
        }

        private void startTelemetryOperation() {
            if (telemetry == null || parent == null || telemetryStartAttempted) {
                return;
            }
            telemetryStartAttempted = true;
            try {
                telemetryOperation = Objects.requireNonNull(
                        telemetry.startOperation(parent, new TelemetryOperationStart(startedAt)),
                        "telemetry.startOperation returned null");
            } catch (RuntimeException failure) {
                increment(telemetryFailures);
            }
        }

        synchronized void startAttempt(int attemptNumber) {
            if (terminal) {
                return;
            }
            if (activeAttempt != 0) {
                throw new AssertionError("observability attempts overlapped");
            }
            activeAttempt = attemptNumber;
            activeAttemptStartNanos = sampleNanos();
            telemetryAttemptEnded = false;
            emit(
                    ObserverEventKind.ATTEMPT_START,
                    attemptNumber,
                    null,
                    null,
                    null,
                    null,
                    HttpStatusClass.NONE,
                    null,
                    Collections.emptyList());
            if (telemetryOperation != null) {
                try {
                    telemetryAttempt = Objects.requireNonNull(
                            telemetryOperation.startAttempt(new TelemetryAttemptStart(
                                    attemptNumber, timestamp(activeAttemptStartNanos))),
                            "telemetry.startAttempt returned null");
                } catch (RuntimeException failure) {
                    increment(telemetryFailures);
                    telemetryAttempt = null;
                }
            }
        }

        synchronized TelemetryScope makeAttemptCurrent() {
            if (terminal) {
                return NOOP_SCOPE;
            }
            TelemetryScope delegate = null;
            if (telemetryAttempt != null) {
                try {
                    delegate = Objects.requireNonNull(
                            telemetryAttempt.makeCurrent(), "telemetry attempt scope returned null");
                } catch (RuntimeException failure) {
                    increment(telemetryFailures);
                }
            }
            if (delegate == null && telemetryOperation != null) {
                try {
                    delegate = Objects.requireNonNull(
                            telemetryOperation.makeCurrent(), "telemetry operation scope returned null");
                } catch (RuntimeException failure) {
                    increment(telemetryFailures);
                }
            }
            if (delegate == null) {
                return NOOP_SCOPE;
            }
            TelemetryScope captured = delegate;
            return () -> {
                try {
                    captured.close();
                } catch (RuntimeException failure) {
                    increment(telemetryFailures);
                }
            };
        }

        synchronized TelemetryScope makeOperationCurrent() {
            if (terminal || telemetryOperation == null) {
                return NOOP_SCOPE;
            }
            TelemetryScope delegate;
            try {
                delegate = Objects.requireNonNull(
                        telemetryOperation.makeCurrent(), "telemetry operation scope returned null");
            } catch (RuntimeException failure) {
                increment(telemetryFailures);
                return NOOP_SCOPE;
            }
            return () -> {
                try {
                    delegate.close();
                } catch (RuntimeException failure) {
                    increment(telemetryFailures);
                }
            };
        }

        synchronized void retry(Duration delay, OperationEvidenceView evidence) {
            if (terminal) {
                return;
            }
            endActiveAttempt(
                    ObserverOutcome.RETRYABLE_FAILURE,
                    evidence.errorCode,
                    evidence.failureReason,
                    evidence.deliveryState,
                    statusClass(evidence.httpStatus));
            emit(
                    ObserverEventKind.RETRY_DELAY,
                    evidence.attemptCount,
                    null,
                    null,
                    evidence.errorCode,
                    evidence.deliveryState,
                    statusClass(evidence.httpStatus),
                    Objects.requireNonNull(delay, "delay"),
                    Collections.emptyList());
        }

        void finish(SendOutcome outcome, boolean cancelled) {
            boolean retrospective;
            synchronized (this) {
                if (terminal || terminalClaimed) {
                    return;
                }
                terminalClaimed = true;
                retrospective = telemetry != null
                        && parent != null
                        && !telemetryStartAttempted;
            }
            if (retrospective) {
                runOnTerminalThread(() -> finishClaimed(outcome, cancelled));
            } else {
                finishClaimed(outcome, cancelled);
            }
        }

        private synchronized void finishClaimed(SendOutcome outcome, boolean cancelled) {
            // Deadline, close, or cancellation may terminalize an admitted operation before its
            // worker runs. Preserve the captured parent and explicit admission timestamp by
            // starting and immediately ending the live operation retrospectively.
            startTelemetryOperation();
            terminal = true;
            ObserverOutcome observerOutcome = observerOutcome(outcome);
            endActiveAttempt(
                    observerOutcome,
                    outcome.getErrorCode(),
                    outcome.getFailureReason(),
                    outcome.getDeliveryState(),
                    statusClass(outcome.getHttpStatus()));
            if (cancelled) {
                emit(
                        ObserverEventKind.OPERATION_CANCEL,
                        null,
                        null,
                        ObserverOutcome.CANCELLED,
                        RepostErrorCode.CANCELLED,
                        outcome.getDeliveryState(),
                        statusClass(outcome.getHttpStatus()),
                        null,
                        Collections.emptyList());
            }
            Duration duration = elapsed(startNanos);
            endTelemetryOperation(outcome, observerOutcome, duration);
            emit(
                    ObserverEventKind.OPERATION_END,
                    null,
                    duration,
                    observerOutcome,
                    outcome.getErrorCode(),
                    outcome.getDeliveryState(),
                    statusClass(outcome.getHttpStatus()),
                    null,
                    attempts);
        }

        private void endActiveAttempt(
                ObserverOutcome outcome,
                RepostErrorCode errorCode,
                RepostFailureReason failureReason,
                DeliveryState deliveryState,
                HttpStatusClass statusClass) {
            if (activeAttempt == 0) {
                return;
            }
            Duration duration = elapsed(activeAttemptStartNanos);
            AttemptSummary summary = new AttemptSummary(
                    activeAttempt, duration, outcome, errorCode, deliveryState, statusClass);
            if (attempts.size() < 10) {
                attempts.add(summary);
            }
            emit(
                    ObserverEventKind.ATTEMPT_END,
                    activeAttempt,
                    duration,
                    outcome,
                    errorCode,
                    deliveryState,
                    statusClass,
                    null,
                    Collections.emptyList());
            if (telemetryAttempt != null && !telemetryAttemptEnded) {
                telemetryAttemptEnded = true;
                try {
                    telemetryAttempt.end(new TelemetryAttemptEnd(
                            activeAttempt,
                            duration,
                            outcome,
                            errorCode,
                            failureReason,
                            deliveryState,
                            statusClass));
                } catch (RuntimeException failure) {
                    increment(telemetryFailures);
                }
            }
            telemetryAttempt = null;
            activeAttempt = 0;
        }

        private void endTelemetryOperation(
                SendOutcome outcome,
                ObserverOutcome observerOutcome,
                Duration duration) {
            if (telemetryOperation == null || telemetryOperationEnded) {
                return;
            }
            telemetryOperationEnded = true;
            try {
                telemetryOperation.end(new TelemetryOperationEnd(
                        duration,
                        observerOutcome,
                        outcome.getErrorCode(),
                        outcome.getFailureReason(),
                        outcome.getDeliveryState(),
                        statusClass(outcome.getHttpStatus()),
                        outcome.getAttemptCount()));
            } catch (RuntimeException failure) {
                increment(telemetryFailures);
            }
        }

        private void emit(
                ObserverEventKind kind,
                Integer attemptNumber,
                Duration duration,
                ObserverOutcome outcome,
                RepostErrorCode errorCode,
                DeliveryState deliveryState,
                HttpStatusClass statusClass,
                Duration retryDelay,
                List<AttemptSummary> summaries) {
            if (dispatcher == null) {
                return;
            }
            long nowNanos = sampleNanos();
            Instant operationEnd = kind == ObserverEventKind.OPERATION_END
                    ? timestamp(nowNanos) : null;
            dispatcher.offer(new ObserverEvent(
                    kind,
                    operationId,
                    timestamp(nowNanos),
                    attemptNumber,
                    duration,
                    outcome,
                    errorCode,
                    deliveryState,
                    statusClass,
                    retryDelay,
                    kind == ObserverEventKind.OPERATION_END ? startedAt : null,
                    operationEnd,
                    summaries));
        }

        private long sampleNanos() {
            try {
                long now = monotonicClock.nanoTime();
                long elapsed = now - startNanos;
                if (elapsed < lastElapsedNanos) {
                    return startNanos + lastElapsedNanos;
                }
                lastElapsedNanos = Math.max(0L, elapsed);
                return startNanos + lastElapsedNanos;
            } catch (RuntimeException failure) {
                return startNanos + lastElapsedNanos;
            }
        }

        private Duration elapsed(long earlierNanos) {
            long value = sampleNanos() - earlierNanos;
            return Duration.ofNanos(Math.max(0L, value));
        }

        private Instant timestamp(long nowNanos) {
            try {
                return startedAt.plusNanos(Math.max(0L, nowNanos - startNanos));
            } catch (RuntimeException failure) {
                return startedAt;
            }
        }
    }

    static final class OperationEvidenceView {
        private final int attemptCount;
        private final DeliveryState deliveryState;
        private final RepostErrorCode errorCode;
        private final RepostFailureReason failureReason;
        private final Integer httpStatus;

        OperationEvidenceView(
                int attemptCount,
                DeliveryState deliveryState,
                RepostErrorCode errorCode,
                RepostFailureReason failureReason,
                Integer httpStatus) {
            this.attemptCount = attemptCount;
            this.deliveryState = Objects.requireNonNull(deliveryState, "deliveryState");
            this.errorCode = errorCode;
            this.failureReason = failureReason;
            this.httpStatus = httpStatus;
        }
    }

    private final class ObserverDispatcher implements Runnable {
        private final ArrayBlockingQueue<ObserverEvent> queue =
                new ArrayBlockingQueue<>(OBSERVER_QUEUE_CAPACITY);
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean closing = new AtomicBoolean();
        private volatile Thread thread;
        private volatile Future<?> activeCallback;

        void offer(ObserverEvent event) {
            if (closing.get()) {
                increment(droppedObserverEvents);
                return;
            }
            startIfNeeded();
            if (!queue.offer(event)) {
                increment(droppedObserverEvents);
            }
        }

        private void startIfNeeded() {
            if (!started.compareAndSet(false, true)) {
                return;
            }
            Thread created = new Thread(this, "repost-observer-dispatch");
            created.setDaemon(true);
            created.setContextClassLoader(null);
            thread = created;
            created.start();
        }

        @Override
        public void run() {
            while (!closing.get()) {
                ObserverEvent event;
                try {
                    event = queue.take();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
                deliver(event);
            }
        }

        private void deliver(ObserverEvent event) {
            if (observerExecutor == null) {
                invokeObserver(event);
                return;
            }
            Future<?> submitted;
            try {
                submitted = observerExecutor.submit(() -> invokeObserver(event));
                activeCallback = submitted;
            } catch (RejectedExecutionException rejection) {
                increment(droppedObserverEvents);
                return;
            }
            try {
                submitted.get();
            } catch (InterruptedException interrupted) {
                submitted.cancel(true);
                Thread.currentThread().interrupt();
            } catch (ExecutionException failure) {
                // invokeObserver accounts nonfatal callback failures at the callback boundary.
            } finally {
                activeCallback = null;
            }
        }

        private void invokeObserver(ObserverEvent event) {
            try {
                observer.onEvent(event);
            } catch (Error fatal) {
                closing.set(true);
                add(droppedObserverEvents, queue.size());
                queue.clear();
                fatalAction.accept(event.getOperationId(), fatal);
            } catch (RuntimeException failure) {
                increment(observerFailures);
            }
        }

        void close(long deadlineNanos) {
            if (!closing.compareAndSet(false, true)) {
                return;
            }
            int omitted = queue.size();
            queue.clear();
            add(droppedObserverEvents, omitted);
            Future<?> callback = activeCallback;
            if (callback != null) {
                callback.cancel(true);
            }
            Thread running = thread;
            if (running == null || running == Thread.currentThread()) {
                return;
            }
            running.interrupt();
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0L) {
                return;
            }
            try {
                TimeUnit.NANOSECONDS.timedJoin(running, remaining);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private Instant safeWallClock() {
        try {
            return Objects.requireNonNull(wallClock.now(), "wallClock returned null");
        } catch (RuntimeException failure) {
            return Instant.EPOCH;
        }
    }

    private static ObserverOutcome observerOutcome(SendOutcome outcome) {
        if (outcome.isAccepted()) {
            return ObserverOutcome.ACCEPTED;
        }
        if (outcome.getErrorCode() == RepostErrorCode.CANCELLED) {
            return ObserverOutcome.CANCELLED;
        }
        if (outcome.getErrorCode() == RepostErrorCode.CLOSED) {
            return ObserverOutcome.CLOSED;
        }
        if (outcome.getDeliveryState() == DeliveryState.REJECTED) {
            return ObserverOutcome.REJECTED;
        }
        return ObserverOutcome.FAILED;
    }

    private static HttpStatusClass statusClass(Integer status) {
        if (status == null) {
            return HttpStatusClass.NONE;
        }
        if (status < 300) {
            return HttpStatusClass.SUCCESS;
        }
        if (status < 400) {
            return HttpStatusClass.REDIRECTION;
        }
        if (status < 500) {
            return HttpStatusClass.CLIENT_ERROR;
        }
        return HttpStatusClass.SERVER_ERROR;
    }

    private static void increment(AtomicLong counter) {
        add(counter, 1L);
    }

    private static void add(AtomicLong counter, long amount) {
        if (amount <= 0L) {
            return;
        }
        while (true) {
            long current = counter.get();
            if (current == RuntimeDiagnostics.MAX_COUNTER_VALUE) {
                return;
            }
            long next = Math.min(RuntimeDiagnostics.MAX_COUNTER_VALUE, current + amount);
            if (counter.compareAndSet(current, next)) {
                return;
            }
        }
    }

    private static long capped(AtomicLong counter) {
        return Math.min(RuntimeDiagnostics.MAX_COUNTER_VALUE, counter.get());
    }
}
