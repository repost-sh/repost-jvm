package sh.repost.client.error;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import sh.repost.client.ConfigurationIssue;
import sh.repost.client.DeliveryState;
import sh.repost.client.RepostCauseCategory;
import sh.repost.client.RepostErrorCode;
import sh.repost.client.RepostErrorDetails;
import sh.repost.client.RepostFailureReason;
import sh.repost.client.ValidationIssue;

/** Base class for credential-free, structured Repost client failures. */
@SuppressWarnings("serial")
public abstract class RepostException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Structured failure metadata retained for the lifetime of this exception. */
    private final RepostErrorDetails details;

    RepostException(RepostErrorDetails details) {
        super(messageFor(Objects.requireNonNull(details, "details").getErrorCode()), null, false, true);
        this.details = details;
    }

    /**
     * Returns the stable error code.
     *
     * @return the stable error code
     */
    public final RepostErrorCode getErrorCode() { return details.getErrorCode(); }
    /**
     * Returns the stable failure reason, or {@code null} when not applicable.
     *
     * @return the stable failure reason, or {@code null} when not applicable
     */
    public final @Nullable RepostFailureReason getFailureReason() { return details.getFailureReason(); }
    /**
     * Returns the low-cardinality cause category, or {@code null} when not applicable.
     *
     * @return the low-cardinality cause category, or {@code null} when not applicable
     */
    public final @Nullable RepostCauseCategory getCauseCategory() { return details.getCauseCategory(); }
    /**
     * Returns the best-known delivery state.
     *
     * @return the best-known delivery state
     */
    public final DeliveryState getDeliveryState() { return details.getDeliveryState(); }
    /**
     * Returns the operation identifier, or {@code null} before an operation starts.
     *
     * @return the operation identifier, or {@code null} before an operation starts
     */
    public final @Nullable String getOperationId() { return details.getOperationId(); }
    /**
     * Returns the reconciliation idempotency key, or {@code null} when unavailable.
     *
     * @return the reconciliation idempotency key, or {@code null} when unavailable
     */
    public final @Nullable String getIdempotencyKey() { return details.getIdempotencyKey(); }
    /**
     * Returns the number of transport attempts that started.
     *
     * @return the number of transport attempts that started
     */
    public final int getAttemptCount() { return details.getAttemptCount(); }
    /**
     * Returns the HTTP status, or {@code null} when none was safely received.
     *
     * @return the HTTP status, or {@code null} when none was safely received
     */
    public final @Nullable Integer getHttpStatus() { return details.getHttpStatus(); }
    /**
     * Returns whether the operation may be retried under the runtime policy.
     *
     * @return whether the operation may be retried under the runtime policy
     */
    public final boolean isRetryable() { return details.isRetryable(); }
    /**
     * Returns compressed response bytes observed before failure.
     *
     * @return compressed response bytes observed before failure
     */
    public final long getCompressedBytes() { return details.getCompressedBytes(); }
    /**
     * Returns decompressed response bytes observed before failure.
     *
     * @return decompressed response bytes observed before failure
     */
    public final long getDecompressedBytes() { return details.getDecompressedBytes(); }
    /**
     * Returns response header fields observed before failure.
     *
     * @return response header fields observed before failure
     */
    public final int getResponseHeaderFields() { return details.getResponseHeaderFields(); }
    /**
     * Returns response header bytes observed before failure.
     *
     * @return response header bytes observed before failure
     */
    public final long getResponseHeaderBytes() { return details.getResponseHeaderBytes(); }
    /**
     * Returns whether bounded diagnostic information was truncated.
     *
     * @return whether bounded diagnostic information was truncated
     */
    public final boolean isTruncated() { return details.isTruncated(); }
    /**
     * Returns the total number of resource-close failures.
     *
     * @return the total number of resource-close failures
     */
    public final int getCloseFailureCount() { return details.getCloseFailureCount(); }
    /**
     * Returns the retained close-failure categories in close order.
     *
     * @return the retained close-failure categories in close order
     */
    public final List<RepostCauseCategory> getCloseFailureCategories() {
        return details.getCloseFailureCategories();
    }
    /**
     * Returns retained validation issues in deterministic order.
     *
     * @return retained validation issues in deterministic order
     */
    public final List<ValidationIssue> getValidationIssues() { return details.getValidationIssues(); }
    /**
     * Returns retained configuration issues in deterministic order.
     *
     * @return retained configuration issues in deterministic order
     */
    public final List<ConfigurationIssue> getConfigurationIssues() {
        return details.getConfigurationIssues();
    }
    /**
     * Returns the total issue count, including issues omitted by bounded retention.
     *
     * @return the total issue count, including issues omitted by bounded retention
     */
    public final int getIssueCount() { return details.getIssueCount(); }
    /**
     * Returns whether the retained issue list is truncated.
     *
     * @return whether the retained issue list is truncated
     */
    public final boolean isIssuesTruncated() { return details.isIssuesTruncated(); }

    @Override
    public final String getMessage() {
        return super.getMessage();
    }

    @Override
    public final String toString() {
        return getClass().getSimpleName() + "[code=" + getErrorCode()
                + ", delivery=" + getDeliveryState() + "]";
    }

    private static String messageFor(RepostErrorCode code) {
        switch (code) {
            case CONFIGURATION: return "Repost client configuration is invalid.";
            case CLOSED: return "The Repost runtime is closed.";
            case VALIDATION: return "The Repost payload is invalid.";
            case SERIALIZATION: return "The Repost payload could not be serialized.";
            case REQUEST_TOO_LARGE: return "The Repost request exceeds the supported size.";
            case DNS: return "The Repost endpoint could not be resolved.";
            case CONNECT: return "The Repost endpoint could not be connected.";
            case PROXY: return "The configured proxy rejected the connection.";
            case TLS: return "The Repost TLS connection failed.";
            case IO: return "The Repost transport failed.";
            case ATTEMPT_TIMEOUT: return "The Repost transport attempt timed out.";
            case OPERATION_DEADLINE: return "The Repost operation deadline elapsed.";
            case CANCELLED: return "The Repost operation was cancelled.";
            case OVERLOADED: return "The Repost runtime is overloaded.";
            case RATE_LIMITED: return "The Repost endpoint rate-limited the request.";
            case HTTP_REJECTED: return "The Repost endpoint rejected the request.";
            case SERVER_FAILURE: return "The Repost endpoint reported a server failure.";
            case RESPONSE_TOO_LARGE: return "The Repost response exceeds the supported size.";
            case RESPONSE_PROTOCOL: return "The Repost response violated the protocol.";
            case DESCRIPTOR_VERSION: return "The generated Repost descriptor version is unsupported.";
            default: throw new AssertionError("unhandled error code");
        }
    }
}
