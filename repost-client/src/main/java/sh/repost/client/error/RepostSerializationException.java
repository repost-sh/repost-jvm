package sh.repost.client.error;

import java.util.EnumSet;
import sh.repost.client.RepostErrorCode;
import sh.repost.client.RepostErrorDetails;

/** Indicates serialization failure or a request exceeding the supported size. */
public final class RepostSerializationException extends RepostException {
    private static final long serialVersionUID = 1L;
    private static final EnumSet<RepostErrorCode> CODES = EnumSet.of(
            RepostErrorCode.SERIALIZATION, RepostErrorCode.REQUEST_TOO_LARGE);

    /**
     * Creates an exception from validated serialization-family details.
     * @param details structured serialization details
     */
    public RepostSerializationException(RepostErrorDetails details) {
        super(requireCode(details));
    }

    private static RepostErrorDetails requireCode(RepostErrorDetails details) {
        if (details == null || !CODES.contains(details.getErrorCode())) {
            throw new IllegalArgumentException("details code does not match exception subclass");
        }
        return details;
    }
}
