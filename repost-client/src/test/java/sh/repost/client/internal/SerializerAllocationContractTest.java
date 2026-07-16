package sh.repost.client.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import sh.repost.client.DefaultValueGenerators;
import sh.repost.client.DeliveryState;
import sh.repost.client.RepostErrorCode;
import sh.repost.client.RepostErrorDetails;
import sh.repost.client.RepostModel;
import sh.repost.client.descriptor.FieldDescriptor;
import sh.repost.client.descriptor.ModelDescriptor;
import sh.repost.client.descriptor.ScalarKind;
import sh.repost.client.descriptor.SchemaDescriptor;
import sh.repost.client.error.RepostConfigurationException;
import sh.repost.client.error.RepostSerializationException;
import sh.repost.client.error.RepostValidationException;

final class SerializerAllocationContractTest {
    private static final String UUID = "00000000-0000-4000-8000-000000000001";
    private static final String CUID = "csequence000000000000001";

    @Test
    void preflightReadsPresenceAndValueExactlyOnceAndOwnsTheFirstSnapshot() {
        ChangingModel model = new ChangingModel();

        byte[] result = Serializer.serializeModel(
                scalarSchema(ScalarKind.STRING), "Payload", model, generators());

        assertEquals("{\"value\":\"aa\"}", utf8(result));
        assertEquals(1, model.presenceReads);
        assertEquals(1, model.valueReads);
    }

    @Test
    void preflightDoesNotRereadASameLengthChangingValue() {
        ChangingValueModel model = new ChangingValueModel();

        byte[] result = Serializer.serializeModel(
                scalarSchema(ScalarKind.STRING), "Payload", model, generators());

        assertEquals("{\"value\":\"aa\"}", utf8(result));
        assertEquals(1, model.presenceReads);
        assertEquals(1, model.valueReads);
    }

    @Test
    void preflightNeverCallsAListSizeOrIteratesItTwice() {
        OneShotCollection values = new OneShotCollection("first", "second");

        byte[] result = Serializer.serializeModel(
                listSchema(), "Payload", model(values), generators());

        assertEquals("{\"value\":[\"first\",\"second\"]}", utf8(result));
        assertEquals(1, values.iteratorCalls);
        assertEquals(0, values.sizeCalls);
    }

    @Test
    void rawMapCapturesEachEntryKeyAndValueOnceBeforeCanonicalSort() {
        OneShotMap raw = new OneShotMap("safe", "value");

        byte[] result = Serializer.serializeModel(
                scalarSchema(ScalarKind.JSON), "Payload", model(raw), generators());

        assertEquals("{\"value\":{\"safe\":\"value\"}}", utf8(result));
        assertEquals(1, raw.entrySetCalls);
        assertEquals(1, raw.entry.keyReads);
        assertEquals(1, raw.entry.valueReads);
    }

    @Test
    void rawMapReadsNoValuesWhenALaterKeyIsInvalid() {
        InvalidLateMap raw = new InvalidLateMap();

        assertThrows(
                RepostValidationException.class,
                () -> Serializer.serializeModel(
                        scalarSchema(ScalarKind.JSON), "Payload", model(raw), generators()));

        assertEquals(1, raw.entrySetCalls);
        assertEquals(2, raw.entries.size());
        assertEquals(0, raw.entries.get(0).valueReads);
        assertEquals(0, raw.entries.get(1).valueReads);
    }

    @Test
    void exactRequestLimitUsesOneExactArrayForModelEnvelopeAndRawJson() {
        assertStringBoundary(false);
        assertStringBoundary(true);
        assertRawJsonBoundary();

        for (Class<?> nested : Serializer.class.getDeclaredClasses()) {
            assertFalse(Arrays.stream(nested.getDeclaredFields())
                    .map(java.lang.reflect.Field::getType)
                    .anyMatch(ByteArrayOutputStream.class::isAssignableFrom));
        }
    }

