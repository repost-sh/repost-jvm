package sh.repost.client;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class JvmRawEvidenceValidator {
    static final int MAX_BYTES = 1 << 20;
    static final int MAX_DEPTH = 16;
    static final int MAX_TOKENS = 50_000;

    private JvmRawEvidenceValidator() {}

    static Map<String, Object> parseContract(byte[] bytes) throws IOException {
        return parse(bytes, 128, 500_000, MAX_BYTES);
    }

    static Map<String, Object> validate(byte[] bytes, Map<String, Object> overlay) throws IOException {
        Map<String, Object> evidence = parse(bytes, MAX_DEPTH, MAX_TOKENS, MAX_BYTES);
        Map<String, Object> protocol = object(overlay, "jvmRawFixtureProtocol");
        Map<String, Object> evidenceSchema = object(protocol, "evidenceSchema");
        Map<String, Object> barrierSchema = object(protocol, "barrierSchema");
        Map<String, Object> contractGroups = object(protocol, "groups");

        exactKeys(evidence, strings(evidenceSchema, "fields"), "evidence");
        require(string(evidence, "schema").equals(string(evidenceSchema, "name")), "evidence schema");
        require(integer(evidence, "protocolVersion") == integer(protocol, "protocolVersion"), "protocol version");

        Map<String, Object> summary = object(evidence, "summary");
        exactKeys(summary, strings(evidenceSchema, "summaryFields"), "summary");
        int total = contractGroups.size();
        require(integer(summary, "total") == total, "summary total");
        require(integer(summary, "success") == total, "summary success");
        require(integer(summary, "skipped") == 0, "summary skipped");
        require(integer(summary, "undeclared") == 0, "summary undeclared");

        Map<String, Object> groups = object(evidence, "groups");
        exactKeys(groups, new ArrayList<>(contractGroups.keySet()), "groups");
        for (Map.Entry<String, Object> entry : contractGroups.entrySet()) {
            validateGroup(
                    entry.getKey(), castObject(entry.getValue()), object(groups, entry.getKey()),
                    evidenceSchema, barrierSchema, integer(protocol, "protocolVersion"));
        }
        rejectRetainedLocations(evidence);
        return evidence;
    }

    private static void validateGroup(
            String groupId,
            Map<String, Object> contract,
            Map<String, Object> evidence,
            Map<String, Object> evidenceSchema,
            Map<String, Object> barrierSchema,
            long protocolVersion) {
        exactKeys(evidence, strings(evidenceSchema, "groupFields"), "group " + groupId);
        require(string(evidence, "schema").equals(string(evidenceSchema, "name")), groupId + " schema");
        require(integer(evidence, "protocolVersion") == protocolVersion, groupId + " protocol version");
        require(string(evidence, "groupId").equals(groupId), groupId + " group ID");
        require(string(evidence, "fixtureCaseId").equals(string(contract, "fixtureCaseId")), groupId + " fixture case");
        require(string(evidence, "outcome").equals("SUCCESS"), groupId + " outcome");
        require(Boolean.FALSE.equals(evidence.get("skipped")), groupId + " skipped");
        require(array(evidence, "controls").equals(array(contract, "requiredControls")), groupId + " controls");
        validateBarriers(
                groupId, array(contract, "requiredBarriers"), array(evidence, "barriers"),
                barrierSchema, protocolVersion);
        exactMap(
                object(evidence, "observedCounters"),
                object(contract, "requiredObservedCounters"),
                groupId + " counters");
        require(string(evidence, "state").equals("CLOSED"), groupId + " state");
        require(array(evidence, "endpointIds").equals(array(contract, "requiredEndpointIds")), groupId + " endpoints");
        require(array(evidence, "assetIds").equals(array(contract, "requiredAssetIds")), groupId + " assets");
        require(
                array(evidence, "capabilities").equals(array(contract, "requiredCapabilities")),
                groupId + " capabilities");
        require(
                array(evidence, "terminalAssertions").equals(array(contract, "terminalAssertions")),
                groupId + " assertions");
    }

    private static void validateBarriers(
            String groupId,
            List<Object> required,
            List<Object> actual,
            Map<String, Object> schema,
            long protocolVersion) {
        require(actual.size() == required.size() * 3, groupId + " barrier count");
        int index = 0;
        for (Object value : required) {
            String barrier = castString(value);
            for (int event = 0; event < 3; event++) {
                Map<String, Object> item = castObject(actual.get(index++));
                exactKeys(item, strings(schema, "fields"), groupId + " barrier");
                require(string(item, "schema").equals(string(schema, "name")), groupId + " barrier schema");
                require(integer(item, "protocolVersion") == protocolVersion, groupId + " barrier version");
                require(string(item, "groupId").equals(groupId), groupId + " barrier group");
                require(string(item, "barrier").equals(barrier), groupId + " barrier name");
                require(
                        string(item, "status").equals(event == 0 ? "WAITING" : "RELEASED"),
                        groupId + " barrier status");
                require(integer(item, "monotonicTimeMs") == (event == 0 ? 0 : 1), groupId + " barrier time");
            }
        }
    }

    private static Map<String, Object> parse(byte[] bytes, int depth, long tokens, int maxBytes) throws IOException {
        if (bytes.length == 0 || bytes.length > maxBytes) {
            throw new IOException("evidence byte limit");
        }
        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxDocumentLength(maxBytes)
                .maxNestingDepth(depth)
                .maxTokenCount(tokens)
                .build();
        JsonFactory factory = JsonFactory.builder()
                .streamReadConstraints(constraints)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        try (JsonParser parser = factory.createParser(bytes)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("JSON root must be an object");
            }
            Map<String, Object> value = readObject(parser);
            if (parser.nextToken() != null) {
                throw new IOException("trailing JSON data");
            }
            return value;
        }
    }

    private static Map<String, Object> readObject(JsonParser parser) throws IOException {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String name = parser.currentName();
            parser.nextToken();
            values.put(name, readValue(parser));
        }
        return values;
    }

    private static Object readValue(JsonParser parser) throws IOException {
        switch (parser.currentToken()) {
            case START_OBJECT: return readObject(parser);
            case START_ARRAY:
                ArrayList<Object> values = new ArrayList<>();
                while (parser.nextToken() != JsonToken.END_ARRAY) values.add(readValue(parser));
                return values;
            case VALUE_STRING: return parser.getText();
            case VALUE_TRUE: return Boolean.TRUE;
            case VALUE_FALSE: return Boolean.FALSE;
            case VALUE_NULL: return null;
            case VALUE_NUMBER_INT: return parser.getLongValue();
            default: throw new IOException("unsupported JSON token " + parser.currentToken());
        }
    }

    private static void exactMap(Map<String, Object> actual, Map<String, Object> expected, String location) {
        exactKeys(actual, new ArrayList<>(expected.keySet()), location);
        require(actual.equals(expected), location);
    }

    private static void exactKeys(Map<String, Object> value, List<String> expected, String location) {
        require(new ArrayList<>(value.keySet()).equals(expected), location + " fields");
    }

    private static void rejectRetainedLocations(Object value) {
        if (value instanceof Map) {
            for (Object child : ((Map<?, ?>) value).values()) rejectRetainedLocations(child);
        } else if (value instanceof List) {
            for (Object child : (List<?>) value) rejectRetainedLocations(child);
        } else if (value instanceof String) {
            String text = (String) value;
            require(
                    !text.contains("://") && !text.startsWith("file:") && !text.startsWith("/"),
                    "unsafe retained location");
        }
    }

    private static Map<String, Object> object(Map<String, Object> value, String field) {
        return castObject(value.get(field));
    }

    private static List<Object> array(Map<String, Object> value, String field) {
        Object candidate = value.get(field);
        if (!(candidate instanceof List)) throw new IllegalArgumentException(field + " must be an array");
        return castArray(candidate);
    }

    private static List<String> strings(Map<String, Object> value, String field) {
        List<String> strings = new ArrayList<>();
        for (Object item : array(value, field)) strings.add(castString(item));
        return strings;
    }

    private static String string(Map<String, Object> value, String field) {
        return castString(value.get(field));
    }

    private static long integer(Map<String, Object> value, String field) {
        Object candidate = value.get(field);
        if (!(candidate instanceof Long)) throw new IllegalArgumentException(field + " must be an integer");
        return (Long) candidate;
    }

    private static String castString(Object value) {
        if (!(value instanceof String)) throw new IllegalArgumentException("value must be a string");
        return (String) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castObject(Object value) {
        if (!(value instanceof Map)) throw new IllegalArgumentException("value must be an object");
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castArray(Object value) {
        return (List<Object>) value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
