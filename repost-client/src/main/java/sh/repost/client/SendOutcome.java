package sh.repost.client;

import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Stable reconciliation metadata that settles even when a send is cancelled. */
public final class SendOutcome {
    private static final Pattern OPERATION_ID = Pattern.compile(
            "^op_[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

    private final String operationId;
    private final DeliveryState deliveryState;
    private final RepostErrorCode errorCode;
    private final RepostFailureReason failureReason;
    private final RepostCauseCategory causeCategory;
    private final int attemptCount;
    private final String idempotencyKey;
    private final Integer httpStatus;

    private SendOutcome(
            String operationId,
            DeliveryState deliveryState,
            RepostErrorCode errorCode,
            RepostFailureReason failureReason,
            RepostCauseCategory causeCategory,
            int attemptCount,
            String idempotencyKey,
            Integer httpStatus) {
        if (operationId != null && !OPERATION_ID.matcher(operationId).matches()) {
            throw new IllegalArgumentException("operationId has invalid SDK-local grammar");
        }
        if (deliveryState == null) {
            throw new NullPointerException("deliveryState");
        }
        if (attemptCount < 0 || attemptCount > 10) {
            throw new IllegalArgumentException("attemptCount must be within 0..10");
        }
        if (idempotencyKey != null) {
            SendOptions.validateIdempotencyKey(idempotencyKey);
        }
        if (httpStatus != null && (httpStatus < 200 || httpStatus > 599)) {
            throw new IllegalArgumentException("httpStatus must be within 200..599");
        }
        boolean accepted = deliveryState == DeliveryState.ACCEPTED;
        if (accepted != (errorCode == null)) {
            throw new IllegalArgumentException("accepted state and errorCode are inconsistent");
        }
        if (accepted) {
            PublicFailureSemantics.validateAcceptedOutcome(
                    failureReason, causeCategory, attemptCount, httpStatus);
        } else {
            PublicFailureSemantics.validateFailedOutcome(
                    errorCode,
                    deliveryState,
                    failureReason,
                    causeCategory,
                    attemptCount,
                    httpStatus);
        }
        this.operationId = operationId;
        this.deliveryState = deliveryState;
        this.errorCode = errorCode;
        this.failureReason = failureReason;
        this.causeCategory = causeCategory;
        this.attemptCount = attemptCount;
        this.idempotencyKey = idempotencyKey;
        this.httpStatus = httpStatus;
    }

    static SendOutcome accepted(
            String operationId,
            int attemptCount,
            String idempotencyKey,
            int httpStatus) {
        return new SendOutcome(
                operationId, DeliveryState.ACCEPTED, null, null, null,
                attemptCount, idempotencyKey, httpStatus);
    }

    static SendOutcome failed(
            String operationId,
            DeliveryState deliveryState,
            RepostErrorCode errorCode,
            RepostFailureReason failureReason,
            RepostCauseCategory causeCategory,
            int attemptCount,
            String idempotencyKey,
            Integer httpStatus) {
        if (errorCode == null) {
            throw new NullPointerException("errorCode");
        }
        return new SendOutcome(
                operationId, deliveryState, errorCode, failureReason, causeCategory,
                attemptCount, idempotencyKey, httpStatus);
    }

    /**
     * Returns whether the server accepted the request.
     *
     * @return whether the server accepted the request
     */
    public boolean isAccepted() { return deliveryState == DeliveryState.ACCEPTED; }
    /**
     * Returns operation identifier, or {@code null} before operation start.
     *
     * @return operation identifier, or {@code null} before operation start
     */
    public @Nullable String getOperationId() { return operationId; }
    /**
     * Returns best-known delivery state.
     *
     * @return best-known delivery state
     */
    public DeliveryState getDeliveryState() { return deliveryState; }
    /**
     * Returns error code, or {@code null} for an accepted request.
     *
     * @return error code, or {@code null} for an accepted request
     */
    public @Nullable RepostErrorCode getErrorCode() { return errorCode; }
    /**
     * Returns network failure reason, or {@code null} when not applicable.
     *
     * @return network failure reason, or {@code null} when not applicable
     */
    public @Nullable RepostFailureReason getFailureReason() { return failureReason; }
    /**
     * Returns safe local cause category, or {@code null} when not applicable.
     *
     * @return safe local cause category, or {@code null} when not applicable
     */
    public @Nullable RepostCauseCategory getCauseCategory() { return causeCategory; }
    /**
     * Returns number of started transport attempts.
     *
     * @return number of started transport attempts
     */
    public int getAttemptCount() { return attemptCount; }
    /**
     * Returns reconciliation key, or {@code null} when unavailable.
     *
     * @return reconciliation key, or {@code null} when unavailable
     */
    public @Nullable String getIdempotencyKey() { return idempotencyKey; }
    /**
     * Returns final HTTP status, or {@code null} when none was safely received.
     *
     * @return final HTTP status, or {@code null} when none was safely received
     */
    public @Nullable Integer getHttpStatus() { return httpStatus; }

    @Override
    public String toString() {
        return "SendOutcome[delivery=" + deliveryState + ", code=" + errorCode + "]";
    }
}
