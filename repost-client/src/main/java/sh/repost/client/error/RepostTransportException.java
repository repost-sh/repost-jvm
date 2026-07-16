package sh.repost.client.error;

import java.util.EnumSet;
import sh.repost.client.RepostErrorCode;
import sh.repost.client.RepostErrorDetails;

/** Indicates runtime lifecycle, admission, network, timeout, or cancellation failure. */
public final class RepostTransportException extends RepostException {
    private static final long serialVersionUID = 1L;
    private static final EnumSet<RepostErrorCode> CODES = EnumSet.of(
            RepostErrorCode.CLOSED,
            RepostErrorCode.DNS,
            RepostErrorCode.CONNECT,
            RepostErrorCode.PROXY,
            RepostErrorCode.TLS,
            RepostErrorCode.IO,
            RepostErrorCode.ATTEMPT_TIMEOUT,
            RepostErrorCode.OPERATION_DEADLINE,
            RepostErrorCode.CANCELLED,
            RepostErrorCode.OVERLOADED);

    /**
     * Creates an exception from validated transport-family details.
     * @param details structured transport details
     */
    public RepostTransportException(RepostErrorDetails details) {
        super(requireCode(details));
    }

    private static RepostErrorDetails requireCode(RepostErrorDetails details) {
        if (details == null || !CODES.contains(details.getErrorCode())) {
            throw new IllegalArgumentException("details code does not match exception subclass");
        }
        return details;
    }
}
