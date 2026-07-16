package sh.repost.client;

import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable privileged request passed to a custom transport. */
public final class TransportRequest {
    private final URI uri;
    private final List<TransportHeaderField> headerFields;
    private final byte[] body;
    private final int attemptNumber;
    private final Duration connectTimeout;
    private final Duration attemptTimeout;

    TransportRequest(
            URI uri,
            List<TransportHeaderField> headerFields,
            ByteBuffer body,
            int attemptNumber,
            Duration connectTimeout,
            Duration attemptTimeout) {
        this(uri, headerFields, body, attemptNumber, connectTimeout, attemptTimeout, true);
    }

    private TransportRequest(
            URI uri,
            List<TransportHeaderField> headerFields,
            ByteBuffer body,
            int attemptNumber,
            Duration connectTimeout,
            Duration attemptTimeout,
            boolean copyBody) {
        this.uri = Objects.requireNonNull(uri, "uri");
        this.headerFields = copyFields(headerFields);
        ByteBuffer providedBody = Objects.requireNonNull(body, "body");
        ByteBuffer source = providedBody.asReadOnlyBuffer();
        if (!copyBody && providedBody.hasArray()
                && providedBody.arrayOffset() + providedBody.position() == 0
                && providedBody.remaining() == providedBody.array().length) {
            this.body = providedBody.array();
        } else {
            this.body = new byte[source.remaining()];
            source.get(this.body);
        }
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber must be positive");
        }
        this.attemptNumber = attemptNumber;
        this.connectTimeout = requirePositive(connectTimeout, "connectTimeout");
        this.attemptTimeout = requirePositive(attemptTimeout, "attemptTimeout");
    }

    static TransportRequest reusingRetainedBody(
            URI uri,
            List<TransportHeaderField> headerFields,
            byte[] retainedBody,
            int attemptNumber,
            Duration connectTimeout,
            Duration attemptTimeout) {
        return new TransportRequest(
                uri,
                headerFields,
                ByteBuffer.wrap(Objects.requireNonNull(retainedBody, "retainedBody")),
                attemptNumber,
                connectTimeout,
                attemptTimeout,
                false);
    }

    /**
     * Returns canonical request URI.
     *
     * @return canonical request URI
     */
    public URI getUri() {
        return uri;
    }

    /**
     * Returns immutable header fields in wire order.
     *
     * @return immutable header fields in wire order
     */
    public List<TransportHeaderField> getHeaderFields() {
        return headerFields;
    }

    /**
     * Returns an independent read-only view of the request body.
     *
     * @return an independent read-only view of the request body
     */
    public ByteBuffer getBody() {
        return ByteBuffer.wrap(body).asReadOnlyBuffer();
    }

    /**
     * Returns one-based attempt number.
     *
     * @return one-based attempt number
     */
    public int getAttemptNumber() {
        return attemptNumber;
    }

    /**
     * Returns connection establishment timeout.
     *
     * @return connection establishment timeout
     */
    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    /**
     * Returns whole-attempt timeout.
     *
     * @return whole-attempt timeout
     */
    public Duration getAttemptTimeout() {
        return attemptTimeout;
    }

    private static List<TransportHeaderField> copyFields(List<TransportHeaderField> source) {
        Objects.requireNonNull(source, "headerFields");
        ArrayList<TransportHeaderField> copy = new ArrayList<>();
        for (TransportHeaderField field : source) {
            copy.add(Objects.requireNonNull(field, "headerFields contains null"));
        }
        return Collections.unmodifiableList(copy);
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
