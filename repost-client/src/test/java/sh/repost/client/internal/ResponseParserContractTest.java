package sh.repost.client.internal;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import sh.repost.client.SendResult;
import sh.repost.client.TransportHeaderField;
import sh.repost.client.TransportResponse;

final class ResponseParserContractTest {
    private static final String TYPE = "book.created";
    private static final String CUSTOMER = "customer_123";
    private static final String TIMESTAMP = "2026-01-01T00:00:00.000Z";

    @Test
    void parsesTheBoundedIdentityEchoWithoutRetainingRemoteText() {
        ResponseParser.Result parsed = parse(
                headers(" Content/JSON "), accepted("msg_book"));

        assertEquals(ResponseParser.Result.Failure.PROTOCOL, parsed.getFailure());
        assertNull(parsed.getSendResult());

        parsed = parse(
                headers(" Application/JSON ; Charset = \"UTF-8\" "),
                accepted("msg_book"));
        SendResult result = parsed.getSendResult();
        assertNotNull(result);
        assertNull(parsed.getFailure());
        assertEquals("msg_book", result.getId());
        assertEquals(TYPE, result.getType());
        assertEquals(CUSTOMER, result.getCustomerId());
        assertEquals(Instant.parse(TIMESTAMP), result.getTimestamp());
    }

    @Test
    void rejectsDuplicateEncodingAndDuplicateJsonMembers() {
        List<TransportHeaderField> duplicateEncoding = Arrays.asList(
                TransportHeaderField.of("Content-Type", "application/json"),
                TransportHeaderField.of("Content-Encoding", "identity"),
                TransportHeaderField.of("content-encoding", "identity"));
        assertEquals(
                ResponseParser.Result.Failure.PROTOCOL,
                parse(duplicateEncoding, accepted("msg_encoding")).getFailure());

        String duplicateId = "{\"id\":\"msg_one\",\"id\":\"msg_two\","
                + "\"type\":\"" + TYPE + "\",\"customerId\":\"" + CUSTOMER + "\","
                + "\"timestamp\":\"" + TIMESTAMP + "\"}";
        assertEquals(
                ResponseParser.Result.Failure.PROTOCOL,
                parse(headers("application/json"), duplicateId).getFailure());
    }

    @Test
    void classifiesParserAmplificationLimitsSeparatelyFromProtocolFailures() {
        String longName = "x".repeat(65);
        String body = "{\"id\":\"msg_limit\",\"type\":\"" + TYPE + "\","
                + "\"customerId\":\"" + CUSTOMER + "\","
                + "\"timestamp\":\"" + TIMESTAMP + "\","
                + "\"" + longName + "\":true}";
        assertEquals(
                ResponseParser.Result.Failure.LIMIT,
                parse(headers("application/json"), body).getFailure());
    }

    @Test
    void decodesOneBoundedGzipResponse() throws IOException {
        byte[] encoded = gzip(accepted("msg_gzip").getBytes(UTF_8));
        TransportResponse response = TransportResponse.of(
                202,
                Arrays.asList(
                        TransportHeaderField.of("Content-Type", "application/json"),
                        TransportHeaderField.of("Content-Encoding", "gzip")),
                new ByteArrayInputStream(encoded));
        try {
            ResponseParser.Result parsed =
                    ResponseParser.parse(response, TYPE, CUSTOMER, TIMESTAMP);
            assertNotNull(parsed.getSendResult());
            assertEquals("msg_gzip", parsed.getSendResult().getId());
        } finally {
            response.close();
        }
    }

    private static ResponseParser.Result parse(
            List<TransportHeaderField> headers,
            String body) {
        TransportResponse response = TransportResponse.of(
                202, headers, new ByteArrayInputStream(body.getBytes(UTF_8)));
        try {
            return ResponseParser.parse(response, TYPE, CUSTOMER, TIMESTAMP);
        } finally {
            response.close();
        }
    }

