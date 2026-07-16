package sh.repost.client;

import java.time.Duration;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Immutable, low-cardinality summary of one completed transport attempt. */
public final class AttemptSummary {
    private final int attemptNumber;
    private final Duration duration;
    private final ObserverOutcome outcome;
    private final RepostErrorCode errorCode;
    private final DeliveryState deliveryState;
    private final HttpStatusClass httpStatusClass;

    AttemptSummary(
            int attemptNumber,
            Duration duration,
            ObserverOutcome outcome,
            RepostErrorCode errorCode,
            DeliveryState deliveryState,
            HttpStatusClass httpStatusClass) {
        if (attemptNumber < 1 || attemptNumber > 10) {
            throw new IllegalArgumentException("attemptNumber must be within 1..10");
        }
        this.attemptNumber = attemptNumber;
        this.duration = Objects.requireNonNull(duration, "duration");
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.errorCode = errorCode;
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
     * Returns error code, or {@code null} for a successful attempt.
     *
     * @return error code, or {@code null} for a successful attempt
     */
    public @Nullable RepostErrorCode getErrorCode() { return errorCode; }
    /**
     * Returns best-known delivery state after this attempt.
     *
     * @return best-known delivery state after this attempt
     */
    public DeliveryState getDeliveryState() { return deliveryState; }
    /**
     * Returns low-cardinality HTTP status class.
     *
     * @return low-cardinality HTTP status class
     */
    public HttpStatusClass getHttpStatusClass() { return httpStatusClass; }
}
