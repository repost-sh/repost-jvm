package sh.repost.client;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

final class JvmRawEvidenceContractTest {
    @Test
    void acceptsCompleteRetainedEvidence() throws IOException {
        Map<String, Object> overlay = overlay();
        assertDoesNotThrow(() -> JvmRawEvidenceValidator.validate(json(pristine(overlay)), overlay));
    }

    @Test
    void rejectsRetainedEvidenceMutations() throws IOException {
        Map<String, Object> overlay = overlay();
        Map<String, Consumer<Map<String, Object>>> mutations = mutations(overlay);
        for (Map.Entry<String, Consumer<Map<String, Object>>> mutation : mutations.entrySet()) {
            Map<String, Object> evidence = pristine(overlay);
            mutation.getValue().accept(evidence);
            assertThrows(Exception.class,
                    () -> JvmRawEvidenceValidator.validate(json(evidence), overlay),
                    mutation.getKey());
        }
    }

    @Test
    void rejectsDuplicateKeysAndResourceLimitViolations() throws IOException {
        Map<String, Object> overlay = overlay();
        assertThrows(IOException.class, () -> JvmRawEvidenceValidator.validate(
                "{\"schema\":\"x\",\"schema\":\"x\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8), overlay));
        assertThrows(IOException.class, () -> JvmRawEvidenceValidator.validate(
                new byte[JvmRawEvidenceValidator.MAX_BYTES + 1], overlay));
        assertThrows(IOException.class, () -> JvmRawEvidenceValidator.validate(
                ("{\"x\":" + "[".repeat(JvmRawEvidenceValidator.MAX_DEPTH + 1) + "0"
                        + "]".repeat(JvmRawEvidenceValidator.MAX_DEPTH + 1) + "}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8), overlay));
        assertThrows(IOException.class, () -> JvmRawEvidenceValidator.validate(
                ("{\"x\":[" + "0,".repeat(JvmRawEvidenceValidator.MAX_TOKENS) + "0]}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8), overlay));
    }

    private static Map<String, Consumer<Map<String, Object>>> mutations(Map<String, Object> overlay) {
        Map<String, Object> contractGroups = object(object(overlay, "jvmRawFixtureProtocol"), "groups");
        String first = contractGroups.keySet().iterator().next();
        String barrierGroup = "h2-rst-refused-cancel-protocol-error-pre-postcommit-retry-exhaustion";
        LinkedHashMap<String, Consumer<Map<String, Object>>> tests = new LinkedHashMap<>();
        tests.put("omitted group", value -> object(value, "groups").remove(first));
        tests.put(
                "unknown group",
                value -> object(value, "groups").put("unknown", object(object(value, "groups"), first)));
        tests.put("reordered groups", value -> value.put("groups", reversed(object(value, "groups"))));
        tests.put("skipped group", value -> group(value, first).put("skipped", true));
        tests.put("wrong summary", value -> object(value, "summary").put("success", 32L));
        tests.put("omitted barrier", value -> array(group(value, barrierGroup), "barriers").remove(3));
        tests.put("reordered barrier", value -> swap(array(group(value, barrierGroup), "barriers"), 3, 4));
        tests.put(
                "wrong barrier name",
                value -> objectAt(group(value, barrierGroup), "barriers", 3).put("barrier", "forged"));
        tests.put(
                "wrong barrier group",
                value -> objectAt(group(value, barrierGroup), "barriers", 3).put("groupId", first));
        tests.put(
                "wrong barrier status",
                value -> objectAt(group(value, barrierGroup), "barriers", 4).put("status", "WAITING"));
        tests.put(
                "wrong barrier time",
                value -> objectAt(group(value, barrierGroup), "barriers", 4).put("monotonicTimeMs", 2L));
        tests.put("omitted counter", value -> removeSecond(object(group(value, barrierGroup), "observedCounters")));
        tests.put("added counter", value -> object(group(value, barrierGroup), "observedCounters").put("forged", 1L));
        tests.put("reordered counters", value -> group(value, barrierGroup).put(
                "observedCounters", reversed(object(group(value, barrierGroup), "observedCounters"))));
        tests.put("wrong counter", value -> replaceFirst(object(group(value, barrierGroup), "observedCounters"), 999L));
        for (String field : List.of("endpointIds", "assetIds", "capabilities")) {
            tests.put("omitted " + field, value -> array(group(value, first), field).remove(0));
            tests.put("added " + field, value -> array(group(value, first), field).add("forged"));
            tests.put("reordered " + field, value -> {
                List<Object> items = array(group(value, first), field);
                if (items.size() > 1) swap(items, 0, 1); else items.add(0, "forged");
            });
        }
        tests.put(
                "unsafe retained URL",
                value -> array(group(value, first), "endpointIds").set(0, "http://127.0.0.1:1"));
        tests.put("omitted field", value -> group(value, first).remove("terminalAssertions"));
        tests.put("reordered fields", value -> object(value, "groups").put(first, reversed(group(value, first))));
        return tests;
    }

    private static Map<String, Object> pristine(Map<String, Object> overlay) {
        Map<String, Object> protocol = object(overlay, "jvmRawFixtureProtocol");
        Map<String, Object> schema = object(protocol, "evidenceSchema");
        Map<String, Object> barrierSchema = object(protocol, "barrierSchema");
        Map<String, Object> contractGroups = object(protocol, "groups");
        LinkedHashMap<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("schema", schema.get("name"));
        evidence.put("protocolVersion", protocol.get("protocolVersion"));
        evidence.put("summary", ordered(
                "total", (long) contractGroups.size(),
                "success", (long) contractGroups.size(),
                "skipped", 0L,
                "undeclared", 0L));
        LinkedHashMap<String, Object> groups = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : contractGroups.entrySet()) {
            String groupId = entry.getKey();
            Map<String, Object> contract = castObject(entry.getValue());
            List<Object> barriers = new ArrayList<>();
            for (Object barrier : array(contract, "requiredBarriers")) {
                barriers.add(barrier(barrierSchema, groupId, barrier, "WAITING", 0L));
                barriers.add(barrier(barrierSchema, groupId, barrier, "RELEASED", 1L));
                barriers.add(barrier(barrierSchema, groupId, barrier, "RELEASED", 1L));
            }
            groups.put(groupId, ordered(
                    "schema", schema.get("name"),
                    "protocolVersion", protocol.get("protocolVersion"),
                    "groupId", groupId,
                    "fixtureCaseId", contract.get("fixtureCaseId"),
                    "outcome", "SUCCESS",
                    "skipped", false,
                    "controls", copyArray(contract, "requiredControls"),
                    "barriers", barriers,
                    "observedCounters", new LinkedHashMap<>(object(contract, "requiredObservedCounters")),
                    "state", "CLOSED",
                    "endpointIds", copyArray(contract, "requiredEndpointIds"),
                    "assetIds", copyArray(contract, "requiredAssetIds"),
                    "capabilities", copyArray(contract, "requiredCapabilities"),
                    "terminalAssertions", copyArray(contract, "terminalAssertions")));
        }
        evidence.put("groups", groups);
        return evidence;
    }

    private static Map<String, Object> barrier(
            Map<String, Object> schema, String groupId, Object barrier, String status, long time) {
        return ordered(
                "schema", schema.get("name"),
                "protocolVersion", 1L,
                "groupId", groupId,
                "barrier", barrier,
                "status", status,
                "monotonicTimeMs", time);
    }

    private static Map<String, Object> overlay() throws IOException {
        Path current = Paths.get(System.getProperty("user.dir"));
        for (Path candidate : List.of(
                current.resolve("certification/transport/jvm-v2.json"),
                current.resolve("sdk/jvm/certification/transport/jvm-v2.json"),
                current.resolve("../certification/transport/jvm-v2.json").normalize())) {
            if (Files.isRegularFile(candidate)) {
                return JvmRawEvidenceValidator.parseContract(Files.readAllBytes(candidate));
            }
        }
        throw new IOException("JVM raw fixture overlay is not reachable from the test working directory");
    }

    private static byte[] json(Object value) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (JsonGenerator generator = new JsonFactory().createGenerator(output)) {
            write(generator, value);
        }
        return output.toByteArray();
    }

    private static void write(JsonGenerator generator, Object value) throws IOException {
        if (value instanceof Map) {
            generator.writeStartObject();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                generator.writeFieldName((String) entry.getKey());
                write(generator, entry.getValue());
            }
            generator.writeEndObject();
        } else if (value instanceof List) {
            generator.writeStartArray();
            for (Object item : (List<?>) value) write(generator, item);
            generator.writeEndArray();
        } else if (value instanceof String) {
            generator.writeString((String) value);
        } else if (value instanceof Number) {
            generator.writeNumber(((Number) value).longValue());
        } else if (value instanceof Boolean) {
            generator.writeBoolean((Boolean) value);
        } else if (value == null) {
            generator.writeNull();
        } else {
            throw new IOException("unsupported test value " + value.getClass());
        }
    }

    private static LinkedHashMap<String, Object> ordered(Object... fields) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        for (int index = 0; index < fields.length; index += 2) {
            value.put((String) fields[index], fields[index + 1]);
        }
        return value;
    }

    private static Map<String, Object> group(Map<String, Object> evidence, String id) {
        return object(object(evidence, "groups"), id);
    }

    private static Map<String, Object> objectAt(Map<String, Object> value, String field, int index) {
        return castObject(array(value, field).get(index));
    }

    private static void swap(List<Object> values, int left, int right) {
        Object temporary = values.get(left);
        values.set(left, values.get(right));
        values.set(right, temporary);
    }

    private static void removeSecond(Map<String, Object> values) {
        values.remove(new ArrayList<>(values.keySet()).get(1));
    }

    private static void replaceFirst(Map<String, Object> values, Object replacement) {
        values.put(values.keySet().iterator().next(), replacement);
    }

    private static LinkedHashMap<String, Object> reversed(Map<String, Object> values) {
        List<Map.Entry<String, Object>> entries = new ArrayList<>(values.entrySet());
        java.util.Collections.reverse(entries);
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : entries) result.put(entry.getKey(), entry.getValue());
        return result;
    }

    private static List<Object> copyArray(Map<String, Object> value, String field) {
        return new ArrayList<>(array(value, field));
    }

    private static Map<String, Object> object(Map<String, Object> value, String field) {
        return castObject(value.get(field));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castObject(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(Map<String, Object> value, String field) {
        return (List<Object>) value.get(field);
    }
}
