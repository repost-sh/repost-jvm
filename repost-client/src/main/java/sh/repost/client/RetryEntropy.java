package sh.repost.client;

/** Entropy source for bounded retry jitter. */
@FunctionalInterface
public interface RetryEntropy {
    /**
     * Samples a value in a half-open range.
     *
     * @param exclusiveUpperBound positive exclusive upper bound
     * @return a value from zero through {@code exclusiveUpperBound - 1}
     */
    long nextLong(long exclusiveUpperBound);
}
