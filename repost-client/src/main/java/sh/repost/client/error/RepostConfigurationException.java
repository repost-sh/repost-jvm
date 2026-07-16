package sh.repost.client.error;

import sh.repost.client.RepostErrorCode;
import sh.repost.client.RepostErrorDetails;

/** Indicates invalid runtime or operation configuration before transport. */
public final class RepostConfigurationException extends RepostException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception from configuration details.
     * @param details structured configuration details
     */
    public RepostConfigurationException(RepostErrorDetails details) {
        super(requireCode(details, RepostErrorCode.CONFIGURATION));
    }

    private static RepostErrorDetails requireCode(RepostErrorDetails details, RepostErrorCode code) {
        if (details == null || details.getErrorCode() != code) {
            throw new IllegalArgumentException("details code does not match exception subclass");
        }
        return details;
    }
}
