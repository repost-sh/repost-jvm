package com.acme.consumer

import com.acme.consumer.generated.Order
import com.acme.consumer.generated.RepostClient
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import sh.repost.client.DefaultValueGenerators
import sh.repost.client.test.StubTransport

fun main(): Unit = runBlocking {
    val transport = StubTransport()
        .enqueueResponse(202, accepted("msg_dsl"))
        .enqueueResponse(202, accepted("msg_prebuilt"))
    RepostClient {
        apiKey = "test-key"
        this.transport = transport
        maxAttempts = 1
        defaultValueGenerators = DefaultValueGenerators.fixed(
            java.time.Instant.EPOCH,
            "00000000-0000-4000-8000-000000000001",
            "csequence000000000000001",
        )
    }.use { client ->
        check(client.webhooks.order.created("customer-123") { id = "order-dsl" }.id == "msg_dsl")
        check(client.webhooks.order.created("customer-123", Order { id = "order-prebuilt" }).id == "msg_prebuilt")

        val pending = transport.enqueuePending()
        val send = launch(start = CoroutineStart.UNDISPATCHED) {
            client.webhooks.order.created("customer-123") { id = "order-cancelled" }
        }
        check(transport.awaitRequestCount(3, Duration.ofSeconds(5)))
        send.cancelAndJoin()
        check(pending.isCancelled)
    }
    check(transport.requests.size == 3)
    check(String(transport.requests[0].bodyBytes, UTF_8).contains("\"id\":\"order-dsl\""))
}

private fun accepted(id: String): String =
    "{\"id\":\"$id\",\"type\":\"order.created\",\"customerId\":\"customer-123\"," +
        "\"timestamp\":\"1970-01-01T00:00:00.000Z\"}"