    @Test
    void preservesCanonicalNumericSpellingsAndFloatingPointEdges() {
        LinkedHashMap<String, Object> raw = new LinkedHashMap<>();
        raw.put("scale", new BigDecimal("1.2300"));
        raw.put("exp", new BigDecimal("1E+100"));
        raw.put("big", new BigInteger("9223372036854775808"));
        raw.put("min", Long.MIN_VALUE);
        raw.put("max", Long.MAX_VALUE);
        raw.put("subnormal", Double.MIN_VALUE);
        raw.put("negativeZero", -0.0d);

        byte[] result = Serializer.serializeModel(
                scalarSchema(ScalarKind.JSON), "Payload", model(raw), generators());

        assertEquals(
                "{\"value\":{\"big\":9223372036854775808,\"exp\":1E+100,"
                        + "\"max\":9223372036854775807,\"min\":-9223372036854775808,"
                        + "\"negativeZero\":-0.0,\"scale\":1.2300,"
                        + "\"subnormal\":4.9E-324}}",
                utf8(result));
    }

    @Test
    void emitsArbitraryPrecisionNumbersWithoutCallingTheirDecimalRenderers() {
        NoStringBigInteger integer =
                new NoStringBigInteger("1234567890123456789012345678901234567890");
        NoStringBigDecimal decimal =
                new NoStringBigDecimal("12345678901234567890.0012300");
        LinkedHashMap<String, Object> raw = new LinkedHashMap<>();
        raw.put("integer", integer);
        raw.put("decimal", decimal);

        byte[] result = Serializer.serializeModel(
                scalarSchema(ScalarKind.JSON), "Payload", model(raw), generators());

        assertEquals(
                "{\"value\":{\"decimal\":12345678901234567890.0012300,"
                        + "\"integer\":1234567890123456789012345678901234567890}}",
                utf8(result));
        assertEquals(0, integer.decimalRenderCalls);
        assertEquals(0, decimal.decimalRenderCalls);
        assertEquals(1, integer.byteArrayCalls);
        assertEquals(1, decimal.scaleCalls);
        assertEquals(1, decimal.unscaledValueCalls);
    }

    @Test
    void emitsPowerOfTenAndBigDecimalLayoutBoundariesExactly() {
        List<Object> raw = Arrays.asList(
                new BigInteger("999999999999999999"),
                new BigInteger("1000000000000000000"),
                new BigInteger("1000000000000000001"),
                new BigDecimal("0.000001"),
                new BigDecimal("1E-7"),
                new BigDecimal("1.2300"),
                new BigDecimal(BigInteger.ONE, Integer.MIN_VALUE),
                new BigDecimal(BigInteger.ONE, Integer.MAX_VALUE));

        byte[] result = Serializer.serializeModel(
                scalarSchema(ScalarKind.JSON), "Payload", model(raw), generators());

        assertEquals(
                "{\"value\":[999999999999999999,1000000000000000000,"
                        + "1000000000000000001,0.000001,1E-7,1.2300,"
                        + "1E+2147483648,1E-2147483647]}",
                utf8(result));
    }

    @Test
    void directDecimalEmissionMatchesJdkCanonicalSpellings() {
        Random random = new Random(0x5eedL);
        for (int index = 0; index < 100; index++) {
            BigInteger magnitude = new BigInteger(1 + random.nextInt(2048), random);
            BigInteger integer = random.nextBoolean() ? magnitude.negate() : magnitude;
            assertEquals(
                    "{\"value\":" + integer.toString() + "}",
                    utf8(Serializer.serializeModel(
                            scalarSchema(ScalarKind.JSON),
                            "Payload",
                            model(integer),
                            generators())));

            int scale = random.nextInt(401) - 200;
            BigDecimal decimal = new BigDecimal(integer, scale);
            assertEquals(
                    "{\"value\":" + decimal.toString() + "}",
                    utf8(Serializer.serializeModel(
                            scalarSchema(ScalarKind.JSON),
                            "Payload",
                            model(decimal),
                            generators())));
        }
    }

