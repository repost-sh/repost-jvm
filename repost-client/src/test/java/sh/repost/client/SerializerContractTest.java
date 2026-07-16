package sh.repost.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import sh.repost.client.descriptor.DefaultSpec;
import sh.repost.client.descriptor.FieldDescriptor;
import sh.repost.client.descriptor.ModelDescriptor;
import sh.repost.client.descriptor.ScalarKind;
import sh.repost.client.descriptor.SchemaDescriptor;
import sh.repost.client.error.RepostConfigurationException;
import sh.repost.client.error.RepostDescriptorVersionException;
import sh.repost.client.error.RepostSerializationException;
import sh.repost.client.error.RepostValidationException;
import sh.repost.client.internal.Serializer;

final class SerializerContractTest {
    private enum Status { ACTIVE }

    @Test
    void writesDeclarationOrderMappedEnumsAndOperationDefaults() {
        SchemaDescriptor schema = schema(
                field(0, "status", ScalarKind.ENUM).descriptorId("Status")
                        .requiredInInput(true).build(),
                field(1, "createdAt", ScalarKind.DATETIME)
                        .defaultSpec(DefaultSpec.now()).build(),
                field(2, "updatedAt", ScalarKind.DATETIME)
                        .defaultSpec(DefaultSpec.now()).build());
        AtomicInteger nowCalls = new AtomicInteger();
        DefaultValueGenerators generators = generators(nowCalls);
        RepostModel model = model(Collections.singletonMap("status", Status.ACTIVE), schema);

        byte[] result = Serializer.serializeEnvelope(
                schema, "Payload", "payload.sent", model, generators);

        assertEquals(
                "{\"type\":\"payload.sent\",\"timestamp\":\"2026-01-01T00:00:00.123Z\","
                        + "\"data\":{\"status\":\"active_wire\","
                        + "\"createdAt\":\"2026-01-01T00:00:00.123Z\","
                        + "\"updatedAt\":\"2026-01-01T00:00:00.123Z\"}}",
                new String(result, StandardCharsets.UTF_8));
        assertEquals(1, nowCalls.get());
    }

    @Test
    void validatesNestedModelsListsAndJsonWithSafePaths() {
        FieldDescriptor authorName = field(0, "name", ScalarKind.STRING)
                .requiredInInput(true).build();
        ModelDescriptor author = ModelDescriptor.of(
                "Author", Collections.singletonList(authorName));
        FieldDescriptor authors = field(0, "authors", ScalarKind.MODEL)
                .descriptorId("Author").requiredInInput(true).list(true).build();
        FieldDescriptor metadata = field(1, "metadata", ScalarKind.JSON)
                .requiredInInput(true).nullableInInput(true).build();
        ModelDescriptor payload = ModelDescriptor.of("Payload", Arrays.asList(authors, metadata));
        SchemaDescriptor schema = SchemaDescriptor.builder(2)
                .addModel(author).addModel(payload).build();
        RepostModel invalidAuthor = model(
                Collections.singletonMap("name", null), author);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("authors", Collections.singletonList(invalidAuthor));
        values.put("metadata", Collections.emptyMap());

        RepostValidationException error = assertThrows(
                RepostValidationException.class,
                () -> Serializer.serializeModel(
                        schema, "Payload", model(values, payload), generators(new AtomicInteger())));

        assertEquals(ValidationIssueCode.NULL_NOT_ALLOWED,
                error.getValidationIssues().get(0).getCode());
        assertEquals("$.authors[0].name", error.getValidationIssues().get(0).getPath());

        LinkedHashMap<String, Object> cyclic = new LinkedHashMap<>();
        cyclic.put("self", cyclic);
        values.put("authors", Collections.singletonList(model(
                Collections.singletonMap("name", "ok"), author)));
        values.put("metadata", cyclic);
        RepostValidationException cycle = assertThrows(
                RepostValidationException.class,
                () -> Serializer.serializeModel(
                        schema, "Payload", model(values, payload), generators(new AtomicInteger())));
        assertEquals(ValidationIssueCode.CYCLE, cycle.getValidationIssues().get(0).getCode());
        assertEquals("$.metadata[{*}]", cycle.getValidationIssues().get(0).getPath());
    }

