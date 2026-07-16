package com.repost.fixture

import com.acme.billing.BillingClient
import com.acme.billing.BillingClientFactory
import com.acme.orders.LineItem
import com.acme.orders.Order
import com.acme.orders.OrderStatus
import com.acme.orders.OrderWebhooks
import com.acme.orders.OrdersClient
import com.acme.orders.OrdersClientFactory
import com.acme.repost.Author
import com.acme.repost.Book
import com.acme.repost.RepostClient
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import sh.repost.client.ClientOptions
import sh.repost.client.DefaultValueGenerators
import sh.repost.client.DeliveryState
import sh.repost.client.GeneratedRepostClientRegistry
import sh.repost.client.RepostErrorCode
import sh.repost.client.RepostRuntime
import sh.repost.client.TransportHeaderField
import sh.repost.client.error.RepostTransportException
import sh.repost.client.test.StubTransport

class GeneratedKotlinClientBehaviorTest {
    @Test
    fun `exact public DSL shape sends absent null and value bytes through StubTransport`() = runBlocking {
        val transport = StubTransport()
            .enqueueResponse(202, acceptedResponse("msg_absent"))
            .enqueueResponse(202, acceptedResponse("msg_null"))
            .enqueueResponse(202, acceptedResponse("msg_value"))

        val results = RepostClient(options(transport)).use { repost ->
            val absent = repost.webhooks.book.created(customerId = "customer_123") {
                title = "Dune"
                authors = listOf(Author { name = "Frank Herbert" })
            }
            val explicitNull = repost.webhooks.book.created(
                customerId = "customer_123",
                data = Book {
                    title = "Dune"
                    authors = listOf(Author { name = "Frank Herbert" })
                    subtitle = null
                },
            )
            val value = repost.webhooks.book.created(customerId = "customer_123") {
                title = "Dune"
                authors = listOf(Author { name = "Frank Herbert" })
                subtitle = "A novel"
            }
            listOf(absent.id, explicitNull.id, value.id)
        }

        assertEquals(listOf("msg_absent", "msg_null", "msg_value"), results)
        val requests = transport.requests
        assertEquals(3, requests.size)
        assertArrayEquals(payload().toByteArray(UTF_8), requests[0].bodyBytes)
        assertArrayEquals(payload("null").toByteArray(UTF_8), requests[1].bodyBytes)
        assertArrayEquals(payload("\"A novel\"").toByteArray(UTF_8), requests[2].bodyBytes)
    }

    @Test
    fun `custom factories register two clients and preserve owned versus borrowed lifecycle`() = runBlocking {
        assertSame(
            OrdersClientFactory,
            OrdersClientFactory::class.java.getField("INSTANCE").get(null),
        )
        assertSame(
            BillingClientFactory,
            BillingClientFactory::class.java.getField("INSTANCE").get(null),
        )
        val registry = GeneratedRepostClientRegistry {
            listOf(OrdersClientFactory, BillingClientFactory)
        }
        assertEquals(
            listOf(OrdersClient::class.java, BillingClient::class.java),
            registry.factories().map { it.clientType() },
        )
        val deprecated = deprecatedLegacySendAnnotations()
        assertEquals(3, deprecated.size)
        assertTrue(deprecated.all { it.message == "Use Order.created instead" })
        assertManifestCompatibility()

        val transport = StubTransport().enqueueResponse(202, orderAcceptedResponse("msg_borrowed"))
        RepostRuntime.create(options(transport)).use { shared ->
            val orders = OrdersClientFactory.create(shared)
            val billing = BillingClientFactory.create(shared)
            orders.close()
            orders.close()
            billing.close()
            assertFalse(shared.isClosed)

            val result = orders.webhooks.order.created("customer_123", order())
            assertEquals("msg_borrowed", result.id)
            assertEquals(1, transport.requests.size)
        }

        val ownedTransport = StubTransport()
        val owned = RepostClient(options(ownedTransport))
        owned.close()
        owned.close()
        assertTrue(owned.diagnostics.isClosed)
        val failure = assertThrows(RepostTransportException::class.java) {
            runBlocking {
                owned.webhooks.book.created("customer_123") {
                    title = "Dune"
                    authors = listOf(Author { name = "Frank Herbert" })
                }
            }
        }
        assertEquals(RepostErrorCode.CLOSED, failure.errorCode)
        assertEquals(DeliveryState.NOT_SENT, failure.deliveryState)
        assertEquals(0, ownedTransport.requests.size)
    }

    @Test
    fun `generated suspend API preserves coroutine context`() = runBlocking {
        val transport = StubTransport().enqueueResponse(202, acceptedResponse("msg_context"))
        RepostClient(options(transport)).use { client ->
            withContext(CoroutineName("generated-context")) {
                val result = client.webhooks.book.created("customer_123") {
                    title = "Dune"
                    authors = listOf(Author { name = "Frank Herbert" })
                }
                assertEquals("msg_context", result.id)
                assertEquals("generated-context", currentCoroutineContext()[CoroutineName]?.name)
            }
        }
    }