    @Test
    void emitsLargeArbitraryPrecisionNumberDirectlyIntoTheExactOutput() {
        BigInteger number = BigInteger.TEN.pow(10_000).add(BigInteger.ONE);

        byte[] result = Serializer.serializeModel(
                scalarSchema(ScalarKind.JSON), "Payload", model(number), generators());

        assertEquals(10_011, result.length);
        assertEquals('{', result[0]);
        assertEquals('1', result[9]);
        assertEquals('0', result[5_009]);
        assertEquals('1', result[10_009]);
        assertEquals('}', result[10_010]);
    }

    @Test
    void arbitraryPrecisionNumberHonorsExactRequestBoundary() {
        int digits = Serializer.MAX_REQUEST_BYTES - 10;
        BigInteger exact = BigInteger.TEN.pow(digits - 1);

        byte[] result = Serializer.serializeModel(
                scalarSchema(ScalarKind.JSON), "Payload", model(exact), generators());

        assertEquals(Serializer.MAX_REQUEST_BYTES, result.length);
        assertEquals('1', result[9]);
        assertEquals('}', result[result.length - 1]);

        RepostSerializationException error = assertThrows(
                RepostSerializationException.class,
                () -> Serializer.serializeModel(
                        scalarSchema(ScalarKind.JSON),
                        "Payload",
                        model(exact.multiply(BigInteger.TEN)),
                        generators()));
        assertEquals(RepostErrorCode.REQUEST_TOO_LARGE, error.getErrorCode());
    }

    @Test
    void writesControlsEscapesUnicodeAndAstralScalarsExactly() {
        String value = "\b\t\n\f\r\"\\\u0001é😀";

        byte[] result = Serializer.serializeModel(
                scalarSchema(ScalarKind.STRING), "Payload", model(value), generators());

        assertArrayEquals(
                ("{\"value\":\"\\b\\t\\n\\f\\r\\\"\\\\\\u0001é😀\"}")
                        .getBytes(StandardCharsets.UTF_8),
                result);
    }

    @Test
    void canonicalComparatorRetainsPrivateUtf8BytesAcrossRepeatedSorts() {
        Object first = utf8Key("é");
        Object second = utf8Key("z");
        byte[] firstBytes = rawUtf8(first);
        byte[] secondBytes = rawUtf8(second);
        java.util.Comparator<Object> comparator = utf8Comparator();

        for (int index = 0; index < 100; index++) {
            assertTrue(comparator.compare(first, second) > 0);
            assertTrue(comparator.compare(second, first) < 0);
        }

        assertTrue(firstBytes == rawUtf8(first));
        assertTrue(secondBytes == rawUtf8(second));
        assertFalse(Arrays.stream(first.getClass().getDeclaredMethods())
                .anyMatch(method -> method.getReturnType() == byte[].class));
    }

    @Test
    void keyFactoryRejectsNonScalarTextAndUncachedKeysKeepCanonicalOrder() {
        assertThrows(
                IllegalArgumentException.class,
                () -> JsonValueWriter.utf8Key("\ud800"));
        Object bmp = utf8Key("\ue000", false);
        Object astral = utf8Key("😀", false);

        assertTrue(utf8Comparator().compare(bmp, astral) < 0);
        assertNull(rawUtf8(bmp));
        assertNull(rawUtf8(astral));
    }

    @Test
    void rejectsKnownOversizeBigIntegerBeforeDecimalRendering() {
        OversizedBigInteger oversized = new OversizedBigInteger();

        RepostSerializationException error = assertThrows(
                RepostSerializationException.class,
                () -> Serializer.serializeModel(
                        scalarSchema(ScalarKind.JSON), "Payload", model(oversized), generators()));

        assertEquals(RepostErrorCode.REQUEST_TOO_LARGE, error.getErrorCode());
        assertEquals(0, oversized.decimalRenderCalls);
    }

