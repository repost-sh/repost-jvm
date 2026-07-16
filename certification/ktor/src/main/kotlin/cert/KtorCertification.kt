package cert

import cert.generated.kotlin.Book
import cert.generated.kotlin.KotlinClientFactory
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.opentelemetry.api.trace.Span
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import java.time.Instant
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import sh.repost.client.ClientOptions
import sh.repost.client.DefaultValueGenerators
import sh.repost.client.RepostRuntime
import sh.repost.client.opentelemetry.OpenTelemetryRepostTelemetry
import sh.repost.client.test.StubTransport

private const val timestamp = "2026-01-01T00:00:00.000Z"

fun main() = runBlocking(CoroutineName("ktor-certification-parent")) {
    val threadsBefore = liveThreads()
    val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
        routing {
            post("/v1/messages") {
                check(call.receiveText().contains("\"type\":\"book.created\""))
                call.respondText(accepted("msg_loopback"), ContentType.Application.Json, HttpStatusCode.Accepted)
            }
        }
    }.start()
    val port = server.engine.resolvedConnectors().single().port

    val exporter = InMemorySpanExporter.create()
    val tracerProvider = SdkTracerProvider.builder()
        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
        .build()
    val telemetry = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build()
    val runtime = RepostRuntime.create(
        ClientOptions.builder()
            .apiKey("key_certification")
            .baseUri("http://127.0.0.1:$port")
            .telemetry(OpenTelemetryRepostTelemetry.create(telemetry))
            .maxAttempts(1)
            .defaultValueGenerators(defaults())
            .build(),
    )
    val client = KotlinClientFactory.create(runtime)

    try {
        val parent = telemetry.getTracer("certification").spanBuilder("ktor-parent").startSpan()
        val loopback = parent.makeCurrent().use {
            check(currentCoroutineContext()[CoroutineName]?.name == "ktor-certification-parent")
            client.webhooks.book.created("customer_123", Book { title = "Dune" })
        }
        check(loopback.id == "msg_loopback")

        val spans = exporter.finishedSpanItems
        val sendSpan = spans.single { it.name == "repost.send" }
        check(sendSpan.traceId == parent.spanContext.traceId)
        check(sendSpan.parentSpanId == parent.spanContext.spanId)
        parent.end()

        val stub = StubTransport().enqueueResponse(202, accepted("msg_stub"))
        RepostRuntime.create(options(stub)).use { stubRuntime ->
            check(KotlinClientFactory.create(stubRuntime).webhooks.book.created(
                "customer_123", Book { title = "Dune" },
            ).id == "msg_stub")
        }

        val pending = StubTransport()
        val controlled = pending.enqueuePending()
        RepostRuntime.create(options(pending)).use { cancelRuntime ->
            val child = async(start = CoroutineStart.UNDISPATCHED) {
                KotlinClientFactory.create(cancelRuntime).webhooks.book.created(
                    "customer_123", Book { title = "Dune" },
                )
            }
            pending.awaitRequestCount(1, java.time.Duration.ofSeconds(2))
            child.cancel(CancellationException("parent cancelled"))
            child.cancelAndJoin()
            check(controlled.isCancelled)
        }
    } finally {
        runtime.close()
        server.stop(0, 5_000)
        tracerProvider.close()
    }

    check(runtime.diagnostics().isClosed)
    println("CERTIFICATION ktor=${System.getProperty("cert.ktorVersion")} kotlin=${KotlinVersion.CURRENT} stub=passed loopback=passed cancellation=passed otel-parent=passed shutdown=passed thread-delta=${liveThreads() - threadsBefore}")
}

private fun options(transport: StubTransport): ClientOptions = ClientOptions.builder()
    .apiKey("key_certification")
    .transport(transport)
    .maxAttempts(1)
    .defaultValueGenerators(defaults())
    .build()

private fun defaults(): DefaultValueGenerators = DefaultValueGenerators.fixed(
    Instant.parse(timestamp),
    "00000000-0000-4000-8000-000000000001",
    "csequence000000000000001",
)

private fun accepted(id: String): String =
    """{"id":"$id","type":"book.created","customerId":"customer_123","timestamp":"$timestamp"}"""

private fun liveThreads(): Int = Thread.getAllStackTraces().keys.count { it.isAlive }
