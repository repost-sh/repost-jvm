package sh.repost.client.test;

import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;
import sh.repost.client.IdempotencyKeyGenerator;

/** Thread-safe fixed, sequenced, or failing idempotency-key generator. */
public final class TestIdempotencyKeyGenerator implements IdempotencyKeyGenerator {
    private final Supplier<String> values;

    private TestIdempotencyKeyGenerator(Supplier<String> values) {
        this.values = values;
    }

    /**
     * Creates a generator that returns the same key for every operation.
     *
     * @param key fixed key
     * @return fixed generator
     */
    public static TestIdempotencyKeyGenerator fixed(String key) {
        String value = Objects.requireNonNull(key, "key");
        return new TestIdempotencyKeyGenerator(() -> value);
    }

    /**
     * Creates a generator that consumes the supplied keys once in order.
     *
     * @param keys ordered keys
     * @return sequenced generator
     */
    public static TestIdempotencyKeyGenerator sequence(String... keys) {
        Objects.requireNonNull(keys, "keys");
        ConcurrentLinkedQueue<String> remaining = new ConcurrentLinkedQueue<>();
        for (String key : keys.clone()) {
            remaining.add(Objects.requireNonNull(key, "keys contains null"));
        }
        return new TestIdempotencyKeyGenerator(() -> {
            String value = remaining.poll();
            if (value == null) {
                throw new IllegalStateException("TestIdempotencyKeyGenerator script exhausted");
            }
            return value;
        });
    }

    /**
     * Creates a generator that always throws the supplied failure.
     *
     * @param failure deterministic failure
     * @return failing generator
     */
    public static TestIdempotencyKeyGenerator failing(RuntimeException failure) {
        RuntimeException fixedFailure = Objects.requireNonNull(failure, "failure");
        return new TestIdempotencyKeyGenerator(() -> { throw fixedFailure; });
    }

    @Override
    public String generate() {
        return values.get();
    }

    @Override
    public String toString() {
        return "TestIdempotencyKeyGenerator[REDACTED]";
    }
}
