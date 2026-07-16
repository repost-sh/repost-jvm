package sh.repost.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import sh.repost.client.descriptor.DefaultSpec;
import sh.repost.client.descriptor.FieldDescriptor;
import sh.repost.client.descriptor.ModelDescriptor;
import sh.repost.client.descriptor.ScalarKind;
import sh.repost.client.descriptor.SchemaDescriptor;
import sh.repost.client.error.RepostValidationException;
import sh.repost.client.internal.Serializer;

final class SharedV2FieldMatrixTest {
    private enum FixtureEnum { ACTIVE }

    @Test
    void discoversEverySharedSerializationAndValidationCase() throws IOException {
        List<FixtureCase> cases = loadCases();
        assertEquals(28, cases.size());
        assertEquals(9, cases.stream().filter(value -> value.expectedError == null).count());
        assertEquals(19, cases.stream().filter(value -> value.expectedError != null).count());
        assertEquals(28, cases.stream().map(value -> value.caseId).distinct().count());
    }

    @Test
    void rejectsRawKeyOraclePathsInsteadOfNormalizingThem() {
        AssertionError error = assertThrows(
                AssertionError.class,
                () -> requireNoQuotedRawKeyPath("$.value[\"sentinel-secret-key\"]"));
        assertFalse(error.getMessage().contains("sentinel-secret-key"));
    }

    @TestFactory
    Stream<DynamicTest> executesEverySharedSerializationAndValidationCase() throws IOException {
        return loadCases().stream().map(fixture -> DynamicTest.dynamicTest(
                fixture.source + " :: " + fixture.caseId,
                () -> execute(fixture)));
    }

    private static void execute(FixtureCase fixture) {
        CountingGenerators generators = new CountingGenerators(fixture.caseData);
        if (fixture.expectedError != null && isDescriptorError(fixture.expectedError.code)) {
            FixtureDescriptorException error = assertThrows(
                    FixtureDescriptorException.class,
                    () -> descriptor(fixture.caseData));
            assertEquals(fixture.expectedError.code, error.code);
            assertEquals(fixture.expectedError.path, error.path);
            assertGeneratorCounts(fixture.caseData, generators);
            return;
        }

        SchemaDescriptor schema = descriptor(fixture.caseData);
        String modelId = string(fixture.caseData, "model");
        ModelDescriptor modelDescriptor = schema.getModels().get(modelId);
        RepostModel model = fixtureModel(
                object(fixture.caseData, "input"), modelDescriptor, schema);
        if (fixture.expectedError == null) {
            byte[] actual;
            if (fixture.caseData.containsKey("expectedEnvelopeJson")) {
                Map<String, Object> operation = object(fixture.caseData, "operation");
                actual = Serializer.serializeEnvelope(
                        schema, modelId, string(operation, "eventType"), model, generators);
                assertEquals(string(fixture.caseData, "expectedEnvelopeJson"),
                        new String(actual, StandardCharsets.UTF_8));
            } else {
                actual = Serializer.serializeModel(schema, modelId, model, generators);
                assertEquals(string(fixture.caseData, "expectedWireJson"),
                        new String(actual, StandardCharsets.UTF_8));
            }
        } else {
            RepostValidationException error = assertThrows(
                    RepostValidationException.class,
                    () -> Serializer.serializeModel(schema, modelId, model, generators));
            assertEquals(publicCode(fixture.caseId, fixture.expectedError.code),
                    error.getValidationIssues().get(0).getCode());
            requireNoQuotedRawKeyPath(fixture.expectedError.path);
            assertEquals(fixture.expectedError.path, error.getValidationIssues().get(0).getPath());
        }
        assertGeneratorCounts(fixture.caseData, generators);
    }

    private static boolean isDescriptorError(String code) {
        return "INVALID_DESCRIPTOR_REFERENCE".equals(code)
                || "UNKNOWN_SCALAR_KIND".equals(code)
                || "UNSUPPORTED_DESCRIPTOR_VERSION".equals(code);
    }

