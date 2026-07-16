package com.repost.fixture;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.acme.repost.Author;
import com.acme.repost.Book;
import com.acme.repost.RepostClient;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import sh.repost.client.ClientOptions;
import sh.repost.client.DefaultValueGenerators;
import sh.repost.client.SendOptions;
import sh.repost.client.SendResult;
import sh.repost.client.test.RecordedRequest;
import sh.repost.client.test.StubTransport;

final class GeneratedClientBehaviorTest {
    private static final String TIMESTAMP = "2026-01-01T00:00:00.000Z";

    @Test
    void publicJavaSnippetPreservesAbsentAndExplicitNullPayloadBytes() {
        StubTransport transport = new StubTransport()
                .enqueueResponse(202, acceptedResponse("msg_absent"))
                .enqueueResponse(202, acceptedResponse("msg_sync"))
                .enqueueResponse(202, acceptedResponse("msg_async"));
        ClientOptions options = ClientOptions.builder()
                .apiKey("key_test")
                .transport(transport)
                .maxAttempts(1)
                .defaultValueGenerators(DefaultValueGenerators.fixed(
                        Instant.parse(TIMESTAMP),
                        "00000000-0000-4000-8000-000000000001",
                        "csequence000000000000001"))
                .idempotencyKeyGenerator(() -> "generated-key")
                .build();
        List<Author> authors =
                List.of(Author.builder().name("Frank Herbert").build());
        Book absentSubtitle = Book.builder()
                .title("Dune")
                .authors(authors)
                .build();

        try (RepostClient repost = RepostClient.create(options)) {
            repost.webhooks().book().created("customer_123", absentSubtitle);

            Book book = Book.builder()
                    .title("Dune")
                    .authors(List.of(Author.builder().name("Frank Herbert").build()))
                    .subtitle(null)
                    .build();

            SendResult result = repost.webhooks().book().created("customer_123", book);

            SendResult asyncResult = repost.webhooks()
                    .book()
                    .createdAsync(
                            "customer_123",
                            book,
                            SendOptions.builder().idempotencyKey("order-123").build())
                    .toCompletableFuture()
                    .join();

            assertEquals("msg_sync", result.getId());
            assertEquals("msg_async", asyncResult.getId());
        }

        List<RecordedRequest> requests = transport.getRequests();
        assertEquals(3, requests.size());
        assertArrayEquals(
                payloadWithoutSubtitle().getBytes(UTF_8),
                requests.get(0).getBodyBytes());
        assertArrayEquals(
                payloadWithNullSubtitle().getBytes(UTF_8),
                requests.get(1).getBodyBytes());
        assertArrayEquals(
                payloadWithNullSubtitle().getBytes(UTF_8),
                requests.get(2).getBodyBytes());
    }

    private static String acceptedResponse(String id) {
        return "{\"id\":\"" + id + "\",\"type\":\"book.created\","
                + "\"customerId\":\"customer_123\",\"timestamp\":\"" + TIMESTAMP + "\"}";
    }

    private static String payloadWithoutSubtitle() {
        return "{\"type\":\"book.created\",\"customerId\":\"customer_123\","
                + "\"timestamp\":\"" + TIMESTAMP + "\",\"data\":{"
                + "\"title\":\"Dune\",\"authors\":[{\"name\":\"Frank Herbert\"}]}}";
    }

    private static String payloadWithNullSubtitle() {
        return "{\"type\":\"book.created\",\"customerId\":\"customer_123\","
                + "\"timestamp\":\"" + TIMESTAMP + "\",\"data\":{"
                + "\"title\":\"Dune\",\"authors\":[{\"name\":\"Frank Herbert\"}],"
                + "\"subtitle\":null}}";
    }
}
