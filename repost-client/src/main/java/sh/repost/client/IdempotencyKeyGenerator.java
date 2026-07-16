package sh.repost.client;

/** Generates stable operation idempotency keys before the first attempt. */
@FunctionalInterface
public interface IdempotencyKeyGenerator {
    /**
     * Returns a new key that satisfies the public idempotency-key grammar.
     *
     * @return a new key that satisfies the public idempotency-key grammar
     */
    String generate();
}
