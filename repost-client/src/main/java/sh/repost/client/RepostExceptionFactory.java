package sh.repost.client;

import java.util.Objects;
import sh.repost.client.error.RepostConfigurationException;
import sh.repost.client.error.RepostDescriptorVersionException;
import sh.repost.client.error.RepostException;
import sh.repost.client.error.RepostPublishException;
import sh.repost.client.error.RepostSerializationException;
import sh.repost.client.error.RepostTransportException;
import sh.repost.client.error.RepostValidationException;

/** Exhaustive owner of public error-code to exception-subclass mapping. */
final class RepostExceptionFactory {
    private RepostExceptionFactory() { }

    static RepostException create(RepostErrorDetails details) {
        Objects.requireNonNull(details, "details");
        switch (details.getErrorCode()) {
            case CONFIGURATION:
                return new RepostConfigurationException(details);
            case VALIDATION:
                return new RepostValidationException(details);
            case SERIALIZATION:
            case REQUEST_TOO_LARGE:
                return new RepostSerializationException(details);
            case RATE_LIMITED:
            case HTTP_REJECTED:
            case SERVER_FAILURE:
            case RESPONSE_TOO_LARGE:
            case RESPONSE_PROTOCOL:
                return new RepostPublishException(details);
            case DESCRIPTOR_VERSION:
                return new RepostDescriptorVersionException(details);
            case CLOSED:
            case DNS:
            case CONNECT:
            case PROXY:
            case TLS:
            case IO:
            case ATTEMPT_TIMEOUT:
            case OPERATION_DEADLINE:
            case CANCELLED:
            case OVERLOADED:
                return new RepostTransportException(details);
            default:
                throw new AssertionError("unhandled error code");
        }
    }
}