    @Test
    void readsTheOriginalTimestampFromTheDuplicateAnswer() {
        String[][] carried = {
            {"\"2025-12-31T23:59:59.500Z\"", "2025-12-31T23:59:59.500Z"},
            {"\"2025-12-31T23:59:59Z\"", "2025-12-31T23:59:59Z"},
            {"\"2025-12-31T23:59:59.123456789+02:00\"", "2025-12-31T21:59:59.123456789Z"},
        };
        for (String[] pair : carried) {
            TransportResponse response = TransportResponse.of(
                    409,
                    headers("application/json"),
                    new ByteArrayInputStream(
                            ("{\"error\":\"duplicate\",\"id\":\"msg_original\",\"timestamp\":" + pair[0] + "}")
                                    .getBytes(UTF_8)));
            try {
                ResponseParser.Result parsed =
                        ResponseParser.parseDuplicate(response, TYPE, CUSTOMER, TIMESTAMP);
                SendResult result = parsed.getSendResult();
                assertNotNull(result, pair[0]);
                assertEquals("msg_original", result.getId());
                assertEquals(Instant.parse(pair[1]), result.getTimestamp(), pair[0]);
            } finally {
                response.close();
            }
        }
    }

    @Test
    void aStrayTimestampOnTheDuplicateAnswerKeepsTheEnvelopeTimestamp() {
        for (String stray : Arrays.asList(
                "\"not-a-time\"", "\"2025-13-01T00:00:00Z\"", "\"2025-02-30T00:00:00Z\"",
                "\"2025-12-31T24:00:00Z\"", "\"2025-12-31 23:59:59Z\"", "\"\"", "17", "null",
                "{\"iso\":\"2025-12-31T23:59:59Z\"}", "[]")) {
            TransportResponse response = TransportResponse.of(
                    409,
                    headers("application/json"),
                    new ByteArrayInputStream(
                            ("{\"id\":\"msg_original\",\"timestamp\":" + stray + "}").getBytes(UTF_8)));
            try {
                ResponseParser.Result parsed =
                        ResponseParser.parseDuplicate(response, TYPE, CUSTOMER, TIMESTAMP);
                SendResult result = parsed.getSendResult();
                assertNotNull(result, stray);
                assertNull(parsed.getFailure(), stray);
                assertEquals("msg_original", result.getId());
                assertEquals(Instant.parse(TIMESTAMP), result.getTimestamp(), stray);
            } finally {
                response.close();
            }
        }
    }

    @Test
    void readsTheDuplicateAnswerWithTheSentIdentity() {
        TransportResponse response = TransportResponse.of(
                409,
                headers("application/json"),
                new ByteArrayInputStream(
                        "{\"error\":\"duplicate\",\"id\":\"msg_original\"}".getBytes(UTF_8)));
        try {
            ResponseParser.Result parsed =
                    ResponseParser.parseDuplicate(response, TYPE, CUSTOMER, TIMESTAMP);
            SendResult result = parsed.getSendResult();
            assertNotNull(result);
            assertEquals("msg_original", result.getId());
            assertEquals(TYPE, result.getType());
            assertEquals(CUSTOMER, result.getCustomerId());
            assertEquals(Instant.parse(TIMESTAMP), result.getTimestamp());
        } finally {
            response.close();
        }

        for (String body : Arrays.asList(
                "{\"error\":{\"code\":\"remote\"}}", "{\"id\":\"not-a-message\"}", "[{\"id\":\"msg_1\"}]")) {
            TransportResponse conflict = TransportResponse.of(
                    409, headers("application/json"), new ByteArrayInputStream(body.getBytes(UTF_8)));
            try {
                ResponseParser.Result parsed =
                        ResponseParser.parseDuplicate(conflict, TYPE, CUSTOMER, TIMESTAMP);
                assertNull(parsed.getSendResult(), body);
                assertEquals(ResponseParser.Result.Failure.PROTOCOL, parsed.getFailure(), body);
            } finally {
                conflict.close();
            }
        }
    }

    private static List<TransportHeaderField> headers(String contentType) {
        return Collections.singletonList(
                TransportHeaderField.of("Content-Type", contentType));
    }

    private static String accepted(String id) {
        return "{\"id\":\"" + id + "\",\"type\":\"" + TYPE + "\","
                + "\"customerId\":\"" + CUSTOMER + "\","
                + "\"timestamp\":\"" + TIMESTAMP + "\"}";
    }

    private static byte[] gzip(byte[] body) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(body);
        }
        return output.toByteArray();
    }
}
