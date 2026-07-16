package sh.repost.client;

import org.jspecify.annotations.Nullable;

/** Supplies the API key once per admitted send operation. */
@FunctionalInterface
public interface ApiKeyProvider {
    /**
     * Returns the API key, or {@code null} when the provider has no credential.
     *
     * @return the API key, or {@code null} when the provider has no credential
     */
    @Nullable String getApiKey();
}
