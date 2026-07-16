package sh.repost.client;

/** Monotonic time source used for deadlines and elapsed durations. */
@FunctionalInterface
public interface MonotonicClock {
    /**
     * Returns an opaque monotonic nanosecond sample.
     *
     * @return an opaque monotonic nanosecond sample
     */
    long nanoTime();
}
