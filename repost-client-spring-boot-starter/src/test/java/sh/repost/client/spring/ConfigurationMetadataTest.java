package sh.repost.client.spring;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class ConfigurationMetadataTest {
    @Test
    void metadataEnumeratesEveryExactKeyWithoutAnApiKeyValue() throws IOException {
        String metadata;
        try (var stream = RepostClientProperties.class.getClassLoader()
                .getResourceAsStream("META-INF/spring-configuration-metadata.json")) {
            assertNotNull(stream);
            metadata = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        for (String key : new String[]{
            "repost.client.enabled",
            "repost.client.api-key",
            "repost.client.base-uri",
            "repost.client.connect-timeout",
            "repost.client.attempt-timeout",
            "repost.client.operation-timeout",
            "repost.client.max-attempts",
            "repost.client.max-in-flight",
            "repost.client.max-buffered-bytes",
            "repost.client.retry-base-delay",
            "repost.client.retry-max-delay",
            "repost.client.user-agent-suffix",
            "repost.client.observability.micrometer-enabled",
            "repost.client.observability.opentelemetry-enabled"
        }) {
            assertTrue(metadata.contains("\"name\": \"" + key + "\""), key);
        }

        int apiKeyStart = metadata.indexOf("\"name\": \"repost.client.api-key\"");
        int apiKeyEnd = metadata.indexOf('}', apiKeyStart);
        String apiKeyMetadata = metadata.substring(apiKeyStart, apiKeyEnd);
        assertFalse(apiKeyMetadata.contains("defaultValue"), apiKeyMetadata);
        assertFalse(apiKeyMetadata.contains("example"), apiKeyMetadata);
        assertFalse(apiKeyMetadata.contains("\"value\""), apiKeyMetadata);
    }
}
