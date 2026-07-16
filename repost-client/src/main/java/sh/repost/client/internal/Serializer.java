package sh.repost.client.internal;

import java.math.BigInteger;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import sh.repost.client.ClientOptionKey;
import sh.repost.client.ConfigurationIssue;
import sh.repost.client.ConfigurationIssueCode;
import sh.repost.client.DefaultValueGenerators;
import sh.repost.client.DeliveryState;
import sh.repost.client.RepostCauseCategory;
import sh.repost.client.RepostErrorCode;
import sh.repost.client.RepostErrorDetails;
import sh.repost.client.RepostModel;
import sh.repost.client.ValidationIssue;
import sh.repost.client.ValidationIssueCode;
import sh.repost.client.descriptor.DefaultSpec;
import sh.repost.client.descriptor.FieldDescriptor;
import sh.repost.client.descriptor.ModelDescriptor;
import sh.repost.client.descriptor.ScalarKind;
import sh.repost.client.descriptor.SchemaDescriptor;
import sh.repost.client.error.RepostConfigurationException;
import sh.repost.client.error.RepostDescriptorVersionException;
import sh.repost.client.error.RepostSerializationException;
import sh.repost.client.error.RepostValidationException;

/** Descriptor-driven serializer used by generated clients. */
public final class Serializer {
    /** Maximum emitted UTF-8 request body size. */
    public static final int MAX_REQUEST_BYTES = 1_048_576;
    /** Maximum model, list, and JSON nesting depth. */
    public static final int MAX_DEPTH = 100;
    /** Maximum aggregate node count visited during validation. */
    public static final int MAX_NODES = 524_288;

    private static final DateTimeFormatter INSTANT_FORMAT = new DateTimeFormatterBuilder()
            .appendPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'")
            .toFormatter(Locale.ROOT)
            .withZone(ZoneOffset.UTC);
    private static final Pattern UUID_V4 = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
    private static final Pattern CUID2 = Pattern.compile("^[a-z][a-z0-9]{23}$");
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Serializer() { }

    /**
     * Validates and serializes one generated model without an envelope.
     *
     * @param schema generated schema descriptor
     * @param modelId model descriptor ID
     * @param model generated model value
     * @param generators default-value generators
     * @return exact UTF-8 JSON bytes
     */
    public static byte[] serializeModel(
            SchemaDescriptor schema,
            String modelId,
            RepostModel model,
            DefaultValueGenerators generators) {
        return serialize(schema, modelId, null, null, model, generators, null);
    }

    /**
     * Validates and serializes one generated model in the v2 request envelope.
     *
     * @param schema generated schema descriptor
     * @param modelId model descriptor ID
     * @param eventType event wire type
     * @param model generated model value
     * @param generators default-value generators
     * @return exact UTF-8 envelope bytes
     */
    public static byte[] serializeEnvelope(
            SchemaDescriptor schema,
            String modelId,
            String eventType,
            RepostModel model,
            DefaultValueGenerators generators) {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(modelId, "modelId");
        requireSupportedVersion(schema);
        if (!schema.getModels().containsKey(modelId)) {
            throw serializationError(RepostErrorCode.SERIALIZATION);
        }
        Objects.requireNonNull(generators, "generators");
        Instant now = generatedNow(generators);
        return serialize(
                schema,
                modelId,
                Objects.requireNonNull(eventType, "eventType"),
                null,
                model,
                generators,
                now);
    }

    /**
     * Serializes one customer-scoped operation envelope and its identity timestamp.
     *
     * @param schema generated schema descriptor
     * @param modelId model descriptor ID
     * @param eventType event wire type
     * @param customerId customer identifier
     * @param model generated model value
     * @param generators operation default generators
     * @return exact envelope bytes and canonical identity timestamp
     */
    public static SerializedEnvelope serializeOperationEnvelope(
            SchemaDescriptor schema,
            String modelId,
            String eventType,
            String customerId,
            RepostModel model,
            DefaultValueGenerators generators) {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(modelId, "modelId");
        requireSupportedVersion(schema);
        if (!schema.getModels().containsKey(modelId)) {
            throw serializationError(RepostErrorCode.SERIALIZATION);
        }
        Objects.requireNonNull(generators, "generators");
        Instant operationNow = generatedNow(generators);
        byte[] bytes = serialize(
                schema,
                modelId,
                Objects.requireNonNull(eventType, "eventType"),
                Objects.requireNonNull(customerId, "customerId"),
                model,
                generators,
                operationNow);
        return new SerializedEnvelope(bytes, formatInstant(operationNow));
    }

