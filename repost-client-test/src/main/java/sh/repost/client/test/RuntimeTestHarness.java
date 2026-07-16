package sh.repost.client.test;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import sh.repost.client.ApiKeyProvider;
import sh.repost.client.ClientOptions;
import sh.repost.client.DefaultValueGenerators;
import sh.repost.client.IdempotencyKeyGenerator;
import sh.repost.client.MonotonicClock;
import sh.repost.client.RepostModel;
import sh.repost.client.RepostObserver;
import sh.repost.client.RepostRuntime;
import sh.repost.client.RepostTelemetry;
import sh.repost.client.RetryEntropy;
import sh.repost.client.SendOperation;
import sh.repost.client.SendOptions;
import sh.repost.client.Transport;
import sh.repost.client.WallClock;
import sh.repost.client.descriptor.EventDescriptor;
import sh.repost.client.descriptor.SchemaDescriptor;

/**
 * Closeable deterministic harness that always enters through the real {@link RepostRuntime}.
 *
 * <p>The harness is thread-safe after construction. Injected transports, executors, and
 * schedulers remain borrowed; closing the harness closes only runtime-owned resources.</p>
 */
public final class RuntimeTestHarness implements AutoCloseable {
    private static final RepostModel EMPTY_MODEL = new RepostModel() {
        @Override public boolean __repostIsPresent(int fieldIndex) { return false; }
        @Override public Object __repostValue(int fieldIndex) { return null; }
    };
    private static final DefaultValueGenerators FIXED_GENERATORS = DefaultValueGenerators.fixed(
            Instant.EPOCH,
            "12345678-1234-4234-9234-123456789abc",
            "abcdefghijklmnopqrstuvwx");

    private final SchemaDescriptor schema;
    private final EventDescriptor event;
    private final String customerId;
    private final Transport transport;
    private final NoNetworkGuard noNetworkGuard;
    private final RepostRuntime runtime;
    private volatile SendOperation lastOperation;

    private RuntimeTestHarness(Builder builder) {
        this.schema = builder.schema;
        this.event = builder.event;
        this.customerId = builder.customerId;
        this.noNetworkGuard = builder.transport == null ? new NoNetworkGuard() : null;
        this.transport = builder.transport == null ? noNetworkGuard.transport() : builder.transport;

        ClientOptions.Builder options = ClientOptions.builder()
                .baseUri(builder.baseUri)
                .transport(transport)
                .maxAttempts(builder.maxAttempts)
                .operationTimeout(builder.operationTimeout)
                .retryBaseDelay(builder.retryBaseDelay)
                .retryMaxDelay(builder.retryMaxDelay)
                .defaultValueGenerators(builder.defaultValueGenerators)
                .idempotencyKeyGenerator(builder.idempotencyKeyGenerator)
                .monotonicClock(builder.monotonicClock)
                .wallClock(builder.wallClock)
                .retryEntropy(builder.retryEntropy);
        if (builder.apiKeyProvider == null) {
            options.apiKey(builder.apiKey);
        } else {
            options.apiKeyProvider(builder.apiKeyProvider);
        }
        if (builder.executor != null) {
            options.executor(builder.executor);
        }
        if (builder.scheduler != null) {
            options.scheduler(builder.scheduler);
        }
        if (builder.observer != null) {
            options.observer(builder.observer);
        }
        if (builder.observerExecutor != null) {
            options.observerExecutor(builder.observerExecutor);
        }
        if (builder.telemetry != null) {
            options.telemetry(builder.telemetry);
        }
        this.runtime = RepostRuntime.create(options.build());
    }

    /**
     * Starts a deterministic harness builder.
     *
     * @param schema schema descriptor used by real serialization
     * @param event event descriptor used by real serialization
     * @return harness builder
     */
    public static Builder builder(SchemaDescriptor schema, EventDescriptor event) {
        return new Builder(schema, event);
    }

    /**
     * Sends the shared empty model through production validation and serialization.
     *
     * @return cancellable operation
     */
    public SendOperation sendEmpty() {
        return send(EMPTY_MODEL, SendOptions.defaults());
    }

    /**
     * Sends one model through production validation and serialization.
     *
     * @param model immutable generated model bridge
     * @param options per-send options
     * @return cancellable operation
     */
    public SendOperation send(RepostModel model, SendOptions options) {
        SendOperation operation = runtime.sendAsync(
                schema,
                event,
                customerId,
                Objects.requireNonNull(model, "model"),
                Objects.requireNonNull(options, "options"));
        lastOperation = operation;
        return operation;
    }

    /**
     * Returns the most recently started operation.
     *
     * @return last operation
     * @throws IllegalStateException when no send has started
     */
    public SendOperation getLastOperation() {
        SendOperation operation = lastOperation;
        if (operation == null) {
            throw new IllegalStateException("No harness send has started");
        }
        return operation;
    }

    /**
     * Returns captured requests when the injected transport is a {@link StubTransport}.
     *
     * @return immutable request snapshot
     */
    public List<RecordedRequest> getCapturedRequests() {
        return transport instanceof StubTransport
                ? ((StubTransport) transport).getRequests() : Collections.emptyList();
    }

    /**
     * Returns the per-harness no-network guard installed when no transport was supplied.
     *
     * @return scoped guard
     * @throws IllegalStateException when an explicit transport was supplied
     */
    public NoNetworkGuard getNoNetworkGuard() {
        if (noNetworkGuard == null) {
            throw new IllegalStateException("Harness uses an explicit transport");
        }
        return noNetworkGuard;
    }

