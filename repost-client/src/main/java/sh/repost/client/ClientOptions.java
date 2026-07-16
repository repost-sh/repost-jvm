package sh.repost.client;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import sh.repost.client.error.RepostConfigurationException;
import sh.repost.client.internal.UriValidator;

/** Immutable framework-neutral runtime configuration. */
public final class ClientOptions {
    static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration DEFAULT_ATTEMPT_TIMEOUT = Duration.ofSeconds(30);
    static final Duration DEFAULT_OPERATION_TIMEOUT = Duration.ofSeconds(120);
    static final Duration DEFAULT_RETRY_BASE_DELAY = Duration.ofMillis(250);
    static final Duration DEFAULT_RETRY_MAX_DELAY = Duration.ofSeconds(60);
    static final long MAX_DURATION_MILLIS = 9_223_372_036_854L;

    private final String apiKey;
    private final ApiKeyProvider apiKeyProvider;
    private final String baseUri;
    private final Duration connectTimeout;
    private final Duration attemptTimeout;
    private final Duration operationTimeout;
    private final int maxAttempts;
    private final int maxInFlightOperations;
    private final long maxBufferedBytes;
    private final Duration retryBaseDelay;
    private final Duration retryMaxDelay;
    private final HttpTransportOptions httpTransportOptions;
    private final Transport transport;
    private final ExecutorService executor;
    private final ScheduledExecutorService scheduler;
    private final RepostObserver observer;
    private final ExecutorService observerExecutor;
    private final RepostTelemetry telemetry;
    private final DefaultValueGenerators defaultValueGenerators;
    private final IdempotencyKeyGenerator idempotencyKeyGenerator;
    private final MonotonicClock monotonicClock;
    private final WallClock wallClock;
    private final RetryEntropy retryEntropy;
    private final String userAgentSuffix;

    private ClientOptions(Builder builder) {
        this.apiKey = validateOptionalApiKey(builder.apiKey);
        this.apiKeyProvider = builder.apiKeyProvider;
        this.baseUri = validateOptionalBaseUri(builder.baseUri);
        this.connectTimeout = validateDuration(
                builder.connectTimeout, ClientOptionKey.CONNECT_TIMEOUT);
        this.attemptTimeout = validateDuration(
                builder.attemptTimeout, ClientOptionKey.ATTEMPT_TIMEOUT);
        this.operationTimeout = validateDuration(
                builder.operationTimeout, ClientOptionKey.OPERATION_TIMEOUT);
        this.maxAttempts = validateRange(
                builder.maxAttempts, 1, 10, ClientOptionKey.MAX_ATTEMPTS);
        this.maxInFlightOperations = validateRange(
                builder.maxInFlightOperations,
                1,
                65_536,
                ClientOptionKey.MAX_IN_FLIGHT_OPERATIONS);
        this.maxBufferedBytes = validateRange(
                builder.maxBufferedBytes,
                4_194_304L,
                1_073_741_824L,
                ClientOptionKey.MAX_BUFFERED_BYTES);
        this.retryBaseDelay = validateDuration(
                builder.retryBaseDelay, ClientOptionKey.RETRY_BASE_DELAY);
        this.retryMaxDelay = validateDuration(
                builder.retryMaxDelay, ClientOptionKey.RETRY_MAX_DELAY);
        if (retryBaseDelay.compareTo(retryMaxDelay) > 0) {
            throw configurationConflict(
                    ClientOptionKey.RETRY_BASE_DELAY, ClientOptionKey.RETRY_MAX_DELAY);
        }
        if (apiKey != null && apiKeyProvider != null) {
            throw configurationConflict(ClientOptionKey.API_KEY, ClientOptionKey.API_KEY_PROVIDER);
        }
        if (builder.transport != null && builder.httpTransportOptions != null) {
            throw configurationConflict(
                    ClientOptionKey.HTTP_TRANSPORT_OPTIONS, ClientOptionKey.TRANSPORT);
        }
        if (builder.observerConflict) {
            throw configurationIssue(
                    ConfigurationIssueCode.INVALID_VALUE, ClientOptionKey.OBSERVER);
        }
        if (builder.telemetryConflict) {
            throw configurationIssue(
                    ConfigurationIssueCode.INVALID_VALUE, ClientOptionKey.TELEMETRY);
        }
        this.httpTransportOptions = builder.httpTransportOptions;
        this.transport = builder.transport;
        this.executor = builder.executor;
        this.scheduler = builder.scheduler;
        this.observer = builder.observer;
        this.observerExecutor = builder.observerExecutor;
        this.telemetry = builder.telemetry;
        this.defaultValueGenerators = builder.defaultValueGenerators;
        this.idempotencyKeyGenerator = builder.idempotencyKeyGenerator;
        this.monotonicClock = builder.monotonicClock;
        this.wallClock = builder.wallClock;
        this.retryEntropy = builder.retryEntropy;
        this.userAgentSuffix = validateOptionalUserAgent(builder.userAgentSuffix);
    }

