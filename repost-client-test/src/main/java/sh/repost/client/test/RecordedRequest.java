package sh.repost.client.test;

import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import sh.repost.client.TransportHeaderField;
import sh.repost.client.TransportRequest;

/** Immutable credential-free request captured by {@link StubTransport}. */
public final class RecordedRequest {
    private final URI uri;
    private final List<TransportHeaderField> headerFields;
    private final byte[] body;
    private final int attemptNumber;
    private final Duration connectTimeout;
    private final Duration attemptTimeout;

    private RecordedRequest(TransportRequest request) {
        this.uri = request.getUri();
        ArrayList<TransportHeaderField> safeHeaders = new ArrayList<>();
        for (TransportHeaderField field : request.getHeaderFields()) {
            if (!field.getName().equalsIgnoreCase("Authorization")) {
                safeHeaders.add(field);
            }
        }
        this.headerFields = Collections.unmodifiableList(safeHeaders);
        ByteBuffer source = request.getBody();
        this.body = new byte[source.remaining()];
        source.get(this.body);
        this.attemptNumber = request.getAttemptNumber();
        this.connectTimeout = request.getConnectTimeout();
        this.attemptTimeout = request.getAttemptTimeout();
    }

    static RecordedRequest capture(TransportRequest request) {
        return new RecordedRequest(request);
    }

    /**
     * Returns the canonical request URI.
     *
     * @return canonical request URI
     */
    public URI getUri() {
        return uri;
    }

    /**
     * Returns immutable request headers with authorization removed.
     *
     * @return credential-free request headers
     */
    public List<TransportHeaderField> getHeaderFields() {
        return headerFields;
    }

    /**
     * Returns an independent copy of the exact serialized request bytes.
     *
     * @return exact serialized request bytes
     */
    public byte[] getBodyBytes() {
        return Arrays.copyOf(body, body.length);
    }

    /**
     * Returns the one-based transport attempt number.
     *
     * @return one-based attempt number
     */
    public int getAttemptNumber() {
        return attemptNumber;
    }

    /**
     * Returns the connect timeout for this attempt.
     *
     * @return connect timeout
     */
    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    /**
     * Returns the whole-attempt timeout.
     *
     * @return whole-attempt timeout
     */
    public Duration getAttemptTimeout() {
        return attemptTimeout;
    }

    @Override
    public String toString() {
        return "RecordedRequest[attempt=" + attemptNumber + ", bodyBytes=" + body.length + "]";
    }
}
