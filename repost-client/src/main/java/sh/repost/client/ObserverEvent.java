package sh.repost.client;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Immutable, versioned, credential-free event delivered to a {@link RepostObserver}. */
public final class ObserverEvent {
    /** Current observer-event schema version. */
    public static final int SCHEMA_VERSION = 1;

    private final ObserverEventKind kind;
    private final String operationId;
    private final Instant timestamp;
    private final Integer attemptNumber;
    private final Duration duration;
    private final ObserverOutcome outcome;
    private final RepostErrorCode errorCode;
    private final DeliveryState deliveryState;
    private final HttpStatusClass httpStatusClass;
    private final Duration retryDelay;
    private final Instant operationStartedAt;
    private final Instant operationEndedAt;
    private final List<AttemptSummary> attemptSummaries;

    ObserverEvent(
            ObserverEventKind kind,
            String operationId,
            Instant timestamp,
            Integer attemptNumber,
            Duration duration,
            ObserverOutcome outcome,
            RepostErrorCode errorCode,
            DeliveryState deliveryState,
            HttpStatusClass httpStatusClass,
            Duration retryDelay,
            Instant operationStartedAt,
            Instant operationEndedAt,
            List<AttemptSummary> attemptSummaries) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.operationId = Objects.requireNonNull(operationId, "operationId");
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp");
        this.attemptNumber = attemptNumber;
        this.duration = duration;
        this.outcome = outcome;
        this.errorCode = errorCode;
        this.deliveryState = deliveryState;
        this.httpStatusClass = Objects.requireNonNull(httpStatusClass, "httpStatusClass");
        this.retryDelay = retryDelay;
        this.operationStartedAt = operationStartedAt;
        this.operationEndedAt = operationEndedAt;
        Objects.requireNonNull(attemptSummaries, "attemptSummaries");
        if (attemptSummaries.size() > 10) {
            throw new IllegalArgumentException("attemptSummaries exceeds 10");
        }
        this.attemptSummaries = Collections.unmodifiableList(new ArrayList<>(attemptSummaries));
    }

    /**
     * Returns event schema version.
     *
     * @return event schema version
     */
    public int getSchemaVersion() { return SCHEMA_VERSION; }
    /**
     * Returns event kind.
     *
     * @return event kind
     */
    public ObserverEventKind getKind() { return kind; }
    /**
     * Returns operation identifier.
     *
     * @return operation identifier
     */
    public String getOperationId() { return operationId; }
    /**
     * Returns event timestamp derived from monotonic elapsed time.
     *
     * @return event timestamp derived from monotonic elapsed time
     */
    public Instant getTimestamp() { return timestamp; }
    /**
     * Returns one-based attempt number, or {@code null} when not applicable.
     *
     * @return one-based attempt number, or {@code null} when not applicable
     */
    public @Nullable Integer getAttemptNumber() { return attemptNumber; }
    /**
     * Returns elapsed duration, or {@code null} when not applicable.
     *
     * @return elapsed duration, or {@code null} when not applicable
     */
    public @Nullable Duration getDuration() { return duration; }
    /**
     * Returns outcome, or {@code null} for nonterminal events.
     *
     * @return outcome, or {@code null} for nonterminal events
     */
    public @Nullable ObserverOutcome getOutcome() { return outcome; }
    /**
     * Returns error code, or {@code null} when not applicable.
     *
     * @return error code, or {@code null} when not applicable
     */
    public @Nullable RepostErrorCode getErrorCode() { return errorCode; }
    /**
     * Returns delivery state, or {@code null} when not applicable.
     *
     * @return delivery state, or {@code null} when not applicable
     */
    public @Nullable DeliveryState getDeliveryState() { return deliveryState; }
    /**
     * Returns low-cardinality HTTP status class.
     *
     * @return low-cardinality HTTP status class
     */
    public HttpStatusClass getHttpStatusClass() { return httpStatusClass; }
    /**
     * Returns scheduled retry delay, or {@code null} for other event kinds.
     *
     * @return scheduled retry delay, or {@code null} for other event kinds
     */
    public @Nullable Duration getRetryDelay() { return retryDelay; }
    /**
     * Returns captured operation start, or {@code null} outside terminal events.
     *
     * @return captured operation start, or {@code null} outside terminal events
     */
    public @Nullable Instant getOperationStartedAt() { return operationStartedAt; }
    /**
     * Returns derived operation end, or {@code null} outside terminal events.
     *
     * @return derived operation end, or {@code null} outside terminal events
     */
    public @Nullable Instant getOperationEndedAt() { return operationEndedAt; }
    /**
     * Returns immutable bounded attempt summaries.
     *
     * @return immutable bounded attempt summaries
     */
    public List<AttemptSummary> getAttemptSummaries() { return attemptSummaries; }
}
