package sh.repost.client;

import java.time.Duration;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Immutable metadata supplied when live telemetry ends an attempt. */
public final class TelemetryAttemptEnd {
    private final int attemptNumber;
    private final Duration duration;
    private final ObserverOutcome outcome;
    private final RepostErrorCode errorCode;
    private final RepostFailureReason failureReason;
    private final DeliveryState deliveryState;
    private final HttpStatusClass httpStatusClass;

    TelemetryAttemptEnd(
            int attemptNumber,
            Duration duration,
            ObserverOutcome outcome,
            RepostErrorCode errorCode,
            RepostFailureReason failureReason,
            DeliveryState deliveryState,
            HttpStatusClass httpStatusClass) {
        this.attemptNumber = attemptNumber;
        this.duration = Objects.requireNonNull(duration, "duration");
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.errorCode = errorCode;
        this.failureReason = failureReason;
        this.deliveryState = Objects.requireNonNull(deliveryState, "deliveryState");
        this.httpStatusClass = Objects.requireNonNull(httpStatusClass, "httpStatusClass");
    }

    /**
     * Returns one-based attempt number.
     *
     * @return one-based attempt number
     */
    public int getAttemptNumber() { return attemptNumber; }
    /**
     * Returns elapsed attempt duration.
     *
     * @return elapsed attempt duration
     */
    public Duration getDuration() { return duration; }
    /**
     * Returns attempt outcome.
     *
     * @return attempt outcome
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
     * Returns best-known delivery state.
     *
     * @return best-known delivery state
     */
    public DeliveryState getDeliveryState() { return deliveryState; }
    /**
     * Returns low-cardinality HTTP status class.
     *
     * @return low-cardinality HTTP status class
     */
    public HttpStatusClass getHttpStatusClass() { return httpStatusClass; }
}
