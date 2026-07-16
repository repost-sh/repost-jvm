package sh.repost.client;

/** Central invariant matrix for public failure values. */
final class PublicFailureSemantics {
    private PublicFailureSemantics() { }

    static void validateDetails(
            RepostErrorCode code,
            DeliveryState delivery,
            RepostFailureReason reason,
            RepostCauseCategory cause,
            int attempts,
            Integer status,
            boolean retryable,
            int closeFailureCount) {
        if (closeFailureCount > 0) {
            require(code == RepostErrorCode.IO
                    && delivery == DeliveryState.NOT_SENT
                    && reason == null
                    && cause == null
                    && attempts == 0
                    && status == null
                    && !retryable,
                    "close aggregate has inconsistent failure fields");
            return;
        }
        validateFailedOutcome(code, delivery, reason, cause, attempts, status);
        switch (code) {
            case DNS:
            case CONNECT:
            case ATTEMPT_TIMEOUT:
            case RATE_LIMITED:
            case SERVER_FAILURE:
            case RESPONSE_PROTOCOL:
                require(retryable, "error code must be retryable");
                break;
            case IO:
                require(retryable || (cause == RepostCauseCategory.CUSTOM_TRANSPORT
                                && reason == RepostFailureReason.UNKNOWN),
                        "IO retryability is inconsistent");
                break;
            case PROXY:
                require(retryable == (status == null
                        && reason != RepostFailureReason.PROXY_AUTH_REQUIRED),
                        "proxy retryability is inconsistent");
                break;
            case HTTP_REJECTED:
                require(retryable == Integer.valueOf(409).equals(status),
                        "HTTP rejection retryability is inconsistent");
                break;
            case CONFIGURATION:
            case CLOSED:
            case VALIDATION:
            case SERIALIZATION:
            case REQUEST_TOO_LARGE:
            case TLS:
            case OPERATION_DEADLINE:
            case CANCELLED:
            case OVERLOADED:
            case RESPONSE_TOO_LARGE:
            case DESCRIPTOR_VERSION:
                require(!retryable, "error code must not be retryable");
                break;
            default:
                throw new AssertionError("unhandled error code");
        }
    }

    static void validateAcceptedOutcome(
            RepostFailureReason reason,
            RepostCauseCategory cause,
            int attempts,
            Integer status) {
        require(reason == null && cause == null && attempts >= 1
                        && status != null && status >= 200 && status <= 299,
                "accepted outcome has inconsistent fields");
    }