    @Test
    void rejectsNonFiniteInvalidUnicodeAndOversizedOutput() {
        SchemaDescriptor floatSchema = schema(
                field(0, "value", ScalarKind.FLOAT64).requiredInInput(true).build());
        assertIssue(
                ValidationIssueCode.NON_FINITE,
                "$.value",
                floatSchema,
                Collections.singletonMap("value", Double.NaN));

        SchemaDescriptor stringSchema = schema(
                field(0, "value", ScalarKind.STRING).requiredInInput(true).build());
        assertIssue(
                ValidationIssueCode.INVALID_UNICODE,
                "$.value",
                stringSchema,
                Collections.singletonMap("value", "\ud800"));

        String exact = "a".repeat(Serializer.MAX_REQUEST_BYTES - 12);
        assertEquals(Serializer.MAX_REQUEST_BYTES, Serializer.serializeModel(
                stringSchema,
                "Payload",
                model(Collections.singletonMap("value", exact), stringSchema),
                generators(new AtomicInteger())).length);
        String oversized = exact + "a";
        assertThrows(
                RepostSerializationException.class,
                () -> Serializer.serializeModel(
                        stringSchema,
                        "Payload",
                        model(Collections.singletonMap("value", oversized), stringSchema),
                        generators(new AtomicInteger())));
    }

    @Test
    void validatesDescriptorVersionDateRangeAndClosedScalarTypes() {
        SchemaDescriptor newer = SchemaDescriptor.builder(3)
                .addModel(ModelDescriptor.of("Payload", Collections.emptyList()))
                .build();
        assertThrows(RepostDescriptorVersionException.class,
                () -> Serializer.serializeModel(
                        newer, "Payload", model(Collections.emptyMap(), newer),
                        generators(new AtomicInteger())));
        AtomicInteger newerNowCalls = new AtomicInteger();
        assertThrows(RepostDescriptorVersionException.class,
                () -> Serializer.serializeEnvelope(
                        newer,
                        "Payload",
                        "payload.sent",
                        model(Collections.emptyMap(), newer),
                        generators(newerNowCalls)));
        assertEquals(0, newerNowCalls.get());

        SchemaDescriptor dateSchema = schema(
                field(0, "value", ScalarKind.DATETIME).requiredInInput(true).build());
        assertIssue(ValidationIssueCode.INVALID_DATETIME, "$.value", dateSchema,
                Collections.singletonMap("value", Instant.parse("+10000-01-01T00:00:00Z")));

        SchemaDescriptor intSchema = schema(
                field(0, "value", ScalarKind.INT64).requiredInInput(true).build());
        assertIssue(ValidationIssueCode.TYPE_MISMATCH, "$.value", intSchema,
                Collections.singletonMap("value", Integer.valueOf(1)));

        SchemaDescriptor enumSchema = schema(
                field(0, "value", ScalarKind.ENUM).descriptorId("Status")
                        .requiredInInput(true).build());
        assertIssue(ValidationIssueCode.TYPE_MISMATCH, "$.value", enumSchema,
                Collections.singletonMap("value", "ACTIVE"));
    }

    @Test
    void retainsFirstThirtyTwoIssuesAndCountsTheRest() {
        ArrayList<FieldDescriptor> fields = new ArrayList<>();
        for (int index = 0; index < 40; index++) {
            fields.add(FieldDescriptor.builder(index, "field" + index, "field" + index,
                    ScalarKind.STRING).requiredInInput(true).build());
        }
        SchemaDescriptor schema = SchemaDescriptor.builder(2)
                .addModel(ModelDescriptor.of("Payload", fields)).build();

        RepostValidationException error = assertThrows(RepostValidationException.class,
                () -> Serializer.serializeModel(
                        schema, "Payload", model(Collections.emptyMap(), schema),
                        generators(new AtomicInteger())));

        assertEquals(32, error.getValidationIssues().size());
        assertEquals(40, error.getIssueCount());
        assertEquals(true, error.isIssuesTruncated());
        assertEquals("$.field0", error.getValidationIssues().get(0).getPath());
        assertEquals("$.field31", error.getValidationIssues().get(31).getPath());
    }

