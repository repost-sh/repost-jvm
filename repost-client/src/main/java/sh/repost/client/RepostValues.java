package sh.repost.client;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Defensive value snapshots used by generated model builders. */
public final class RepostValues {
    /** Maximum nested-container depth accepted in a raw JSON snapshot. */
    public static final int MAX_SNAPSHOT_DEPTH = 100;
    /** Maximum aggregate scalar, container, and map-key nodes in a raw JSON snapshot. */
    public static final int MAX_SNAPSHOT_NODES = 524_288;
    /** Maximum lower-bound UTF-8 size of a raw JSON snapshot. */
    public static final int MAX_SNAPSHOT_UTF8_BYTES = 1_048_576;

    private RepostValues() { }

    /**
     * Returns an immutable, recursively defensive snapshot of an accepted JSON value.
     *
     * @param value accepted JSON-domain value
     * @return immutable snapshot, or {@code null}
     */
    public static @Nullable Object snapshotJson(@Nullable Object value) {
        return new Snapshotter().copyJson(value, 0);
    }

    /**
     * Returns an immutable shallow snapshot of a generated non-null list value.
     *
     * @param <T> element type
     * @param values source values
     * @return immutable snapshot
     */
    public static <T> List<T> snapshotList(Collection<? extends T> values) {
        Objects.requireNonNull(values, "values");
        int declaredSize = collectionSize(values);
        if (declaredSize > MAX_SNAPSHOT_NODES) {
            throw invalid("list contains too many values");
        }
        ArrayList<T> copy = new ArrayList<>(boundedInitialCapacity(declaredSize));
        Iterator<? extends T> iterator = collectionIterator(values);
        while (iteratorHasNext(iterator)) {
            T value = iteratorNext(iterator);
            if (value == null) {
                throw invalid("list values must not be null");
            }
            if (copy.size() == MAX_SNAPSHOT_NODES) {
                throw invalid("list contains too many values");
            }
            copy.add(value);
        }
        return Collections.unmodifiableList(copy);
    }

    /**
     * Returns an immutable shallow snapshot of a generated non-null array value.
     *
     * @param <T> element type
     * @param values source values
     * @return immutable snapshot
     */
    public static <T> List<T> snapshotList(T[] values) {
        Objects.requireNonNull(values, "values");
        if (values.length > MAX_SNAPSHOT_NODES) {
            throw invalid("list contains too many values");
        }
        ArrayList<T> copy = new ArrayList<>(values.length);
        for (T value : values) {
            if (value == null) {
                throw invalid("list values must not be null");
            }
            copy.add(value);
        }
        return Collections.unmodifiableList(copy);
    }

