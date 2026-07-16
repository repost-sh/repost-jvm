package sh.repost.client.error;

import java.util.EnumSet;
import sh.repost.client.RepostErrorCode;
import sh.repost.client.RepostErrorDetails;

/** Indicates a server rejection or response processing failure after publication. */
public final class RepostPublishException extends RepostException {
    private static final long serialVersionUID = 1L;
    private static final EnumSet<RepostErrorCode> CODES = EnumSet.of(
            RepostErrorCode.RATE_LIMITED,
            RepostErrorCode.HTTP_REJECTED,
            RepostErrorCode.SERVER_FAILURE,
            RepostErrorCode.RESPONSE_TOO_LARGE,
            RepostErrorCode.RESPONSE_PROTOCOL);

    /**
     * Creates an exception from validated publish-family details.
     * @param details structured publish details
     */
    public RepostPublishException(RepostErrorDetails details) {
        super(requireCode(details));
    }

    private static RepostErrorDetails requireCode(RepostErrorDetails details) {
        if (details == null || !CODES.contains(details.getErrorCode())) {
            throw new IllegalArgumentException("details code does not match exception subclass");
        }
        return details;
    }
}