    static void validateFailedOutcome(
            RepostErrorCode code,
            DeliveryState delivery,
            RepostFailureReason reason,
            RepostCauseCategory cause,
            int attempts,
            Integer status) {
        require(code != null, "errorCode");
        require(delivery != DeliveryState.ACCEPTED, "failed outcome cannot be accepted");
        switch (code) {
            case CONFIGURATION:
                require(cause == null || cause == RepostCauseCategory.API_KEY_PROVIDER
                                || cause == RepostCauseCategory.DEFAULT_GENERATOR
                                || cause == RepostCauseCategory.IDEMPOTENCY_GENERATOR
                                || cause == RepostCauseCategory.RETRY_ENTROPY
                                || cause == RepostCauseCategory.PROXY_CREDENTIAL_PROVIDER
                                || cause == RepostCauseCategory.UNKNOWN,
                        "configuration cause is inconsistent");
                if (attempts >= 1) {
                    require((delivery == DeliveryState.NOT_SENT
                                    || delivery == DeliveryState.POSSIBLY_SENT)
                                    && reason == null,
                            "retry entropy failure evidence is inconsistent");
                } else {
                    requireLocal(delivery, reason, attempts, status);
                }
                break;
            case CLOSED:
                require(delivery == DeliveryState.NOT_SENT
                                || delivery == DeliveryState.CANCELLED_UNKNOWN,
                        "closed delivery is inconsistent");
                require(reason == null && attempts >= 0
                                && (status == null
                                        || (attempts >= 1 && status >= 200 && status <= 599)),
                        "closed fields are inconsistent");
                if (attempts == 0) {
                    require(delivery == DeliveryState.NOT_SENT && status == null,
                            "pre-attempt close evidence is inconsistent");
                }
                require(cause == null, "closed failure cannot have a cause category");
                break;
            case VALIDATION:
            case SERIALIZATION:
            case REQUEST_TOO_LARGE:
            case DESCRIPTOR_VERSION:
                requireLocal(delivery, reason, attempts, status);
                require(cause == null, "local failure cannot have a cause category");
                break;
            case DNS:
                requireNetwork(delivery, attempts, status);
                require(reason == null || reason == RepostFailureReason.DNS_NOT_FOUND
                                || reason == RepostFailureReason.DNS_TIMEOUT
                                || reason == RepostFailureReason.UNKNOWN,
                        "DNS reason is inconsistent");
                require(cause == null || cause == RepostCauseCategory.DNS_RESOLVER
                                || isTransportCause(cause),
                        "DNS cause is inconsistent");
                break;
            case CONNECT:
                requireNetwork(delivery, attempts, status);
                require(reason == null || reason == RepostFailureReason.CONNECT_REFUSED
                                || reason == RepostFailureReason.CONNECT_TIMEOUT
                                || reason == RepostFailureReason.CONNECTION_RESET
                                || reason == RepostFailureReason.CONNECTION_CLOSED
                                || reason == RepostFailureReason.UNKNOWN,
                        "connect reason is inconsistent");
                require(cause == null || isTransportCause(cause),
                        "connect cause is inconsistent");
                break;
            case PROXY:
                require(delivery == DeliveryState.NOT_SENT
                                || delivery == DeliveryState.POSSIBLY_SENT,
                        "proxy delivery is inconsistent");
                require(attempts >= 1 && (status == null || status == 407),
                        "proxy attempt/status is inconsistent");
                require(reason == null || reason == RepostFailureReason.PROXY_AUTH_REQUIRED
                                || reason == RepostFailureReason.PROXY_CONNECT_FAILED
                                || reason == RepostFailureReason.UNKNOWN,
                        "proxy reason is inconsistent");
                require(cause == null || cause == RepostCauseCategory.PROXY_CREDENTIAL_PROVIDER
                                || isTransportCause(cause),
                        "proxy cause is inconsistent");
                break;
            case TLS:
                requireNetwork(delivery, attempts, status);
                require(reason == null || reason == RepostFailureReason.TLS_UNTRUSTED
                                || reason == RepostFailureReason.TLS_CERTIFICATE_EXPIRED
                                || reason == RepostFailureReason.TLS_CERTIFICATE_NOT_YET_VALID
                                || reason == RepostFailureReason.TLS_HOSTNAME_MISMATCH
                                || reason == RepostFailureReason.TLS_NEGOTIATION
                                || reason == RepostFailureReason.UNKNOWN,
                        "TLS reason is inconsistent");
                require(cause == null || cause == RepostCauseCategory.TLS_PROVIDER
                                || isTransportCause(cause),
                        "TLS cause is inconsistent");
                break;
            case IO:
                requireNetwork(delivery, attempts, status);
                require(reason == null || reason == RepostFailureReason.CONNECTION_RESET
                                || reason == RepostFailureReason.CONNECTION_CLOSED
                                || reason == RepostFailureReason.UNKNOWN,
                        "IO reason is inconsistent");
                require(cause == null || cause == RepostCauseCategory.RESPONSE_BODY
                                || isTransportCause(cause),
                        "IO cause is inconsistent");
                break;
            case ATTEMPT_TIMEOUT:
                requireNetwork(delivery, attempts, status);
                require(reason == null || reason == RepostFailureReason.DNS_TIMEOUT
                                || reason == RepostFailureReason.CONNECT_TIMEOUT
                                || reason == RepostFailureReason.UNKNOWN,
                        "attempt timeout reason is inconsistent");
                require(cause == null || cause == RepostCauseCategory.DNS_RESOLVER
                                || cause == RepostCauseCategory.TLS_PROVIDER
                                || isTransportCause(cause),
                        "attempt timeout cause is inconsistent");
                break;
            case OPERATION_DEADLINE:
                require(delivery == DeliveryState.NOT_SENT
                                || delivery == DeliveryState.POSSIBLY_SENT,
                        "deadline delivery is inconsistent");
                require(reason == null && status == null,
                        "deadline fields are inconsistent");
                require(cause == null || cause == RepostCauseCategory.RESPONSE_BODY
                                || cause == RepostCauseCategory.HTTP_RUNTIME
                                || cause == RepostCauseCategory.UNKNOWN,
                        "deadline cause is inconsistent");
                break;
            case CANCELLED:
                require(delivery == DeliveryState.NOT_SENT
                                || delivery == DeliveryState.CANCELLED_UNKNOWN,
                        "cancelled delivery is inconsistent");
                require(reason == null && attempts >= 0
                                && (status == null
                                        || (attempts >= 1 && status >= 200 && status <= 599)),
                        "cancelled fields are inconsistent");
                if (attempts == 0) {
                    require(delivery == DeliveryState.NOT_SENT && status == null,
                            "pre-attempt cancellation evidence is inconsistent");
                }
                require(cause == null || cause == RepostCauseCategory.RESPONSE_BODY
                                || cause == RepostCauseCategory.HTTP_RUNTIME
                                || cause == RepostCauseCategory.UNKNOWN,
                        "cancelled cause is inconsistent");
                break;
            case OVERLOADED:
                requireLocal(delivery, reason, attempts, status);
                require(cause == null || cause == RepostCauseCategory.HTTP_RUNTIME
                                || cause == RepostCauseCategory.UNKNOWN,
                        "overloaded cause is inconsistent");
                break;
            case RATE_LIMITED:
                require(delivery == DeliveryState.REJECTED
                                || delivery == DeliveryState.POSSIBLY_SENT,
                        "rate-limit delivery is inconsistent");
                require(reason == null && cause == null && attempts >= 1
                                && Integer.valueOf(429).equals(status),
                        "rate-limit fields are inconsistent");
                break;
            case HTTP_REJECTED:
                require(delivery == DeliveryState.REJECTED
                                || delivery == DeliveryState.POSSIBLY_SENT,
                        "HTTP rejection delivery is inconsistent");
                require(reason == null && cause == null && attempts >= 1
                                && status != null && status >= 300 && status <= 499
                                && status != 407 && status != 429,
                        "HTTP rejection fields are inconsistent");
                if (status == 409) {
                    require(delivery == DeliveryState.POSSIBLY_SENT,
                            "HTTP 409 must use POSSIBLY_SENT");
                }
                break;
            case SERVER_FAILURE:
                require(delivery == DeliveryState.POSSIBLY_SENT
                                && reason == null && cause == null && attempts >= 1
                                && status != null && status >= 500 && status <= 599,
                        "server failure fields are inconsistent");
                break;
            case RESPONSE_TOO_LARGE:
            case RESPONSE_PROTOCOL:
                require(delivery == DeliveryState.POSSIBLY_SENT
                                && reason == null && attempts >= 1
                                && status != null && status >= 200 && status <= 299,
                        "response failure fields are inconsistent");
                require(cause == null || cause == RepostCauseCategory.RESPONSE_BODY
                                || cause == RepostCauseCategory.HTTP_RUNTIME
                                || cause == RepostCauseCategory.UNKNOWN,
                        "response failure cause is inconsistent");
                break;
            default:
                throw new AssertionError("unhandled error code");
        }
    }

