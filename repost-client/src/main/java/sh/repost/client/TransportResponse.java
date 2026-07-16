package sh.repost.client;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** An acquired response whose body is owned until {@link #close()}. */
public final class TransportResponse implements AutoCloseable {
    private static final int MAX_HEADER_FIELDS = 100;
    private static final int MAX_HEADER_NAME_BYTES = 256;
    private static final int MAX_HEADER_VALUE_BYTES = 8_192;
    private static final long MAX_HEADER_BYTES = 65_536L;

    enum HeaderFailureKind {
        NONE,
        PROTOCOL,
        LIMIT
    }

    private final int statusCode;
    private final List<TransportHeaderField> headerFields;
    private final InputStream body;
    private final HeaderFailureKind headerFailureKind;
    private final int observedHeaderFields;
    private final long observedHeaderBytes;
    private final AtomicBoolean closed = new AtomicBoolean();

    private TransportResponse(
            int statusCode,
            List<TransportHeaderField> headerFields,
            InputStream body,
            HeaderFailureKind headerFailureKind,
            int observedHeaderFields,
            long observedHeaderBytes) {
        this.statusCode = statusCode;
        this.headerFields = headerFields;
        this.body = body;
        this.headerFailureKind = headerFailureKind;
        this.observedHeaderFields = observedHeaderFields;
        this.observedHeaderBytes = observedHeaderBytes;
    }

    /**
     * Acquires an owned transport response.
     *
     * @param statusCode final HTTP status from 200 through 599
     * @param headerFields raw final response headers
     * @param body owned response body, closed on factory failure or {@link #close()}
     * @return acquired response
     */
    public static TransportResponse of(
            int statusCode,
            List<TransportHeaderField> headerFields,
            InputStream body) {
        Objects.requireNonNull(body, "body");
        try {
            if (statusCode < 200 || statusCode > 599) {
                throw new IllegalArgumentException("statusCode must be between 200 and 599");
            }
            Objects.requireNonNull(headerFields, "headerFields");
            return copyHeaders(statusCode, headerFields, body);
        } catch (Throwable throwable) {
            closeAfterFactoryFailure(body, throwable);
            throw throwable;
        }
    }

    /**
     * Returns final HTTP status.
     *
     * @return final HTTP status
     */
    public int getStatusCode() {
        return statusCode;
    }

    /**
     * Returns immutable final response header fields.
     *
     * @return immutable final response header fields
     */
    public List<TransportHeaderField> getHeaderFields() {
        return headerFields;
    }

    /**
     * Returns owned response body stream.
     *
     * @return owned response body stream
     */
    public InputStream getBody() {
        return body;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            body.close();
        } catch (IOException ignored) {
            // Core accounts response-close failure without exposing a raw cause.
        }
    }

    boolean hasHeaderFailure() {
        return headerFailureKind != HeaderFailureKind.NONE;
    }

    HeaderFailureKind getHeaderFailureKind() {
        return headerFailureKind;
    }

    int getObservedHeaderFields() {
        return observedHeaderFields;
    }

    long getObservedHeaderBytes() {
        return observedHeaderBytes;
    }

    long getObservedCompressedBytes() {
        return body instanceof BodyDiagnostics
                ? ((BodyDiagnostics) body).compressedBytes()
                : 0L;
    }

    long getObservedDecompressedBytes() {
        return body instanceof BodyDiagnostics
                ? ((BodyDiagnostics) body).decompressedBytes()
                : 0L;
    }

    boolean isBodyDiagnosticTruncated() {
        return body instanceof BodyDiagnostics
                && ((BodyDiagnostics) body).truncated();
    }

    private static TransportResponse copyHeaders(
            int statusCode,
            List<TransportHeaderField> source,
            InputStream body) {
        ArrayList<TransportHeaderField> copy = new ArrayList<>(MAX_HEADER_FIELDS);
        Iterator<TransportHeaderField> iterator = source.iterator();
        int fieldCount = 0;
        long logicalBytes = 0L;
        while (iterator.hasNext()) {
            TransportHeaderField field = Objects.requireNonNull(
                    iterator.next(), "headerFields contains null");
            fieldCount++;
            if (fieldCount > MAX_HEADER_FIELDS) {
                return invalid(
                        statusCode, copy, body, HeaderFailureKind.LIMIT, fieldCount, logicalBytes);
            }

            String name = field.getName();
            String value = field.getValue();
            HeaderMeasurement nameMeasurement = measureName(name);
            if (nameMeasurement.failureKind != HeaderFailureKind.NONE) {
                long observed = addLogical(logicalBytes, nameMeasurement.bytes, 2L);
                return invalid(
                        statusCode, copy, body, nameMeasurement.failureKind, fieldCount, observed);
            }
            HeaderMeasurement valueMeasurement = measureValue(value);
            long fieldBytes = addLogical(nameMeasurement.bytes, 2L, valueMeasurement.bytes, 2L);
            logicalBytes = saturatingAdd(logicalBytes, fieldBytes);
            if (valueMeasurement.failureKind != HeaderFailureKind.NONE) {
                return invalid(
                        statusCode, copy, body, valueMeasurement.failureKind, fieldCount, logicalBytes);
            }
            if (logicalBytes > MAX_HEADER_BYTES) {
                return invalid(
                        statusCode, copy, body, HeaderFailureKind.LIMIT, fieldCount, logicalBytes);
            }
            copy.add(field);
        }
        return new TransportResponse(
                statusCode,
                Collections.unmodifiableList(copy),
                body,
                HeaderFailureKind.NONE,
                fieldCount,
                logicalBytes);
    }

    private static TransportResponse invalid(
            int statusCode,
            List<TransportHeaderField> validPrefix,
            InputStream body,
            HeaderFailureKind kind,
            int fields,
            long bytes) {
        return new TransportResponse(
                statusCode,
                Collections.unmodifiableList(new ArrayList<>(validPrefix)),
                body,
                kind,
                fields,
                bytes);
    }

    private static HeaderMeasurement measureName(String value) {
        int length = value.length();
        if (length == 0) {
            return new HeaderMeasurement(0L, HeaderFailureKind.PROTOCOL);
        }
        for (int index = 0; index < length; index++) {
            char character = value.charAt(index);
            if (character > 0x7f || !isTokenCharacter(character)) {
                return new HeaderMeasurement(index + 1L, HeaderFailureKind.PROTOCOL);
            }
            if (index + 1 > MAX_HEADER_NAME_BYTES) {
                return new HeaderMeasurement(index + 1L, HeaderFailureKind.LIMIT);
            }
        }
        return new HeaderMeasurement(length, HeaderFailureKind.NONE);
    }

    private static HeaderMeasurement measureValue(String value) {
        long bytes = 0L;
        for (int index = 0; index < value.length();) {
            char first = value.charAt(index);
            int codePoint;
            if (Character.isHighSurrogate(first)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    return new HeaderMeasurement(bytes, HeaderFailureKind.PROTOCOL);
                }
                codePoint = Character.toCodePoint(first, value.charAt(index + 1));
                index += 2;
            } else if (Character.isLowSurrogate(first)) {
                return new HeaderMeasurement(bytes, HeaderFailureKind.PROTOCOL);
            } else {
                codePoint = first;
                index++;
            }
            if ((codePoint < 0x20 && codePoint != '\t') || codePoint == 0x7f) {
                return new HeaderMeasurement(bytes, HeaderFailureKind.PROTOCOL);
            }
            bytes = saturatingAdd(bytes, utf8Length(codePoint));
            if (bytes > MAX_HEADER_VALUE_BYTES) {
                return new HeaderMeasurement(bytes, HeaderFailureKind.LIMIT);
            }
        }
        return new HeaderMeasurement(bytes, HeaderFailureKind.NONE);
    }

    private static boolean isTokenCharacter(char value) {
        if ((value >= 'a' && value <= 'z')
                || (value >= 'A' && value <= 'Z')
                || (value >= '0' && value <= '9')) {
            return true;
        }
        return value == '!' || value == '#' || value == '$' || value == '%'
                || value == '&' || value == '\'' || value == '*' || value == '+'
                || value == '-' || value == '.' || value == '^' || value == '_'
                || value == '`' || value == '|' || value == '~';
    }

    private static int utf8Length(int codePoint) {
        if (codePoint <= 0x7f) {
            return 1;
        }
        if (codePoint <= 0x7ff) {
            return 2;
        }
        if (codePoint <= 0xffff) {
            return 3;
        }
        return 4;
    }

    private static long addLogical(long... values) {
        long result = 0L;
        for (long value : values) {
            result = saturatingAdd(result, value);
        }
        return result;
    }

    private static long saturatingAdd(long left, long right) {
        if (Long.MAX_VALUE - left < right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static void closeAfterFactoryFailure(InputStream body, Throwable primary) {
        try {
            body.close();
        } catch (Throwable closeFailure) {
            if (!(primary instanceof Error) && closeFailure instanceof Error) {
                throw (Error) closeFailure;
            }
            // Never chain or suppress transport-controlled close failures.
        }
    }

    private static final class HeaderMeasurement {
        private final long bytes;
        private final HeaderFailureKind failureKind;

        private HeaderMeasurement(long bytes, HeaderFailureKind failureKind) {
            this.bytes = bytes;
            this.failureKind = failureKind;
        }
    }

    interface BodyDiagnostics {
        long compressedBytes();

        long decompressedBytes();

        boolean truncated();
    }
}