    @Test
    void sanitizesCallerCallbackFailuresWithoutCauseClassOrMessage() {
        RepostSerializationException error = assertThrows(
                RepostSerializationException.class,
                () -> Serializer.serializeModel(
                        scalarSchema(ScalarKind.STRING),
                        "Payload",
                        new ThrowingModel(),
                        generators()));

        assertEquals(RepostErrorCode.SERIALIZATION, error.getErrorCode());
        assertNull(error.getCause());
        StringWriter rendered = new StringWriter();
        error.printStackTrace(new PrintWriter(rendered));
        assertFalse(rendered.toString().contains("dynamic-sentinel-message"));
        assertFalse(rendered.toString().contains(SentinelFailure.class.getName()));
    }

    @Test
    void doesNotPreserveAnSdkExceptionSpoofedByACallerCallback() {
        RepostSerializationException error = assertThrows(
                RepostSerializationException.class,
                () -> Serializer.serializeModel(
                        scalarSchema(ScalarKind.STRING),
                        "Payload",
                        new SpoofingModel(),
                        generators()));

        assertEquals(RepostErrorCode.SERIALIZATION, error.getErrorCode());
        assertNull(error.getCause());
    }

    private static void assertStringBoundary(boolean envelope) {
        SchemaDescriptor schema = scalarSchema(ScalarKind.STRING);
        String prefix = envelope
                ? "{\"type\":\"event\",\"timestamp\":\"1970-01-01T00:00:00.000Z\","
                        + "\"data\":{\"value\":\""
                : "{\"value\":\"";
        String suffix = envelope ? "\"}}" : "\"}";
        String exact = "a".repeat(Serializer.MAX_REQUEST_BYTES
                - prefix.getBytes(StandardCharsets.UTF_8).length
                - suffix.getBytes(StandardCharsets.UTF_8).length);
        byte[] result = envelope
                ? Serializer.serializeEnvelope(
                        schema, "Payload", "event", model(exact), generators())
                : Serializer.serializeModel(schema, "Payload", model(exact), generators());
        assertEquals(Serializer.MAX_REQUEST_BYTES, result.length);

        RepostSerializationException error = assertThrows(
                RepostSerializationException.class,
                () -> {
                    if (envelope) {
                        Serializer.serializeEnvelope(
                                schema, "Payload", "event", model(exact + "a"), generators());
                    } else {
                        Serializer.serializeModel(
                                schema, "Payload", model(exact + "a"), generators());
                    }
                });
        assertEquals(RepostErrorCode.REQUEST_TOO_LARGE, error.getErrorCode());
    }

    private static void assertRawJsonBoundary() {
        SchemaDescriptor schema = scalarSchema(ScalarKind.JSON);
        String prefix = "{\"value\":{\"k\":\"";
        String suffix = "\"}}";
        String exact = "a".repeat(Serializer.MAX_REQUEST_BYTES
                - prefix.getBytes(StandardCharsets.UTF_8).length
                - suffix.getBytes(StandardCharsets.UTF_8).length);
        Map<String, Object> raw = Collections.singletonMap("k", exact);
        assertEquals(Serializer.MAX_REQUEST_BYTES,
                Serializer.serializeModel(schema, "Payload", model(raw), generators()).length);

        Map<String, Object> tooLarge = Collections.singletonMap("k", exact + "a");
        RepostSerializationException error = assertThrows(
                RepostSerializationException.class,
                () -> Serializer.serializeModel(
                        schema, "Payload", model(tooLarge), generators()));
        assertEquals(RepostErrorCode.REQUEST_TOO_LARGE, error.getErrorCode());
    }

