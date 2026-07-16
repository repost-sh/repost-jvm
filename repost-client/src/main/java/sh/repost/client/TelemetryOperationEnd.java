package sh.repost.client;

import java.time.Duration;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Immutable metadata supplied when live telemetry ends an operation. */
public final class TelemetryOperationEnd {
    private final Duration duration;
    private final ObserverOutcome outcome;
    private final RepostErrorCode errorCode;
    private final RepostFailureReason failureReason;
    private final DeliveryState deliveryState;
    private final HttpStatusClass httpStatusClass;
    private final int attemptCount;

    TelemetryOperationEnd(
            Duration duration,
            ObserverOutcome outcome,
            RepostErrorCode errorCode,
            RepostFailureReason failureReason,
            DeliveryState deliveryState,
            HttpStatusClass httpStatusClass,
            int attemptCount) {
        this.duration = Objects.requireNonNull(duration, "duration");
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.errorCode = errorCode;
        this.failureReason = failureReason;
        this.deliveryState = Objects.requireNonNull(deliveryState, "deliveryState");
        this.httpStatusClass = Objects.requireNonNull(httpStatusClass, "httpStatusClass");
        if (attemptCount < 0 || attemptCount > 10) {
            throw new IllegalArgumentException("attemptCount must be within 0..10");
        }
        this.attemptCount = attemptCount;
    }

    /**
     * Returns elapsed operation duration.
     *
     * @return elapsed operation duration
     */
    public Duration getDuration() { return duration; }
    /**
     * Returns terminal operation outcome.
     *
     * @return terminal operation outcome
     */
    public ObserverOutcome getOutcome() { return outcome; }
    /**
     * Returns error code, or {@code null} for success.
     *
     * @return error code, or {@code null} for success
     */
    public @Nullable RepostErrorCode getErrorCode() { return errorCode; }
    /**
     * Returns network failure reason, or {@code null} when not applicable.
     *
     * @return network failure reason, or {@code null} when not applicable
     */
    public @Nullable RepostFailureReason getFailureReason() { return failureReason; }
    /**
     * Returns final delivery state.
     *
     * @return final delivery state
     */
    public DeliveryState getDeliveryState() { return deliveryState; }
    /**
     * Returns low-cardinality HTTP status class.
     *
     * @return low-cardinality HTTP status class
     */
    public HttpStatusClass getHttpStatusClass() { return httpStatusClass; }
    /**
     * Returns number of started transport attempts.
     *
     * @return number of started transport attempts
     */
    public int getAttemptCount() { return attemptCount; }
}
