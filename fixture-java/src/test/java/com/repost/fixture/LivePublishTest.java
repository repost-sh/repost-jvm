package com.repost.fixture;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acme.repost.Author;
import com.acme.repost.Book;
import com.acme.repost.RepostClient;
import com.acme.repost.RepostClientFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import sh.repost.client.ClientOptions;
import sh.repost.client.DefaultValueGenerators;
import sh.repost.client.SendOptions;
import sh.repost.client.SendResult;

final class LivePublishTest {
    @Test
    void generatedJavaClientPublishesToTheRealProduct() throws Exception {
        String apiKey = System.getenv("REPOST_E2E_PUBLISH_KEY");
        String baseUri = System.getenv("REPOST_E2E_PUBLISH_URL");
        String runId = System.getenv("REPOST_E2E_RUN_ID");
        String resultFile = System.getenv("REPOST_E2E_RESULT_FILE");
        Assumptions.assumeTrue(apiKey != null && baseUri != null
                && runId != null && resultFile != null, "live product environment is not configured");

        String customer = "jvm-java-" + runId;
        Instant timestamp = Instant.now();
        ClientOptions options = ClientOptions.builder()
                .apiKey(apiKey)
                .baseUri(baseUri)
                // Caller-driven replay must preserve the exact envelope bytes.
                .defaultValueGenerators(DefaultValueGenerators.fixed(
                        timestamp,
                        "00000000-0000-4000-8000-000000000000",
                        "a00000000000000000000000"))
                .build();
        Book data = book("Java generated client");
        SendResult sync;
        SendResult async;
        SendResult replay;
        RepostClient direct = RepostClient.create(options);
        try {
            sync = direct.webhooks().book().created(
                    customer, data, sendOptions("java-sync-" + runId));
            async = direct.webhooks().book().createdAsync(
                    customer, book("Java async client"), sendOptions("java-async-" + runId))
                    .toCompletableFuture().join();
            replay = direct.webhooks().book().created(
                    customer, book("Java idempotent retry"),
                    sendOptions("java-retry-" + runId));
        } finally {
            direct.close();
        }
        assertTrue(direct.diagnostics().isClosed());

        String invalidCustomer = "jvm-invalid-" + runId;
        assertThrows(IllegalStateException.class,
                () -> Book.builder().title("invalid").build());

        String json = "{"
                + field("surface", "java") + ","
                + field("factory", RepostClientFactory.class.getName()) + ","
                + field("customer", customer) + ","
                + field("invalidCustomer", invalidCustomer) + ","
                + field("syncId", sync.getId()) + ","
                + field("asyncId", async.getId()) + ","
                + field("retryId", replay.getId()) + ","
                + "\"closed\":true,\"validationRejected\":true}\n";
        Files.writeString(Path.of(resultFile), json, StandardCharsets.UTF_8);
    }

    private static Book book(String title) {
        return Book.builder()
                .title(title)
                .authors(List.of(Author.builder().name("Repost SDK").build()))
                .build();
    }

    private static SendOptions sendOptions(String key) {
        return SendOptions.builder().idempotencyKey(key).build();
    }

    private static String field(String name, String value) {
        return "\"" + name + "\":\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