    private static Object utf8Key(String value) {
        try {
            java.lang.reflect.Method method =
                    JsonValueWriter.class.getDeclaredMethod("utf8Key", String.class);
            method.setAccessible(true);
            return method.invoke(null, value);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static Object utf8Key(String value, boolean cacheBytes) {
        try {
            java.lang.reflect.Method method = JsonValueWriter.class.getDeclaredMethod(
                    "utf8Key", String.class, boolean.class);
            method.setAccessible(true);
            return method.invoke(null, value, cacheBytes);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static java.util.Comparator<Object> utf8Comparator() {
        try {
            java.lang.reflect.Field field =
                    JsonValueWriter.class.getDeclaredField("UNSIGNED_UTF8");
            field.setAccessible(true);
            return (java.util.Comparator<Object>) field.get(null);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static byte[] rawUtf8(Object key) {
        try {
            java.lang.reflect.Field field = key.getClass().getDeclaredField("utf8");
            field.setAccessible(true);
            return (byte[]) field.get(key);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static SchemaDescriptor scalarSchema(ScalarKind kind) {
        FieldDescriptor field = FieldDescriptor.builder(0, "value", "value", kind)
                .requiredInInput(true)
                .build();
        return SchemaDescriptor.builder(2)
                .addModel(ModelDescriptor.of("Payload", Collections.singletonList(field)))
                .build();
    }

    private static SchemaDescriptor listSchema() {
        FieldDescriptor field = FieldDescriptor.builder(0, "value", "value", ScalarKind.STRING)
                .requiredInInput(true)
                .list(true)
                .build();
        return SchemaDescriptor.builder(2)
                .addModel(ModelDescriptor.of("Payload", Collections.singletonList(field)))
                .build();
    }

    private static RepostModel model(Object value) {
        return new RepostModel() {
            @Override
            public boolean __repostIsPresent(int fieldIndex) {
                return true;
            }

            @Override
            public Object __repostValue(int fieldIndex) {
                return value;
            }
        };
    }

    private static DefaultValueGenerators generators() {
        return DefaultValueGenerators.fixed(Instant.EPOCH, UUID, CUID);
    }

    private static String utf8(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }

    private static final class ChangingModel implements RepostModel {
        private int presenceReads;
        private int valueReads;

        @Override
        public boolean __repostIsPresent(int fieldIndex) {
            presenceReads++;
            return presenceReads == 1;
        }

        @Override
        public Object __repostValue(int fieldIndex) {
            valueReads++;
            if (valueReads > 1) {
                throw new SentinelFailure("dynamic-sentinel-message");
            }
            return "aa";
        }
    }

    private static final class ChangingValueModel implements RepostModel {
        private int presenceReads;
        private int valueReads;

        @Override
        public boolean __repostIsPresent(int fieldIndex) {
            presenceReads++;
            return true;
        }

        @Override
        public Object __repostValue(int fieldIndex) {
            valueReads++;
            if (valueReads != 1) {
                throw new SentinelFailure("dynamic-sentinel-message");
            }
            return "aa";
        }
    }

    private static final class ThrowingModel implements RepostModel {
        @Override
        public boolean __repostIsPresent(int fieldIndex) {
            throw new SentinelFailure("dynamic-sentinel-message");
        }

        @Override
        public Object __repostValue(int fieldIndex) {
            throw new AssertionError("must not read");
        }
    }

    private static final class SpoofingModel implements RepostModel {
        @Override
        public boolean __repostIsPresent(int fieldIndex) {
            throw new RepostConfigurationException(
                    RepostErrorDetails.builder(
                            RepostErrorCode.CONFIGURATION, DeliveryState.NOT_SENT).build());
        }

        @Override
        public Object __repostValue(int fieldIndex) {
            throw new AssertionError("must not read");
        }
    }

    private static final class OneShotCollection extends AbstractList<Object> {
        private final List<Object> values;
        private int iteratorCalls;
        private int sizeCalls;

        private OneShotCollection(Object... values) {
            this.values = Arrays.asList(values);
        }

        @Override
        public Iterator<Object> iterator() {
            iteratorCalls++;
            if (iteratorCalls != 1) {
                throw new SentinelFailure("dynamic-sentinel-message");
            }
            return values.iterator();
        }

        @Override
        public int size() {
            sizeCalls++;
            throw new SentinelFailure("dynamic-sentinel-message");
        }

        @Override
        public Object get(int index) {
            throw new SentinelFailure("dynamic-sentinel-message");
        }
    }

    private static final class OneShotMap extends AbstractMap<String, Object> {
        private final OneShotEntry entry;
        private int entrySetCalls;

        private OneShotMap(String key, Object value) {
            entry = new OneShotEntry(key, value);
        }

        @Override
        public Set<Entry<String, Object>> entrySet() {
            entrySetCalls++;
            if (entrySetCalls != 1) {
                throw new SentinelFailure("dynamic-sentinel-message");
            }
            return Collections.singleton(entry);
        }
    }

    private static final class OneShotEntry implements Map.Entry<String, Object> {
        private final String key;
        private final Object value;
        private int keyReads;
        private int valueReads;

        private OneShotEntry(String key, Object value) {
            this.key = key;
            this.value = value;
        }

        @Override
        public String getKey() {
            keyReads++;
            if (keyReads != 1) {
                return "\ud800";
            }
            return key;
        }

        @Override
        public Object getValue() {
            valueReads++;
            if (valueReads != 1) {
                throw new SentinelFailure("dynamic-sentinel-message");
            }
            return value;
        }

        @Override
        public Object setValue(Object replacement) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class InvalidLateMap extends AbstractMap<Object, Object> {
        private final List<CountingEntry> entries = Arrays.asList(
                new CountingEntry("safe", "value"),
                new CountingEntry("\ud800", "must-not-read"));
        private int entrySetCalls;

        @Override
        public Set<Entry<Object, Object>> entrySet() {
            entrySetCalls++;
            @SuppressWarnings({"unchecked", "rawtypes"})
            Set<Entry<Object, Object>> result =
                    (Set) new java.util.LinkedHashSet<>(entries);
            return result;
        }
    }

    private static final class CountingEntry implements Map.Entry<Object, Object> {
        private final Object key;
        private final Object value;
        private int valueReads;

        private CountingEntry(Object key, Object value) {
            this.key = key;
            this.value = value;
        }

        @Override
        public Object getKey() {
            return key;
        }

        @Override
        public Object getValue() {
            valueReads++;
            return value;
        }

        @Override
        public Object setValue(Object replacement) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class SentinelFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private SentinelFailure(String message) {
            super(message);
        }
    }

    private static final class OversizedBigInteger extends BigInteger {
        private static final long serialVersionUID = 1L;
        private int decimalRenderCalls;

        private OversizedBigInteger() {
            super("1");
        }

        @Override
        public int bitLength() {
            return 100_000_000;
        }

        @Override
        public int signum() {
            return 1;
        }

        @Override
        public String toString() {
            decimalRenderCalls++;
            throw new SentinelFailure("dynamic-sentinel-message");
        }
    }

    private static final class NoStringBigInteger extends BigInteger {
        private static final long serialVersionUID = 1L;
        private int decimalRenderCalls;
        private int byteArrayCalls;

        private NoStringBigInteger(String value) {
            super(value);
        }

        @Override
        public String toString() {
            decimalRenderCalls++;
            throw new SentinelFailure("decimal renderer must not be called");
        }

        @Override
        public byte[] toByteArray() {
            byteArrayCalls++;
            return super.toByteArray();
        }
    }

    private static final class NoStringBigDecimal extends BigDecimal {
        private static final long serialVersionUID = 1L;
        private int decimalRenderCalls;
        private int scaleCalls;
        private int unscaledValueCalls;

        private NoStringBigDecimal(String value) {
            super(value);
        }

        @Override
        public String toString() {
            decimalRenderCalls++;
            throw new SentinelFailure("decimal renderer must not be called");
        }

        @Override
        public int scale() {
            scaleCalls++;
            return super.scale();
        }

        @Override
        public BigInteger unscaledValue() {
            unscaledValueCalls++;
            return super.unscaledValue();
        }
    }
}
