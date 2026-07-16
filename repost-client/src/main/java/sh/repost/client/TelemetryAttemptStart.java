package sh.repost.client;

import java.time.Instant;
import java.util.Objects;

/** Immutable metadata supplied when live telemetry starts an attempt. */
public final class TelemetryAttemptStart {
    private final int attemptNumber;
    private final Instant startedAt;

    TelemetryAttemptStart(int attemptNumber, Instant startedAt) {
        if (attemptNumber < 1 || attemptNumber > 10) {
            throw new IllegalArgumentException("attemptNumber must be within 1..10");
        }
        this.attemptNumber = attemptNumber;
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
    }

    /**
     * Returns one-based attempt number.
     *
     * @return one-based attempt number
     */
    public int getAttemptNumber() { return attemptNumber; }
    /**
     * Returns derived wall-clock attempt start.
     *
     * @return derived wall-clock attempt start
     */
    public Instant getStartedAt() { return startedAt; }
}
