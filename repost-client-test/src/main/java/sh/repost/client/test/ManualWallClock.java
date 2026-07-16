package sh.repost.client.test;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import sh.repost.client.WallClock;

/** Thread-safe manually advanced wall clock. */
public final class ManualWallClock implements WallClock {
    private final AtomicReference<Instant> value;

    /**
     * Creates a clock at the supplied instant.
     *
     * @param initial initial instant
     */
    public ManualWallClock(Instant initial) {
        value = new AtomicReference<>(Objects.requireNonNull(initial, "initial"));
    }

    /**
     * Advances the clock without sleeping.
     *
     * @param duration nonnegative duration
     */
    public void advance(Duration duration) {
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative");
        }
        value.updateAndGet(current -> current.plus(duration));
    }

    @Override
    public Instant now() {
        return value.get();
    }
}