    private static ValidationIssueCode publicCode(String caseId, String code) {
        switch (code) {
            case "REQUIRED_FIELD": return ValidationIssueCode.REQUIRED;
            case "NULL_NOT_ALLOWED":
            case "NULL_LIST_ELEMENT": return ValidationIssueCode.NULL_NOT_ALLOWED;
            case "NON_FINITE_NUMBER": return ValidationIssueCode.NON_FINITE;
            case "INVALID_JSON":
                return caseId.startsWith("cyclic-")
                        ? ValidationIssueCode.CYCLE : ValidationIssueCode.INVALID_JSON;
            default: throw new AssertionError("unmapped shared error code: " + code);
        }
    }

    private static void requireNoQuotedRawKeyPath(String path) {
        if (path.contains("[\"")) {
            throw new AssertionError("raw JSON fixture paths must use the fixed placeholder");
        }
    }

    private static SchemaDescriptor descriptor(Map<String, Object> data) {
        int version = Math.toIntExact(number(data, "descriptorVersion").longValue());
        if (version != SchemaDescriptor.SUPPORTED_FORMAT_VERSION) {
            throw new FixtureDescriptorException("UNSUPPORTED_DESCRIPTOR_VERSION", "$");
        }
        Map<String, Object> rawEnums = object(data, "enums");
        Map<String, Object> rawModels = object(data, "models");
        prevalidateReferences(rawEnums, rawModels, data);
        SchemaDescriptor.Builder schema = SchemaDescriptor.builder(version);
        for (Map.Entry<String, Object> enumEntry : rawEnums.entrySet()) {
            LinkedHashMap<String, String> members = new LinkedHashMap<>();
            for (Map.Entry<String, Object> member
                    : castObject(enumEntry.getValue()).entrySet()) {
                members.put(member.getKey(), (String) member.getValue());
            }
            schema.addEnum(enumEntry.getKey(), members);
        }
        for (Map.Entry<String, Object> modelEntry : rawModels.entrySet()) {
            List<Object> rawFields = array(castObject(modelEntry.getValue()), "fields");
            ArrayList<FieldDescriptor> fields = new ArrayList<>(rawFields.size());
            for (int index = 0; index < rawFields.size(); index++) {
                Map<String, Object> rawField = castObject(rawFields.get(index));
                ScalarKind kind = scalarKind((String) rawField.get("scalarKind"),
                        "$." + string(rawField, "schemaName"));
                FieldDescriptor.Builder field = FieldDescriptor.builder(
                                index,
                                string(rawField, "schemaName"),
                                string(rawField, "wireName"),
                                kind)
                        .requiredInInput(bool(rawField, "requiredInInput"))
                        .nullableInInput(bool(rawField, "nullableInInput"))
                        .list(bool(rawField, "list"));
                if (rawField.get("descriptorId") != null) {
                    field.descriptorId((String) rawField.get("descriptorId"));
                }
                if (rawField.get("default") != null) {
                    field.defaultSpec(defaultSpec(castObject(rawField.get("default")), kind));
                }
                fields.add(field.build());
            }
            schema.addModel(ModelDescriptor.of(modelEntry.getKey(), fields));
        }
        return schema.build();
    }

    private static void prevalidateReferences(
            Map<String, Object> enums,
            Map<String, Object> models,
            Map<String, Object> data) {
        for (Map.Entry<String, Object> modelEntry : models.entrySet()) {
            List<Object> fields = array(castObject(modelEntry.getValue()), "fields");
            for (int index = 0; index < fields.size(); index++) {
                Map<String, Object> field = castObject(fields.get(index));
                String kind = string(field, "scalarKind");
                if (!"enum".equals(kind) && !"model".equals(kind)) {
                    continue;
                }
                Object descriptorId = field.get("descriptorId");
                boolean valid = descriptorId instanceof String
                        && ("enum".equals(kind)
                                ? enums.containsKey(descriptorId) : models.containsKey(descriptorId));
                if (!valid) {
                    throw new FixtureDescriptorException(
                            "INVALID_DESCRIPTOR_REFERENCE",
                            "$.models." + modelEntry.getKey() + ".fields[" + index
                                    + "].descriptorId");
                }
            }
        }
        String requestedModel = string(data, "model");
        if (!models.containsKey(requestedModel)) {
            throw new FixtureDescriptorException(
                    "INVALID_DESCRIPTOR_REFERENCE", "$.webhooks.vectors.send.model");
        }
    }

