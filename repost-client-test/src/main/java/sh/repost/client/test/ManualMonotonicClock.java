package sh.repost.client.test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import sh.repost.client.MonotonicClock;

/** Thread-safe manually advanced monotonic clock. */
public final class ManualMonotonicClock implements MonotonicClock {
    private final AtomicLong nanos;

    /**
     * Creates a clock at the supplied opaque nanosecond value.
     *
     * @param initialNanos initial sample
     */
    public ManualMonotonicClock(long initialNanos) {
        nanos = new AtomicLong(initialNanos);
    }

    /**
     * Advances the clock without sleeping.
     *
     * @param duration nonnegative duration
     */
    public void advance(Duration duration) {
        long delta = duration.toNanos();
        if (delta < 0L) {
            throw new IllegalArgumentException("duration must not be negative");
        }
        nanos.addAndGet(delta);
    }

    @Override
    public long nanoTime() {
        return nanos.get();
    }
}
