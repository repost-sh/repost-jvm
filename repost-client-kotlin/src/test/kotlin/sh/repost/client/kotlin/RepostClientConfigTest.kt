package sh.repost.client.kotlin

import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import sh.repost.client.ClientOptions
import sh.repost.client.ClientOptionKey
import sh.repost.client.ConfigurationIssueCode
import sh.repost.client.DefaultValueGenerators
import sh.repost.client.HttpTransportOptions
import sh.repost.client.IdempotencyKeyGenerator
import sh.repost.client.MonotonicClock
import sh.repost.client.RepostObserver
import sh.repost.client.RepostRuntime
import sh.repost.client.RepostTelemetry
import sh.repost.client.RetryEntropy
import sh.repost.client.Transport
import sh.repost.client.WallClock
import sh.repost.client.error.RepostConfigurationException

class RepostClientConfigTest {
    @Test
    fun `configuration exposes every approved typed nullable setting`() {
        val operationExecutor = Executors.newSingleThreadExecutor()
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val observerExecutor = Executors.newSingleThreadExecutor()
        val transport = Transport { error("unused") }
        val observer = RepostObserver { }
        val telemetry = object : RepostTelemetry {
            override fun captureContext() = error("unused")
            override fun startOperation(parent: sh.repost.client.CapturedTelemetryContext, start: sh.repost.client.TelemetryOperationStart) = error("unused")
        }
        val generators = DefaultValueGenerators.fixed(
            Instant.EPOCH,
            "12345678-1234-4234-9234-123456789abc",
            "abcdefghijklmnopqrstuvwx",
        )
        val keyGenerator = IdempotencyKeyGenerator { "kotlin-key" }
        val monotonicClock = MonotonicClock { 1L }
        val wallClock = WallClock { Instant.EPOCH }
        val entropy = RetryEntropy { 0L }
        try {
            val config = RepostClientConfig().apply {
                apiKey = "test-key"
                baseUri = "https://example.com"
                connectTimeout = Duration.ofSeconds(1)
                attemptTimeout = Duration.ofSeconds(2)
                operationTimeout = Duration.ofSeconds(3)
                retryBaseDelay = Duration.ofMillis(4)
                retryMaxDelay = Duration.ofMillis(5)
                maxAttempts = 2
                maxInFlightOperations = 3
                maxBufferedBytes = 4_194_304L
                this.transport = transport
                executor = operationExecutor
                this.scheduler = scheduler
                this.observer = observer
                this.observerExecutor = observerExecutor
                this.telemetry = telemetry
                userAgentSuffix = "contract-test/1"
                defaultValueGenerators = generators
                idempotencyKeyGenerator = keyGenerator
                this.monotonicClock = monotonicClock
                this.wallClock = wallClock
                retryEntropy = entropy
            }

            val options = config.build()
            val runtime = RepostRuntime.create(options)
            runtime.close()

            assertFalse(operationExecutor.isShutdown)
            assertFalse(scheduler.isShutdown)
            assertFalse(observerExecutor.isShutdown)
            assertSame(transport, config.transport)
            assertSame(observer, config.observer)
            assertSame(telemetry, config.telemetry)
        } finally {
            operationExecutor.shutdownNow()
            scheduler.shutdownNow()
            observerExecutor.shutdownNow()
        }
    }

    @Test
    fun `unset values preserve Java defaults and null clears a prior DSL value`() {
        val config = RepostClientConfig()
        config.maxAttempts = 1
        config.maxAttempts = null

        config.build()
    }

    @Test
    fun `Java and Kotlin configuration report identical validation details`() {
        val javaFailure = assertThrows(RepostConfigurationException::class.java) {
            ClientOptions.builder()
                .retryBaseDelay(Duration.ofSeconds(2))
                .retryMaxDelay(Duration.ofSeconds(1))
                .build()
        }
        val kotlinFailure = assertThrows(RepostConfigurationException::class.java) {
            RepostClientConfig().apply {
                retryBaseDelay = Duration.ofSeconds(2)
                retryMaxDelay = Duration.ofSeconds(1)
            }.build()
        }

        assertEquals(javaFailure.errorCode, kotlinFailure.errorCode)
        assertEquals(javaFailure.deliveryState, kotlinFailure.deliveryState)
        assertEquals(
            javaFailure.configurationIssues.single().code,
            kotlinFailure.configurationIssues.single().code,
        )
        assertEquals(
            javaFailure.configurationIssues.single().optionKeys,
            kotlinFailure.configurationIssues.single().optionKeys,
        )
        assertEquals(ConfigurationIssueCode.CONFLICT, kotlinFailure.configurationIssues.single().code)
        assertEquals(
            listOf(ClientOptionKey.RETRY_BASE_DELAY, ClientOptionKey.RETRY_MAX_DELAY),
            kotlinFailure.configurationIssues.single().optionKeys,
        )
    }

    @Test
    fun `raw base URI is validated only by the canonical Java builder`() {
        val raw = " https://example.com/"
        val javaFailure = assertThrows(RepostConfigurationException::class.java) {
            ClientOptions.builder().baseUri(raw).build()
        }
        val kotlinFailure = assertThrows(RepostConfigurationException::class.java) {
            RepostClientConfig().apply { baseUri = raw }.build()
        }

        assertEquals(javaFailure.errorCode, kotlinFailure.errorCode)
        assertEquals(
            javaFailure.configurationIssues.single().code,
            kotlinFailure.configurationIssues.single().code,
        )
        assertEquals(
            javaFailure.configurationIssues.single().optionKeys,
            kotlinFailure.configurationIssues.single().optionKeys,
        )
    }

    @Test
    fun `transport alternatives retain Java mutual exclusion`() {
        val transport = Transport { error("unused") }
        val failure = assertThrows(RepostConfigurationException::class.java) {
            RepostClientConfig().apply {
                httpTransportOptions = HttpTransportOptions.defaults()
                this.transport = transport
            }.build()
        }

        assertEquals(ConfigurationIssueCode.CONFLICT, failure.configurationIssues.single().code)
        assertEquals(
            listOf(ClientOptionKey.HTTP_TRANSPORT_OPTIONS, ClientOptionKey.TRANSPORT),
            failure.configurationIssues.single().optionKeys,
        )
    }
}