    private static ScalarKind scalarKind(String value, String path) {
        switch (value) {
            case "string": return ScalarKind.STRING;
            case "boolean": return ScalarKind.BOOLEAN;
            case "int64": return ScalarKind.INT64;
            case "float64": return ScalarKind.FLOAT64;
            case "datetime": return ScalarKind.DATETIME;
            case "json": return ScalarKind.JSON;
            case "enum": return ScalarKind.ENUM;
            case "model": return ScalarKind.MODEL;
            default: throw new FixtureDescriptorException("UNKNOWN_SCALAR_KIND", path);
        }
    }

    private static DefaultSpec defaultSpec(Map<String, Object> raw, ScalarKind kind) {
        switch (string(raw, "kind")) {
            case "literal":
                return DefaultSpec.literal(convertScalar(raw.get("value"), kind, null, null));
            case "now": return DefaultSpec.now();
            case "uuid": return DefaultSpec.uuid();
            case "cuid": return DefaultSpec.cuid();
            default: throw new AssertionError("unknown default fixture kind");
        }
    }

    private static RepostModel fixtureModel(
            Map<String, Object> input,
            ModelDescriptor descriptor,
            SchemaDescriptor schema) {
        LinkedHashMap<String, Object> converted = new LinkedHashMap<>();
        for (FieldDescriptor field : descriptor.getFields()) {
            if (input.containsKey(field.getSchemaName())) {
                Object raw = input.get(field.getSchemaName());
                if (raw == null) {
                    converted.put(field.getSchemaName(), null);
                } else if (field.isList()) {
                    ArrayList<Object> elements = new ArrayList<>();
                    for (Object element : castArray(raw)) {
                        elements.add(element == null ? null
                                : convertScalar(element, field.getScalarKind(),
                                        nestedDescriptor(field, schema), schema));
                    }
                    converted.put(field.getSchemaName(), Collections.unmodifiableList(elements));
                } else {
                    converted.put(field.getSchemaName(), convertScalar(
                            raw, field.getScalarKind(), nestedDescriptor(field, schema), schema));
                }
            }
        }
        return new FixtureModel(converted, descriptor);
    }

    private static ModelDescriptor nestedDescriptor(
            FieldDescriptor field, SchemaDescriptor schema) {
        return field.getScalarKind() == ScalarKind.MODEL
                ? schema.getModels().get(field.getDescriptorId()) : null;
    }

    private static Object convertScalar(
            Object raw,
            ScalarKind kind,
            ModelDescriptor nestedDescriptor,
            SchemaDescriptor schema) {
        switch (kind) {
            case STRING: return raw;
            case BOOLEAN: return raw;
            case INT64: return ((Number) raw).longValue();
            case FLOAT64: return controlValue(raw, true);
            case DATETIME: return Instant.parse((String) raw);
            case JSON: return controlValue(raw, false);
            case ENUM: return FixtureEnum.valueOf((String) raw);
            case MODEL:
                return fixtureModel(castObject(raw), nestedDescriptor, schema);
            default: throw new AssertionError("unhandled scalar kind");
        }
    }

