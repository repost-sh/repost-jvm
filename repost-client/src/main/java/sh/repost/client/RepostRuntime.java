package sh.repost.client;

import java.net.URI;
import java.security.SecureRandom;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import sh.repost.client.descriptor.EventDescriptor;
import sh.repost.client.descriptor.SchemaDescriptor;
import sh.repost.client.error.RepostConfigurationException;
import sh.repost.client.error.RepostException;
import sh.repost.client.error.RepostTransportException;
import sh.repost.client.internal.DefaultGenerators;
import sh.repost.client.internal.ResponseParser;
import sh.repost.client.internal.Serializer;
import sh.repost.client.internal.UriValidator;

/** One immutable, thread-safe, framework-neutral runtime. */
public final class RepostRuntime implements AutoCloseable {
    private static final String DEFAULT_BASE_URI = "https://api.repost.sh";
    private static final String USER_AGENT = "repost-jvm/1.0.0";
    private static final DateTimeFormatter RETRY_AFTER_DATE = new DateTimeFormatterBuilder()
            .parseCaseSensitive()
            .appendPattern("EEE, dd MMM uuuu HH:mm:ss 'GMT'")
            .toFormatter(Locale.US)
            .withResolverStyle(ResolverStyle.STRICT)
            .withZone(ZoneOffset.UTC);
    private static final long CLOSE_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(5L);
    private static final long REQUEST_SERIALIZATION_WORKSPACE = Serializer.MAX_REQUEST_BYTES;
    private static final long RESPONSE_WORKSPACE = 1_048_576L;
    private static final long JSON_PARSER_SCRATCH_RESERVATION = 262_144L;
    private static final long INITIAL_BYTE_RESERVATION = REQUEST_SERIALIZATION_WORKSPACE
            + RESPONSE_WORKSPACE + JSON_PARSER_SCRATCH_RESERVATION;
    private static final long RETAINED_RESPONSE_AND_PARSER =
            RESPONSE_WORKSPACE + JSON_PARSER_SCRATCH_RESERVATION;

    private final Object lifecycleLock = new Object();
    private final URI endpointUri;
    private final int maxInFlightOperations;
    private final long maxBufferedBytes;
    private final long operationTimeoutNanos;
    private final long connectTimeoutNanos;
    private final long attemptTimeoutNanos;
    private final int maxAttempts;
    private final long retryBaseDelayMillis;
    private final long retryMaxDelayMillis;
    private final MonotonicClock monotonicClock;
    private final IdempotencyKeyGenerator idempotencyKeyGenerator;
    private final WallClock wallClock;
    private final RetryEntropy retryEntropy;
    private final DefaultValueGenerators defaultValueGenerators;
    private final RuntimeObservability observability;
    private final String userAgent;
    private OperationCredentialResolver credentialResolver;
    private ExecutorService executor;
    private java.util.concurrent.ScheduledExecutorService scheduler;
    private final boolean ownsExecutor;
    private final boolean ownsScheduler;
    private final boolean ownsTransport;
    private Transport transport;
    private final CompletableFuture<Void> closeFuture = new CompletableFuture<>();
    private final CompletionStage<Void> closeStage = closeFuture.minimalCompletionStage();
    private final Set<OperationSettlement> activeSettlements =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private Lifecycle lifecycle = Lifecycle.OPEN;
    private boolean closeStagesFinished;
    private long closeDeadlineNanos;
    private RepostTransportException closeFailure;
    private int inFlightOperations;
    private long bufferedBytes;
    private long concurrencyOverloadRejections;
    private long requestByteOverloadRejections;
    private long executorOverloadRejections;
    private long schedulerOverloadRejections;

    private RepostRuntime(ClientOptions options, Function<String, String> environment) {
        this(options, environment, null);
    }

    private RepostRuntime(
            ClientOptions options,
            Function<String, String> environment,
            OwnedResources testResources) {
        String rawBaseUri = options.baseUri();
        if (rawBaseUri == null) {
            String environmentValue = environment.apply("REPOST_API_URL");
            rawBaseUri = environmentValue == null ? DEFAULT_BASE_URI : environmentValue;
        }
        try {
            this.endpointUri = UriValidator.canonicalEndpoint(rawBaseUri);
        } catch (IllegalArgumentException invalid) {
            throw ClientOptions.configurationIssue(
                    ConfigurationIssueCode.INVALID_VALUE, ClientOptionKey.BASE_URI);
        }
        this.maxInFlightOperations = options.maxInFlightOperations();
        this.maxBufferedBytes = options.maxBufferedBytes();
        this.operationTimeoutNanos = options.operationTimeout().toNanos();
        this.connectTimeoutNanos = options.connectTimeout().toNanos();
        this.attemptTimeoutNanos = options.attemptTimeout().toNanos();
        this.maxAttempts = options.maxAttempts();
        this.retryBaseDelayMillis = options.retryBaseDelay().toMillis();
        this.retryMaxDelayMillis = options.retryMaxDelay().toMillis();
        MonotonicClock configuredClock = options.monotonicClock();
        this.monotonicClock = configuredClock == null ? System::nanoTime : configuredClock;
        IdempotencyKeyGenerator configuredKeyGenerator = options.idempotencyKeyGenerator();
        this.idempotencyKeyGenerator = configuredKeyGenerator == null
                ? () -> UUID.randomUUID().toString() : configuredKeyGenerator;
        WallClock configuredWallClock = options.wallClock();
        this.wallClock = configuredWallClock == null ? Instant::now : configuredWallClock;
        RetryEntropy configuredEntropy = options.retryEntropy();
        this.retryEntropy = configuredEntropy == null
                ? secureRetryEntropy() : configuredEntropy;
        DefaultValueGenerators configuredGenerators = options.defaultValueGenerators();
        this.defaultValueGenerators = configuredGenerators == null
                ? DefaultGenerators.system() : configuredGenerators;
        this.observability = new RuntimeObservability(
                options.observer(),
                options.observerExecutor(),
                options.telemetry(),
                monotonicClock,
                wallClock,
                this::handleAsynchronousFatal);
        this.userAgent = options.userAgentSuffix() == null
                ? USER_AGENT : USER_AGENT + " " + options.userAgentSuffix();
        HttpTransportOptions httpOptions = options.httpTransportOptions();
        if (httpOptions != null && httpOptions.proxy() != null
                && !"https".equals(endpointUri.getScheme())) {
            throw proxyRequiresHttps();
        }
        this.credentialResolver = new OperationCredentialResolver(
                options.apiKeyProvider(), options.apiKey(), environment);

        ExecutorService configuredExecutor = options.executor();
        if (configuredExecutor == null) {
            this.executor = testResources == null
                    ? createOperationExecutor(options.maxInFlightOperations())
                    : testResources.executor;
            this.ownsExecutor = true;
        } else {
            this.executor = configuredExecutor;
            this.ownsExecutor = false;
        }

        java.util.concurrent.ScheduledExecutorService configuredScheduler = options.scheduler();
        if (configuredScheduler == null) {
            this.scheduler = testResources == null
                    ? createScheduler() : testResources.scheduler;
            this.ownsScheduler = true;
        } else {
            this.scheduler = configuredScheduler;
            this.ownsScheduler = false;
        }
        Transport configuredTransport = options.transport();
        if (configuredTransport == null) {
            this.transport = testResources == null
                    ? new ApacheHttpTransport(
                            httpOptions == null ? HttpTransportOptions.defaults() : httpOptions,
                            options.maxInFlightOperations(),
                            monotonicClock,
                            executor,
                            scheduler)
                    : testResources.transport;
            this.ownsTransport = true;
        } else {
            this.transport = configuredTransport;
            this.ownsTransport = false;
        }
    }

    /**
     * Returns a runtime using production defaults and environment fallbacks.
     *
     * @return new runtime
     */
    public static RepostRuntime create() {
        return create(ClientOptions.builder().build());
    }

    /**
     * Creates a runtime from immutable options.
     *
     * @param options validated runtime options
     * @return new runtime
     */
    public static RepostRuntime create(ClientOptions options) {
        return new RepostRuntime(Objects.requireNonNull(options, "options"), System::getenv);
    }

    static RepostRuntime createForTesting(
            ClientOptions options,
            Function<String, String> environment) {
        return new RepostRuntime(
                Objects.requireNonNull(options, "options"),
                Objects.requireNonNull(environment, "environment"));
    }

    static RepostRuntime createForTesting(
            ClientOptions options,
            Function<String, String> environment,
            ExecutorService ownedExecutor,
            java.util.concurrent.ScheduledExecutorService ownedScheduler,
            Transport ownedTransport) {
        Objects.requireNonNull(options, "options");
        if (options.executor() != null
                || options.scheduler() != null
                || options.transport() != null) {
            throw new IllegalArgumentException(
                    "owned test resources require absent configured resources");
        }
        return new RepostRuntime(
                options,
                Objects.requireNonNull(environment, "environment"),
                new OwnedResources(ownedExecutor, ownedScheduler, ownedTransport));
    }