    /** Exact operation-envelope output retained by the runtime without another body copy. */
    public static final class SerializedEnvelope {
        private final byte[] bytes;
        private final String timestamp;

        private SerializedEnvelope(byte[] bytes, String timestamp) {
            this.bytes = bytes;
            this.timestamp = timestamp;
        }

        /**
         * Returns the serializer-owned exact bytes for runtime retention.
         *
         * @return exact mutable-by-owner envelope bytes
         */
        public byte[] getBytes() { return bytes; }

        /**
         * Returns the canonical timestamp serialized into the envelope.
         *
         * @return canonical timestamp
         */
        public String getTimestamp() { return timestamp; }
    }

    private static byte[] serialize(
            SchemaDescriptor schema,
            String modelId,
            @Nullable String eventType,
            @Nullable String customerId,
            RepostModel model,
            DefaultValueGenerators generators,
            @Nullable Instant operationNow) {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(generators, "generators");
        requireSupportedVersion(schema);
        ModelDescriptor descriptor = schema.getModels().get(modelId);
        if (descriptor == null) {
            throw serializationError(RepostErrorCode.SERIALIZATION);
        }

        Context context = new Context(schema, generators, operationNow);
        PlannedValue root;
        try {
            if (eventType != null && !JsonValueWriter.isScalarString(eventType)) {
                context.sink.add(ValidationIssueCode.INVALID_UNICODE, "$.type");
            }
            if (customerId != null && !JsonValueWriter.isScalarString(customerId)) {
                context.sink.add(ValidationIssueCode.INVALID_UNICODE, "$.customerId");
            }
            PlannedValue data = context.planModel(descriptor, model, "$", 0);
            root = eventType == null
                    ? data : context.planEnvelope(eventType, customerId, data);
        } catch (RepostConfigurationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw serializationError(RepostErrorCode.SERIALIZATION);
        }

        if (context.sink.issueCount() != 0) {
            throw context.sink.exception();
        }
        SerializationPlan plan = new SerializationPlan(root, context.size.exactSize());
        try {
            return plan.write();
        } catch (RuntimeException exception) {
            throw serializationError(RepostErrorCode.SERIALIZATION);
        }
    }

    private static void requireSupportedVersion(SchemaDescriptor schema) {
        if (schema.getDescriptorFormatVersion() != SchemaDescriptor.SUPPORTED_FORMAT_VERSION) {
            RepostErrorDetails details = RepostErrorDetails.builder(
                    RepostErrorCode.DESCRIPTOR_VERSION, DeliveryState.NOT_SENT).build();
            throw new RepostDescriptorVersionException(details);
        }
    }

    static String appendPath(String path, String segment) {
        if (path.endsWith(".<truncated>")) {
            return path;
        }
        String candidate = path + segment;
        if (utf8Bytes(candidate) <= RepostErrorDetails.MAX_ISSUE_PATH_UTF8_BYTES) {
            return candidate;
        }
        String truncated = path + ".<truncated>";
        if (utf8Bytes(truncated) <= RepostErrorDetails.MAX_ISSUE_PATH_UTF8_BYTES) {
            return truncated;
        }
        return "$.<truncated>";
    }

