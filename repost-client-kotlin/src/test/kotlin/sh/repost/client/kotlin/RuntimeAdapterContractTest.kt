package sh.repost.client.kotlin

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import sh.repost.client.ClientOptions
import sh.repost.client.DefaultValueGenerators
import sh.repost.client.DeliveryState
import sh.repost.client.RepostErrorCode
import sh.repost.client.RepostModel
import sh.repost.client.RepostRuntime
import sh.repost.client.RetryEntropy
import sh.repost.client.SendOperation
import sh.repost.client.SendOptions
import sh.repost.client.SendOutcome
import sh.repost.client.SendResult
import sh.repost.client.Transport
import sh.repost.client.TransportHeaderField
import sh.repost.client.TransportRequest
import sh.repost.client.TransportResponse
import sh.repost.client.descriptor.EventDescriptor
import sh.repost.client.descriptor.ModelDescriptor
import sh.repost.client.descriptor.SchemaDescriptor
import sh.repost.client.error.RepostException

class RuntimeAdapterContractTest {
    @Test
    fun `Java and Kotlin success vectors have identical wire input result and outcome`() {
        val java = runVector(AdapterPath.JAVA, acceptedResponse())
        val kotlin = runVector(AdapterPath.KOTLIN, acceptedResponse())

        assertEquals(java, kotlin)
        assertEquals("msg_kotlin_adapter", kotlin.result?.id)
        assertEquals(DeliveryState.ACCEPTED, kotlin.outcome.deliveryState)
        assertEquals("Bearer test-key", kotlin.request.headers["authorization"])
        assertEquals("repost-jvm/1.0.0 parity/1", kotlin.request.headers["user-agent"])
        assertEquals(Duration.ofSeconds(2), kotlin.request.connectTimeout)
        assertEquals(Duration.ofSeconds(3), kotlin.request.attemptTimeout)
    }

    @Test
    fun `Java and Kotlin failure vectors preserve every stable error field`() {
        val java = runVector(AdapterPath.JAVA, response(503))
        val kotlin = runVector(AdapterPath.KOTLIN, response(503))

        assertEquals(java, kotlin)
        assertEquals(RepostErrorCode.SERVER_FAILURE, kotlin.failure?.errorCode)
        assertEquals(DeliveryState.POSSIBLY_SENT, kotlin.failure?.deliveryState)
        assertEquals(1, kotlin.failure?.attemptCount)
        assertEquals(503, kotlin.failure?.httpStatus)
        assertEquals("adapter-key", kotlin.failure?.idempotencyKey)
        assertTrue(kotlin.failure?.retryable == true)
    }