    /**
     * Returns a new options builder.
     *
     * @return options builder with production defaults
     */
    public static Builder builder() {
        return new Builder();
    }

    String apiKey() {
        return apiKey;
    }

    ApiKeyProvider apiKeyProvider() {
        return apiKeyProvider;
    }

    String baseUri() {
        return baseUri;
    }

    Duration connectTimeout() {
        return connectTimeout;
    }

    Duration attemptTimeout() {
        return attemptTimeout;
    }

    Duration operationTimeout() {
        return operationTimeout;
    }

    int maxAttempts() {
        return maxAttempts;
    }

    int maxInFlightOperations() {
        return maxInFlightOperations;
    }

    long maxBufferedBytes() {
        return maxBufferedBytes;
    }

    Duration retryBaseDelay() {
        return retryBaseDelay;
    }

    Duration retryMaxDelay() {
        return retryMaxDelay;
    }

    HttpTransportOptions httpTransportOptions() {
        return httpTransportOptions;
    }

    Transport transport() {
        return transport;
    }

    ExecutorService executor() {
        return executor;
    }

    ScheduledExecutorService scheduler() {
        return scheduler;
    }

    RepostObserver observer() {
        return observer;
    }

    ExecutorService observerExecutor() {
        return observerExecutor;
    }

    RepostTelemetry telemetry() {
        return telemetry;
    }

    DefaultValueGenerators defaultValueGenerators() {
        return defaultValueGenerators;
    }

    IdempotencyKeyGenerator idempotencyKeyGenerator() {
        return idempotencyKeyGenerator;
    }

    MonotonicClock monotonicClock() {
        return monotonicClock;
    }

    WallClock wallClock() {
        return wallClock;
    }

    RetryEntropy retryEntropy() {
        return retryEntropy;
    }

    String userAgentSuffix() {
        return userAgentSuffix;
    }

    @Override
    public String toString() {
        return "ClientOptions[REDACTED]";
    }

