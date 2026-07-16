package sh.repost.client.descriptor;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Immutable descriptor for a literal or generated schema default. */
public final class DefaultSpec {
    /** Default evaluation strategy. */
    public enum Kind {
        /** Descriptor-owned literal value. */ LITERAL,
        /** Operation-snapshotted current instant. */ NOW,
        /** Per-occurrence UUIDv4. */ UUID,
        /** Per-occurrence CUID2-shaped identifier. */ CUID
    }

    private static final DefaultSpec NOW = new DefaultSpec(Kind.NOW, null);
    private static final DefaultSpec UUID = new DefaultSpec(Kind.UUID, null);
    private static final DefaultSpec CUID = new DefaultSpec(Kind.CUID, null);

    private final Kind kind;
    private final Object literalValue;

    private DefaultSpec(Kind kind, Object literalValue) {
        this.kind = kind;
        this.literalValue = literalValue;
    }

    /**
     * Creates a defensively snapshotted literal default.
     *
     * @param value supported literal value, including {@code null}
     * @return literal default descriptor
     */
    public static DefaultSpec literal(@Nullable Object value) {
        return new DefaultSpec(Kind.LITERAL, snapshotLiteral(value));
    }

    /**
     * Returns the operation-time instant default.
     *
     * @return shared immutable descriptor
     */
    public static DefaultSpec now() { return NOW; }
    /**
     * Returns the per-occurrence UUID default.
     *
     * @return shared immutable descriptor
     */
    public static DefaultSpec uuid() { return UUID; }
    /**
     * Returns the per-occurrence CUID default.
     *
     * @return shared immutable descriptor
     */
    public static DefaultSpec cuid() { return CUID; }

    /**
     * Returns the default strategy.
     *
     * @return default strategy
     */
    public Kind getKind() { return kind; }
    /**
     * Returns the literal value.
     *
     * @return snapshotted literal or {@code null}
     */
    public @Nullable Object getLiteralValue() { return literalValue; }

    @Override
    public String toString() {
        return "DefaultSpec[kind=" + kind + "]";
    }

    private static Object snapshotLiteral(Object value) {
        if (value == null || value instanceof Boolean
                || value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long
                || value instanceof BigInteger || value instanceof BigDecimal
                || value instanceof Instant || value instanceof Enum<?>) {
            return value;
        }
        if (value instanceof String) {
            DescriptorText.requireScalar((String) value, "literal string");
            return value;
        }
        if (value instanceof Float) {
            if (!Float.isFinite((Float) value)) {
                throw new IllegalArgumentException("literal number must be finite");
            }
            return value;
        }
        if (value instanceof Double) {
            if (!Double.isFinite((Double) value)) {
                throw new IllegalArgumentException("literal number must be finite");
            }
            return value;
        }
        if (value instanceof List<?>) {
            ArrayList<Object> copy = new ArrayList<>(((List<?>) value).size());
            for (Object element : (List<?>) value) {
                copy.add(snapshotLiteral(element));
            }
            return Collections.unmodifiableList(copy);
        }
        if (value instanceof Map<?, ?>) {
            LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!(entry.getKey() instanceof String)) {
                    throw new IllegalArgumentException("literal map keys must be strings");
                }
                String key = DescriptorText.requireScalar(
                        (String) entry.getKey(), "literal map key");
                copy.put(key, snapshotLiteral(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof Object[]) {
            Object[] source = (Object[]) value;
            ArrayList<Object> copy = new ArrayList<>(source.length);
            for (Object element : source) {
                copy.add(snapshotLiteral(element));
            }
            return Collections.unmodifiableList(copy);
        }
        throw new IllegalArgumentException("unsupported literal default value");
    }
}
