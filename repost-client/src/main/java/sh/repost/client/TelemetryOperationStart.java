package sh.repost.client;

import java.time.Instant;
import java.util.Objects;

/** Immutable metadata supplied when live telemetry starts an operation. */
public final class TelemetryOperationStart {
    private final Instant startedAt;

    TelemetryOperationStart(Instant startedAt) {
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
    }

    /**
     * Returns wall-clock operation start sampled once at admission.
     *
     * @return wall-clock operation start sampled once at admission
     */
    public Instant getStartedAt() { return startedAt; }
}