    @Test
    fun `public await preserves the original completed RepostException instance`() {
        val runtime = RepostRuntime.create(options(Transport { response(503) }))
        try {
            val operation = send(runtime)
            val expected = assertThrows(ExecutionException::class.java, operation::get).cause
            val observed = assertThrows(expected!!::class.java) {
                runBlocking { operation.awaitRepost() }
            }

            assertSame(expected, observed)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `cancel before completion cancels SendOperation and retains outcome reconciliation`() = runBlocking {
        val started = CountDownLatch(1)
        val pending = CompletableFuture<TransportResponse>()
        val runtime = RepostRuntime.create(options(Transport {
            started.countDown()
            pending
        }))
        try {
            val operation = send(runtime)
            val deferred = async(start = CoroutineStart.UNDISPATCHED) { operation.awaitRepost() }
            assertTrue(started.await(2, TimeUnit.SECONDS))

            deferred.cancel(CancellationException("caller-cancelled"))
            val observed = try {
                deferred.await()
                error("await unexpectedly completed")
            } catch (cancelled: CancellationException) {
                cancelled
            }

            assertEquals("caller-cancelled", observed.message)
            assertTrue(operation.isCancelled)
            assertTrue(pending.isCancelled)
            assertCancelledOutcome(operation.outcome().toCompletableFuture().join(), 1, null)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `parent scope cancellation propagates to the in-flight SendOperation`() = runBlocking {
        val started = CountDownLatch(1)
        val pending = CompletableFuture<TransportResponse>()
        val runtime = RepostRuntime.create(options(Transport {
            started.countDown()
            pending
        }))
        val parent = SupervisorJob()
        val scope = CoroutineScope(parent + Dispatchers.Default)
        try {
            val operation = send(runtime)
            val deferred = scope.async(start = CoroutineStart.UNDISPATCHED) { operation.awaitRepost() }
            assertTrue(started.await(2, TimeUnit.SECONDS))

            parent.cancel(CancellationException("scope-cancelled"))
            val observed = try {
                deferred.await()
                error("await unexpectedly completed")
            } catch (cancelled: CancellationException) {
                cancelled
            }

            assertEquals("scope-cancelled", observed.message)
            assertTrue(operation.isCancelled)
            assertTrue(pending.isCancelled)
        } finally {
            scope.cancel()
            runtime.close()
        }
    }

    @Test
    fun `cancellation during retry backoff prevents another transport attempt`() = runBlocking {
        val retryScheduled = CountDownLatch(1)
        val scheduler = RetryDetectingScheduler(retryScheduled)
        val calls = AtomicInteger()
        val transport = Transport {
            calls.incrementAndGet()
            response(429)
        }
        val config = RepostClientConfig().apply {
            apiKey = "test-key"
            baseUri = "https://example.com"
            this.transport = transport
            this.scheduler = scheduler
            attemptTimeout = Duration.ofSeconds(5)
            operationTimeout = Duration.ofSeconds(60)
            retryBaseDelay = Duration.ofSeconds(30)
            retryMaxDelay = Duration.ofSeconds(30)
            maxAttempts = 2
            defaultValueGenerators = GENERATORS
            idempotencyKeyGenerator = sh.repost.client.IdempotencyKeyGenerator { "adapter-key" }
            retryEntropy = RetryEntropy { upperBound -> upperBound - 1L }
        }
        val runtime = RepostRuntime.create(config.build())
        try {
            val operation = send(runtime)
            val deferred = async(start = CoroutineStart.UNDISPATCHED) { operation.awaitRepost() }
            assertTrue(retryScheduled.await(2, TimeUnit.SECONDS))

            deferred.cancel(CancellationException("backoff-cancelled"))
            try {
                deferred.await()
            } catch (_: CancellationException) {
                // Expected native coroutine cancellation.
            }

            assertTrue(operation.isCancelled)
            assertEquals(1, calls.get())
            assertCancelledOutcome(operation.outcome().toCompletableFuture().join(), 1, 429)
        } finally {
            runtime.close()
            scheduler.shutdownNow()
        }
    }

    private fun runVector(path: AdapterPath, response: CompletableFuture<TransportResponse>): VectorSnapshot {
        val request = AtomicReference<TransportRequest>()
        val transport = Transport { candidate ->
            request.set(candidate)
            response
        }
        val runtimeOptions = if (path == AdapterPath.JAVA) {
            options(transport)
        } else {
            RepostClientConfig().apply {
                apiKey = "test-key"
                baseUri = "https://example.com"
                connectTimeout = Duration.ofSeconds(2)
                attemptTimeout = Duration.ofSeconds(3)
                operationTimeout = Duration.ofSeconds(4)
                maxAttempts = 1
                this.transport = transport
                userAgentSuffix = "parity/1"
                defaultValueGenerators = GENERATORS
                idempotencyKeyGenerator = sh.repost.client.IdempotencyKeyGenerator { "adapter-key" }
            }.build()
        }
        val runtime = RepostRuntime.create(runtimeOptions)
        try {
            val operation = send(runtime)
            var result: SendResult? = null
            var failure: RepostException? = null
            try {
                result = if (path == AdapterPath.JAVA) {
                    operation.get()
                } else {
                    runBlocking { operation.awaitRepost() }
                }
            } catch (caught: Throwable) {
                failure = unwrap(caught) as RepostException
            }
            return VectorSnapshot(
                request = requestSnapshot(request.get()),
                result = result?.let(::resultSnapshot),
                failure = failure?.let(::failureSnapshot),
                outcome = outcomeSnapshot(operation.outcome().toCompletableFuture().join()),
            )
        } finally {
            runtime.close()
        }
    }

    private fun options(transport: Transport): ClientOptions = ClientOptions.builder()
        .apiKey("test-key")
        .baseUri("https://example.com")
        .connectTimeout(Duration.ofSeconds(2))
        .attemptTimeout(Duration.ofSeconds(3))
        .operationTimeout(Duration.ofSeconds(4))
        .maxAttempts(1)
        .transport(transport)
        .userAgentSuffix("parity/1")
        .defaultValueGenerators(GENERATORS)
        .idempotencyKeyGenerator { "adapter-key" }
        .build()

    private fun send(runtime: RepostRuntime): SendOperation = runtime.sendAsync(
        SCHEMA,
        EVENT,
        "cus_adapter",
        EMPTY_MODEL,
        SendOptions.defaults(),
    )

    private fun assertCancelledOutcome(outcome: SendOutcome, attempts: Int, status: Int?) {
        assertEquals(RepostErrorCode.CANCELLED, outcome.errorCode)
        assertEquals(attempts, outcome.attemptCount)
        assertEquals("adapter-key", outcome.idempotencyKey)
        assertEquals(status, outcome.httpStatus)
    }

    private fun requestSnapshot(request: TransportRequest): RequestSnapshot = RequestSnapshot(
        uri = request.uri.toASCIIString(),
        headers = request.headerFields.associate { it.name.lowercase() to it.value },
        body = bytes(request.body).toString(UTF_8),
        attemptNumber = request.attemptNumber,
        connectTimeout = request.connectTimeout,
        attemptTimeout = request.attemptTimeout,
    )

    private fun resultSnapshot(result: SendResult): ResultSnapshot = ResultSnapshot(
        id = result.id,
        type = result.type,
        customerId = result.customerId,
        timestamp = result.timestamp,
        deliveryState = result.deliveryState,
    )

    private fun failureSnapshot(failure: RepostException): FailureSnapshot = FailureSnapshot(
        errorCode = failure.errorCode,
        failureReason = failure.failureReason?.name,
        causeCategory = failure.causeCategory?.name,
        deliveryState = failure.deliveryState,
        operationIdPresent = failure.operationId != null,
        idempotencyKey = failure.idempotencyKey,
        attemptCount = failure.attemptCount,
        httpStatus = failure.httpStatus,
        retryable = failure.isRetryable,
    )

    private fun outcomeSnapshot(outcome: SendOutcome): OutcomeSnapshot = OutcomeSnapshot(
        accepted = outcome.isAccepted,
        operationIdPresent = outcome.operationId != null,
        deliveryState = outcome.deliveryState,
        errorCode = outcome.errorCode,
        failureReason = outcome.failureReason?.name,
        causeCategory = outcome.causeCategory?.name,
        attemptCount = outcome.attemptCount,
        idempotencyKey = outcome.idempotencyKey,
        httpStatus = outcome.httpStatus,
    )

    private fun unwrap(failure: Throwable): Throwable {
        var current = failure
        while ((current is CompletionException || current is ExecutionException) && current.cause != null) {
            current = current.cause!!
        }
        return current
    }

    private class RetryDetectingScheduler(
        private val retryScheduled: CountDownLatch,
    ) : ScheduledThreadPoolExecutor(1) {
        override fun schedule(
            command: Runnable,
            delay: Long,
            unit: TimeUnit,
        ): ScheduledFuture<*> {
            if (unit.toMillis(delay) == 30_000L) {
                retryScheduled.countDown()
            }
            return super.schedule(command, delay, unit)
        }
    }

    private enum class AdapterPath { JAVA, KOTLIN }

    private data class VectorSnapshot(
        val request: RequestSnapshot,
        val result: ResultSnapshot?,
        val failure: FailureSnapshot?,
        val outcome: OutcomeSnapshot,
    )

    private data class RequestSnapshot(
        val uri: String,
        val headers: Map<String, String>,
        val body: String,
        val attemptNumber: Int,
        val connectTimeout: Duration,
        val attemptTimeout: Duration,
    )

    private data class ResultSnapshot(
        val id: String,
        val type: String,
        val customerId: String,
        val timestamp: Instant,
        val deliveryState: DeliveryState,
    )

    private data class FailureSnapshot(
        val errorCode: RepostErrorCode,
        val failureReason: String?,
        val causeCategory: String?,
        val deliveryState: DeliveryState,
        val operationIdPresent: Boolean,
        val idempotencyKey: String?,
        val attemptCount: Int,
        val httpStatus: Int?,
        val retryable: Boolean,
    )

    private data class OutcomeSnapshot(
        val accepted: Boolean,
        val operationIdPresent: Boolean,
        val deliveryState: DeliveryState,
        val errorCode: RepostErrorCode?,
        val failureReason: String?,
        val causeCategory: String?,
        val attemptCount: Int,
        val idempotencyKey: String?,
        val httpStatus: Int?,
    )

    companion object {
        private val GENERATORS = DefaultValueGenerators.fixed(
            Instant.EPOCH,
            "12345678-1234-4234-9234-123456789abc",
            "abcdefghijklmnopqrstuvwx",
        )
        private val SCHEMA = SchemaDescriptor.builder(2)
            .addModel(ModelDescriptor.of("Payload", emptyList()))
            .addEvent("events", "created", EventDescriptor.of("contract.sent", "Payload"))
            .build()
        private val EVENT = SCHEMA.webhooks.getValue("events").getValue("created")
        private val EMPTY_MODEL = object : RepostModel {
            override fun __repostIsPresent(fieldIndex: Int): Boolean = false
            override fun __repostValue(fieldIndex: Int): Any? = null
        }

        private fun acceptedResponse(): CompletableFuture<TransportResponse> = response(
            202,
            """{"id":"msg_kotlin_adapter","type":"contract.sent","customerId":"cus_adapter","timestamp":"1970-01-01T00:00:00.000Z"}""".toByteArray(UTF_8),
            listOf(TransportHeaderField.of("Content-Type", "application/json")),
        )

        private fun response(
            status: Int,
            body: ByteArray = ByteArray(0),
            headers: List<TransportHeaderField> = emptyList(),
        ): CompletableFuture<TransportResponse> = CompletableFuture.completedFuture(
            TransportResponse.of(status, headers, ByteArrayInputStream(body)),
        )

        private fun bytes(source: ByteBuffer): ByteArray {
            val copy = source.asReadOnlyBuffer()
            return ByteArray(copy.remaining()).also(copy::get)
        }
    }
}