    /**
     * Returns the real runtime for diagnostics and lifecycle assertions.
     *
     * @return real runtime
     */
    public RepostRuntime getRuntime() {
        return runtime;
    }

    @Override
    public void close() {
        runtime.close();
    }

    /** Builder for a deterministic real-runtime harness. */
    public static final class Builder {
        private final SchemaDescriptor schema;
        private final EventDescriptor event;
        private String customerId = "cus_test";
        private String apiKey = "test-key";
        private ApiKeyProvider apiKeyProvider;
        private String baseUri = "https://example.com";
        private Transport transport;
        private int maxAttempts = 1;
        private Duration operationTimeout = Duration.ofSeconds(30);
        private Duration retryBaseDelay = Duration.ofMillis(100);
        private Duration retryMaxDelay = Duration.ofSeconds(1);
        private DefaultValueGenerators defaultValueGenerators = FIXED_GENERATORS;
        private IdempotencyKeyGenerator idempotencyKeyGenerator = () -> "test-idempotency-key";
        private MonotonicClock monotonicClock = System::nanoTime;
        private WallClock wallClock = () -> Instant.EPOCH;
        private RetryEntropy retryEntropy = bound -> 0L;
        private ExecutorService executor;
        private ScheduledExecutorService scheduler;
        private RepostObserver observer;
        private ExecutorService observerExecutor;
        private RepostTelemetry telemetry;

        private Builder(SchemaDescriptor schema, EventDescriptor event) {
            this.schema = Objects.requireNonNull(schema, "schema");
            this.event = Objects.requireNonNull(event, "event");
        }

        /**
         * Sets the customer identifier.
         *
         * @param value customer identifier
         * @return this builder
         */
        public Builder customerId(String value) {
            customerId = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Sets a fixed API key and clears any provider.
         *
         * @param value fixed test API key
         * @return this builder
         */
        public Builder apiKey(String value) {
            apiKey = Objects.requireNonNull(value, "value");
            apiKeyProvider = null;
            return this;
        }

        /**
         * Sets an operation-snapshotted API-key provider and clears the fixed key.
         *
         * @param value test API-key provider
         * @return this builder
         */
        public Builder apiKeyProvider(ApiKeyProvider value) {
            apiKeyProvider = Objects.requireNonNull(value, "value");
            apiKey = null;
            return this;
        }

        /**
         * Sets the validated runtime base URI.
         *
         * @param value base URI
         * @return this builder
         */
        public Builder baseUri(String value) {
            baseUri = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Sets the explicit test transport.
         *
         * @param value explicit test transport
         * @return this builder
         */
        public Builder transport(Transport value) {
            transport = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Sets the maximum number of transport attempts.
         *
         * @param value maximum transport attempts
         * @return this builder
         */
        public Builder maxAttempts(int value) {
            maxAttempts = value;
            return this;
        }

        /**
         * Sets the operation-wide timeout.
         *
         * @param value operation timeout
         * @return this builder
         */
        public Builder operationTimeout(Duration value) {
            operationTimeout = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Sets the retry base delay.
         *
         * @param value retry base delay
         * @return this builder
         */
        public Builder retryBaseDelay(Duration value) {
            retryBaseDelay = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Sets the retry maximum delay.
         *
         * @param value retry maximum delay
         * @return this builder
         */
        public Builder retryMaxDelay(Duration value) {
            retryMaxDelay = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Sets deterministic schema-default generators.
         *
         * @param value deterministic default generators
         * @return this builder
         */
        public Builder defaultValueGenerators(DefaultValueGenerators value) {
            defaultValueGenerators = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Sets the deterministic idempotency-key generator.
         *
         * @param value deterministic idempotency generator
         * @return this builder
         */
        public Builder idempotencyKeyGenerator(IdempotencyKeyGenerator value) {
            idempotencyKeyGenerator = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Sets the monotonic clock.
         *
         * @param value monotonic clock
         * @return this builder
         */
        public Builder monotonicClock(MonotonicClock value) {
            monotonicClock = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Sets the wall clock.
         *
         * @param value wall clock
         * @return this builder
         */
        public Builder wallClock(WallClock value) {
            wallClock = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Sets deterministic retry entropy.
         *
         * @param value retry entropy
         * @return this builder
         */
        public Builder retryEntropy(RetryEntropy value) {
            retryEntropy = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Sets the borrowed operation executor.
         *
         * @param value borrowed operation executor
         * @return this builder
         */
        public Builder executor(ExecutorService value) {
            executor = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Sets the borrowed scheduler.
         *
         * @param value borrowed scheduler
         * @return this builder
         */
        public Builder scheduler(ScheduledExecutorService value) {
            scheduler = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Sets the observer integration.
         *
         * @param value observer
         * @return this builder
         */
        public Builder observer(RepostObserver value) {
            observer = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Sets the borrowed observer executor.
         *
         * @param value borrowed observer executor
         * @return this builder
         */
        public Builder observerExecutor(ExecutorService value) {
            observerExecutor = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Sets the live telemetry integration.
         *
         * @param value telemetry integration
         * @return this builder
         */
        public Builder telemetry(RepostTelemetry value) {
            telemetry = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Builds the closeable harness.
         *
         * @return deterministic harness
         */
        public RuntimeTestHarness build() {
            return new RuntimeTestHarness(this);
        }
    }
}
