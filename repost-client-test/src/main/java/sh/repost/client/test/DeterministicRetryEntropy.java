package sh.repost.client.test;

import java.util.concurrent.ConcurrentLinkedQueue;
import sh.repost.client.RetryEntropy;

/** Thread-safe scripted retry-jitter entropy source. */
public final class DeterministicRetryEntropy implements RetryEntropy {
    private final ConcurrentLinkedQueue<Long> values = new ConcurrentLinkedQueue<>();

    private DeterministicRetryEntropy(long[] sequence) {
        for (long value : sequence) {
            values.add(value);
        }
    }

    /**
     * Creates an entropy source that returns the supplied values once in order.
     *
     * @param values ordered samples
     * @return deterministic entropy source
     */
    public static DeterministicRetryEntropy sequence(long... values) {
        if (values == null) {
            throw new NullPointerException("values");
        }
        return new DeterministicRetryEntropy(values.clone());
    }

    /**
     * Creates an entropy source that always throws.
     *
     * @param failure deterministic failure
     * @return throwing entropy source
     */
    public static RetryEntropy failing(RuntimeException failure) {
        if (failure == null) {
            throw new NullPointerException("failure");
        }
        return bound -> { throw failure; };
    }

    @Override
    public long nextLong(long exclusiveUpperBound) {
        Long next = values.poll();
        if (next == null) {
            throw new IllegalStateException("DeterministicRetryEntropy script exhausted");
        }
        if (next < 0L || next >= exclusiveUpperBound) {
            throw new IllegalArgumentException("scripted value is outside the requested range");
        }
        return next;
    }
}