    private static int utf8Bytes(String value) {
        int bytes = 0;
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            index += Character.charCount(codePoint);
            bytes += codePoint <= 0x7f ? 1 : codePoint <= 0x7ff ? 2
                    : codePoint <= 0xffff ? 3 : 4;
        }
        return bytes;
    }

    static int quotedUtf8Size(String value) {
        return encodeJsonString(value, null);
    }

    private static int encodeJsonString(String value, @Nullable ExactWriter writer) {
        int size = 2;
        if (writer != null) {
            writer.writeByte('"');
        }
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '"':
                    size += 2;
                    if (writer != null) {
                        writer.writeByte('\\');
                        writer.writeByte('"');
                    }
                    break;
                case '\\':
                    size += 2;
                    if (writer != null) {
                        writer.writeByte('\\');
                        writer.writeByte('\\');
                    }
                    break;
                case '\b':
                    size += 2;
                    if (writer != null) {
                        writer.writeByte('\\');
                        writer.writeByte('b');
                    }
                    break;
                case '\f':
                    size += 2;
                    if (writer != null) {
                        writer.writeByte('\\');
                        writer.writeByte('f');
                    }
                    break;
                case '\n':
                    size += 2;
                    if (writer != null) {
                        writer.writeByte('\\');
                        writer.writeByte('n');
                    }
                    break;
                case '\r':
                    size += 2;
                    if (writer != null) {
                        writer.writeByte('\\');
                        writer.writeByte('r');
                    }
                    break;
                case '\t':
                    size += 2;
                    if (writer != null) {
                        writer.writeByte('\\');
                        writer.writeByte('t');
                    }
                    break;
                default:
                    if (current < 0x20) {
                        size += 6;
                        if (writer != null) {
                            writer.writeByte('\\');
                            writer.writeByte('u');
                            writer.writeByte('0');
                            writer.writeByte('0');
                            writer.writeByte(HEX[(current >>> 4) & 0xf]);
                            writer.writeByte(HEX[current & 0xf]);
                        }
                    } else if (current <= 0x7f) {
                        size++;
                        if (writer != null) {
                            writer.writeByte(current);
                        }
                    } else if (current <= 0x7ff) {
                        size += 2;
                        if (writer != null) {
                            writer.writeByte(0xc0 | (current >>> 6));
                            writer.writeByte(0x80 | (current & 0x3f));
                        }
                    } else if (Character.isHighSurrogate(current)
                            && index + 1 < value.length()
                            && Character.isLowSurrogate(value.charAt(index + 1))) {
                        size += 4;
                        int codePoint = Character.toCodePoint(
                                current, value.charAt(++index));
                        if (writer != null) {
                            writer.writeByte(0xf0 | (codePoint >>> 18));
                            writer.writeByte(0x80 | ((codePoint >>> 12) & 0x3f));
                            writer.writeByte(0x80 | ((codePoint >>> 6) & 0x3f));
                            writer.writeByte(0x80 | (codePoint & 0x3f));
                        }
                    } else {
                        size += 3;
                        if (writer != null) {
                            writer.writeByte(0xe0 | (current >>> 12));
                            writer.writeByte(0x80 | ((current >>> 6) & 0x3f));
                            writer.writeByte(0x80 | (current & 0x3f));
                        }
                    }
                    break;
            }
            if (writer == null && size > MAX_REQUEST_BYTES) {
                return MAX_REQUEST_BYTES + 1;
            }
        }
        if (writer != null) {
            writer.writeByte('"');
        }
        return size;
    }

    private static String formatInstant(Instant value) {
        try {
            Instant truncated = value.truncatedTo(ChronoUnit.MILLIS);
            LocalDateTime dateTime = LocalDateTime.ofInstant(truncated, ZoneOffset.UTC);
            int year = dateTime.getYear();
            if (year < 0 || year > 9999) {
                throw new DateTimeException("instant is outside the wire range");
            }
            return INSTANT_FORMAT.format(truncated);
        } catch (DateTimeException exception) {
            throw new InvalidInstantException();
        }
    }

    private static RepostSerializationException serializationError(RepostErrorCode code) {
        return new RepostSerializationException(
                RepostErrorDetails.builder(code, DeliveryState.NOT_SENT).build());
    }

    private static Instant generatedNow(DefaultValueGenerators generators) {
        try {
            Instant value = Objects.requireNonNull(generators.now(), "generated now");
            formatInstant(value);
            return value;
        } catch (RuntimeException exception) {
            throw defaultGeneratorError();
        }
    }

    private static String generatedUuid(DefaultValueGenerators generators) {
        try {
            String value = Objects.requireNonNull(generators.uuid(), "generated UUID");
            if (!UUID_V4.matcher(value).matches()) {
                throw new IllegalArgumentException("invalid generated UUID");
            }
            return value;
        } catch (RuntimeException exception) {
            throw defaultGeneratorError();
        }
    }

    private static String generatedCuid(DefaultValueGenerators generators) {
        try {
            String value = Objects.requireNonNull(generators.cuid(), "generated CUID");
            if (!CUID2.matcher(value).matches()) {
                throw new IllegalArgumentException("invalid generated CUID");
            }
            return value;
        } catch (RuntimeException exception) {
            throw defaultGeneratorError();
        }
    }

    private static RepostConfigurationException defaultGeneratorError() {
        ConfigurationIssue issue = ConfigurationIssue.of(
                ConfigurationIssueCode.INVALID_VALUE,
                Collections.singletonList(ClientOptionKey.DEFAULT_VALUE_GENERATORS));
        RepostErrorDetails details = RepostErrorDetails.builder(
                        RepostErrorCode.CONFIGURATION, DeliveryState.NOT_SENT)
                .causeCategory(RepostCauseCategory.DEFAULT_GENERATOR)
                .configurationIssues(Collections.singletonList(issue), 1, false)
                .build();
        return new RepostConfigurationException(details);
    }

    static PlannedValue nullValue(SizeCounter size) {
        size.add(4);
        return NULL;
    }

    static PlannedValue booleanValue(boolean value, SizeCounter size) {
        size.add(value ? 4 : 5);
        return value ? TRUE : FALSE;
    }

    static PlannedValue stringValue(String value, SizeCounter size) {
        size.add(quotedUtf8Size(value));
        return new StringValue(value);
    }

    static PlannedValue numberValue(String value, SizeCounter size) {
        if (value.length() > 32) {
            throw new IllegalStateException("small number encoder received a large value");
        }
        size.add(value.length());
        return new NumberValue(value);
    }

    static int decimalDigitLowerBound(int bitLength) {
        long bits = Math.max(1, bitLength);
        return (int) (((bits - 1L) * 301_029_995L) / 1_000_000_000L + 1L);
    }

    static PlannedValue bigIntegerValue(BigInteger snapshot, SizeCounter size) {
        boolean negative = snapshot.signum() < 0;
        BigInteger magnitude = snapshot.abs();
        int digits = exactDecimalDigits(magnitude);
        int total = digits + (negative ? 1 : 0);
        size.add(total);
        if (size.isExceeded()) {
            return ZERO_NUMBER;
        }
        return new IntegerDecimalValue(
                new DecimalMagnitude(magnitude, digits), negative);
    }

    static PlannedValue bigDecimalValue(
            BigInteger unscaledSnapshot, int scale, SizeCounter size) {
        boolean negative = unscaledSnapshot.signum() < 0;
        BigInteger magnitude = unscaledSnapshot.abs();
        int digits = exactDecimalDigits(magnitude);
        long adjusted = -(long) scale + digits - 1L;
        int mode;
        int decimalPoint = -1;
        int leadingZeros = 0;
        String exponent = "";
        long encodedSize;
        if (scale >= 0 && adjusted >= -6L) {
            if (scale == 0) {
                mode = DecimalValue.INTEGER;
                encodedSize = digits;
            } else if (adjusted >= 0L) {
                mode = DecimalValue.INSERT_POINT;
                decimalPoint = digits - scale;
                encodedSize = (long) digits + 1L;
            } else {
                mode = DecimalValue.LEADING_ZERO;
                leadingZeros = (int) (-adjusted - 1L);
                encodedSize = 2L + leadingZeros + digits;
            }
        } else {
            mode = DecimalValue.SCIENTIFIC;
            decimalPoint = digits > 1 ? 1 : -1;
            exponent = adjusted >= 0L
                    ? "+" + Long.toString(adjusted)
                    : Long.toString(adjusted);
            encodedSize = digits + (digits > 1 ? 1L : 0L) + 1L + exponent.length();
        }
        if (negative) {
            encodedSize++;
        }
        if (encodedSize > size.remaining()) {
            size.exceed();
            return ZERO_NUMBER;
        }
        size.add((int) encodedSize);
        return new DecimalValue(
                new DecimalMagnitude(magnitude, digits),
                negative,
                mode,
                decimalPoint,
                leadingZeros,
                exponent);
    }

    private static int exactDecimalDigits(BigInteger magnitude) {
        if (magnitude.signum() == 0) {
            return 1;
        }
        int digits = decimalDigitLowerBound(magnitude.bitLength());
        BigInteger boundary = BigInteger.TEN.pow(digits);
        while (magnitude.compareTo(boundary) >= 0) {
            digits++;
            boundary = boundary.multiply(BigInteger.TEN);
        }
        return digits;
    }

    static final class Limits {
        private int nodes;
        private boolean exhausted;

        boolean countNode(String path, ValidationSink sink) {
            if (exhausted) {
                return false;
            }
            if (nodes == MAX_NODES) {
                sink.add(ValidationIssueCode.COLLECTION_LIMIT, path);
                exhausted = true;
                return false;
            }
            nodes++;
            return true;
        }

        int remainingNodes() {
            return MAX_NODES - nodes;
        }

        boolean isExhausted() {
            return exhausted;
        }
    }

    static final class SizeCounter {
        private int size;
        private boolean exceeded;

        void add(int amount) {
            if (exceeded) {
                return;
            }
            if (amount < 0 || amount > MAX_REQUEST_BYTES - size) {
                exceeded = true;
                return;
            }
            size += amount;
        }

        int remaining() {
            return exceeded ? 0 : MAX_REQUEST_BYTES - size;
        }

        void exceed() {
            exceeded = true;
        }

        boolean isExceeded() {
            return exceeded;
        }

        int exactSize() {
            if (exceeded) {
                throw serializationError(RepostErrorCode.REQUEST_TOO_LARGE);
            }
            return size;
        }
    }

    static final class ValidationSink {
        private final ArrayList<ValidationIssue> retained = new ArrayList<>();
        private final Set<String> retainedPaths = new HashSet<>();
        private int issueCount;
        private int retainedPathBytes;

        void add(ValidationIssueCode code, String path) {
            if (issueCount != RepostErrorDetails.MAX_ISSUE_COUNT) {
                issueCount++;
            }
            if (retained.size() == RepostErrorDetails.MAX_ISSUES
                    || retainedPaths.contains(path)) {
                return;
            }
            int pathBytes = utf8Bytes(path);
            if (pathBytes > RepostErrorDetails.MAX_ISSUE_PATH_UTF8_BYTES
                    || pathBytes > RepostErrorDetails.MAX_TOTAL_ISSUE_PATH_UTF8_BYTES
                            - retainedPathBytes) {
                return;
            }
            retained.add(ValidationIssue.of(code, path));
            retainedPaths.add(path);
            retainedPathBytes += pathBytes;
        }

        int issueCount() {
            return issueCount;
        }

        RepostValidationException exception() {
            List<ValidationIssue> issues = retained.isEmpty()
                    ? Collections.singletonList(
                            ValidationIssue.of(ValidationIssueCode.COLLECTION_LIMIT, "$"))
                    : retained;
            RepostErrorDetails details = RepostErrorDetails.builder(
                    RepostErrorCode.VALIDATION, DeliveryState.NOT_SENT)
                    .validationIssues(issues, issueCount, issueCount != issues.size())
                    .build();
            return new RepostValidationException(details);
        }
    }

    static final class Context {
        final SchemaDescriptor schema;
        final DefaultValueGenerators generators;
        final ValidationSink sink = new ValidationSink();
        final Limits limits = new Limits();
        final SizeCounter size = new SizeCounter();
        final IdentityHashMap<Object, Boolean> active = new IdentityHashMap<>();
        private @Nullable Instant operationNow;

        private Context(
                SchemaDescriptor schema,
                DefaultValueGenerators generators,
                @Nullable Instant operationNow) {
            this.schema = schema;
            this.generators = generators;
            this.operationNow = operationNow;
        }

        private Instant operationNow() {
            Instant current = operationNow;
            if (current == null) {
                current = generatedNow(generators);
                operationNow = current;
            }
            return current;
        }

        private PlannedValue planEnvelope(
                String eventType,
                @Nullable String customerId,
                PlannedValue data) {
            size.add(2);
            PlannedValue type = stringValue(eventType, size);
            PlannedValue timestamp = stringValue(formatInstant(operationNow()), size);
            Member[] members;
            if (customerId == null) {
                members = new Member[] {
                    member("type", type, false),
                    member("timestamp", timestamp, true),
                    member("data", data, true)
                };
            } else {
                PlannedValue customer = stringValue(customerId, size);
                members = new Member[] {
                    member("type", type, false),
                    member("customerId", customer, true),
                    member("timestamp", timestamp, true),
                    member("data", data, true)
                };
            }
            return new ObjectValue(members);
        }

        private Member member(String name, PlannedValue value, boolean comma) {
            if (comma) {
                size.add(1);
            }
            size.add(quotedUtf8Size(name));
            size.add(1);
            return new Member(name, value);
        }

        PlannedValue planModel(
                ModelDescriptor descriptor, RepostModel model, String path, int depth) {
            if (!limits.countNode(path, sink)) {
                return nullValue(size);
            }
            if (depth == MAX_DEPTH) {
                sink.add(ValidationIssueCode.COLLECTION_LIMIT, path);
                return nullValue(size);
            }
            if (active.put(model, Boolean.TRUE) != null) {
                sink.add(ValidationIssueCode.CYCLE, path);
                return nullValue(size);
            }

            size.add(2);
            ArrayList<Member> members = new ArrayList<>(descriptor.getFields().size());
            try {
                for (FieldDescriptor field : descriptor.getFields()) {
                    String fieldPath = appendPath(path, "." + field.getSchemaName());
                    boolean present = readPresence(model, field.getFieldIndex());
                    Object value;
                    if (!present) {
                        DefaultSpec defaultSpec = field.getDefaultSpec();
                        if (defaultSpec == null) {
                            if (field.isRequiredInInput()) {
                                sink.add(ValidationIssueCode.REQUIRED, fieldPath);
                            }
                            continue;
                        }
                        value = resolveDefault(defaultSpec);
                    } else {
                        value = readValue(model, field.getFieldIndex());
                    }

                    limits.countNode(fieldPath, sink);
                    PlannedValue planned = planField(field, value, fieldPath, depth + 1);
                    if (!members.isEmpty()) {
                        size.add(1);
                    }
                    size.add(quotedUtf8Size(field.getWireName()));
                    size.add(1);
                    members.add(new Member(field.getWireName(), planned));
                    if (limits.isExhausted() || size.isExceeded()) {
                        break;
                    }
                }
            } finally {
                active.remove(model);
            }
            return new ObjectValue(members.toArray(new Member[0]));
        }

        private Object resolveDefault(DefaultSpec defaultSpec) {
            switch (defaultSpec.getKind()) {
                case LITERAL:
                    return defaultSpec.getLiteralValue();
                case NOW:
                    return operationNow();
                case UUID:
                    return generatedUuid(generators);
                case CUID:
                    return generatedCuid(generators);
                default:
                    throw new AssertionError("unhandled default kind");
            }
        }

        private PlannedValue planField(
                FieldDescriptor field, @Nullable Object value, String path, int depth) {
            if (value == null) {
                if (!field.isNullableInInput()) {
                    sink.add(ValidationIssueCode.NULL_NOT_ALLOWED, path);
                }
                return nullValue(size);
            }
            if (!field.isList()) {
                return planScalar(field, value, path, depth);
            }
            if (!(value instanceof List<?>)) {
                sink.add(ValidationIssueCode.TYPE_MISMATCH, path);
                return nullValue(size);
            }
            if (depth == MAX_DEPTH) {
                sink.add(ValidationIssueCode.COLLECTION_LIMIT, path);
                return nullValue(size);
            }
            if (active.put(value, Boolean.TRUE) != null) {
                sink.add(ValidationIssueCode.CYCLE, path);
                return nullValue(size);
            }

            size.add(2);
            ArrayList<PlannedValue> values = new ArrayList<>();
            try {
                Iterator<?> iterator = readIterator((List<?>) value);
                int index = 0;
                while (hasNext(iterator)) {
                    if (!limits.countNode(path, sink)) {
                        break;
                    }
                    Object element = next(iterator);
                    String elementPath = appendPath(path, "[" + index++ + "]");
                    if (!values.isEmpty()) {
                        size.add(1);
                    }
                    if (element == null) {
                        sink.add(ValidationIssueCode.NULL_NOT_ALLOWED, elementPath);
                        values.add(nullValue(size));
                    } else {
                        values.add(planScalar(field, element, elementPath, depth));
                    }
                    if (limits.isExhausted() || size.isExceeded()) {
                        break;
                    }
                }
            } finally {
                active.remove(value);
            }
            return new ArrayValue(values.toArray(new PlannedValue[0]));
        }

        private PlannedValue planScalar(
                FieldDescriptor field, Object value, String path, int depth) {
            switch (field.getScalarKind()) {
                case STRING:
                    if (!(value instanceof String)) {
                        return typeMismatch(path);
                    }
                    if (!JsonValueWriter.isScalarString((String) value)) {
                        sink.add(ValidationIssueCode.INVALID_UNICODE, path);
                        return nullValue(size);
                    }
                    return stringValue((String) value, size);
                case BOOLEAN:
                    return value instanceof Boolean
                            ? booleanValue((Boolean) value, size) : typeMismatch(path);
                case INT64:
                    return value instanceof Long
                            ? numberValue(Long.toString((Long) value), size)
                            : typeMismatch(path);
                case FLOAT64:
                    if (!(value instanceof Double)) {
                        return typeMismatch(path);
                    }
                    double number = (Double) value;
                    if (!Double.isFinite(number)) {
                        sink.add(ValidationIssueCode.NON_FINITE, path);
                        return nullValue(size);
                    }
                    return numberValue(Double.toString(number), size);
                case DATETIME:
                    if (!(value instanceof Instant)) {
                        return typeMismatch(path);
                    }
                    try {
                        return stringValue(formatInstant((Instant) value), size);
                    } catch (InvalidInstantException exception) {
                        sink.add(ValidationIssueCode.INVALID_DATETIME, path);
                        return nullValue(size);
                    }
                case JSON:
                    return JsonValueWriter.plan(value, path, depth, this);
                case ENUM:
                    if (!(value instanceof Enum<?>)) {
                        return typeMismatch(path);
                    }
                    String wireValue = schema.getEnums().get(field.getDescriptorId())
                            .get(((Enum<?>) value).name());
                    if (wireValue == null) {
                        sink.add(ValidationIssueCode.INVALID_ENUM, path);
                        return nullValue(size);
                    }
                    return stringValue(wireValue, size);
                case MODEL:
                    if (!(value instanceof RepostModel)) {
                        return typeMismatch(path);
                    }
                    return planModel(schema.getModels().get(field.getDescriptorId()),
                            (RepostModel) value, path, depth);
                default:
                    throw new AssertionError("unhandled scalar kind");
            }
        }

        private PlannedValue typeMismatch(String path) {
            sink.add(ValidationIssueCode.TYPE_MISMATCH, path);
            return nullValue(size);
        }

        private static boolean readPresence(RepostModel model, int fieldIndex) {
            try {
                return model.__repostIsPresent(fieldIndex);
            } catch (RuntimeException exception) {
                throw new SourceAccessException();
            }
        }

        private static Object readValue(RepostModel model, int fieldIndex) {
            try {
                return model.__repostValue(fieldIndex);
            } catch (RuntimeException exception) {
                throw new SourceAccessException();
            }
        }

        private static Iterator<?> readIterator(List<?> values) {
            try {
                return values.iterator();
            } catch (RuntimeException exception) {
                throw new SourceAccessException();
            }
        }

        private static boolean hasNext(Iterator<?> iterator) {
            try {
                return iterator.hasNext();
            } catch (RuntimeException exception) {
                throw new SourceAccessException();
            }
        }

        private static Object next(Iterator<?> iterator) {
            try {
                return iterator.next();
            } catch (RuntimeException exception) {
                throw new SourceAccessException();
            }
        }
    }

    static final class SerializationPlan {
        private final PlannedValue root;
        private final int byteSize;

        SerializationPlan(PlannedValue root, int byteSize) {
            this.root = root;
            this.byteSize = byteSize;
        }

        byte[] write() {
            byte[] output = new byte[byteSize];
            ExactWriter writer = new ExactWriter(output);
            root.write(writer);
            if (!writer.isComplete()) {
                throw new IllegalStateException("serialization plan size mismatch");
            }
            return output;
        }
    }

    abstract static class PlannedValue {
        abstract void write(ExactWriter writer);
    }

    private static final class LiteralValue extends PlannedValue {
        private final String value;

        private LiteralValue(String value) {
            this.value = value;
        }

        @Override
        void write(ExactWriter writer) {
            writer.writeAscii(value);
        }
    }

    private static final PlannedValue NULL = new LiteralValue("null");
    private static final PlannedValue TRUE = new LiteralValue("true");
    private static final PlannedValue FALSE = new LiteralValue("false");
    private static final PlannedValue ZERO_NUMBER = new NumberValue("0");

    private static final class StringValue extends PlannedValue {
        private final String value;

        private StringValue(String value) {
            this.value = value;
        }

        @Override
        void write(ExactWriter writer) {
            writer.writeJsonString(value);
        }
    }

    private static final class NumberValue extends PlannedValue {
        private final String value;

        private NumberValue(String value) {
            this.value = value;
        }

        @Override
        void write(ExactWriter writer) {
            writer.writeAscii(value);
        }
    }

    private static final class IntegerDecimalValue extends PlannedValue {
        private final DecimalMagnitude magnitude;
        private final boolean negative;

        private IntegerDecimalValue(DecimalMagnitude magnitude, boolean negative) {
            this.magnitude = magnitude;
            this.negative = negative;
        }

        @Override
        void write(ExactWriter writer) {
            if (negative) {
                writer.writeByte('-');
            }
            magnitude.write(writer, -1);
        }
    }

    private static final class DecimalValue extends PlannedValue {
        private static final int INTEGER = 0;
        private static final int INSERT_POINT = 1;
        private static final int LEADING_ZERO = 2;
        private static final int SCIENTIFIC = 3;

        private final DecimalMagnitude magnitude;
        private final boolean negative;
        private final int mode;
        private final int decimalPoint;
        private final int leadingZeros;
        private final String exponent;

        private DecimalValue(
                DecimalMagnitude magnitude,
                boolean negative,
                int mode,
                int decimalPoint,
                int leadingZeros,
                String exponent) {
            this.magnitude = magnitude;
            this.negative = negative;
            this.mode = mode;
            this.decimalPoint = decimalPoint;
            this.leadingZeros = leadingZeros;
            this.exponent = exponent;
        }

        @Override
        void write(ExactWriter writer) {
            if (negative) {
                writer.writeByte('-');
            }
            if (mode == LEADING_ZERO) {
                writer.writeAscii("0.");
                for (int index = 0; index < leadingZeros; index++) {
                    writer.writeByte('0');
                }
                magnitude.write(writer, -1);
            } else {
                magnitude.write(writer, decimalPoint);
                if (mode == SCIENTIFIC) {
                    writer.writeByte('E');
                    writer.writeAscii(exponent);
                }
            }
        }
    }

    private static final class DecimalMagnitude {
        private static final int DIRECT_DIGITS = 18;
        private final BigInteger value;
        private final int digits;
        private final Map<Integer, BigInteger> powers;

        private DecimalMagnitude(BigInteger value, int digits) {
            this.value = value;
            this.digits = digits;
            HashMap<Integer, BigInteger> prepared = new HashMap<>();
            preparePowers(digits, new HashSet<>(), prepared);
            this.powers = Collections.unmodifiableMap(prepared);
        }

        private static void preparePowers(
                int width,
                Set<Integer> visited,
                Map<Integer, BigInteger> powers) {
            if (width <= DIRECT_DIGITS || !visited.add(width)) {
                return;
            }
            int lowerDigits = width / 2;
            powers.computeIfAbsent(lowerDigits, BigInteger.TEN::pow);
            preparePowers(width - lowerDigits, visited, powers);
            preparePowers(lowerDigits, visited, powers);
        }

        private void write(ExactWriter writer, int decimalPoint) {
            DigitWriter digitsWriter = new DigitWriter(writer, decimalPoint);
            writePart(value, digits, digitsWriter);
        }

        private void writePart(
                BigInteger part, int width, DigitWriter writer) {
            if (width <= DIRECT_DIGITS) {
                writer.writePadded(part.longValue(), width);
                return;
            }
            int lowerDigits = width / 2;
            BigInteger divisor = powers.get(lowerDigits);
            BigInteger[] split = part.divideAndRemainder(divisor);
            writePart(split[0], width - lowerDigits, writer);
            writePart(split[1], lowerDigits, writer);
        }
    }

    private static final class DigitWriter {
        private final ExactWriter writer;
        private final int decimalPoint;
        private int written;

        private DigitWriter(ExactWriter writer, int decimalPoint) {
            this.writer = writer;
            this.decimalPoint = decimalPoint;
        }

        private void writePadded(long value, int width) {
            long divisor = 1L;
            for (int index = 1; index < width; index++) {
                divisor *= 10L;
            }
            for (int index = 0; index < width; index++) {
                if (written == decimalPoint) {
                    writer.writeByte('.');
                }
                int digit = (int) (value / divisor);
                writer.writeByte('0' + digit);
                value %= divisor;
                if (divisor != 1L) {
                    divisor /= 10L;
                }
                written++;
            }
        }
    }

    static final class ArrayValue extends PlannedValue {
        private final PlannedValue[] values;

        ArrayValue(PlannedValue[] values) {
            this.values = values;
        }

        @Override
        void write(ExactWriter writer) {
            writer.writeByte('[');
            for (int index = 0; index < values.length; index++) {
                if (index != 0) {
                    writer.writeByte(',');
                }
                values[index].write(writer);
            }
            writer.writeByte(']');
        }
    }

    static final class Member {
        private final String name;
        private final PlannedValue value;

        Member(String name, PlannedValue value) {
            this.name = name;
            this.value = value;
        }
    }

    static final class ObjectValue extends PlannedValue {
        private final Member[] members;

        ObjectValue(Member[] members) {
            this.members = members;
        }

        @Override
        void write(ExactWriter writer) {
            writer.writeByte('{');
            for (int index = 0; index < members.length; index++) {
                if (index != 0) {
                    writer.writeByte(',');
                }
                Member member = members[index];
                writer.writeJsonString(member.name);
                writer.writeByte(':');
                member.value.write(writer);
            }
            writer.writeByte('}');
        }
    }

    static final class ExactWriter {
        private final byte[] output;
        private int position;

        ExactWriter(byte[] output) {
            this.output = output;
        }

        void writeByte(int value) {
            output[position++] = (byte) value;
        }

        void writeAscii(String value) {
            for (int index = 0; index < value.length(); index++) {
                output[position++] = (byte) value.charAt(index);
            }
        }

        void writeJsonString(String value) {
            encodeJsonString(value, this);
        }

        boolean isComplete() {
            return position == output.length;
        }
    }

    private static final class InvalidInstantException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private InvalidInstantException() {
            super(null, null, false, false);
        }
    }

    private static final class SourceAccessException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private SourceAccessException() {
            super(null, null, false, false);
        }
    }
}
