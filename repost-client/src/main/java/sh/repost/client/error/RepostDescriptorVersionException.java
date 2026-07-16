package sh.repost.client.error;

import sh.repost.client.RepostErrorCode;
import sh.repost.client.RepostErrorDetails;

/** Indicates that generated code uses an unsupported descriptor format version. */
public final class RepostDescriptorVersionException extends RepostException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception from descriptor-version details.
     * @param details structured descriptor-version details
     */
    public RepostDescriptorVersionException(RepostErrorDetails details) {
        super(requireCode(details));
    }

    private static RepostErrorDetails requireCode(RepostErrorDetails details) {
        if (details == null || details.getErrorCode() != RepostErrorCode.DESCRIPTOR_VERSION) {
            throw new IllegalArgumentException("details code does not match exception subclass");
        }
        return details;
    }
}