    /**
     * Sends synchronously through the same path as {@link #sendAsync}.
     *
     * @param schema generated schema descriptor
     * @param event generated event descriptor
     * @param customerId customer identifier
     * @param model immutable generated model
     * @param options per-send options
     * @return accepted send result
     */
    public SendResult send(
            SchemaDescriptor schema,
            EventDescriptor event,
            String customerId,
            RepostModel model,
            SendOptions options) {
        SendOperation operation = sendAsync(schema, event, customerId, model, options);
        try {
            return operation.get();
        } catch (InterruptedException exception) {
            boolean cancellationWon = operation.cancel(true);
            Thread.currentThread().interrupt();
            if (cancellationWon || operation.isCancelled()) {
                throw cancelledTransportException(
                        operation.outcome().toCompletableFuture().join());
            }
            try {
                return operation.toCompletableFuture().join();
            } catch (CompletionException completed) {
                Throwable cause = completed.getCause();
                if (cause instanceof RepostException) {
                    throw (RepostException) cause;
                }
                if (cause instanceof Error) {
                    throw (Error) cause;
                }
                throw transportException(
                        RepostErrorCode.IO,
                        DeliveryState.NOT_SENT,
                        RepostCauseCategory.HTTP_RUNTIME);
            }
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RepostException) {
                throw (RepostException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw transportException(
                    RepostErrorCode.IO,
                    DeliveryState.NOT_SENT,
                    RepostCauseCategory.HTTP_RUNTIME);
        }
    }

    /**
     * Starts one cancellable asynchronous send.
     *
     * @param schema generated schema descriptor
     * @param event generated event descriptor
     * @param customerId customer identifier
     * @param model immutable generated model
     * @param options per-send options
     * @return cancellable send operation
     */
    public SendOperation sendAsync(
            SchemaDescriptor schema,
            EventDescriptor event,
            String customerId,
            RepostModel model,
            SendOptions options) {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(options, "options");
        if (schema.getDescriptorFormatVersion() != SchemaDescriptor.SUPPORTED_FORMAT_VERSION) {
            RepostErrorDetails details = RepostErrorDetails.builder(
                    RepostErrorCode.DESCRIPTOR_VERSION, DeliveryState.NOT_SENT).build();
            return failedOperation(RepostExceptionFactory.create(details));
        }
        String operationId;
        OperationCredentialResolver admittedResolver;
        ExecutorService admittedExecutor;
        java.util.concurrent.ScheduledExecutorService admittedScheduler;
        Transport admittedTransport;
        OperationSettlement settlement;
        synchronized (lifecycleLock) {
            if (lifecycle != Lifecycle.OPEN) {
                return failedOperation(
                        transportException(RepostErrorCode.CLOSED, DeliveryState.NOT_SENT, null));
            }
            operationId = "op_" + UUID.randomUUID();
            if (inFlightOperations >= maxInFlightOperations) {
                concurrencyOverloadRejections = incrementCounter(concurrencyOverloadRejections);
                return failedOperation(
                        transportException(
                                RepostErrorCode.OVERLOADED,
                                DeliveryState.NOT_SENT,
                                null,
                                operationId));
            }
            if (bufferedBytes > maxBufferedBytes - INITIAL_BYTE_RESERVATION) {
                requestByteOverloadRejections = incrementCounter(requestByteOverloadRejections);
                return failedOperation(
                        transportException(
                                RepostErrorCode.OVERLOADED,
                                DeliveryState.NOT_SENT,
                                null,
                                operationId));
            }
            inFlightOperations++;
            bufferedBytes += INITIAL_BYTE_RESERVATION;
            settlement = new OperationSettlement(
                    operationId,
                    INITIAL_BYTE_RESERVATION,
                    this::updateOperationResources,
                    this::completeCloseAfterOperationPublication,
                    this::beginFatalShutdown);
            activeSettlements.add(settlement);
            admittedResolver = credentialResolver;
            admittedExecutor = executor;
            admittedScheduler = scheduler;
            admittedTransport = transport;
        }
        long operationStartNanos;
        try {
            operationStartNanos = monotonicClock.nanoTime();
        } catch (RuntimeException failure) {
            failMonotonicClock(settlement);
            return settlement.view();
        }
        if (observability.isEnabled()) {
            try {
                settlement.attachObservability(
                        observability.begin(operationId, operationStartNanos));
            } catch (Error fatal) {
                settlement.fatal(fatal);
                return settlement.view();
            }
        }
        OperationDeadline operationDeadline =
                new OperationDeadline(operationStartNanos, operationTimeoutNanos);

        if (!scheduleDeadline(admittedScheduler, settlement, operationDeadline)) {
            return settlement.view();
        }

        SubmissionGuard submissionGuard = new SubmissionGuard();
        try {
            Future<?> worker = admittedExecutor.submit(() -> {
                if (!settlement.beginWorker()) {
                    return;
                }
                try {
                    if (submissionGuard.isInlineBeforeReturn()) {
                        RepostConfigurationException failure =
                                unsupportedInlineExecutor(settlement.operationId());
                        settlement.fail(failure, failedOutcome(failure));
                        return;
                    }
                    runAdmittedOperation(
                            admittedResolver,
                            admittedTransport,
                            admittedExecutor,
                            admittedScheduler,
                            settlement,
                            operationDeadline,
                            schema,
                            event,
                            customerId,
                            model,
                            options);
                } catch (Error fatal) {
                    settlement.fatal(fatal);
                } finally {
                    settlement.finishWorker();
                }
            });
            submissionGuard.markReturned();
            settlement.attachWorker(worker);
        } catch (RejectedExecutionException rejection) {
            submissionGuard.markReturned();
            synchronized (lifecycleLock) {
                executorOverloadRejections = incrementCounter(executorOverloadRejections);
            }
            RepostTransportException failure = transportException(
                    RepostErrorCode.OVERLOADED,
                    DeliveryState.NOT_SENT,
                    null,
                    settlement.operationId());
            settlement.fail(failure, failedOutcome(failure));
        }
        return settlement.view();
    }

    /**
     * Returns a credential-free point-in-time diagnostics snapshot.
     *
     * @return runtime diagnostics
     */
    public RuntimeDiagnostics diagnostics() {
        synchronized (lifecycleLock) {
            return new RuntimeDiagnostics(
                    inFlightOperations,
                    bufferedBytes,
                    concurrencyOverloadRejections,
                    requestByteOverloadRejections,
                    0L,
                    0L,
                    executorOverloadRejections,
                    schedulerOverloadRejections,
                    0L,
                    observability.droppedObserverEvents(),
                    observability.observerFailures(),
                    observability.telemetryFailures(),
                    lifecycle == Lifecycle.CLOSED);
        }
    }

    /**
     * Reports whether close completed.
     *
     * @return {@code true} after owned resources terminate
     */
    public boolean isClosed() {
        synchronized (lifecycleLock) {
            return lifecycle == Lifecycle.CLOSED;
        }
    }

    /**
     * Returns the stable read-only close stage.
     *
     * @return non-cancellable close completion
     */
    public CompletionStage<Void> closeCompletion() {
        return closeStage;
    }

    @Override
    public void close() {
        boolean closeOwner = false;
        long closeDeadline;
        List<OperationSettlement> closingOperations = Collections.emptyList();
        List<OperationSettlement> observedOperations;
        synchronized (lifecycleLock) {
            if (lifecycle == Lifecycle.CLOSED) {
                throwStoredCloseFailure();
                return;
            }
            if (lifecycle == Lifecycle.OPEN) {
                lifecycle = Lifecycle.CLOSING;
                closeDeadlineNanos = System.nanoTime() + CLOSE_BUDGET_NANOS;
                closeOwner = true;
                closingOperations = new ArrayList<>(activeSettlements);
            }
            closeDeadline = closeDeadlineNanos;
            observedOperations = new ArrayList<>(activeSettlements);
        }
        boolean reentrantOperation = hasCurrentOperationWorker(observedOperations);
        if (!closeOwner) {
            if (!reentrantOperation) {
                awaitCloseCompletion(closeDeadline);
                throwStoredCloseFailureIfClosed();
            }
            return;
        }
        runCloseStages(closingOperations, closeDeadline, reentrantOperation);
        if (reentrantOperation) {
            return;
        }
        awaitCloseCompletion(closeDeadline);
        throwStoredCloseFailureIfClosed();
    }

    URI endpointUri() {
        return endpointUri;
    }

    ExecutorService executor() {
        return executor;
    }

    java.util.concurrent.ScheduledExecutorService scheduler() {
        return scheduler;
    }

    Transport transport() {
        return transport;
    }

    OperationCredentialResolver credentialResolver() {
        return credentialResolver;
    }

    @Override
    public String toString() {
        return "RepostRuntime[state=" + lifecycleSnapshot() + "]";
    }

    private String lifecycleSnapshot() {
        synchronized (lifecycleLock) {
            return lifecycle.name();
        }
    }

    private static ExecutorService createOperationExecutor(int maxInFlightOperations) {
        int processors = Runtime.getRuntime().availableProcessors();
        int workers = Math.min(32, Math.max(4, processors * 2));
        ThreadPoolExecutor result = new ThreadPoolExecutor(
                workers,
                workers,
                30L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(maxInFlightOperations),
                namedDaemonFactory("repost-operation"),
                new ThreadPoolExecutor.AbortPolicy());
        result.allowCoreThreadTimeOut(true);
        return result;
    }

    private static ScheduledThreadPoolExecutor createScheduler() {
        int processors = Runtime.getRuntime().availableProcessors();
        int workers = Math.min(8, Math.max(2, (processors + 1) / 2));
        ScheduledThreadPoolExecutor result = new ScheduledThreadPoolExecutor(
                workers, namedDaemonFactory("repost-timer"));
        result.setRemoveOnCancelPolicy(true);
        result.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        result.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return result;
    }

    private static ThreadFactory namedDaemonFactory(String prefix) {
        return new ThreadFactory() {
            private final java.util.concurrent.atomic.AtomicLong sequence =
                    new java.util.concurrent.atomic.AtomicLong();

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = Executors.defaultThreadFactory().newThread(runnable);
                thread.setName(prefix + "-" + sequence.incrementAndGet());
                thread.setDaemon(true);
                thread.setContextClassLoader(null);
                return thread;
            }
        };
    }

    private static RetryEntropy secureRetryEntropy() {
        SecureRandom random = new SecureRandom();
        return exclusiveUpperBound -> secureBoundedLong(random, exclusiveUpperBound);
    }

    private static long secureBoundedLong(SecureRandom random, long bound) {
        if (bound <= 0L) {
            throw new IllegalArgumentException("bound must be positive");
        }
        long mask = bound - 1L;
        long sampled = random.nextLong() >>> 1;
        if ((bound & mask) == 0L) {
            return sampled & mask;
        }
        long candidate = sampled % bound;
        while (sampled + mask - candidate < 0L) {
            sampled = random.nextLong() >>> 1;
            candidate = sampled % bound;
        }
        return candidate;
    }

