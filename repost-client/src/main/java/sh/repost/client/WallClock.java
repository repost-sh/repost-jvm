package sh.repost.client;

import java.time.Instant;

/** Wall-clock source for externally meaningful timestamps. */
@FunctionalInterface
public interface WallClock {
    /**
     * Returns the current wall-clock instant.
     *
     * @return the current wall-clock instant
     */
    Instant now();
}
