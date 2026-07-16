package sh.repost.client;

import java.util.EnumSet;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Explicit closed retry signal available to a custom transport. */
public final class TransportFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private static final EnumSet<RepostErrorCode> ALLOWED_CODES = EnumSet.of(
            RepostErrorCode.IO,
            RepostErrorCode.DNS,
            RepostErrorCode.CONNECT,
            RepostErrorCode.TLS,
            RepostErrorCode.PROXY,
            RepostErrorCode.ATTEMPT_TIMEOUT);

    /** Stable transport failure code. */
    private final RepostErrorCode errorCode;
    /** Best-known publication state. */
    private final RequestCommitState commitState;
    /** Optional safe network failure reason. */
    private final RepostFailureReason failureReason;

    private TransportFailure(
            RepostErrorCode errorCode,
            RequestCommitState commitState,
            RepostFailureReason failureReason) {
        super("Custom transport failed.", null, false, false);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
        if (!ALLOWED_CODES.contains(errorCode)) {
            throw new IllegalArgumentException("errorCode is not available to a custom transport");
        }
        this.commitState = Objects.requireNonNull(commitState, "commitState");
        this.failureReason = failureReason;
        PublicFailureSemantics.validateTransportFailure(errorCode, commitState, failureReason);
    }

    /**
     * Creates a transport failure without a more specific network reason.
     *
     * @param code allowed transport error code
     * @param state best-known publication state
     * @return structured transport failure
     */
    public static TransportFailure of(RepostErrorCode code, RequestCommitState state) {
        return new TransportFailure(code, state, null);
    }

    /**
     * Creates a transport failure with a stable network reason.
     *
     * @param code allowed transport error code
     * @param state best-known publication state
     * @param reason stable network reason
     * @return structured transport failure
     */
    public static TransportFailure of(
            RepostErrorCode code,
            RequestCommitState state,
            RepostFailureReason reason) {
        return new TransportFailure(code, state, Objects.requireNonNull(reason, "reason"));
    }

    /**
     * Returns transport error code.
     *
     * @return transport error code
     */
    public RepostErrorCode getErrorCode() { return errorCode; }
    /**
     * Returns best-known publication state.
     *
     * @return best-known publication state
     */
    public RequestCommitState getCommitState() { return commitState; }
    /**
     * Returns network reason, or {@code null} when unspecified.
     *
     * @return network reason, or {@code null} when unspecified
     */
    public @Nullable RepostFailureReason getFailureReason() { return failureReason; }
    /**
     * Returns {@link RepostCauseCategory#CUSTOM_TRANSPORT}.
     *
     * @return {@link RepostCauseCategory#CUSTOM_TRANSPORT}
     */
    public RepostCauseCategory getCauseCategory() { return RepostCauseCategory.CUSTOM_TRANSPORT; }

    @Override
    public String toString() {
        return "TransportFailure[REDACTED]";
    }
}