    private void runCloseStages(
            List<OperationSettlement> closingOperations,
            long deadline,
            boolean reentrantOperation) {
        boolean interrupted = Thread.currentThread().isInterrupted();
        ArrayList<RepostCauseCategory> failures = new ArrayList<>(3);
        for (OperationSettlement settlement : closingOperations) {
            settlement.closeByRuntime();
        }

        observability.close(deadline);

        if (ownsScheduler) {
            CloseStageResult schedulerResult = closeExecutorStage(scheduler, deadline, false);
            interrupted |= schedulerResult.interrupted;
            if (schedulerResult.failed) {
                failures.add(RepostCauseCategory.SCHEDULER_CLOSE);
            }
        }
        if (ownsTransport && !closeOwnedTransport(transport)) {
            failures.add(RepostCauseCategory.TRANSPORT_CLOSE);
        }
        // Caller-supplied transports remain borrowed, even when AutoCloseable.
        if (ownsExecutor) {
            CloseStageResult executorResult = closeExecutorStage(
                    executor, deadline, reentrantOperation);
            interrupted |= executorResult.interrupted;
            if (executorResult.failed) {
                failures.add(RepostCauseCategory.OPERATION_EXECUTOR_CLOSE);
            }
        }

        synchronized (lifecycleLock) {
            closeFailure = failures.isEmpty() ? null : aggregateCloseFailure(failures);
            closeStagesFinished = true;
            completeCloseIfReady();
            lifecycleLock.notifyAll();
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void beginFatalShutdown(Error fatal) {
        List<OperationSettlement> closingOperations = Collections.emptyList();
        long deadline = System.nanoTime() + CLOSE_BUDGET_NANOS;
        boolean closeOwner = false;
        synchronized (lifecycleLock) {
            if (lifecycle == Lifecycle.OPEN) {
                lifecycle = Lifecycle.CLOSING;
                closeDeadlineNanos = deadline;
                closingOperations = new ArrayList<>(activeSettlements);
                closeOwner = true;
            } else if (closeDeadlineNanos != 0L) {
                deadline = closeDeadlineNanos;
            }
        }
        if (FatalThrowable.isUnrecoverable(fatal)) {
            return;
        }
        List<OperationSettlement> operations = closingOperations;
        long closeDeadline = deadline;
        boolean ownsClose = closeOwner;
        Thread shutdown = new Thread(() -> {
            try {
                if (ownsClose) {
                    runCloseStages(operations, closeDeadline, false);
                } else {
                    awaitCloseCompletion(closeDeadline);
                }
            } catch (RuntimeException ignored) {
                // The originating fatal Error remains the mandatory uncaught boundary.
            }
            throw fatal;
        }, "repost-fatal-shutdown");
        shutdown.setDaemon(true);
        shutdown.setContextClassLoader(null);
        shutdown.start();
    }

    private void handleAsynchronousFatal(String operationId, Error fatal) {
        OperationSettlement originating = null;
        synchronized (lifecycleLock) {
            for (OperationSettlement settlement : activeSettlements) {
                if (settlement.operationId().equals(operationId)) {
                    originating = settlement;
                    break;
                }
            }
        }
        if (originating == null) {
            beginFatalShutdown(fatal);
        } else {
            originating.fatal(fatal);
        }
    }

    private static CloseStageResult closeExecutorStage(
            ExecutorService resource,
            long deadline,
            boolean skipCurrentWait) {
        boolean failed = false;
        boolean interrupted = false;
        try {
            resource.shutdown();
        } catch (RuntimeException failure) {
            return new CloseStageResult(true, false);
        }
        if (skipCurrentWait) {
            return new CloseStageResult(false, false);
        }

        long remaining = deadline - System.nanoTime();
        try {
            if (remaining <= 0L
                    || !resource.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
                failed = true;
                try {
                    resource.shutdownNow();
                } catch (RuntimeException shutdownFailure) {
                    // One bounded category represents every failure in this close stage.
                }
            }
        } catch (InterruptedException waitInterrupted) {
            interrupted = true;
            failed = true;
            try {
                resource.shutdownNow();
            } catch (RuntimeException shutdownFailure) {
                // One bounded category represents every failure in this close stage.
            }
        }
        return new CloseStageResult(failed, interrupted);
    }

    private static boolean closeOwnedTransport(Transport resource) {
        if (!(resource instanceof AutoCloseable)) {
            return false;
        }
        try {
            ((AutoCloseable) resource).close();
            return true;
        } catch (Exception failure) {
            return false;
        }
    }

    private static boolean hasCurrentOperationWorker(
            List<OperationSettlement> settlements) {
        for (OperationSettlement settlement : settlements) {
            if (settlement.isCurrentWorker()) {
                return true;
            }
        }
        return false;
    }

    private void awaitCloseCompletion(long deadline) {
        synchronized (lifecycleLock) {
            while (lifecycle != Lifecycle.CLOSED) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    return;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(lifecycleLock, remaining);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void completeCloseIfReady() {
        if (lifecycle != Lifecycle.CLOSING
                || !closeStagesFinished
                || inFlightOperations != 0
                || bufferedBytes != 0L
                || !activeSettlements.isEmpty()) {
            return;
        }
        credentialResolver = null;
        transport = null;
        executor = null;
        scheduler = null;
        lifecycle = Lifecycle.CLOSED;
        if (closeFailure == null) {
            closeFuture.complete(null);
        } else {
            closeFuture.completeExceptionally(closeFailure);
        }
        lifecycleLock.notifyAll();
    }

    private void throwStoredCloseFailureIfClosed() {
        synchronized (lifecycleLock) {
            if (lifecycle == Lifecycle.CLOSED) {
                throwStoredCloseFailure();
            }
        }
    }

    private void throwStoredCloseFailure() {
        if (closeFailure != null) {
            throw closeFailure;
        }
    }

    private static RepostTransportException aggregateCloseFailure(
            List<RepostCauseCategory> failures) {
        RepostErrorDetails details = RepostErrorDetails.builder(
                        RepostErrorCode.IO, DeliveryState.NOT_SENT)
                .closeFailures(failures)
                .build();
        return new RepostTransportException(details);
    }

    private static RepostTransportException transportException(
            RepostErrorCode code,
            DeliveryState deliveryState,
            RepostCauseCategory causeCategory) {
        return transportException(code, deliveryState, causeCategory, null);
    }

    private static RepostTransportException transportException(
            RepostErrorCode code,
            DeliveryState deliveryState,
            RepostCauseCategory causeCategory,
            String operationId) {
        RepostErrorDetails.Builder details = RepostErrorDetails.builder(code, deliveryState);
        if (operationId != null) {
            details.operationId(operationId);
        }
        if (causeCategory != null) {
            details.causeCategory(causeCategory);
        }
        if (code == RepostErrorCode.IO) {
            details.attemptCount(1).retryable(true);
        }
        return (RepostTransportException) RepostExceptionFactory.create(details.build());
    }

    private boolean scheduleDeadline(
            java.util.concurrent.ScheduledExecutorService admittedScheduler,
            OperationSettlement settlement,
            OperationDeadline deadline) {
        if (settlement.isTerminal()) {
            return false;
        }
        long delayNanos;
        try {
            delayNanos = deadline.remainingNanos(monotonicClock.nanoTime());
        } catch (RuntimeException failure) {
            failMonotonicClock(settlement);
            return false;
        }
        if (delayNanos == 0L) {
            failOperationDeadline(settlement);
            return false;
        }
        try {
            ScheduledFuture<?> scheduled = admittedScheduler.schedule(
                    () -> onDeadline(admittedScheduler, settlement, deadline),
                    delayNanos,
                    TimeUnit.NANOSECONDS);
            settlement.attachDeadline(scheduled);
            return true;
        } catch (RejectedExecutionException rejection) {
            synchronized (lifecycleLock) {
                schedulerOverloadRejections = incrementCounter(schedulerOverloadRejections);
            }
            RepostTransportException failure = transportException(
                    RepostErrorCode.OVERLOADED,
                    DeliveryState.NOT_SENT,
                    null,
                    settlement.operationId());
            settlement.fail(failure, failedOutcome(failure));
            return false;
        }
    }

    private void onDeadline(
            java.util.concurrent.ScheduledExecutorService admittedScheduler,
            OperationSettlement settlement,
            OperationDeadline deadline) {
        if (settlement.isTerminal()) {
            return;
        }
        scheduleDeadline(admittedScheduler, settlement, deadline);
    }

    private static void failOperationDeadline(OperationSettlement settlement) {
        OperationEvidence evidence = settlement.evidence();
        RepostErrorDetails.Builder details = RepostErrorDetails.builder(
                        RepostErrorCode.OPERATION_DEADLINE,
                        evidence.unresolved ? DeliveryState.POSSIBLY_SENT : DeliveryState.NOT_SENT)
                .operationId(settlement.operationId())
                .attemptCount(evidence.attemptCount);
        if (evidence.idempotencyKey != null) {
            details.idempotencyKey(evidence.idempotencyKey);
        }
        RepostTransportException failure = (RepostTransportException)
                RepostExceptionFactory.create(details.build());
        settlement.fail(failure, failedOutcome(failure));
    }

    private void runAdmittedOperation(
            OperationCredentialResolver admittedResolver,
            Transport admittedTransport,
            ExecutorService admittedExecutor,
            java.util.concurrent.ScheduledExecutorService admittedScheduler,
            OperationSettlement settlement,
            OperationDeadline deadline,
            SchemaDescriptor schema,
            EventDescriptor event,
            String customerId,
            RepostModel model,
            SendOptions sendOptions) {
        settlement.startObservabilityWorker();
        try (TelemetryScope ignored = settlement.makeOperationCurrent()) {
            runAdmittedOperationWithContext(
                    admittedResolver,
                    admittedTransport,
                    admittedExecutor,
                    admittedScheduler,
                    settlement,
                    deadline,
                    schema,
                    event,
                    customerId,
                    model,
                    sendOptions);
        }
    }

    private void runAdmittedOperationWithContext(
            OperationCredentialResolver admittedResolver,
            Transport admittedTransport,
            ExecutorService admittedExecutor,
            java.util.concurrent.ScheduledExecutorService admittedScheduler,
            OperationSettlement settlement,
            OperationDeadline deadline,
            SchemaDescriptor schema,
            EventDescriptor event,
            String customerId,
            RepostModel model,
            SendOptions sendOptions) {
        if (settlement.isTerminal()) {
            return;
        }
        if (failIfDeadlineExpired(settlement, deadline)) {
            return;
        }
        String credential;
        try {
            credential = admittedResolver.resolve().value();
        } catch (RepostConfigurationException exception) {
            if (failIfDeadlineExpired(settlement, deadline)) {
                return;
            }
            RepostConfigurationException failure = (RepostConfigurationException)
                    copyWithOperationEvidence(exception, settlement);
            settlement.fail(failure, failedOutcome(failure));
            return;
        }
        String idempotencyKey = sendOptions.getIdempotencyKey();
        if (idempotencyKey == null) {
            try {
                idempotencyKey = SendOptions.validateIdempotencyKey(
                        idempotencyKeyGenerator.generate());
            } catch (RuntimeException failure) {
                if (failIfDeadlineExpired(settlement, deadline)) {
                    return;
                }
                failConfigurationHook(
                        settlement,
                        ClientOptionKey.IDEMPOTENCY_KEY_GENERATOR,
                        RepostCauseCategory.IDEMPOTENCY_GENERATOR,
                        DeliveryState.NOT_SENT,
                        0,
                        null,
                        null);
                return;
            }
        }
        settlement.recordEvidence(0, idempotencyKey, false);
        if (settlement.isTerminal()) {
            return;
        }
        if (failIfDeadlineExpired(settlement, deadline)) {
            return;
        }
        Serializer.SerializedEnvelope serializedEnvelope;
        try {
            serializedEnvelope = Serializer.serializeOperationEnvelope(
                    schema,
                    event.getModelId(),
                    event.getType(),
                    customerId,
                    model,
                    defaultValueGenerators);
        } catch (RepostException exception) {
            if (failIfDeadlineExpired(settlement, deadline)) {
                return;
            }
            RepostException failure = copyWithOperationEvidence(exception, settlement);
            settlement.fail(failure, failedOutcome(failure));
            return;
        }
        byte[] serializedRequest = serializedEnvelope.getBytes();
        if (!retainSerializedRequest(settlement, serializedRequest)) {
            return;
        }
        if (failIfDeadlineExpired(settlement, deadline)) {
            return;
        }
        AttemptContext context = new AttemptContext(
                admittedTransport,
                admittedExecutor,
                admittedScheduler,
                ownsTransport
                        ? RepostCauseCategory.HTTP_RUNTIME
                        : RepostCauseCategory.CUSTOM_TRANSPORT,
                settlement,
                deadline,
                credential,
                idempotencyKey,
                serializedRequest,
                event.getType(),
                customerId,
                serializedEnvelope.getTimestamp());
        executeAttempt(context);
    }

    private void executeAttempt(AttemptContext context) {
        if (context.settlement.isTerminal()) {
            return;
        }
        long remainingNanos = remainingNanos(context.settlement, context.deadline);
        if (remainingNanos == 0L) {
            return;
        }
        context.attemptCount++;
        context.attemptGeneration++;
        context.unresolvedBeforeAttempt = context.unresolved;
        context.unresolved = true;
        context.settlement.recordEvidence(
                context.attemptCount, context.idempotencyKey, context.unresolved);
        Duration connectTimeout = Duration.ofNanos(Math.min(connectTimeoutNanos, remainingNanos));
        Duration attemptTimeout = Duration.ofNanos(Math.min(attemptTimeoutNanos, remainingNanos));
        long attemptGeneration = context.attemptGeneration;
        if (!context.settlement.prepareAttempt(attemptGeneration)) {
            return;
        }
        context.settlement.startObservedAttempt(context.attemptCount);
        ScheduledFuture<?> timeout;
        try {
            timeout = context.scheduler.schedule(
                    () -> {
                        try (TelemetryScope ignored = context.settlement.makeAttemptCurrent()) {
                            onAttemptTimeout(context, attemptGeneration);
                        }
                    },
                    attemptTimeout.toNanos(),
                    TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException rejection) {
            context.settlement.finishAttempt(attemptGeneration);
            synchronized (lifecycleLock) {
                schedulerOverloadRejections = incrementCounter(schedulerOverloadRejections);
            }
            failRuntimeHandoff(context);
            return;
        }
        context.settlement.attachAttemptTimeout(attemptGeneration, timeout);
        if (context.settlement.isTerminal()) {
            return;
        }
        TransportRequest request = TransportRequest.reusingRetainedBody(
                endpointUri,
                context.headers,
                context.requestBody,
                context.attemptCount,
                connectTimeout,
                attemptTimeout);
        CompletionStage<TransportResponse> responseStage;
        try (TelemetryScope ignored = context.settlement.makeAttemptCurrent()) {
            responseStage = context.transport.execute(request);
        } catch (RuntimeException failure) {
            failCustomTransportDefect(context, attemptGeneration);
            return;
        }
        if (responseStage == null) {
            failCustomTransportDefect(context, attemptGeneration);
            return;
        }
        CompletableFuture<TransportResponse> cancellableAttempt;
        try {
            cancellableAttempt = responseStage.toCompletableFuture();
        } catch (RuntimeException failure) {
            failCustomTransportDefect(context, attemptGeneration);
            return;
        }
        if (cancellableAttempt == null) {
            failCustomTransportDefect(context, attemptGeneration);
            return;
        }
        context.settlement.attachAttempt(attemptGeneration, cancellableAttempt);
        try {
            responseStage.whenComplete((response, failure) ->
                    submitAttemptCompletion(
                            context, attemptGeneration, response, failure));
        } catch (RuntimeException failure) {
            failCustomTransportDefect(context, attemptGeneration);
        }
    }

    private void onAttemptTimeout(AttemptContext context, long attemptGeneration) {
        if (context.settlement.isTerminal()
                || failIfDeadlineExpired(context.settlement, context.deadline)
                || !context.settlement.expireAttempt(attemptGeneration)) {
            return;
        }
        context.lastHttpStatus = null;
        context.lastErrorCode = RepostErrorCode.ATTEMPT_TIMEOUT;
        context.lastFailureReason = null;
        if (context.attemptCount < maxAttempts) {
            scheduleRetry(context, null);
            return;
        }
        RepostErrorDetails details = RepostErrorDetails.builder(
                        RepostErrorCode.ATTEMPT_TIMEOUT,
                        context.unresolved
                                ? DeliveryState.POSSIBLY_SENT : DeliveryState.NOT_SENT)
                .operationId(context.settlement.operationId())
                .attemptCount(context.attemptCount)
                .idempotencyKey(context.idempotencyKey)
                .retryable(true)
                .build();
        fail(context.settlement, details);
    }

    private void submitAttemptCompletion(
            AttemptContext context,
            long attemptGeneration,
            TransportResponse response,
            Throwable failure) {
        if (response != null && !context.settlement.attachResponse(
                attemptGeneration,
                response,
                context.attemptCount,
                context.idempotencyKey,
                unresolvedAfterStatus(context, response.getStatusCode()))) {
            closeResponseSafely(response);
            return;
        }
        if (context.settlement.isTerminal()) {
            if (response != null) {
                closeResponseSafely(response);
            }
            return;
        }
        try {
            context.executor.execute(() -> {
                try (TelemetryScope ignored = context.settlement.makeAttemptCurrent()) {
                    handleAttemptCompletion(context, attemptGeneration, response, failure);
                }
            });
        } catch (RejectedExecutionException rejection) {
            if (response != null) {
                context.settlement.releaseResponse(attemptGeneration, response);
                closeResponseSafely(response);
            }
            failRuntimeHandoff(context);
        }
    }

    private void handleAttemptCompletion(
            AttemptContext context,
            long attemptGeneration,
            TransportResponse response,
            Throwable rawFailure) {
        if (!context.settlement.isCurrentAttempt(attemptGeneration)) {
            if (response != null) {
                context.settlement.releaseResponse(attemptGeneration, response);
                closeResponseSafely(response);
            }
            return;
        }
        if (context.settlement.isTerminal()) {
            if (response != null) {
                context.settlement.releaseResponse(attemptGeneration, response);
                closeResponseSafely(response);
            }
            return;
        }
        if (rawFailure != null) {
            if (response != null) {
                context.settlement.releaseResponse(attemptGeneration, response);
                closeResponseSafely(response);
            }
            if (!context.settlement.finishAttempt(attemptGeneration)) {
                return;
            }
            Throwable failure = unwrapCompletionFailure(rawFailure);
            if (FatalThrowable.isFatal(failure)) {
                context.settlement.fatal((Error) failure);
            } else if (failure instanceof TransportFailure) {
                handleTransportFailure(context, (TransportFailure) failure);
            } else if (failure instanceof BuiltinTransportFailure
                    && context.transportCauseCategory == RepostCauseCategory.HTTP_RUNTIME) {
                handleBuiltinTransportFailure(context, (BuiltinTransportFailure) failure);
            } else {
                failCustomTransportDefect(context);
            }
            return;
        }
        if (response == null) {
            failCustomTransportDefect(context, attemptGeneration);
            return;
        }
        handleTransportResponse(context, attemptGeneration, response);
    }

    private static boolean unresolvedAfterStatus(AttemptContext context, int status) {
        if ((status >= 200 && status <= 299) || status == 409 || status >= 500) {
            return true;
        }
        return context.unresolvedBeforeAttempt;
    }

    private void handleTransportFailure(AttemptContext context, TransportFailure failure) {
        context.lastHttpStatus = null;
        context.lastErrorCode = failure.getErrorCode();
        context.lastFailureReason = failure.getFailureReason();
        if (failure.getCommitState() == RequestCommitState.NOT_COMMITTED) {
            context.unresolved = context.unresolvedBeforeAttempt;
        } else {
            context.unresolved = true;
        }
        context.settlement.recordEvidence(
                context.attemptCount, context.idempotencyKey, context.unresolved);
        boolean retryable = failure.getErrorCode() != RepostErrorCode.TLS
                && !(failure.getErrorCode() == RepostErrorCode.PROXY
                        && failure.getFailureReason() == RepostFailureReason.PROXY_AUTH_REQUIRED);
        if (retryable && context.attemptCount < maxAttempts) {
            scheduleRetry(context, null);
            return;
        }
        RepostErrorDetails.Builder details = RepostErrorDetails.builder(
                        failure.getErrorCode(),
                        context.unresolved ? DeliveryState.POSSIBLY_SENT : DeliveryState.NOT_SENT)
                .operationId(context.settlement.operationId())
                .attemptCount(context.attemptCount)
                .idempotencyKey(context.idempotencyKey)
                .retryable(retryable)
                .causeCategory(context.transportCauseCategory);
        if (failure.getFailureReason() != null) {
            details.failureReason(failure.getFailureReason());
        }
        fail(context.settlement, details.build());
    }

    private void handleBuiltinTransportFailure(
            AttemptContext context,
            BuiltinTransportFailure failure) {
        context.lastHttpStatus = null;
        context.lastErrorCode = failure.errorCode();
        context.lastFailureReason = failure.failureReason();
        if (failure.commitState() == RequestCommitState.NOT_COMMITTED) {
            context.unresolved = context.unresolvedBeforeAttempt;
        } else {
            context.unresolved = true;
        }
        context.settlement.recordEvidence(
                context.attemptCount, context.idempotencyKey, context.unresolved);
        if (failure.errorCode() == RepostErrorCode.CONFIGURATION) {
            failMonotonicClock(context.settlement);
            return;
        }
        if (failure.retryable() && context.attemptCount < maxAttempts) {
            scheduleRetry(context, null);
            return;
        }
        RepostErrorDetails.Builder details = RepostErrorDetails.builder(
                        failure.errorCode(),
                        context.unresolved ? DeliveryState.POSSIBLY_SENT : DeliveryState.NOT_SENT)
                .operationId(context.settlement.operationId())
                .retryable(failure.retryable());
        if (failure.hasAttemptEvidence()) {
            details.attemptCount(context.attemptCount)
                    .idempotencyKey(context.idempotencyKey);
        }
        if (failure.failureReason() != null) {
            details.failureReason(failure.failureReason());
        }
        if (failure.causeCategory() != null) {
            details.causeCategory(failure.causeCategory());
        }
        fail(context.settlement, details.build());
    }

    private void handleTransportResponse(
            AttemptContext context,
            long attemptGeneration,
            TransportResponse response) {
        int status = response.getStatusCode();
        context.lastHttpStatus = status;
        List<TransportHeaderField> headers = response.getHeaderFields();
        boolean invalidHeaders = response.hasHeaderFailure();

        if (status >= 200 && status <= 299) {
            ResponseParser.Result parsed = invalidHeaders
                    ? null
                    : ResponseParser.parse(
                            response,
                            context.eventType,
                            context.customerId,
                            context.timestamp);
            boolean responseOwned =
                    context.settlement.releaseResponse(attemptGeneration, response);
            closeResponseSafely(response);
            if (!responseOwned) {
                return;
            }
            if (!context.settlement.finishAttempt(attemptGeneration)) {
                return;
            }
            context.unresolved = true;
            context.settlement.recordEvidence(
                    context.attemptCount, context.idempotencyKey, true, status);
            if (parsed != null && parsed.getSendResult() != null) {
                context.settlement.succeed(
                        parsed.getSendResult(),
                        SendOutcome.accepted(
                                context.settlement.operationId(),
                                context.attemptCount,
                                context.idempotencyKey,
                                status));
                return;
            }
            boolean limitFailure = invalidHeaders
                    ? response.getHeaderFailureKind()
                            == TransportResponse.HeaderFailureKind.LIMIT
                    : parsed.getFailure() == ResponseParser.Result.Failure.LIMIT;
            if (!limitFailure && context.attemptCount < maxAttempts) {
                context.lastErrorCode = RepostErrorCode.RESPONSE_PROTOCOL;
                context.lastFailureReason = null;
                scheduleRetry(context, null);
                return;
            }
            failHttp(
                    context,
                    limitFailure
                            ? RepostErrorCode.RESPONSE_TOO_LARGE
                            : RepostErrorCode.RESPONSE_PROTOCOL,
                    DeliveryState.POSSIBLY_SENT,
                    status,
                    !limitFailure);
            return;
        }

        boolean responseOwned =
                context.settlement.releaseResponse(attemptGeneration, response);
        closeResponseSafely(response);
        if (!responseOwned) {
            return;
        }
        if (!context.settlement.finishAttempt(attemptGeneration)) {
            return;
        }

        if (status == 429) {
            context.lastErrorCode = RepostErrorCode.RATE_LIMITED;
            context.lastFailureReason = null;
            context.unresolved = context.unresolvedBeforeAttempt;
            context.settlement.recordEvidence(
                    context.attemptCount, context.idempotencyKey, context.unresolved, status);
            if (!invalidHeaders && context.attemptCount < maxAttempts) {
                scheduleRetry(context, headers);
                return;
            }
            failHttp(
                    context,
                    RepostErrorCode.RATE_LIMITED,
                    context.unresolved ? DeliveryState.POSSIBLY_SENT : DeliveryState.REJECTED,
                    status,
                    true,
                    response);
            return;
        }
        if (status == 409 || status >= 500) {
            context.lastErrorCode = status == 409
                    ? RepostErrorCode.HTTP_REJECTED : RepostErrorCode.SERVER_FAILURE;
            context.lastFailureReason = null;
            context.unresolved = true;
            context.settlement.recordEvidence(
                    context.attemptCount, context.idempotencyKey, true, status);
            if (!invalidHeaders && context.attemptCount < maxAttempts) {
                scheduleRetry(context, headers);
                return;
            }
            failHttp(
                    context,
                    status == 409 ? RepostErrorCode.HTTP_REJECTED : RepostErrorCode.SERVER_FAILURE,
                    DeliveryState.POSSIBLY_SENT,
                    status,
                    true,
                    response);
            return;
        }
        context.unresolved = context.unresolvedBeforeAttempt;
        context.settlement.recordEvidence(
                context.attemptCount, context.idempotencyKey, context.unresolved, status);
        if (status == 407) {
            RepostErrorDetails.Builder details = addResponseBodyDiagnostics(
                    RepostErrorDetails.builder(
                            RepostErrorCode.PROXY,
                            context.unresolved
                                    ? DeliveryState.POSSIBLY_SENT : DeliveryState.NOT_SENT)
                    .operationId(context.settlement.operationId())
                    .attemptCount(context.attemptCount)
                    .idempotencyKey(context.idempotencyKey)
                    .httpStatus(status)
                    .failureReason(RepostFailureReason.PROXY_AUTH_REQUIRED),
                    response);
            fail(context.settlement, details.build());
            return;
        }
        failHttp(
                context,
                RepostErrorCode.HTTP_REJECTED,
                context.unresolved ? DeliveryState.POSSIBLY_SENT : DeliveryState.REJECTED,
                status,
                status == 409,
                response);
    }

    private void scheduleRetry(
            AttemptContext context,
            List<TransportHeaderField> responseHeaders) {
        long remainingNanos = remainingNanos(context.settlement, context.deadline);
        if (remainingNanos == 0L) {
            return;
        }
        long remainingMillis = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
        long capMillis = Math.min(
                Math.min(retryMaxDelayMillis, exponentialCapMillis(context.attemptCount)),
                remainingMillis);
        long entropyBound = capMillis + 1L;
        long jitterMillis;
        try {
            jitterMillis = retryEntropy.nextLong(entropyBound);
            if (jitterMillis < 0L || jitterMillis >= entropyBound) {
                throw new IllegalArgumentException("retry entropy returned an out-of-range value");
            }
        } catch (RuntimeException failure) {
            failConfigurationHook(
                    context.settlement,
                    ClientOptionKey.RETRY_ENTROPY,
                    RepostCauseCategory.RETRY_ENTROPY,
                    context.unresolved ? DeliveryState.POSSIBLY_SENT : DeliveryState.NOT_SENT,
                    context.attemptCount,
                    context.idempotencyKey,
                    context.lastHttpStatus);
            return;
        }
        Long retryAfterMillis = parseRetryAfterMillis(responseHeaders);
        long delayMillis = retryAfterMillis == null
                ? jitterMillis
                : Math.max(
                        jitterMillis,
                        Math.min(retryAfterMillis, capForRetryAfter(remainingMillis)));
        if (context.settlement.isTerminal()) {
            return;
        }
        context.settlement.observeRetry(
                Duration.ofMillis(delayMillis),
                context.lastErrorCode,
                context.lastFailureReason);
        try {
            ScheduledFuture<?> retry = context.scheduler.schedule(
                    () -> submitRetry(context),
                    delayMillis,
                    TimeUnit.MILLISECONDS);
            context.settlement.attachRetry(retry);
        } catch (RejectedExecutionException rejection) {
            synchronized (lifecycleLock) {
                schedulerOverloadRejections = incrementCounter(schedulerOverloadRejections);
            }
            failRuntimeHandoff(context);
        }
    }

    private void submitRetry(AttemptContext context) {
        if (context.settlement.isTerminal()) {
            return;
        }
        try {
            context.executor.execute(() -> executeAttempt(context));
        } catch (RejectedExecutionException rejection) {
            synchronized (lifecycleLock) {
                executorOverloadRejections = incrementCounter(executorOverloadRejections);
            }
            failRuntimeHandoff(context);
        }
    }

    private long remainingNanos(OperationSettlement settlement, OperationDeadline deadline) {
        long remaining;
        try {
            remaining = deadline.remainingNanos(monotonicClock.nanoTime());
        } catch (RuntimeException failure) {
            failMonotonicClock(settlement);
            return 0L;
        }
        if (remaining == 0L) {
            failOperationDeadline(settlement);
        }
        return remaining;
    }

    private long exponentialCapMillis(int completedAttempts) {
        int shift = completedAttempts - 1;
        if (shift >= 63 || retryBaseDelayMillis > (Long.MAX_VALUE >> shift)) {
            return Long.MAX_VALUE;
        }
        return retryBaseDelayMillis << shift;
    }

    private long capForRetryAfter(long remainingMillis) {
        return Math.min(retryMaxDelayMillis, remainingMillis);
    }

    private Long parseRetryAfterMillis(List<TransportHeaderField> responseHeaders) {
        if (responseHeaders == null) {
            return null;
        }
        String value = null;
        for (TransportHeaderField field : responseHeaders) {
            if (!"retry-after".equalsIgnoreCase(field.getName())) {
                continue;
            }
            if (value == null) {
                value = field.getValue();
            } else if (!value.equals(field.getValue())) {
                return null;
            }
        }
        if (value == null || value.isEmpty()) {
            return null;
        }
        Long delta = parseRetryAfterDelta(value);
        if (delta != null) {
            return delta;
        }
        try {
            ZonedDateTime parsed = ZonedDateTime.parse(value, RETRY_AFTER_DATE);
            if (!value.equals(RETRY_AFTER_DATE.format(parsed))) {
                return null;
            }
            Instant now = Objects.requireNonNull(wallClock.now(), "wallClock returned null");
            Duration delay = Duration.between(now, parsed.toInstant());
            if (delay.isZero() || delay.isNegative()) {
                return null;
            }
            return delay.toMillis();
        } catch (DateTimeException | ArithmeticException | NullPointerException failure) {
            return null;
        }
    }

    private static Long parseRetryAfterDelta(String value) {
        long seconds = 0L;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                return null;
            }
            int digit = character - '0';
            if (seconds > (Long.MAX_VALUE - digit) / 10L) {
                return null;
            }
            seconds = seconds * 10L + digit;
        }
        if (seconds == 0L || seconds > Long.MAX_VALUE / 1_000L) {
            return null;
        }
        return seconds * 1_000L;
    }

    private void failCustomTransportDefect(
            AttemptContext context,
            long attemptGeneration) {
        if (!context.settlement.finishAttempt(attemptGeneration)) {
            return;
        }
        failCustomTransportDefect(context);
    }

    private void failCustomTransportDefect(AttemptContext context) {
        context.unresolved = true;
        context.settlement.recordEvidence(
                context.attemptCount, context.idempotencyKey, true);
        RepostErrorDetails details = RepostErrorDetails.builder(
                        RepostErrorCode.IO, DeliveryState.POSSIBLY_SENT)
                .operationId(context.settlement.operationId())
                .attemptCount(context.attemptCount)
                .idempotencyKey(context.idempotencyKey)
                .retryable(false)
                .failureReason(RepostFailureReason.UNKNOWN)
                .causeCategory(RepostCauseCategory.CUSTOM_TRANSPORT)
                .build();
        fail(context.settlement, details);
    }

    private void failRuntimeHandoff(AttemptContext context) {
        RepostErrorDetails details = RepostErrorDetails.builder(
                        RepostErrorCode.IO,
                        context.unresolved ? DeliveryState.POSSIBLY_SENT : DeliveryState.NOT_SENT)
                .operationId(context.settlement.operationId())
                .attemptCount(context.attemptCount)
                .idempotencyKey(context.idempotencyKey)
                .retryable(true)
                .causeCategory(RepostCauseCategory.HTTP_RUNTIME)
                .build();
        fail(context.settlement, details);
    }

    private void failHttp(
            AttemptContext context,
            RepostErrorCode code,
            DeliveryState delivery,
            int status,
            boolean retryable) {
        failHttp(context, code, delivery, status, retryable, null);
    }

    private void failHttp(
            AttemptContext context,
            RepostErrorCode code,
            DeliveryState delivery,
            int status,
            boolean retryable,
            TransportResponse response) {
        context.lastHttpStatus = status;
        RepostErrorDetails.Builder details = addResponseBodyDiagnostics(
                RepostErrorDetails.builder(code, delivery)
                        .operationId(context.settlement.operationId())
                        .attemptCount(context.attemptCount)
                        .idempotencyKey(context.idempotencyKey)
                        .httpStatus(status)
                        .retryable(retryable),
                response);
        fail(context.settlement, details.build());
    }

    private static RepostErrorDetails.Builder addResponseBodyDiagnostics(
            RepostErrorDetails.Builder details,
            TransportResponse response) {
        if (response == null) {
            return details;
        }
        return details
                .compressedBytes(response.getObservedCompressedBytes())
                .decompressedBytes(response.getObservedDecompressedBytes())
                .truncated(response.isBodyDiagnosticTruncated());
    }

    private static Throwable unwrapCompletionFailure(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static void closeResponseSafely(TransportResponse response) {
        try {
            response.close();
        } catch (RuntimeException ignored) {
            // A transport-controlled close defect cannot replace terminal SDK state.
        }
    }

    private static void fail(OperationSettlement settlement, RepostErrorDetails details) {
        RepostException failure = RepostExceptionFactory.create(details);
        settlement.fail(failure, failedOutcome(failure));
    }

    private static void failConfigurationHook(
            OperationSettlement settlement,
            ClientOptionKey optionKey,
            RepostCauseCategory cause,
            DeliveryState delivery,
            int attempts,
            String idempotencyKey,
            Integer httpStatus) {
        ConfigurationIssue issue = ConfigurationIssue.of(
                ConfigurationIssueCode.INVALID_VALUE,
                java.util.Collections.singletonList(optionKey));
        RepostErrorDetails.Builder details = RepostErrorDetails.builder(
                        RepostErrorCode.CONFIGURATION, delivery)
                .operationId(settlement.operationId())
                .attemptCount(attempts)
                .causeCategory(cause)
                .configurationIssues(java.util.Collections.singletonList(issue), 1, false);
        if (idempotencyKey != null) {
            details.idempotencyKey(idempotencyKey);
        }
        if (httpStatus != null) {
            details.httpStatus(httpStatus);
        }
        fail(settlement, details.build());
    }

    private boolean retainSerializedRequest(
            OperationSettlement settlement,
            byte[] serializedRequest) {
        synchronized (settlement) {
            if (settlement.isTerminal()) {
                return false;
            }
            synchronized (lifecycleLock) {
                if (!activeSettlements.contains(settlement)
                        || settlement.reservedBytes != INITIAL_BYTE_RESERVATION) {
                    throw new AssertionError("request reservation converted outside active operation");
                }
                long retainedBytes = RETAINED_RESPONSE_AND_PARSER + serializedRequest.length;
                bufferedBytes -= settlement.reservedBytes - retainedBytes;
                settlement.reservedBytes = retainedBytes;
                settlement.retainedRequestBody = serializedRequest;
                return true;
            }
        }
    }

    private boolean failIfDeadlineExpired(
            OperationSettlement settlement,
            OperationDeadline deadline) {
        boolean expired;
        try {
            expired = deadline.isExpired(monotonicClock.nanoTime());
        } catch (RuntimeException failure) {
            failMonotonicClock(settlement);
            return true;
        }
        if (!expired) {
            return false;
        }
        failOperationDeadline(settlement);
        return true;
    }

    private static void failMonotonicClock(OperationSettlement settlement) {
        OperationEvidence evidence = settlement.evidence();
        ConfigurationIssue issue = ConfigurationIssue.of(
                ConfigurationIssueCode.INVALID_VALUE,
                java.util.Collections.singletonList(ClientOptionKey.MONOTONIC_CLOCK));
        RepostErrorDetails.Builder details = RepostErrorDetails.builder(
                        RepostErrorCode.CONFIGURATION,
                        evidence.unresolved
                                ? DeliveryState.POSSIBLY_SENT : DeliveryState.NOT_SENT)
                .operationId(settlement.operationId())
                .attemptCount(evidence.attemptCount)
                .configurationIssues(java.util.Collections.singletonList(issue), 1, false);
        if (evidence.idempotencyKey != null) {
            details.idempotencyKey(evidence.idempotencyKey);
        }
        RepostConfigurationException failure =
                new RepostConfigurationException(details.build());
        settlement.fail(failure, failedOutcome(failure));
    }

    private static RepostException copyWithOperationEvidence(
            RepostException exception,
            OperationSettlement settlement) {
        OperationEvidence evidence = settlement.evidence();
        RepostErrorDetails.Builder details = RepostErrorDetails.builder(
                        exception.getErrorCode(), exception.getDeliveryState())
                .operationId(settlement.operationId())
                .attemptCount(exception.getAttemptCount())
                .retryable(exception.isRetryable())
                .compressedBytes(exception.getCompressedBytes())
                .decompressedBytes(exception.getDecompressedBytes())
                .responseHeaderFields(exception.getResponseHeaderFields())
                .responseHeaderBytes(exception.getResponseHeaderBytes())
                .truncated(exception.isTruncated());
        if (exception.getFailureReason() != null) {
            details.failureReason(exception.getFailureReason());
        }
        if (exception.getCauseCategory() != null) {
            details.causeCategory(exception.getCauseCategory());
        }
        String idempotencyKey = exception.getIdempotencyKey() == null
                ? evidence.idempotencyKey : exception.getIdempotencyKey();
        if (idempotencyKey != null) {
            details.idempotencyKey(idempotencyKey);
        }
        if (exception.getHttpStatus() != null) {
            details.httpStatus(exception.getHttpStatus());
        }
        if (!exception.getCloseFailureCategories().isEmpty()) {
            details.closeFailures(exception.getCloseFailureCategories());
        }
        if (!exception.getValidationIssues().isEmpty()) {
            details.validationIssues(
                    exception.getValidationIssues(),
                    exception.getIssueCount(),
                    exception.isIssuesTruncated());
        } else if (!exception.getConfigurationIssues().isEmpty()) {
            details.configurationIssues(
                    exception.getConfigurationIssues(),
                    exception.getIssueCount(),
                    exception.isIssuesTruncated());
        }
        return RepostExceptionFactory.create(details.build());
    }

    private static RepostTransportException cancelledTransportException(
            SendOutcome outcome) {
        RepostErrorDetails.Builder details = RepostErrorDetails.builder(
                        RepostErrorCode.CANCELLED, outcome.getDeliveryState())
                .operationId(outcome.getOperationId())
                .attemptCount(outcome.getAttemptCount());
        if (outcome.getIdempotencyKey() != null) {
            details.idempotencyKey(outcome.getIdempotencyKey());
        }
        if (outcome.getHttpStatus() != null) {
            details.httpStatus(outcome.getHttpStatus());
        }
        return (RepostTransportException) RepostExceptionFactory.create(details.build());
    }

    private static RepostTransportException runtimeClosedException(
            String operationId,
            OperationEvidence evidence) {
        RepostErrorDetails.Builder details = RepostErrorDetails.builder(
                        RepostErrorCode.CLOSED,
                        evidence.unresolved
                                ? DeliveryState.CANCELLED_UNKNOWN : DeliveryState.NOT_SENT)
                .operationId(operationId)
                .attemptCount(evidence.attemptCount);
        if (evidence.idempotencyKey != null) {
            details.idempotencyKey(evidence.idempotencyKey);
        }
        if (evidence.httpStatus != null) {
            details.httpStatus(evidence.httpStatus);
        }
        return (RepostTransportException) RepostExceptionFactory.create(details.build());
    }

    private void updateOperationResources(OperationSettlement settlement) {
        synchronized (lifecycleLock) {
            if (!settlement.isTerminal()) {
                return;
            }
            if (settlement.admissionActive) {
                if (!activeSettlements.contains(settlement) || inFlightOperations <= 0) {
                    throw new AssertionError("operation admission released more than once");
                }
                inFlightOperations--;
                settlement.admissionActive = false;
            }
            if (settlement.reservedBytes != 0L
                    && (!settlement.workerStarted || settlement.workerFinished)) {
                if (settlement.reservedBytes > bufferedBytes
                        || !activeSettlements.remove(settlement)) {
                    throw new AssertionError("operation byte reservation released more than once");
                }
                bufferedBytes -= settlement.reservedBytes;
                settlement.reservedBytes = 0L;
                settlement.retainedRequestBody = null;
                lifecycleLock.notifyAll();
            }
        }
    }

    private void completeCloseAfterOperationPublication() {
        synchronized (lifecycleLock) {
            completeCloseIfReady();
        }
    }

    private static long incrementCounter(long value) {
        return value == RuntimeDiagnostics.MAX_COUNTER_VALUE ? value : value + 1L;
    }

    private static SendOutcome failedOutcome(RepostException exception) {
        return SendOutcome.failed(
                exception.getOperationId(),
                exception.getDeliveryState(),
                exception.getErrorCode(),
                exception.getFailureReason(),
                exception.getCauseCategory(),
                exception.getAttemptCount(),
                exception.getIdempotencyKey(),
                exception.getHttpStatus());
    }

    private static RepostConfigurationException unsupportedInlineExecutor(String operationId) {
        ConfigurationIssue issue = ConfigurationIssue.of(
                ConfigurationIssueCode.UNSUPPORTED,
                java.util.Collections.singletonList(ClientOptionKey.EXECUTOR));
        RepostErrorDetails details = RepostErrorDetails.builder(
                        RepostErrorCode.CONFIGURATION, DeliveryState.NOT_SENT)
                .operationId(operationId)
                .configurationIssues(java.util.Collections.singletonList(issue), 1, false)
                .build();
        return new RepostConfigurationException(details);
    }

    private static SendOperation failedOperation(RepostException exception) {
        SendOutcome outcome = SendOutcome.failed(
                exception.getOperationId(),
                exception.getDeliveryState(),
                exception.getErrorCode(),
                exception.getFailureReason(),
                exception.getCauseCategory(),
                exception.getAttemptCount(),
                exception.getIdempotencyKey(),
                exception.getHttpStatus());
        OperationSettlement settlement =
                new OperationSettlement(
                        exception.getOperationId(), 0L, ignored -> { }, () -> { }, ignored -> { });
        settlement.fail(exception, outcome);
        return settlement.view();
    }

    private static RepostConfigurationException proxyRequiresHttps() {
        RepostErrorDetails details = RepostErrorDetails.builder(
                        RepostErrorCode.CONFIGURATION, DeliveryState.NOT_SENT)
                .configurationIssues(
                        java.util.Collections.singletonList(ConfigurationIssue.of(
                                ConfigurationIssueCode.RESOURCE_MISMATCH,
                                java.util.Arrays.asList(
                                        ClientOptionKey.BASE_URI,
                                        ClientOptionKey.HTTP_TRANSPORT_OPTIONS))),
                        1,
                        false)
                .build();
        return new RepostConfigurationException(details);
    }

    private final class AttemptContext {
        private final Transport transport;
        private final ExecutorService executor;
        private final java.util.concurrent.ScheduledExecutorService scheduler;
        private final RepostCauseCategory transportCauseCategory;
        private final OperationSettlement settlement;
        private final OperationDeadline deadline;
        private final String idempotencyKey;
        private final byte[] requestBody;
        private final List<TransportHeaderField> headers;
        private final String eventType;
        private final String customerId;
        private final String timestamp;
        private int attemptCount;
        private boolean unresolved;
        private boolean unresolvedBeforeAttempt;
        private Integer lastHttpStatus;
        private RepostErrorCode lastErrorCode;
        private RepostFailureReason lastFailureReason;
        private long attemptGeneration;

        private AttemptContext(
                Transport transport,
                ExecutorService executor,
                java.util.concurrent.ScheduledExecutorService scheduler,
                RepostCauseCategory transportCauseCategory,
                OperationSettlement settlement,
                OperationDeadline deadline,
                String credential,
                String idempotencyKey,
                byte[] requestBody,
                String eventType,
                String customerId,
                String timestamp) {
            this.transport = transport;
            this.executor = executor;
            this.scheduler = scheduler;
            this.transportCauseCategory = transportCauseCategory;
            this.settlement = settlement;
            this.deadline = deadline;
            this.idempotencyKey = idempotencyKey;
            this.requestBody = requestBody;
            this.eventType = eventType;
            this.customerId = customerId;
            this.timestamp = timestamp;
            ArrayList<TransportHeaderField> requestHeaders = new ArrayList<>(5);
            requestHeaders.add(TransportHeaderField.of("Authorization", "Bearer " + credential));
            requestHeaders.add(TransportHeaderField.of("Content-Type", "application/json"));
            requestHeaders.add(TransportHeaderField.of("Accept-Encoding", "gzip"));
            requestHeaders.add(TransportHeaderField.of("User-Agent", userAgent));
            requestHeaders.add(TransportHeaderField.of("Idempotency-Key", idempotencyKey));
            this.headers = Collections.unmodifiableList(requestHeaders);
        }
    }

    private static final class OperationSettlement {
        private final String operationId;
        private final OperationFuture primary;
        private final CompletableFuture<SendOutcome> outcome = new CompletableFuture<>();
        private final CompletionStage<SendOutcome> outcomeView = outcome.minimalCompletionStage();
        private final SendOperation view;
        private final TerminalAction terminalAction;
        private final Runnable terminalPublicationAction;
        private final Consumer<Error> fatalAction;
        private final AtomicBoolean terminal = new AtomicBoolean();
        private Future<?> worker;
        private ScheduledFuture<?> deadline;
        private ScheduledFuture<?> retry;
        private Future<?> attempt;
        private ScheduledFuture<?> attemptTimeout;
        private TransportResponse response;
        private long attemptGeneration;
        private boolean interruptWorkerOnAttach;
        private boolean admissionActive;
        private long reservedBytes;
        private byte[] retainedRequestBody;
        private boolean workerStarted;
        private boolean workerFinished;
        private Thread workerThread;
        private RepostException pendingWorkerFailure;
        private SendOutcome pendingWorkerOutcome;
        private int evidenceAttemptCount;
        private String evidenceIdempotencyKey;
        private boolean evidenceUnresolved;
        private Integer evidenceHttpStatus;
        private RuntimeObservability.Operation observability;

        private OperationSettlement(
                String operationId,
                long reservedBytes,
                TerminalAction terminalAction,
                Runnable terminalPublicationAction,
                Consumer<Error> fatalAction) {
            this.operationId = operationId;
            this.primary = new OperationFuture(this);
            this.view = new SendOperationView(this);
            this.admissionActive = reservedBytes != 0L;
            this.reservedBytes = reservedBytes;
            this.terminalAction = terminalAction;
            this.terminalPublicationAction = terminalPublicationAction;
            this.fatalAction = fatalAction;
        }

        private void fail(RepostException exception, SendOutcome sendOutcome) {
            boolean deferPublication = isCurrentWorker();
            if (!beginTerminal(false)) {
                return;
            }
            if (!finishObservability(sendOutcome, false)) {
                return;
            }
            if (deferPublication) {
                synchronized (this) {
                    pendingWorkerFailure = exception;
                    pendingWorkerOutcome = sendOutcome;
                }
            } else {
                publishFailure(exception, sendOutcome);
                signalPublicationCheckpoint();
            }
        }

        private void succeed(SendResult sendResult, SendOutcome sendOutcome) {
            if (!beginTerminal(false)) {
                return;
            }
            if (!finishObservability(sendOutcome, false)) {
                return;
            }
            primary.completeFromCore(sendResult);
            outcome.complete(sendOutcome);
            signalPublicationCheckpoint();
        }

        private boolean cancel(boolean mayInterruptIfRunning) {
            if (!beginTerminal(mayInterruptIfRunning)) {
                return false;
            }
            OperationEvidence evidence = evidence();
            SendOutcome sendOutcome = SendOutcome.failed(
                    operationId,
                    evidence.unresolved
                            ? DeliveryState.CANCELLED_UNKNOWN : DeliveryState.NOT_SENT,
                    RepostErrorCode.CANCELLED,
                    null,
                    null,
                    evidence.attemptCount,
                    evidence.idempotencyKey,
                    evidence.httpStatus);
            if (!finishObservability(sendOutcome, true)) {
                return true;
            }
            primary.cancelFromCore(mayInterruptIfRunning);
            outcome.complete(sendOutcome);
            signalPublicationCheckpoint();
            return true;
        }

        private void closeByRuntime() {
            boolean deferPublication = isCurrentWorker();
            if (!beginTerminal(false)) {
                return;
            }
            OperationEvidence evidence = evidence();
            RepostTransportException failure = runtimeClosedException(operationId, evidence);
            SendOutcome sendOutcome = failedOutcome(failure);
            if (!finishObservability(sendOutcome, false)) {
                return;
            }
            if (deferPublication) {
                synchronized (this) {
                    pendingWorkerFailure = failure;
                    pendingWorkerOutcome = sendOutcome;
                }
            } else {
                publishFailure(failure, sendOutcome);
                signalPublicationCheckpoint();
            }
        }

        private synchronized void attachWorker(Future<?> value) {
            if (terminal.get()) {
                value.cancel(interruptWorkerOnAttach);
            } else {
                worker = value;
            }
        }

        private synchronized void attachObservability(
                RuntimeObservability.Operation value) {
            if (observability != null) {
                throw new AssertionError("operation observability attached more than once");
            }
            observability = Objects.requireNonNull(value, "value");
        }

        private void startObservabilityWorker() {
            RuntimeObservability.Operation value;
            synchronized (this) {
                value = observability;
            }
            if (value != null) {
                value.startWorker();
            }
        }

        private void startObservedAttempt(int attemptNumber) {
            RuntimeObservability.Operation value;
            synchronized (this) {
                value = observability;
            }
            if (value != null) {
                value.startAttempt(attemptNumber);
            }
        }

        private TelemetryScope makeAttemptCurrent() {
            RuntimeObservability.Operation value;
            synchronized (this) {
                value = observability;
            }
            return value == null ? () -> { } : value.makeAttemptCurrent();
        }

        private TelemetryScope makeOperationCurrent() {
            RuntimeObservability.Operation value;
            synchronized (this) {
                value = observability;
            }
            return value == null ? () -> { } : value.makeOperationCurrent();
        }

        private void observeRetry(
                Duration delay,
                RepostErrorCode errorCode,
                RepostFailureReason failureReason) {
            RuntimeObservability.Operation value;
            OperationEvidence evidence;
            synchronized (this) {
                value = observability;
                evidence = evidence();
            }
            if (value == null) {
                return;
            }
            value.retry(delay, new RuntimeObservability.OperationEvidenceView(
                    evidence.attemptCount,
                    evidence.unresolved ? DeliveryState.POSSIBLY_SENT : DeliveryState.NOT_SENT,
                    errorCode,
                    failureReason,
                    evidence.httpStatus));
        }

        private boolean finishObservability(SendOutcome sendOutcome, boolean cancelled) {
            RuntimeObservability.Operation value;
            synchronized (this) {
                value = observability;
            }
            if (value != null) {
                try {
                    value.finish(sendOutcome, cancelled);
                } catch (Error fatal) {
                    fatal(fatal);
                    return false;
                }
            }
            return true;
        }

        private void fatal(Error failure) {
            if (!terminal.get() && !beginTerminal(false)) {
                return;
            }
            primary.failFromCore(failure);
            outcome.completeExceptionally(failure);
            signalPublicationCheckpoint();
            fatalAction.accept(failure);
        }

        private synchronized void attachDeadline(ScheduledFuture<?> value) {
            if (terminal.get()) {
                value.cancel(false);
            } else {
                deadline = value;
            }
        }

        private synchronized void attachRetry(ScheduledFuture<?> value) {
            if (terminal.get()) {
                value.cancel(false);
            } else {
                retry = value;
            }
        }

        private synchronized boolean prepareAttempt(long generation) {
            if (terminal.get()) {
                return false;
            }
            if (attemptGeneration != 0L) {
                throw new AssertionError("transport attempts overlapped");
            }
            attemptGeneration = generation;
            return true;
        }

        private synchronized boolean isCurrentAttempt(long generation) {
            return !terminal.get() && attemptGeneration == generation;
        }

        private synchronized void attachAttemptTimeout(
                long generation,
                ScheduledFuture<?> value) {
            if (terminal.get() || attemptGeneration != generation) {
                value.cancel(false);
            } else {
                attemptTimeout = value;
            }
        }

        private synchronized void attachAttempt(long generation, Future<?> value) {
            if (terminal.get() || attemptGeneration != generation) {
                value.cancel(false);
            } else {
                attempt = value;
            }
        }

        private synchronized boolean attachResponse(
                long generation,
                TransportResponse value,
                int attemptCount,
                String idempotencyKey,
                boolean unresolved) {
            if (terminal.get() || attemptGeneration != generation) {
                return false;
            }
            if (response != null) {
                throw new AssertionError("attempt acquired more than one response");
            }
            response = value;
            recordEvidence(
                    attemptCount,
                    idempotencyKey,
                    unresolved,
                    value.getStatusCode());
            return true;
        }

        private synchronized boolean releaseResponse(
                long generation,
                TransportResponse value) {
            if (terminal.get() || attemptGeneration != generation || response != value) {
                return false;
            }
            response = null;
            return true;
        }

        private boolean finishAttempt(long generation) {
            ScheduledFuture<?> timeoutSnapshot;
            synchronized (this) {
                if (terminal.get() || attemptGeneration != generation) {
                    return false;
                }
                attemptGeneration = 0L;
                attempt = null;
                timeoutSnapshot = attemptTimeout;
                attemptTimeout = null;
            }
            if (timeoutSnapshot != null) {
                timeoutSnapshot.cancel(false);
            }
            return true;
        }

        private boolean expireAttempt(long generation) {
            Future<?> attemptSnapshot;
            ScheduledFuture<?> timeoutSnapshot;
            TransportResponse responseSnapshot;
            synchronized (this) {
                if (terminal.get() || attemptGeneration != generation) {
                    return false;
                }
                attemptGeneration = 0L;
                attemptSnapshot = attempt;
                attempt = null;
                timeoutSnapshot = attemptTimeout;
                attemptTimeout = null;
                responseSnapshot = response;
                response = null;
            }
            if (attemptSnapshot != null) {
                attemptSnapshot.cancel(false);
            }
            if (timeoutSnapshot != null) {
                timeoutSnapshot.cancel(false);
            }
            if (responseSnapshot != null) {
                closeResponseSafely(responseSnapshot);
            }
            return true;
        }

        private synchronized void recordEvidence(
                int attemptCount,
                String idempotencyKey,
                boolean unresolved) {
            recordEvidence(attemptCount, idempotencyKey, unresolved, null);
        }

        private synchronized void recordEvidence(
                int attemptCount,
                String idempotencyKey,
                boolean unresolved,
                Integer httpStatus) {
            if (terminal.get()) {
                return;
            }
            if (attemptCount < evidenceAttemptCount) {
                throw new AssertionError("operation attempt evidence moved backwards");
            }
            evidenceAttemptCount = attemptCount;
            evidenceIdempotencyKey = idempotencyKey;
            evidenceUnresolved = unresolved;
            evidenceHttpStatus = httpStatus;
        }

        private synchronized OperationEvidence evidence() {
            return new OperationEvidence(
                    evidenceAttemptCount,
                    evidenceIdempotencyKey,
                    evidenceUnresolved,
                    evidenceHttpStatus);
        }

        private synchronized boolean beginWorker() {
            if (terminal.get()) {
                return false;
            }
            if (workerStarted) {
                throw new AssertionError("operation worker started more than once");
            }
            workerStarted = true;
            workerThread = Thread.currentThread();
            return true;
        }

        private void finishWorker() {
            RepostException failure;
            SendOutcome sendOutcome;
            synchronized (this) {
                if (!workerStarted || workerFinished || workerThread != Thread.currentThread()) {
                    throw new AssertionError("operation worker finished outside its lifetime");
                }
                workerFinished = true;
                workerThread = null;
                terminalAction.run(this);
                failure = pendingWorkerFailure;
                sendOutcome = pendingWorkerOutcome;
                pendingWorkerFailure = null;
                pendingWorkerOutcome = null;
            }
            if (failure != null) {
                publishFailure(failure, sendOutcome);
            }
            signalPublicationCheckpoint();
        }

        private void signalPublicationCheckpoint() {
            // Worker exit can release resources after another thread published terminal state.
            terminalPublicationAction.run();
        }

        private boolean isTerminal() {
            return terminal.get();
        }

        private String operationId() {
            return operationId;
        }

        private synchronized boolean isCurrentWorker() {
            return workerStarted && !workerFinished && workerThread == Thread.currentThread();
        }

        private void publishFailure(RepostException exception, SendOutcome sendOutcome) {
            primary.failFromCore(exception);
            outcome.complete(sendOutcome);
        }

        private boolean beginTerminal(boolean mayInterruptWorker) {
            Future<?> workerSnapshot;
            ScheduledFuture<?> deadlineSnapshot;
            ScheduledFuture<?> retrySnapshot;
            Future<?> attemptSnapshot;
            ScheduledFuture<?> attemptTimeoutSnapshot;
            TransportResponse responseSnapshot;
            synchronized (this) {
                if (!terminal.compareAndSet(false, true)) {
                    return false;
                }
                interruptWorkerOnAttach = mayInterruptWorker;
                workerSnapshot = worker;
                deadlineSnapshot = deadline;
                retrySnapshot = retry;
                attemptSnapshot = attempt;
                attemptTimeoutSnapshot = attemptTimeout;
                responseSnapshot = response;
                response = null;
                terminalAction.run(this);
            }
            if (workerSnapshot != null) {
                workerSnapshot.cancel(mayInterruptWorker);
            }
            if (deadlineSnapshot != null) {
                deadlineSnapshot.cancel(false);
            }
            if (retrySnapshot != null) {
                retrySnapshot.cancel(false);
            }
            if (attemptSnapshot != null) {
                attemptSnapshot.cancel(false);
            }
            if (attemptTimeoutSnapshot != null) {
                attemptTimeoutSnapshot.cancel(false);
            }
            if (responseSnapshot != null) {
                closeResponseSafely(responseSnapshot);
            }
            return true;
        }

        private SendOperation view() {
            return view;
        }
    }

    private static final class OperationEvidence {
        private final int attemptCount;
        private final String idempotencyKey;
        private final boolean unresolved;
        private final Integer httpStatus;

        private OperationEvidence(
                int attemptCount,
                String idempotencyKey,
                boolean unresolved,
                Integer httpStatus) {
            this.attemptCount = attemptCount;
            this.idempotencyKey = idempotencyKey;
            this.unresolved = unresolved;
            this.httpStatus = httpStatus;
        }
    }

    private static final class OperationFuture extends CompletableFuture<SendResult> {
        private final OperationSettlement settlement;

        private OperationFuture(OperationSettlement settlement) {
            this.settlement = settlement;
        }

        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            return settlement.cancel(mayInterruptIfRunning);
        }

        @Override public boolean complete(SendResult value) { return false; }

        @Override public boolean completeExceptionally(Throwable failure) { return false; }

        @Override public void obtrudeValue(SendResult value) {
            throw new UnsupportedOperationException("operation settlement is SDK-owned");
        }

        @Override public void obtrudeException(Throwable failure) {
            throw new UnsupportedOperationException("operation settlement is SDK-owned");
        }

        @Override public CompletableFuture<SendResult> completeAsync(
                Supplier<? extends SendResult> supplier) {
            Objects.requireNonNull(supplier, "supplier");
            return this;
        }

        @Override public CompletableFuture<SendResult> completeAsync(
                Supplier<? extends SendResult> supplier,
                java.util.concurrent.Executor executor) {
            Objects.requireNonNull(supplier, "supplier");
            Objects.requireNonNull(executor, "executor");
            return this;
        }

        @Override public CompletableFuture<SendResult> orTimeout(
                long timeout,
                TimeUnit unit) {
            Objects.requireNonNull(unit, "unit");
            return this;
        }

        @Override public CompletableFuture<SendResult> completeOnTimeout(
                SendResult value,
                long timeout,
                TimeUnit unit) {
            Objects.requireNonNull(unit, "unit");
            return this;
        }

        @Override public <U> CompletableFuture<U> newIncompleteFuture() {
            return new CompletableFuture<>();
        }

        private boolean completeFromCore(SendResult value) {
            return super.complete(value);
        }

        private boolean failFromCore(Throwable failure) {
            return super.completeExceptionally(failure);
        }

        private boolean cancelFromCore(boolean mayInterruptIfRunning) {
            return super.cancel(mayInterruptIfRunning);
        }
    }

    private static final class OperationDeadline {
        private final long startNanos;
        private final long timeoutNanos;

        private OperationDeadline(long startNanos, long timeoutNanos) {
            this.startNanos = startNanos;
            this.timeoutNanos = timeoutNanos;
        }

        private boolean isExpired(long nowNanos) {
            return nowNanos - startNanos >= timeoutNanos;
        }

        private long remainingNanos(long nowNanos) {
            long elapsedNanos = nowNanos - startNanos;
            return elapsedNanos >= timeoutNanos ? 0L : timeoutNanos - elapsedNanos;
        }
    }

    @FunctionalInterface
    private interface TerminalAction {
        void run(OperationSettlement settlement);
    }

    private static final class SubmissionGuard {
        private final Thread submittingThread = Thread.currentThread();
        private final AtomicBoolean returned = new AtomicBoolean();

        private boolean isInlineBeforeReturn() {
            return Thread.currentThread() == submittingThread && !returned.get();
        }

        private void markReturned() {
            returned.set(true);
        }
    }

    private static final class SendOperationView extends ReadOnlyCompletionStage<SendResult>
            implements SendOperation {
        private final OperationSettlement settlement;

        private SendOperationView(OperationSettlement settlement) {
            super(settlement.primary);
            this.settlement = settlement;
        }

        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            return settlement.cancel(mayInterruptIfRunning);
        }

        @Override public boolean isCancelled() { return settlement.primary.isCancelled(); }

        @Override public boolean isDone() { return settlement.primary.isDone(); }

        @Override public CompletableFuture<SendResult> toCompletableFuture() {
            return settlement.primary;
        }

        @Override public SendResult get() throws InterruptedException, ExecutionException {
            return settlement.primary.get();
        }

        @Override public SendResult get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            return settlement.primary.get(timeout, unit);
        }

        @Override
        public CompletionStage<SendOutcome> outcome() {
            return settlement.outcomeView;
        }
    }

    private static final class OwnedResources {
        private final ExecutorService executor;
        private final java.util.concurrent.ScheduledExecutorService scheduler;
        private final Transport transport;

        private OwnedResources(
                ExecutorService executor,
                java.util.concurrent.ScheduledExecutorService scheduler,
                Transport transport) {
            this.executor = Objects.requireNonNull(executor, "ownedExecutor");
            this.scheduler = Objects.requireNonNull(scheduler, "ownedScheduler");
            this.transport = Objects.requireNonNull(transport, "ownedTransport");
            if (!(transport instanceof AutoCloseable)) {
                throw new IllegalArgumentException("ownedTransport must be AutoCloseable");
            }
        }
    }

    private static final class CloseStageResult {
        private final boolean failed;
        private final boolean interrupted;

        private CloseStageResult(boolean failed, boolean interrupted) {
            this.failed = failed;
            this.interrupted = interrupted;
        }
    }

    private enum Lifecycle {
        OPEN,
        CLOSING,
        CLOSED
    }
}