    /**
     * Compares indexed generated-model fields, including their presence bits.
     * Generated {@code equals} methods remain responsible for checking model type identity.
     *
     * @param left first generated model
     * @param right second generated model of the same generated type
     * @param fieldCount descriptor field count
     * @return whether presence and present values match in declaration order
     */
    public static boolean fieldsEqual(
            RepostModel left, RepostModel right, int fieldCount) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        requireFieldCount(fieldCount);
        for (int index = 0; index < fieldCount; index++) {
            boolean leftPresent = left.__repostIsPresent(index);
            if (leftPresent != right.__repostIsPresent(index)) {
                return false;
            }
            if (leftPresent && !Objects.deepEquals(
                    left.__repostValue(index), right.__repostValue(index))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Hashes indexed generated-model fields, including declaration-ordered presence bits.
     *
     * @param model generated model
     * @param fieldCount descriptor field count
     * @return presence-aware field hash
     */
    public static int fieldsHashCode(RepostModel model, int fieldCount) {
        Objects.requireNonNull(model, "model");
        requireFieldCount(fieldCount);
        int result = 1;
        for (int index = 0; index < fieldCount; index++) {
            boolean present = model.__repostIsPresent(index);
            result = 31 * result + (present ? 1231 : 1237);
            result = 31 * result + (present
                    ? deepHashCode(model.__repostValue(index)) : 0);
        }
        return result;
    }

    /**
     * Renders only generated type metadata and present field names, never field values.
     *
     * @param typeName generated type name
     * @param model generated model
     * @param fieldNames declaration-ordered source field names
     * @return redacted debug rendering
     */
    public static String redactedToString(
            String typeName, RepostModel model, String[] fieldNames) {
        requireScalarString(Objects.requireNonNull(typeName, "typeName"));
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(fieldNames, "fieldNames");
        requireFieldCount(fieldNames.length);
        StringBuilder result = new StringBuilder(typeName).append("[setFields=[");
        boolean first = true;
        for (int index = 0; index < fieldNames.length; index++) {
            String fieldName = Objects.requireNonNull(fieldNames[index], "fieldNames contains null");
            requireScalarString(fieldName);
            if (model.__repostIsPresent(index)) {
                if (!first) {
                    result.append(", ");
                }
                result.append(fieldName);
                first = false;
            }
        }
        return result.append("]]").toString();
    }

    private static int deepHashCode(@Nullable Object value) {
        if (value instanceof Object[]) {
            return java.util.Arrays.deepHashCode((Object[]) value);
        }
        if (value instanceof boolean[]) return java.util.Arrays.hashCode((boolean[]) value);
        if (value instanceof byte[]) return java.util.Arrays.hashCode((byte[]) value);
        if (value instanceof short[]) return java.util.Arrays.hashCode((short[]) value);
        if (value instanceof int[]) return java.util.Arrays.hashCode((int[]) value);
        if (value instanceof long[]) return java.util.Arrays.hashCode((long[]) value);
        if (value instanceof char[]) return java.util.Arrays.hashCode((char[]) value);
        if (value instanceof float[]) return java.util.Arrays.hashCode((float[]) value);
        if (value instanceof double[]) return java.util.Arrays.hashCode((double[]) value);
        return Objects.hashCode(value);
    }

    private static void requireFieldCount(int fieldCount) {
        if (fieldCount < 0 || fieldCount > 512) {
            throw invalid("field count is outside the generated-model contract");
        }
    }

    private static final class Snapshotter {
        private final IdentityHashMap<Object, Boolean> active = new IdentityHashMap<>();
        private int nodes;
        private int utf8Bytes;

        private @Nullable Object copyJson(@Nullable Object value, int depth) {
            countNode();
            if (value == null || value instanceof Boolean
                    || value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long
                    || value instanceof BigInteger || value instanceof BigDecimal) {
                countScalarBytes(value);
                return value;
            }
            if (value instanceof String) {
                requireScalarString((String) value);
                countStringBytes((String) value);
                return value;
            }
            if (value instanceof Float) {
                if (!Float.isFinite((Float) value)) {
                    throw invalid("JSON numbers must be finite");
                }
                countBytes(value.toString().length());
                return value;
            }
            if (value instanceof Double) {
                if (!Double.isFinite((Double) value)) {
                    throw invalid("JSON numbers must be finite");
                }
                countBytes(value.toString().length());
                return value;
            }
            if (value instanceof Map<?, ?>) {
                return copyMap((Map<?, ?>) value, enterDepth(depth));
            }
            if (value instanceof Collection<?>) {
                return copyCollection((Collection<?>) value, enterDepth(depth));
            }
            if (value instanceof Object[]) {
                return copyArray((Object[]) value, enterDepth(depth));
            }
            if (value instanceof boolean[]) {
                return copyArray((boolean[]) value, enterDepth(depth));
            }
            if (value instanceof byte[]) {
                return copyArray((byte[]) value, enterDepth(depth));
            }
            if (value instanceof short[]) {
                return copyArray((short[]) value, enterDepth(depth));
            }
            if (value instanceof int[]) {
                return copyArray((int[]) value, enterDepth(depth));
            }
            if (value instanceof long[]) {
                return copyArray((long[]) value, enterDepth(depth));
            }
            if (value instanceof float[]) {
                return copyArray((float[]) value, enterDepth(depth));
            }
            if (value instanceof double[]) {
                return copyArray((double[]) value, enterDepth(depth));
            }
            throw invalid("unsupported JSON value");
        }

        private Map<String, Object> copyMap(Map<?, ?> source, int depth) {
            int declaredSize = mapSize(source);
            if (declaredSize > (MAX_SNAPSHOT_NODES - nodes) / 2) {
                throw invalid("JSON value contains too many nodes");
            }
            enter(source);
            try {
                ArrayList<JsonKey> keys = new ArrayList<>(boundedInitialCapacity(declaredSize));
                Iterator<?> iterator = mapKeyIterator(source);
                while (iteratorHasNext(iterator)) {
                    Object key = iteratorNext(iterator);
                    if (!(key instanceof String)) {
                        throw invalid("JSON map keys must be strings");
                    }
                    String stringKey = (String) key;
                    requireScalarString(stringKey);
                    countNode();
                    countStringBytes(stringKey);
                    keys.add(new JsonKey(stringKey));
                }
                keys.sort(UNSIGNED_UTF8);
                LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
                countBytes(2);
                for (int index = 0; index < keys.size(); index++) {
                    String key = keys.get(index).value;
                    if (index > 0) {
                        countBytes(1);
                    }
                    countBytes(1);
                    copy.put(key, copyJson(mapValue(source, key), depth));
                }
                return Collections.unmodifiableMap(copy);
            } finally {
                active.remove(source);
            }
        }

        private List<Object> copyCollection(Collection<?> source, int depth) {
            int declaredSize = collectionSize(source);
            if (declaredSize > MAX_SNAPSHOT_NODES - nodes) {
                throw invalid("JSON value contains too many nodes");
            }
            enter(source);
            try {
                ArrayList<Object> copy = new ArrayList<>(boundedInitialCapacity(declaredSize));
                countBytes(2);
                Iterator<?> iterator = collectionIterator(source);
                while (iteratorHasNext(iterator)) {
                    Object element = iteratorNext(iterator);
                    if (!copy.isEmpty()) {
                        countBytes(1);
                    }
                    copy.add(copyJson(element, depth));
                }
                return Collections.unmodifiableList(copy);
            } finally {
                active.remove(source);
            }
        }

        private List<Object> copyArray(Object[] source, int depth) {
            enter(source);
            try {
                ArrayList<Object> copy = new ArrayList<>(source.length);
                countBytes(2);
                for (Object element : source) {
                    if (!copy.isEmpty()) {
                        countBytes(1);
                    }
                    copy.add(copyJson(element, depth));
                }
                return Collections.unmodifiableList(copy);
            } finally {
                active.remove(source);
            }
        }

        private List<Object> copyArray(boolean[] source, int depth) {
            ArrayList<Object> copy = beginArray(source.length);
            for (boolean value : source) {
                addArrayValue(copy, value, depth);
            }
            return Collections.unmodifiableList(copy);
        }

        private List<Object> copyArray(byte[] source, int depth) {
            ArrayList<Object> copy = beginArray(source.length);
            for (byte value : source) {
                addArrayValue(copy, value, depth);
            }
            return Collections.unmodifiableList(copy);
        }

        private List<Object> copyArray(short[] source, int depth) {
            ArrayList<Object> copy = beginArray(source.length);
            for (short value : source) {
                addArrayValue(copy, value, depth);
            }
            return Collections.unmodifiableList(copy);
        }

        private List<Object> copyArray(int[] source, int depth) {
            ArrayList<Object> copy = beginArray(source.length);
            for (int value : source) {
                addArrayValue(copy, value, depth);
            }
            return Collections.unmodifiableList(copy);
        }

        private List<Object> copyArray(long[] source, int depth) {
            ArrayList<Object> copy = beginArray(source.length);
            for (long value : source) {
                addArrayValue(copy, value, depth);
            }
            return Collections.unmodifiableList(copy);
        }

        private List<Object> copyArray(float[] source, int depth) {
            ArrayList<Object> copy = beginArray(source.length);
            for (float value : source) {
                addArrayValue(copy, value, depth);
            }
            return Collections.unmodifiableList(copy);
        }

        private List<Object> copyArray(double[] source, int depth) {
            ArrayList<Object> copy = beginArray(source.length);
            for (double value : source) {
                addArrayValue(copy, value, depth);
            }
            return Collections.unmodifiableList(copy);
        }

        private ArrayList<Object> beginArray(int length) {
            if (length > MAX_SNAPSHOT_NODES - nodes) {
                throw invalid("JSON value contains too many nodes");
            }
            countBytes(2);
            return new ArrayList<>(length);
        }

        private void addArrayValue(ArrayList<Object> copy, Object value, int depth) {
            if (!copy.isEmpty()) {
                countBytes(1);
            }
            copy.add(copyJson(value, depth));
        }

        private int enterDepth(int depth) {
            if (depth == MAX_SNAPSHOT_DEPTH) {
                throw invalid("JSON value is too deeply nested");
            }
            return depth + 1;
        }

        private void countNode() {
            if (nodes == MAX_SNAPSHOT_NODES) {
                throw invalid("JSON value contains too many nodes");
            }
            nodes++;
        }

        private void countScalarBytes(@Nullable Object value) {
            if (value == null) {
                countBytes(4);
            } else if (value instanceof Boolean) {
                countBytes((Boolean) value ? 4 : 5);
            } else {
                countBytes(value.toString().getBytes(StandardCharsets.UTF_8).length);
            }
        }

        private void countStringBytes(String value) {
            int bytes = 2;
            if (bytes > MAX_SNAPSHOT_UTF8_BYTES - utf8Bytes) {
                throw invalid("JSON value is too large");
            }
            for (int index = 0; index < value.length();) {
                char character = value.charAt(index);
                if (character == '"' || character == '\\'
                        || character == '\b' || character == '\t'
                        || character == '\n' || character == '\f'
                        || character == '\r') {
                    bytes += 2;
                    index++;
                } else if (character < 0x20) {
                    bytes += 6;
                    index++;
                } else if (character <= 0x7f) {
                    bytes++;
                    index++;
                } else if (character <= 0x7ff) {
                    bytes += 2;
                    index++;
                } else if (Character.isHighSurrogate(character)) {
                    bytes += 4;
                    index += 2;
                } else {
                    bytes += 3;
                    index++;
                }
                if (bytes > MAX_SNAPSHOT_UTF8_BYTES - utf8Bytes) {
                    throw invalid("JSON value is too large");
                }
            }
            countBytes(bytes);
        }

        private void countBytes(int amount) {
            if (amount > MAX_SNAPSHOT_UTF8_BYTES - utf8Bytes) {
                throw invalid("JSON value is too large");
            }
            utf8Bytes += amount;
        }

        private void enter(Object container) {
            if (active.put(container, Boolean.TRUE) != null) {
                throw invalid("JSON value contains a cycle");
            }
        }
    }

    private static final Comparator<JsonKey> UNSIGNED_UTF8 = (left, right) -> {
        int length = Math.min(left.utf8.length, right.utf8.length);
        for (int index = 0; index < length; index++) {
            int comparison = Integer.compare(
                    left.utf8[index] & 0xff,
                    right.utf8[index] & 0xff);
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(left.utf8.length, right.utf8.length);
    };

    private static int collectionSize(Collection<?> values) {
        try {
            int size = values.size();
            if (size < 0) {
                throw invalid("collection reported an invalid size");
            }
            return size;
        } catch (RuntimeException exception) {
            throw invalid("collection size could not be read");
        }
    }

    private static int mapSize(Map<?, ?> values) {
        try {
            int size = values.size();
            if (size < 0) {
                throw invalid("map reported an invalid size");
            }
            return size;
        } catch (RuntimeException exception) {
            throw invalid("map size could not be read");
        }
    }

    private static int boundedInitialCapacity(int declaredSize) {
        return Math.min(declaredSize, 1_024);
    }

    private static <T> Iterator<T> collectionIterator(Collection<T> values) {
        try {
            return Objects.requireNonNull(values.iterator(), "collection iterator");
        } catch (RuntimeException exception) {
            throw invalid("collection iterator could not be created");
        }
    }

    private static Iterator<?> mapKeyIterator(Map<?, ?> values) {
        try {
            return Objects.requireNonNull(values.keySet().iterator(), "map key iterator");
        } catch (RuntimeException exception) {
            throw invalid("map key iterator could not be created");
        }
    }

    private static boolean iteratorHasNext(Iterator<?> iterator) {
        try {
            return iterator.hasNext();
        } catch (RuntimeException exception) {
            throw invalid("collection traversal failed");
        }
    }

    private static <T> T iteratorNext(Iterator<T> iterator) {
        try {
            return iterator.next();
        } catch (RuntimeException exception) {
            throw invalid("collection traversal failed");
        }
    }

    private static @Nullable Object mapValue(Map<?, ?> values, String key) {
        try {
            return values.get(key);
        } catch (RuntimeException exception) {
            throw invalid("map value could not be read");
        }
    }

    private static final class JsonKey {
        private final String value;
        private final byte[] utf8;

        private JsonKey(String value) {
            this.value = value;
            this.utf8 = value.getBytes(StandardCharsets.UTF_8);
        }
    }

    static void requireScalarString(String value) {
        for (int index = 0; index < value.length();) {
            char first = value.charAt(index);
            if (Character.isHighSurrogate(first)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw invalid("string contains invalid Unicode");
                }
                index += 2;
            } else if (Character.isLowSurrogate(first)) {
                throw invalid("string contains invalid Unicode");
            } else {
                index++;
            }
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
