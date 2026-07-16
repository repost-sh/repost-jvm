package com.repost.fixture

import com.acme.repost.Author
import com.acme.repost.Book
import com.acme.repost.RepostClient
import com.acme.repost.RepostClientFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import sh.repost.client.ClientOptions

class LivePublishTest {
    @Test
    fun `generated Kotlin DSL and prebuilt model publish to the real product`() = runBlocking {
        val apiKey = System.getenv("REPOST_E2E_PUBLISH_KEY")
        val baseUri = System.getenv("REPOST_E2E_PUBLISH_URL")
        val runId = System.getenv("REPOST_E2E_RUN_ID")
        val resultFile = System.getenv("REPOST_E2E_RESULT_FILE")
        assumeTrue(
            apiKey != null && baseUri != null && runId != null && resultFile != null,
            "live product environment is not configured",
        )

        val customer = "jvm-kotlin-$runId"
        val client = RepostClient(
            ClientOptions.builder()
                .apiKey(apiKey)
                .baseUri(baseUri)
                .build(),
        )
        val dsl = client.use { repost ->
            val first = repost.webhooks.book.created(
                customerId = customer,
                idempotencyKey = "kotlin-dsl-$runId",
            ) {
                title = "Kotlin DSL client"
                authors = listOf(Author { name = "Repost SDK" })
            }
            val second = repost.webhooks.book.created(
                customerId = customer,
                data = Book {
                    title = "Kotlin prebuilt model"
                    authors = listOf(Author { name = "Repost SDK" })
                    subtitle = null
                },
                idempotencyKey = "kotlin-model-$runId",
            )
            first to second
        }
        assertTrue(client.diagnostics.isClosed)

        assertThrows(IllegalStateException::class.java) {
            Book { title = "invalid" }
        }

        val json = buildString {
            append('{')
            append(field("surface", "kotlin")).append(',')
            append(field("factory", RepostClientFactory::class.java.name)).append(',')
            append(field("customer", customer)).append(',')
            append(field("dslId", dsl.first.id)).append(',')
            append(field("modelId", dsl.second.id)).append(',')
            append("\"closed\":true,\"validationRejected\":true}\n")
        }
        Files.writeString(Path.of(resultFile), json)
        Unit
    }

    private fun field(name: String, value: String): String =
        "\"$name\":\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}
