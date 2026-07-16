package sh.repost.client.kotlin

import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import sh.repost.client.ClientOptions
import sh.repost.client.DefaultValueGenerators
import sh.repost.client.HttpTransportOptions
import sh.repost.client.IdempotencyKeyGenerator
import sh.repost.client.MonotonicClock
import sh.repost.client.RepostObserver
import sh.repost.client.RepostTelemetry
import sh.repost.client.RetryEntropy
import sh.repost.client.Transport
import sh.repost.client.WallClock

/**
 * Kotlin configuration facade over the framework-neutral Java [ClientOptions] contract.
 *
 * A `null` property means "leave the Java builder default or fallback unchanged". Validation,
 * mutual exclusion, precedence, and resource ownership are therefore identical to Java.
 */
@RepostDsl
public class RepostClientConfig {
    /** Fixed API key, or `null` to retain the Java environment fallback. */
    public var apiKey: String? = null

    /** Raw base URI text, or `null` to retain the Java environment/default fallback. */
    public var baseUri: String? = null

    /** Connection establishment timeout. */
    public var connectTimeout: Duration? = null

    /** Timeout for one HTTP attempt. */
    public var attemptTimeout: Duration? = null

    /** Deadline for the complete operation, including retries. */
    public var operationTimeout: Duration? = null

    /** Initial retry delay. */
    public var retryBaseDelay: Duration? = null

    /** Maximum retry delay. */
    public var retryMaxDelay: Duration? = null

    /** Maximum number of transport attempts. */
    public var maxAttempts: Int? = null

    /** Maximum number of admitted operations. */
    public var maxInFlightOperations: Int? = null

    /** Aggregate request/response byte budget. */
    public var maxBufferedBytes: Long? = null

    /** Built-in HTTP transport options, mutually exclusive with [transport]. */
    public var httpTransportOptions: HttpTransportOptions? = null

    /** Borrowed custom one-attempt transport, mutually exclusive with [httpTransportOptions]. */
    public var transport: Transport? = null

    /** Borrowed operation executor. */
    public var executor: ExecutorService? = null

    /** Borrowed retry scheduler. */
    public var scheduler: ScheduledExecutorService? = null

    /** Terminal observer integration. */
    public var observer: RepostObserver? = null

    /** Borrowed observer callback executor. */
    public var observerExecutor: ExecutorService? = null

    /** Live telemetry integration. */
    public var telemetry: RepostTelemetry? = null

    /** Additional validated User-Agent product token text. */
    public var userAgentSuffix: String? = null

    /** Schema default-value generators. */
    public var defaultValueGenerators: DefaultValueGenerators? = null

    /** Idempotency-key generator used when a send does not provide a key. */
    public var idempotencyKeyGenerator: IdempotencyKeyGenerator? = null

    /** Monotonic time source used for operation deadlines. */
    public var monotonicClock: MonotonicClock? = null

    /** Wall-clock time source used for protocol dates. */
    public var wallClock: WallClock? = null

    /** Retry jitter entropy source. */
    public var retryEntropy: RetryEntropy? = null

    /**
     * Builds immutable Java runtime options using the canonical Java validation path.
     *
     * @return validated immutable client options
     */
    public fun build(): ClientOptions {
        val builder = ClientOptions.builder()
        apiKey?.let(builder::apiKey)
        baseUri?.let(builder::baseUri)
        connectTimeout?.let(builder::connectTimeout)
        attemptTimeout?.let(builder::attemptTimeout)
        operationTimeout?.let(builder::operationTimeout)
        retryBaseDelay?.let(builder::retryBaseDelay)
        retryMaxDelay?.let(builder::retryMaxDelay)
        maxAttempts?.let(builder::maxAttempts)
        maxInFlightOperations?.let(builder::maxInFlightOperations)
        maxBufferedBytes?.let(builder::maxBufferedBytes)
        httpTransportOptions?.let(builder::httpTransportOptions)
        transport?.let(builder::transport)
        executor?.let(builder::executor)
        scheduler?.let(builder::scheduler)
        observer?.let(builder::observer)
        observerExecutor?.let(builder::observerExecutor)
        telemetry?.let(builder::telemetry)
        userAgentSuffix?.let(builder::userAgentSuffix)
        defaultValueGenerators?.let(builder::defaultValueGenerators)
        idempotencyKeyGenerator?.let(builder::idempotencyKeyGenerator)
        monotonicClock?.let(builder::monotonicClock)
        wallClock?.let(builder::wallClock)
        retryEntropy?.let(builder::retryEntropy)
        return builder.build()
    }
}