    @Test
    fun `generated suspend API cancellation reaches StubTransport`() = runBlocking {
        val transport = StubTransport()
        val controlled = transport.enqueuePending()
        RepostClient(options(transport)).use { client ->
            val deferred = async(CoroutineName("generated-cancel"), start = CoroutineStart.UNDISPATCHED) {
                client.webhooks.book.created("customer_123") {
                    title = "Dune"
                    authors = listOf(Author { name = "Frank Herbert" })
                }
            }
            awaitRequestCount(transport, 1)
            deferred.cancel(CancellationException("fixture-cancelled"))
            deferred.cancelAndJoin()

            assertTrue(deferred.isCancelled)
            assertTrue(controlled.isCancelled)
            assertEquals(1, transport.requests.size)
        }
    }

    private fun options(transport: StubTransport): ClientOptions = ClientOptions.builder()
        .apiKey("key_test")
        .transport(transport)
        .maxAttempts(1)
        .defaultValueGenerators(DefaultValueGenerators.fixed(
            Instant.parse(TIMESTAMP),
            "00000000-0000-4000-8000-000000000001",
            "csequence000000000000001",
        ))
        .idempotencyKeyGenerator { "generated-key" }
        .build()

    private fun order(): Order = Order {
        number = "order-123"
        items = listOf(LineItem { sku = "sku-1" })
        status = OrderStatus.OPEN
    }

    private fun deprecatedLegacySendAnnotations(): List<Deprecated> = OrderWebhooks::class.java.declaredMethods
        .filter { method -> method.name == "legacyCreated" || method.name == "legacyCreatedOperation" }
        .mapNotNull { method -> method.getAnnotation(Deprecated::class.java) }

    private fun assertManifestCompatibility() {
        val expected = listOf(
            "79ddc13e228dee9f" to "com.acme.orders.OrdersClientFactory",
            "0c778e4f4d988328" to "com.acme.billing.BillingClientFactory",
        )
        for ((generatorId, factoryType) in expected) {
            val path = "META-INF/repost/generated-clients/v1/$generatorId/client.json"
            val manifest = requireNotNull(javaClass.classLoader.getResourceAsStream(path)) { path }
                .bufferedReader(UTF_8)
                .use { it.readText() }
            assertTrue(manifest.endsWith("\n"), path)
            assertTrue(manifest.contains("\"formatVersion\": 1"), path)
            assertTrue(manifest.contains("\"generatorId\": \"$generatorId\""), path)
            assertTrue(manifest.contains("\"language\": \"kotlin\""), path)
            assertTrue(manifest.contains("\"factoryType\": \"$factoryType\""), path)
            assertTrue(manifest.contains("\"descriptorVersion\": 2"), path)
            assertTrue(manifest.contains("\"runtimeVersion\": \"1.0.0\""), path)
        }
    }

    private fun awaitRequestCount(transport: StubTransport, expected: Int) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (transport.requests.size < expected && System.nanoTime() < deadline) {
            Thread.onSpinWait()
        }
        assertEquals(expected, transport.requests.size)
    }

    private fun acceptedResponse(id: String): String =
        "{\"id\":\"$id\",\"type\":\"book.created\",\"customerId\":\"customer_123\",\"timestamp\":\"$TIMESTAMP\"}"

    private fun orderAcceptedResponse(id: String): String =
        "{\"id\":\"$id\",\"type\":\"order.created\",\"customerId\":\"customer_123\",\"timestamp\":\"$TIMESTAMP\"}"

    private fun payload(subtitle: String? = null): String = buildString {
        append("{\"type\":\"book.created\",\"customerId\":\"customer_123\",")
        append("\"timestamp\":\"$TIMESTAMP\",\"data\":{")
        append("\"title\":\"Dune\",\"authors\":[{\"name\":\"Frank Herbert\"}]")
        if (subtitle != null) append(",\"subtitle\":$subtitle")
        append("}}")
    }

    private companion object {
        const val TIMESTAMP = "2026-01-01T00:00:00.000Z"
    }
}

@Suppress("unused")
private suspend fun exactPublicSnippetCompiles() {
    RepostClient().use { repost ->
        val result = repost.webhooks.book.created(customerId = "customer_123") {
            title = "Dune"
            authors = listOf(Author { name = "Frank Herbert" })
            subtitle = null
        }
        println(result.id)
    }
}

@Suppress("unused")
private fun exactConfigurationSnippetCompiles() {
    RepostClient {
        apiKey = System.getenv("REPOST_SEND_API_KEY")
    }.use {
        // send with it
    }
}