    private static Object controlValue(Object raw, boolean scalarFloat) {
        if (raw instanceof Map<?, ?>) {
            Map<String, Object> object = castObject(raw);
            if (object.containsKey("$nonFinite")) {
                String value = string(object, "$nonFinite");
                return "NaN".equals(value) ? Double.NaN : Double.POSITIVE_INFINITY;
            }
            if (object.containsKey("$testValue")) {
                switch (string(object, "$testValue")) {
                    case "shared-object": {
                        LinkedHashMap<String, Object> leaf = new LinkedHashMap<>();
                        leaf.put("leaf", 1L);
                        LinkedHashMap<String, Object> shared = new LinkedHashMap<>();
                        shared.put("left", leaf);
                        shared.put("right", leaf);
                        return shared;
                    }
                    case "cyclic-object": {
                        LinkedHashMap<String, Object> cycle = new LinkedHashMap<>();
                        cycle.put("self", cycle);
                        return cycle;
                    }
                    case "cyclic-list": {
                        ArrayList<Object> cycle = new ArrayList<>();
                        cycle.add(cycle);
                        return cycle;
                    }
                    default: throw new AssertionError("unknown fixture control");
                }
            }
            LinkedHashMap<String, Object> converted = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : object.entrySet()) {
                converted.put(entry.getKey(), controlValue(entry.getValue(), false));
            }
            return converted;
        }
        if (raw instanceof List<?>) {
            ArrayList<Object> converted = new ArrayList<>();
            for (Object value : (List<?>) raw) {
                converted.add(controlValue(value, false));
            }
            return converted;
        }
        if (raw instanceof BigDecimal && !scalarFloat) {
            return raw;
        }
        if (scalarFloat && raw instanceof Number) {
            return ((Number) raw).doubleValue();
        }
        return raw;
    }

    private static void assertGeneratorCounts(
            Map<String, Object> data, CountingGenerators generators) {
        if (!data.containsKey("expectedGeneratorCalls")) {
            return;
        }
        Map<String, Object> expected = object(data, "expectedGeneratorCalls");
        assertEquals(number(expected, "now").intValue(), generators.nowCalls.get());
        assertEquals(number(expected, "uuid").intValue(), generators.uuidCalls.get());
        assertEquals(number(expected, "cuid").intValue(), generators.cuidCalls.get());
    }

    private static List<FixtureCase> loadCases() throws IOException {
        Path root = conformanceRoot();
        ArrayList<FixtureCase> cases = new ArrayList<>();
        for (String group : new String[] {"serialization", "validation"}) {
            Path directory = root.resolve(group);
            List<Path> files = new ArrayList<>();
            try (Stream<Path> entries = Files.list(directory)) {
                entries.filter(path -> path.getFileName().toString().endsWith(".json"))
                        .sorted().forEach(files::add);
            }
            for (Path file : files) {
                Map<String, Object> rootObject = readObject(file);
                assertEquals(2, number(rootObject, "formatVersion").intValue());
                for (Object rawCase : array(rootObject, "cases")) {
                    Map<String, Object> data = castObject(rawCase);
                    ErrorExpectation error = data.get("expectedError") == null ? null
                            : new ErrorExpectation(
                                    string(castObject(data.get("expectedError")), "code"),
                                    string(castObject(data.get("expectedError")), "path"));
                    cases.add(new FixtureCase(
                            group + "/" + file.getFileName(),
                            string(data, "caseId"), data, error));
                }
            }
        }
        return cases;
    }

    private static Path conformanceRoot() {
        Path current = Paths.get(System.getProperty("user.dir"));
        Path direct = current.resolve("sdk/conformance/v2");
        if (Files.isDirectory(direct)) return direct;
        Path parent = current.resolve("../conformance/v2").normalize();
        if (Files.isDirectory(parent)) return parent;
        Path grandparent = current.resolve("../../conformance/v2").normalize();
        if (Files.isDirectory(grandparent)) return grandparent;
        throw new AssertionError("sdk/conformance/v2 is not reachable from the test working directory");
    }

    private static Map<String, Object> readObject(Path file) throws IOException {
        try (JsonParser parser = new JsonFactory().createParser(file.toFile())) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("fixture root must be an object");
            }
            return readCurrentObject(parser);
        }
    }

    private static Map<String, Object> readCurrentObject(JsonParser parser) throws IOException {
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
            case START_OBJECT: return readCurrentObject(parser);
            case START_ARRAY: {
                ArrayList<Object> values = new ArrayList<>();
                while (parser.nextToken() != JsonToken.END_ARRAY) values.add(readValue(parser));
                return values;
            }
            case VALUE_STRING: return parser.getText();
            case VALUE_TRUE: return Boolean.TRUE;
            case VALUE_FALSE: return Boolean.FALSE;
            case VALUE_NULL: return null;
            case VALUE_NUMBER_INT: return parser.getLongValue();
            case VALUE_NUMBER_FLOAT: return parser.getDecimalValue();
            default: throw new IOException("unsupported fixture token");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castObject(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castArray(Object value) {
        return (List<Object>) value;
    }

    private static Map<String, Object> object(Map<String, Object> values, String name) {
        return castObject(values.get(name));
    }

    private static List<Object> array(Map<String, Object> values, String name) {
        return castArray(values.get(name));
    }

    private static String string(Map<String, Object> values, String name) {
        return (String) values.get(name);
    }

    private static Number number(Map<String, Object> values, String name) {
        return (Number) values.get(name);
    }

    private static boolean bool(Map<String, Object> values, String name) {
        return (Boolean) values.get(name);
    }

    private static final class FixtureModel implements RepostModel {
        private final Map<String, Object> values;
        private final ModelDescriptor descriptor;

        private FixtureModel(Map<String, Object> values, ModelDescriptor descriptor) {
            this.values = values;
            this.descriptor = descriptor;
        }

        @Override
        public boolean __repostIsPresent(int fieldIndex) {
            return values.containsKey(descriptor.getFields().get(fieldIndex).getSchemaName());
        }

        @Override
        public Object __repostValue(int fieldIndex) {
            return values.get(descriptor.getFields().get(fieldIndex).getSchemaName());
        }
    }

    private static final class CountingGenerators implements DefaultValueGenerators {
        private final Map<String, Object> generators;
        private final AtomicInteger nowCalls = new AtomicInteger();
        private final AtomicInteger uuidCalls = new AtomicInteger();
        private final AtomicInteger cuidCalls = new AtomicInteger();

        private CountingGenerators(Map<String, Object> data) {
            generators = data.get("generators") == null
                    ? Collections.emptyMap() : object(data, "generators");
        }

        @Override
        public Instant now() {
            int index = nowCalls.getAndIncrement();
            return Instant.parse(sequence("now", index, "2026-01-01T00:00:00.000Z"));
        }

        @Override
        public String uuid() {
            return sequence("uuid", uuidCalls.getAndIncrement(),
                    "00000000-0000-4000-8000-000000000000");
        }

        @Override
        public String cuid() {
            return sequence("cuid", cuidCalls.getAndIncrement(), "c00000000000000000000000");
        }

        private String sequence(String name, int index, String fallback) {
            if (!generators.containsKey(name)) return fallback;
            return (String) castArray(generators.get(name)).get(index);
        }
    }

    private static final class FixtureCase {
        private final String source;
        private final String caseId;
        private final Map<String, Object> caseData;
        private final ErrorExpectation expectedError;

        private FixtureCase(
                String source,
                String caseId,
                Map<String, Object> caseData,
                ErrorExpectation expectedError) {
            this.source = source;
            this.caseId = caseId;
            this.caseData = caseData;
            this.expectedError = expectedError;
        }
    }

    private static final class ErrorExpectation {
        private final String code;
        private final String path;

        private ErrorExpectation(String code, String path) {
            this.code = code;
            this.path = path;
        }
    }

    private static final class FixtureDescriptorException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;
        private final String path;

        private FixtureDescriptorException(String code, String path) {
            super(null, null, false, false);
            this.code = code;
            this.path = path;
        }
    }
}