    static String validateApiKey(String value) {
        Objects.requireNonNull(value, "apiKey");
        if (value.isEmpty() || value.length() > 4_096) {
            throw new IllegalArgumentException("apiKey must contain 1..4096 ASCII bytes");
        }
        boolean nonSpace = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x20 || character > 0x7e) {
                throw new IllegalArgumentException("apiKey must be printable ASCII");
            }
            nonSpace |= character != ' ';
        }
        if (!nonSpace) {
            throw new IllegalArgumentException("apiKey must contain a non-space byte");
        }
        return value;
    }

    private static String validateOptionalApiKey(String value) {
        if (value == null) {
            return null;
        }
        try {
            return validateApiKey(value);
        } catch (IllegalArgumentException invalid) {
            throw configurationIssue(
                    ConfigurationIssueCode.INVALID_VALUE, ClientOptionKey.API_KEY);
        }
    }

    private static String validateOptionalBaseUri(String value) {
        if (value != null) {
            try {
                UriValidator.canonicalEndpoint(value);
            } catch (IllegalArgumentException invalid) {
                throw configurationIssue(
                        ConfigurationIssueCode.INVALID_VALUE, ClientOptionKey.BASE_URI);
            }
        }
        return value;
    }

    private static String validateOptionalUserAgent(String value) {
        if (value == null) {
            return null;
        }
        if (value.isEmpty() || value.length() > 256
                || value.charAt(0) == ' ' || value.charAt(value.length() - 1) == ' ') {
            throw configurationIssue(
                    ConfigurationIssueCode.INVALID_VALUE, ClientOptionKey.USER_AGENT_SUFFIX);
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x20 || character > 0x7e) {
                throw configurationIssue(
                        ConfigurationIssueCode.INVALID_VALUE, ClientOptionKey.USER_AGENT_SUFFIX);
            }
        }
        return value;
    }

    private static Duration validateDuration(Duration value, ClientOptionKey optionKey) {
        Objects.requireNonNull(value, "value");
        if (value.isZero() || value.isNegative()
                || value.getNano() % 1_000_000 != 0
                || value.compareTo(Duration.ofMillis(MAX_DURATION_MILLIS)) > 0) {
            throw configurationIssue(ConfigurationIssueCode.OUT_OF_RANGE, optionKey);
        }
        return value;
    }

    private static int validateRange(
            int value,
            int minimum,
            int maximum,
            ClientOptionKey optionKey) {
        if (value < minimum || value > maximum) {
            throw configurationIssue(ConfigurationIssueCode.OUT_OF_RANGE, optionKey);
        }
        return value;
    }

    private static long validateRange(
            long value,
            long minimum,
            long maximum,
            ClientOptionKey optionKey) {
        if (value < minimum || value > maximum) {
            throw configurationIssue(ConfigurationIssueCode.OUT_OF_RANGE, optionKey);
        }
        return value;
    }

    private static RepostConfigurationException configurationConflict(
            ClientOptionKey first,
            ClientOptionKey second) {
        return configurationIssue(ConfigurationIssueCode.CONFLICT, first, second);
    }

    static RepostConfigurationException configurationIssue(
            ConfigurationIssueCode code,
            ClientOptionKey... optionKeys) {
        ConfigurationIssue issue = ConfigurationIssue.of(
                code,
                java.util.Arrays.asList(optionKeys));
        RepostErrorDetails details = RepostErrorDetails.builder(
                        RepostErrorCode.CONFIGURATION, DeliveryState.NOT_SENT)
                .configurationIssues(java.util.Collections.singletonList(issue), 1, false)
                .build();
        return new RepostConfigurationException(details);
    }

    /** Builder for immutable framework-neutral runtime configuration. */
    public static final class Builder {
        private String apiKey;
        private ApiKeyProvider apiKeyProvider;
        private String baseUri;
        private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;
        private Duration attemptTimeout = DEFAULT_ATTEMPT_TIMEOUT;
        private Duration operationTimeout = DEFAULT_OPERATION_TIMEOUT;
        private int maxAttempts = 4;
        private int maxInFlightOperations = 256;
        private long maxBufferedBytes = 67_108_864L;
        private Duration retryBaseDelay = DEFAULT_RETRY_BASE_DELAY;
        private Duration retryMaxDelay = DEFAULT_RETRY_MAX_DELAY;
        private HttpTransportOptions httpTransportOptions;
        private Transport transport;
        private ExecutorService executor;
        private ScheduledExecutorService scheduler;
        private RepostObserver observer;
        private ExecutorService observerExecutor;
        private RepostTelemetry telemetry;
        private DefaultValueGenerators defaultValueGenerators;
        private IdempotencyKeyGenerator idempotencyKeyGenerator;
        private MonotonicClock monotonicClock;
        private WallClock wallClock;
        private RetryEntropy retryEntropy;
        private String userAgentSuffix;
        private boolean observerConflict;
        private boolean telemetryConflict;

        private Builder() { }

        /**
         * Sets a fixed API key.
         *
         * @param value printable ASCII credential
         * @return this builder
         */
        public Builder apiKey(String value) {
            this.apiKey = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Sets the operation-time API key provider.
         *
         * @param value provider
         * @return this builder
         */
        public Builder apiKeyProvider(ApiKeyProvider value) {
            this.apiKeyProvider = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Sets the raw Repost base URI.
         *
         * @param rawValue URI text
         * @return this builder
         */
        public Builder baseUri(String rawValue) {
            this.baseUri = Objects.requireNonNull(rawValue, "rawValue");
            return this;
        }

        /**
         * Sets the connection timeout.
         *
         * @param value positive whole-millisecond duration
         * @return this builder
         */
        public Builder connectTimeout(Duration value) {
            this.connectTimeout = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Sets the per-attempt timeout.
         *
         * @param value positive whole-millisecond duration
         * @return this builder
         */
        public Builder attemptTimeout(Duration value) {
            this.attemptTimeout = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Sets the operation deadline.
         *
         * @param value positive whole-millisecond duration
         * @return this builder
         */
        public Builder operationTimeout(Duration value) {
            this.operationTimeout = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Sets the maximum transport attempts.
         *
         * @param value value from 1 through 10
         * @return this builder
         */
        public Builder maxAttempts(int value) {
            this.maxAttempts = value;
            return this;
        }

        /**
         * Sets the maximum admitted operations.
         *
         * @param value supported positive bound
         * @return this builder
         */
        public Builder maxInFlightOperations(int value) {
            this.maxInFlightOperations = value;
            return this;
        }

        /**
         * Sets the aggregate byte budget.
         *
         * @param value supported byte bound
         * @return this builder
         */
        public Builder maxBufferedBytes(long value) {
            this.maxBufferedBytes = value;
            return this;
        }

        /**
         * Sets the initial retry delay.
         *
         * @param value positive whole-millisecond duration
         * @return this builder
         */
        public Builder retryBaseDelay(Duration value) {
            this.retryBaseDelay = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Sets the maximum retry delay.
         *
         * @param value positive whole-millisecond duration
         * @return this builder
         */
        public Builder retryMaxDelay(Duration value) {
            this.retryMaxDelay = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Sets built-in HTTP options.
         *
         * @param value HTTP transport options
         * @return this builder
         */
        public Builder httpTransportOptions(HttpTransportOptions value) {
            this.httpTransportOptions = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Sets a custom one-attempt transport.
         *
         * @param value transport
         * @return this builder
         */
        public Builder transport(Transport value) {
            this.transport = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Sets a borrowed operation executor.
         *
         * @param value executor
         * @return this builder
         */
        public Builder executor(ExecutorService value) {
            this.executor = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Sets a borrowed retry scheduler.
         *
         * @param value scheduler
         * @return this builder
         */
        public Builder scheduler(ScheduledExecutorService value) {
            this.scheduler = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Sets the terminal observer slot.
         *
         * @param value observer
         * @return this builder
         */
        public Builder observer(RepostObserver value) {
            Objects.requireNonNull(value, "value");
            observerConflict |= observer != null && observer != value;
            this.observer = value;
            return this;
        }

        /**
         * Reports whether the observer slot is already configured.
         *
         * @return {@code true} when an observer was assigned
         */
        public boolean hasObserver() {
            return observer != null;
        }

        /**
         * Sets a borrowed observer callback executor.
         *
         * @param value executor
         * @return this builder
         */
        public Builder observerExecutor(ExecutorService value) {
            this.observerExecutor = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Sets the live telemetry slot.
         *
         * @param value telemetry integration
         * @return this builder
         */
        public Builder telemetry(RepostTelemetry value) {
            Objects.requireNonNull(value, "value");
            telemetryConflict |= telemetry != null && telemetry != value;
            this.telemetry = value;
            return this;
        }

        /**
         * Reports whether the telemetry slot is already configured.
         *
         * @return {@code true} when telemetry was assigned
         */
        public boolean hasTelemetry() {
            return telemetry != null;
        }

        /**
         * Sets schema default-value generators.
         *
         * @param value generators
         * @return this builder
         */
        public Builder defaultValueGenerators(DefaultValueGenerators value) {
            this.defaultValueGenerators = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Sets the idempotency-key generator.
         *
         * @param value generator
         * @return this builder
         */
        public Builder idempotencyKeyGenerator(IdempotencyKeyGenerator value) {
            this.idempotencyKeyGenerator = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Sets the monotonic time source.
         *
         * @param value clock
         * @return this builder
         */
        public Builder monotonicClock(MonotonicClock value) {
            this.monotonicClock = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Sets the wall-clock time source.
         *
         * @param value clock
         * @return this builder
         */
        public Builder wallClock(WallClock value) {
            this.wallClock = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Sets the retry jitter source.
         *
         * @param value entropy source
         * @return this builder
         */
        public Builder retryEntropy(RetryEntropy value) {
            this.retryEntropy = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Sets a validated User-Agent suffix.
         *
         * @param value printable ASCII suffix
         * @return this builder
         */
        public Builder userAgentSuffix(String value) {
            this.userAgentSuffix = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Builds validated immutable options.
         *
         * @return client options
         */
        public ClientOptions build() {
            return new ClientOptions(this);
        }
    }
}
