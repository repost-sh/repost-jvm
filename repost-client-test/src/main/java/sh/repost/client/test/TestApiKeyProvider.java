package sh.repost.client.test;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import sh.repost.client.ApiKeyProvider;

/** Thread-safe deterministic API-key provider for rotation and failure tests. */
public final class TestApiKeyProvider implements ApiKeyProvider {
    private final ConcurrentLinkedQueue<Object> values;

    private TestApiKeyProvider(Object[] values) {
        this.values = new ConcurrentLinkedQueue<>(Arrays.asList(values));
    }

    /**
     * Creates a provider that returns each key once in order.
     *
     * @param keys ordered API keys
     * @return rotating provider
     */
    public static TestApiKeyProvider rotating(String... keys) {
        Objects.requireNonNull(keys, "keys");
        Object[] values = keys.clone();
        for (Object value : values) {
            Objects.requireNonNull(value, "keys contains null");
        }
        return new TestApiKeyProvider(values);
    }

    /**
     * Creates a provider that throws the supplied failure once invoked.
     *
     * @param failure deterministic provider failure
     * @return failing provider
     */
    public static TestApiKeyProvider failing(RuntimeException failure) {
        return new TestApiKeyProvider(new Object[] {Objects.requireNonNull(failure, "failure")});
    }

    @Override
    public String getApiKey() {
        Object next = values.poll();
        if (next == null) {
            throw new IllegalStateException("TestApiKeyProvider script exhausted");
        }
        if (next instanceof RuntimeException) {
            throw (RuntimeException) next;
        }
        return (String) next;
    }

    @Override
    public String toString() {
        return "TestApiKeyProvider[REDACTED]";
    }
}