    @Test
    void sanitizesInvalidDefaultGeneratorResults() {
        SchemaDescriptor nowSchema = schema(
                field(0, "createdAt", ScalarKind.DATETIME)
                        .defaultSpec(DefaultSpec.now()).build());
        DefaultValueGenerators invalidNow = DefaultValueGenerators.fixed(
                Instant.parse("+10000-01-01T00:00:00Z"),
                "00000000-0000-4000-8000-000000000001",
                "csequence000000000000001");
        assertThrows(RepostConfigurationException.class,
                () -> Serializer.serializeEnvelope(
                        nowSchema,
                        "Payload",
                        "payload.sent",
                        model(Collections.emptyMap(), nowSchema),
                        invalidNow));

        SchemaDescriptor uuidSchema = schema(
                field(0, "id", ScalarKind.STRING)
                        .defaultSpec(DefaultSpec.uuid()).build());
        DefaultValueGenerators invalidUuid = DefaultValueGenerators.fixed(
                Instant.EPOCH, "not-a-uuid", "csequence000000000000001");
        assertThrows(RepostConfigurationException.class,
                () -> Serializer.serializeModel(
                        uuidSchema,
                        "Payload",
                        model(Collections.emptyMap(), uuidSchema),
                        invalidUuid));
    }

    @Test
    void canonicalizesRawJsonAndPreservesExactNumericSpellings() {
        SchemaDescriptor schema = schema(
                field(0, "value", ScalarKind.JSON).requiredInInput(true).build());
        LinkedHashMap<String, Object> raw = new LinkedHashMap<>();
        raw.put("z", new java.math.BigDecimal("1.2300"));
        raw.put("a", new java.math.BigInteger("9223372036854775808"));

        byte[] result = Serializer.serializeModel(
                schema,
                "Payload",
                model(Collections.singletonMap("value", raw), schema),
                generators(new AtomicInteger()));

        assertEquals(
                "{\"value\":{\"a\":9223372036854775808,\"z\":1.2300}}",
                new String(result, StandardCharsets.UTF_8));
    }

    private static FieldDescriptor.Builder field(int index, String name, ScalarKind kind) {
        return FieldDescriptor.builder(index, name, name, kind);
    }

    private static SchemaDescriptor schema(FieldDescriptor... fields) {
        LinkedHashMap<String, String> status = new LinkedHashMap<>();
        status.put("ACTIVE", "active_wire");
        return SchemaDescriptor.builder(2)
                .addEnum("Status", status)
                .addModel(ModelDescriptor.of("Payload", Arrays.asList(fields)))
                .build();
    }

    private static RepostModel model(Map<String, Object> values, SchemaDescriptor schema) {
        return model(values, schema.getModels().get("Payload"));
    }

    private static RepostModel model(Map<String, Object> values, ModelDescriptor descriptor) {
        return new RepostModel() {
            @Override
            public boolean __repostIsPresent(int fieldIndex) {
                return values.containsKey(descriptor.getFields().get(fieldIndex).getSchemaName());
            }

            @Override
            public Object __repostValue(int fieldIndex) {
                return values.get(descriptor.getFields().get(fieldIndex).getSchemaName());
            }
        };
    }

    private static DefaultValueGenerators generators(AtomicInteger nowCalls) {
        return new DefaultValueGenerators() {
            @Override
            public Instant now() {
                nowCalls.incrementAndGet();
                return Instant.parse("2026-01-01T00:00:00.123456789Z");
            }

            @Override public String uuid() { return "00000000-0000-4000-8000-000000000001"; }
            @Override public String cuid() { return "csequence000000000000001"; }
        };
    }

    private static void assertIssue(
            ValidationIssueCode code,
            String path,
            SchemaDescriptor schema,
            Map<String, Object> values) {
        RepostValidationException error = assertThrows(
                RepostValidationException.class,
                () -> Serializer.serializeModel(
                        schema, "Payload", model(values, schema), generators(new AtomicInteger())));
        assertEquals(code, error.getValidationIssues().get(0).getCode());
        assertEquals(path, error.getValidationIssues().get(0).getPath());
    }
}
