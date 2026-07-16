package sh.repost.client.internal;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonFactoryBuilder;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.core.util.JsonRecyclerPools;
import java.io.IOException;
import java.io.InputStream;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import sh.repost.client.SendResult;
import sh.repost.client.TransportHeaderField;
import sh.repost.client.TransportResponse;

/** Bounded parser for accepted publish responses. */
public final class ResponseParser {
    private static final int MAX_DOCUMENT_BYTES = 1_048_576;
    private static final int MAX_STRING_UTF8_BYTES = 8_192;
    private static final int MAX_FIELD_NAME_UTF8_BYTES = 64;
    private static final int MAX_MEMBERS_PER_OBJECT = 16;
    private static final int MAX_NESTING_DEPTH = 32;
    private static final int MAX_EXPANSION_RATIO = 100;
    private static final JsonFactory JSON_FACTORY = new JsonFactoryBuilder()
            .recyclerPool(JsonRecyclerPools.nonRecyclingPool())
            .disable(JsonFactory.Feature.CANONICALIZE_FIELD_NAMES)
            .disable(JsonFactory.Feature.INTERN_FIELD_NAMES)
            .disable(JsonFactory.Feature.USE_THREAD_LOCAL_FOR_BUFFER_RECYCLING)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(StreamReadFeature.AUTO_CLOSE_SOURCE)
            .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(MAX_NESTING_DEPTH)
                    .maxDocumentLength(MAX_DOCUMENT_BYTES)
                    .maxTokenCount(10_000L)
                    .maxNumberLength(128)
                    .maxStringLength(8_192)
                    .maxNameLength(64)
                    .build())
            .build();

    private ResponseParser() { }

    /** Closed parser result that never retains a response body or parser exception. */
    public static final class Result {
        /** Stable failure kind for an unsuccessful parse. */
        public enum Failure {
            /** Malformed headers, encoding, JSON, identity, or result fields. */
            PROTOCOL,
            /** A fixed response amplification limit was exceeded. */
            LIMIT
        }

        private final SendResult sendResult;
        private final Failure failure;

        private Result(SendResult sendResult, Failure failure) {
            this.sendResult = sendResult;
            this.failure = failure;
        }

        /**
         * Returns the parsed accepted result.
         *
         * @return parsed result, or {@code null} when parsing failed
         */
        public SendResult getSendResult() { return sendResult; }

        /**
         * Returns the stable parser failure kind.
         *
         * @return stable failure kind, or {@code null} when parsing succeeded
         */
        public Failure getFailure() { return failure; }
    }

    /**
     * Parses one successful response and verifies its request identity echo.
     *
     * @param response acquired successful response
     * @param expectedType serialized request event type
     * @param expectedCustomerId serialized request customer identifier
     * @param expectedTimestamp serialized request timestamp
     * @return closed parse result
     */
    public static Result parse(
            TransportResponse response,
            String expectedType,
            String expectedCustomerId,
            String expectedTimestamp) {
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(expectedType, "expectedType");
        Objects.requireNonNull(expectedCustomerId, "expectedCustomerId");
        Objects.requireNonNull(expectedTimestamp, "expectedTimestamp");

        String contentEncoding = contentEncoding(response.getHeaderFields());
        if (!hasJsonContentType(response.getHeaderFields()) || contentEncoding == null) {
            return failed(Result.Failure.PROTOCOL);
        }

        CountingInputStream raw = new CountingInputStream(
                response.getBody(), MAX_DOCUMENT_BYTES, null);
        try {
            InputStream decoded = raw;
            if ("gzip".equals(contentEncoding)) {
                decoded = new GZIPInputStream(raw, 16_384);
            }
            CountingInputStream document = new CountingInputStream(
                    decoded,
                    MAX_DOCUMENT_BYTES,
                    "gzip".equals(contentEncoding) ? raw : null);
            return parseJson(document, expectedType, expectedCustomerId, expectedTimestamp);
        } catch (LimitExceededException | StreamConstraintsException exception) {
            return failed(Result.Failure.LIMIT);
        } catch (IOException | RuntimeException exception) {
            return failed(Result.Failure.PROTOCOL);
        }
    }

    private static Result parseJson(
            InputStream input,
            String expectedType,
            String expectedCustomerId,
            String expectedTimestamp) throws IOException {
        try (JsonParser parser = JSON_FACTORY.createParser(input)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return failed(Result.Failure.PROTOCOL);
            }
            int depth = 1;
            boolean[] objectAtDepth = new boolean[MAX_NESTING_DEPTH + 1];
            int[] membersAtDepth = new int[MAX_NESTING_DEPTH + 1];
            objectAtDepth[depth] = true;
            String topLevelField = null;
            String id = null;
            String type = null;
            String customerId = null;
            String timestamp = null;

            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if (token == JsonToken.FIELD_NAME) {
                    if (!objectAtDepth[depth]
                            || ++membersAtDepth[depth] > MAX_MEMBERS_PER_OBJECT) {
                        throw new LimitExceededException();
                    }
                    String name = parser.currentName();
                    requireUtf8Bound(name, MAX_FIELD_NAME_UTF8_BYTES);
                    if (depth == 1) {
                        topLevelField = name;
                    }
                    continue;
                }
                if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
                    if (depth == 1 && isRequiredField(topLevelField)) {
                        return failed(Result.Failure.PROTOCOL);
                    }
                    if (depth == MAX_NESTING_DEPTH) {
                        throw new LimitExceededException();
                    }
                    depth++;
                    objectAtDepth[depth] = token == JsonToken.START_OBJECT;
                    membersAtDepth[depth] = 0;
                    continue;
                }
                if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) {
                    if (depth == 1) {
                        if (token != JsonToken.END_OBJECT || parser.nextToken() != null) {
                            return failed(Result.Failure.PROTOCOL);
                        }
                        break;
                    }
                    depth--;
                    continue;
                }
                if (token == JsonToken.VALUE_STRING) {
                    String value = parser.getText();
                    requireUtf8Bound(value, MAX_STRING_UTF8_BYTES);
                    if (depth == 1) {
                        if ("id".equals(topLevelField)) {
                            if (id != null) { return failed(Result.Failure.PROTOCOL); }
                            id = value;
                        } else if ("type".equals(topLevelField)) {
                            if (type != null) { return failed(Result.Failure.PROTOCOL); }
                            type = value;
                        } else if ("customerId".equals(topLevelField)) {
                            if (customerId != null) { return failed(Result.Failure.PROTOCOL); }
                            customerId = value;
                        } else if ("timestamp".equals(topLevelField)) {
                            if (timestamp != null) { return failed(Result.Failure.PROTOCOL); }
                            timestamp = value;
                        }
                    }
                    continue;
                }
                if (depth == 1 && isRequiredField(topLevelField)) {
                    return failed(Result.Failure.PROTOCOL);
                }
            }

            if (id == null || !expectedType.equals(type)
                    || !expectedCustomerId.equals(customerId)
                    || !expectedTimestamp.equals(timestamp)) {
                return failed(Result.Failure.PROTOCOL);
            }
            try {
                Instant parsedTimestamp = Instant.parse(timestamp);
                SendResult sendResult = SendResult.builder()
                        .id(id)
                        .type(type)
                        .customerId(customerId)
                        .timestamp(parsedTimestamp)
                        .build();
                return new Result(sendResult, null);
            } catch (DateTimeException | IllegalArgumentException exception) {
                return failed(Result.Failure.PROTOCOL);
            }
        }
    }

    private static boolean hasJsonContentType(List<TransportHeaderField> fields) {
        String value = uniqueHeader(fields, "content-type");
        if (value == null || containsNonAscii(value) || value.indexOf(',') >= 0) {
            return false;
        }
        String trimmed = trimOws(value);
        int semicolon = trimmed.indexOf(';');
        String mediaType = semicolon < 0 ? trimmed : trimOws(trimmed.substring(0, semicolon));
        if (!asciiEqualsIgnoreCase(mediaType, "application/json")) {
            return false;
        }
        if (semicolon < 0) {
            return true;
        }
        String parameter = trimOws(trimmed.substring(semicolon + 1));
        if (parameter.isEmpty() || parameter.indexOf(';') >= 0) {
            return false;
        }
        int equals = parameter.indexOf('=');
        if (equals <= 0 || equals != parameter.lastIndexOf('=')) {
            return false;
        }
        String name = trimOws(parameter.substring(0, equals));
        String charset = trimOws(parameter.substring(equals + 1));
        return asciiEqualsIgnoreCase(name, "charset")
                && (asciiEqualsIgnoreCase(charset, "utf-8")
                        || (charset.length() == 7
                                && charset.charAt(0) == '"'
                                && charset.charAt(6) == '"'
                                && asciiEqualsIgnoreCase(charset.substring(1, 6), "utf-8")));
    }

    private static String contentEncoding(List<TransportHeaderField> fields) {
        String value = null;
        int matches = 0;
        for (TransportHeaderField field : fields) {
            if (asciiEqualsIgnoreCase(field.getName(), "content-encoding")) {
                matches++;
                value = field.getValue();
            }
        }
        if (matches == 0) {
            return "identity";
        }
        if (matches != 1 || containsNonAscii(value)) {
            return null;
        }
        String trimmed = trimOws(value);
        if (trimmed.indexOf(',') >= 0 || trimmed.indexOf(';') >= 0
                || trimmed.indexOf('"') >= 0 || containsInternalOws(trimmed)) {
            return null;
        }
        if (asciiEqualsIgnoreCase(trimmed, "identity")) {
            return "identity";
        }
        return asciiEqualsIgnoreCase(trimmed, "gzip") ? "gzip" : null;
    }

    private static String uniqueHeader(List<TransportHeaderField> fields, String expectedName) {
        String value = null;
        for (TransportHeaderField field : fields) {
            if (!asciiEqualsIgnoreCase(field.getName(), expectedName)) {
                continue;
            }
            if (value != null) {
                return null;
            }
            value = field.getValue();
        }
        return value;
    }

    private static boolean isRequiredField(String name) {
        return "id".equals(name) || "type".equals(name)
                || "customerId".equals(name) || "timestamp".equals(name);
    }

    private static void requireUtf8Bound(String value, int maximum)
            throws LimitExceededException {
        int bytes = 0;
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            index += Character.charCount(codePoint);
            bytes += codePoint <= 0x7f ? 1
                    : codePoint <= 0x7ff ? 2 : codePoint <= 0xffff ? 3 : 4;
            if (bytes > maximum) {
                throw new LimitExceededException();
            }
        }
    }

    private static boolean asciiEqualsIgnoreCase(String left, String right) {
        if (left == null || left.length() != right.length()) {
            return false;
        }
        for (int index = 0; index < left.length(); index++) {
            char a = left.charAt(index);
            char b = right.charAt(index);
            if (a >= 'A' && a <= 'Z') { a = (char) (a + ('a' - 'A')); }
            if (b >= 'A' && b <= 'Z') { b = (char) (b + ('a' - 'A')); }
            if (a != b) { return false; }
        }
        return true;
    }

    private static boolean containsNonAscii(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) > 0x7f) { return true; }
        }
        return false;
    }

    private static boolean containsInternalOws(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == ' ' || character == '\t') { return true; }
        }
        return false;
    }

    private static String trimOws(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isOws(value.charAt(start))) { start++; }
        while (end > start && isOws(value.charAt(end - 1))) { end--; }
        return value.substring(start, end);
    }

    private static boolean isOws(char value) {
        return value == ' ' || value == '\t';
    }

    private static Result failed(Result.Failure failure) {
        return new Result(null, failure);
    }

    private static final class CountingInputStream extends InputStream {
        private final InputStream delegate;
        private final long maximum;
        private final CountingInputStream compressed;
        private long count;

        private CountingInputStream(
                InputStream delegate,
                long maximum,
                CountingInputStream compressed) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.maximum = maximum;
            this.compressed = compressed;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) { record(1); }
            return value;
        }

        @Override
        public int read(byte[] destination, int offset, int length) throws IOException {
            int read = delegate.read(destination, offset, length);
            if (read > 0) { record(read); }
            return read;
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        private void record(int amount) throws LimitExceededException {
            count += amount;
            if (count > maximum
                    || (compressed != null
                            && count > Math.max(1L, compressed.count) * MAX_EXPANSION_RATIO)) {
                throw new LimitExceededException();
            }
        }
    }

    private static final class LimitExceededException extends IOException {
        private static final long serialVersionUID = 1L;
    }
}