    static void validateTransportFailure(
            RepostErrorCode code,
            RequestCommitState commitState,
            RepostFailureReason reason) {
        require(commitState != null, "commitState");
        DeliveryState delivery = commitState == RequestCommitState.NOT_COMMITTED
                ? DeliveryState.NOT_SENT : DeliveryState.POSSIBLY_SENT;
        validateFailedOutcome(
                code,
                delivery,
                reason,
                RepostCauseCategory.CUSTOM_TRANSPORT,
                1,
                null);
    }

    private static void requireLocal(
            DeliveryState delivery,
            RepostFailureReason reason,
            int attempts,
            Integer status) {
        require(delivery == DeliveryState.NOT_SENT && reason == null
                        && attempts == 0 && status == null,
                "local failure fields are inconsistent");
    }

    private static void requireNetwork(
            DeliveryState delivery,
            int attempts,
            Integer status) {
        require((delivery == DeliveryState.NOT_SENT || delivery == DeliveryState.POSSIBLY_SENT)
                        && attempts >= 1 && status == null,
                "network failure fields are inconsistent");
    }

    private static boolean isTransportCause(RepostCauseCategory cause) {
        return cause == RepostCauseCategory.CUSTOM_TRANSPORT
                || cause == RepostCauseCategory.HTTP_RUNTIME
                || cause == RepostCauseCategory.UNKNOWN;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
