package sh.repost.client.error;

import sh.repost.client.RepostErrorCode;
import sh.repost.client.RepostErrorDetails;

/** Indicates generated model or raw-JSON validation failure before transport. */
public final class RepostValidationException extends RepostException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception from validated {@link RepostErrorCode#VALIDATION} details.
     * @param details structured validation details
     */
    public RepostValidationException(RepostErrorDetails details) {
        super(requireCode(details));
    }

    private static RepostErrorDetails requireCode(RepostErrorDetails details) {
        if (details == null || details.getErrorCode() != RepostErrorCode.VALIDATION) {
            throw new IllegalArgumentException("details code does not match exception subclass");
        }
        return details;
    }
}
