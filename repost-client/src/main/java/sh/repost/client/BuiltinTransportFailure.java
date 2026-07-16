package sh.repost.client;

/** Structured failure emitted only by the SDK-owned production transport. */
final class BuiltinTransportFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final RepostErrorCode errorCode;
    private final RequestCommitState commitState;
    private final RepostFailureReason failureReason;
    private final RepostCauseCategory causeCategory;
    private final boolean retryable;
    private final boolean attemptEvidence;

    private BuiltinTransportFailure(
            RepostErrorCode errorCode,
            RequestCommitState commitState,
            RepostFailureReason failureReason,
            RepostCauseCategory causeCategory,
            boolean retryable,
            boolean attemptEvidence) {
        super("Built-in transport failed.", null, false, false);
        this.errorCode = errorCode;
        this.commitState = commitState;
        this.failureReason = failureReason;
        this.causeCategory = causeCategory;
        this.retryable = retryable;
        this.attemptEvidence = attemptEvidence;
    }

    static BuiltinTransportFailure dnsNotFound() {
        return new BuiltinTransportFailure(
                RepostErrorCode.DNS,
                RequestCommitState.NOT_COMMITTED,
                RepostFailureReason.DNS_NOT_FOUND,
                RepostCauseCategory.DNS_RESOLVER,
                true,
                true);
    }

    static BuiltinTransportFailure dnsTimeout() {
        return new BuiltinTransportFailure(
                RepostErrorCode.ATTEMPT_TIMEOUT,
                RequestCommitState.NOT_COMMITTED,
                RepostFailureReason.DNS_TIMEOUT,
                RepostCauseCategory.DNS_RESOLVER,
                true,
                true);
    }

    static BuiltinTransportFailure dnsProvider() {
        return new BuiltinTransportFailure(
                RepostErrorCode.DNS,
                RequestCommitState.NOT_COMMITTED,
                RepostFailureReason.UNKNOWN,
                RepostCauseCategory.DNS_RESOLVER,
                true,
                true);
    }

    static BuiltinTransportFailure overloaded() {
        return new BuiltinTransportFailure(
                RepostErrorCode.OVERLOADED,
                RequestCommitState.NOT_COMMITTED,
                null,
                RepostCauseCategory.HTTP_RUNTIME,
                false,
                false);
    }

    static BuiltinTransportFailure closed() {
        return new BuiltinTransportFailure(
                RepostErrorCode.CLOSED,
                RequestCommitState.NOT_COMMITTED,
                null,
                null,
                false,
                true);
    }

    static BuiltinTransportFailure monotonicClock() {
        return new BuiltinTransportFailure(
                RepostErrorCode.CONFIGURATION,
                RequestCommitState.NOT_COMMITTED,
                null,
                null,
                false,
                true);
    }

    RepostErrorCode errorCode() {
        return errorCode;
    }

    RequestCommitState commitState() {
        return commitState;
    }

    RepostFailureReason failureReason() {
        return failureReason;
    }

    RepostCauseCategory causeCategory() {
        return causeCategory;
    }

    boolean retryable() {
        return retryable;
    }

    boolean hasAttemptEvidence() {
        return attemptEvidence;
    }

    @Override
    public String toString() {
        return "BuiltinTransportFailure[REDACTED]";
    }
}
