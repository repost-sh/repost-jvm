package sh.repost.client.internal;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import sh.repost.client.ValidationIssueCode;

final class JsonValueWriter {
    static final Comparator<Utf8Key> UNSIGNED_UTF8 = (left, right) -> {
        if (left.utf8 == null || right.utf8 == null) {
            return compareCodePoints(left.value, right.value);
        }
        int length = Math.min(left.utf8.length, right.utf8.length);
        for (int index = 0; index < length; index++) {
            int comparison = Integer.compare(
                    left.utf8[index] & 0xff, right.utf8[index] & 0xff);
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(left.utf8.length, right.utf8.length);
    };

    private JsonValueWriter() { }

    static Utf8Key utf8Key(String value) {
        return utf8Key(value, true);
    }

    static Utf8Key utf8Key(String value, boolean cacheBytes) {
        if (!isScalarString(value)) {
            throw new IllegalArgumentException("key must contain Unicode scalar values");
        }
        return new Utf8Key(
                value, cacheBytes ? value.getBytes(StandardCharsets.UTF_8) : null);
    }

    static Serializer.PlannedValue plan(
            Object value, String path, int depth, Serializer.Context context) {
        if (!context.limits.countNode(path, context.sink)) {
            return Serializer.nullValue(context.size);
        }
        if (value == null) {
            return Serializer.nullValue(context.size);
        }
        if (value instanceof Boolean) {
            return Serializer.booleanValue((Boolean) value, context.size);
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer) {
            return Serializer.numberValue(
                    Integer.toString(((Number) value).intValue()), context.size);
        }
        if (value instanceof Long) {
            return Serializer.numberValue(Long.toString((Long) value), context.size);
        }
        if (value instanceof BigInteger) {
            return planBigInteger((BigInteger) value, context.size);
        }
        if (value instanceof BigDecimal) {
            return planBigDecimal((BigDecimal) value, context.size);
        }
        if (value instanceof Float) {
            float number = (Float) value;
            if (!Float.isFinite(number)) {
                context.sink.add(ValidationIssueCode.NON_FINITE, path);
                return Serializer.nullValue(context.size);
            }
            return Serializer.numberValue(Float.toString(number), context.size);
        }
        if (value instanceof Double) {
            double number = (Double) value;
            if (!Double.isFinite(number)) {
                context.sink.add(ValidationIssueCode.NON_FINITE, path);
                return Serializer.nullValue(context.size);
            }
            return Serializer.numberValue(Double.toString(number), context.size);
        }
        if (value instanceof String) {
            String string = (String) value;
            if (!isScalarString(string)) {
                context.sink.add(ValidationIssueCode.INVALID_UNICODE, path);
                return Serializer.nullValue(context.size);
            }
            return Serializer.stringValue(string, context.size);
        }
        if (value instanceof Map<?, ?>) {
            return planMap((Map<?, ?>) value, path, depth, context);
        }
        if (value instanceof Collection<?>) {
            return planCollection((Collection<?>) value, path, depth, context);
        }
        if (value instanceof Object[]) {
            return planObjectArray((Object[]) value, path, depth, context);
        }
        if (value instanceof boolean[]) {
            return planBooleanArray((boolean[]) value, path, depth, context);
        }
        if (value instanceof byte[]) {
            return planByteArray((byte[]) value, path, depth, context);
        }
        if (value instanceof short[]) {
            return planShortArray((short[]) value, path, depth, context);
        }
        if (value instanceof int[]) {
            return planIntArray((int[]) value, path, depth, context);
        }
        if (value instanceof long[]) {
            return planLongArray((long[]) value, path, depth, context);
        }
        if (value instanceof float[]) {
            return planFloatArray((float[]) value, path, depth, context);
        }
        if (value instanceof double[]) {
            return planDoubleArray((double[]) value, path, depth, context);
        }
        context.sink.add(ValidationIssueCode.INVALID_JSON, path);
        return Serializer.nullValue(context.size);
    }

    static boolean isScalarString(String value) {
        for (int index = 0; index < value.length();) {
            char first = value.charAt(index);
            if (Character.isHighSurrogate(first)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    return false;
                }
                index += 2;
            } else if (Character.isLowSurrogate(first)) {
                return false;
            } else {
                index++;
            }
        }
        return true;
    }

    private static Serializer.PlannedValue planMap(
            Map<?, ?> source, String path, int depth, Serializer.Context context) {
        if (!enterContainer(source, path, depth, context)) {
            return Serializer.nullValue(context.size);
        }

        String entryPath = Serializer.appendPath(path, "[{*}]");
        ArrayList<RawEntry> entries = new ArrayList<>();
        Set<String> names = new HashSet<>();
        boolean invalidKey = false;
        boolean invalidUnicode = false;
        boolean duplicateKey = false;
        try {
            context.size.add(2);
            Iterator<? extends Map.Entry<?, ?>> iterator = mapIterator(source);
            while (hasNext(iterator)) {
                if (!context.limits.countNode(entryPath, context.sink)) {
                    break;
                }
                Map.Entry<?, ?> entry = nextEntry(iterator);
                Object rawKey = entryKey(entry);
                if (!(rawKey instanceof String)) {
                    invalidKey = true;
                    continue;
                }
                String key = (String) rawKey;
                int quotedSize = Serializer.quotedUtf8Size(key);
                if (!entries.isEmpty()) {
                    context.size.add(1);
                }
                context.size.add(quotedSize);
                context.size.add(1);
                Utf8Key utf8;
                try {
                    utf8 = utf8Key(key, !context.size.isExceeded());
                } catch (IllegalArgumentException exception) {
                    invalidUnicode = true;
                    continue;
                }
                if (!names.add(key)) {
                    duplicateKey = true;
                    continue;
                }
                entries.add(new RawEntry(utf8, entry));
            }

            if (invalidKey || duplicateKey) {
                context.sink.add(ValidationIssueCode.INVALID_JSON, entryPath);
            }
            if (invalidUnicode) {
                context.sink.add(ValidationIssueCode.INVALID_UNICODE, entryPath);
            }
            if (invalidKey || invalidUnicode || duplicateKey) {
                return Serializer.nullValue(context.size);
            }

            entries.sort((left, right) -> UNSIGNED_UTF8.compare(left.key, right.key));
            if (context.size.isExceeded() || context.limits.isExhausted()) {
                return new Serializer.ObjectValue(new Serializer.Member[0]);
            }
            ArrayList<Serializer.Member> members = new ArrayList<>(entries.size());
            for (RawEntry entry : entries) {
                Object capturedValue = entryValue(entry.source);
                Serializer.PlannedValue value =
                        plan(capturedValue, entryPath, depth + 1, context);
                members.add(new Serializer.Member(entry.key.value, value));
                if (context.limits.isExhausted() || context.size.isExceeded()) {
                    break;
                }
            }
            return new Serializer.ObjectValue(members.toArray(new Serializer.Member[0]));
        } finally {
            context.active.remove(source);
        }
    }

    private static Serializer.PlannedValue planCollection(
            Collection<?> source, String path, int depth, Serializer.Context context) {
        if (!enterContainer(source, path, depth, context)) {
            return Serializer.nullValue(context.size);
        }
        context.size.add(2);
        ArrayList<Serializer.PlannedValue> values = new ArrayList<>();
        try {
            Iterator<?> iterator = collectionIterator(source);
            int index = 0;
            while (hasNext(iterator)) {
                Object captured = nextValue(iterator);
                if (!values.isEmpty()) {
                    context.size.add(1);
                }
                values.add(plan(captured,
                        Serializer.appendPath(path, "[" + index++ + "]"),
                        depth + 1,
                        context));
                if (context.limits.isExhausted() || context.size.isExceeded()) {
                    break;
                }
            }
            return new Serializer.ArrayValue(
                    values.toArray(new Serializer.PlannedValue[0]));
        } finally {
            context.active.remove(source);
        }
    }

    private static Serializer.PlannedValue planObjectArray(
            Object[] source, String path, int depth, Serializer.Context context) {
        if (!enterContainer(source, path, depth, context)) {
            return Serializer.nullValue(context.size);
        }
        context.size.add(2);
        ArrayList<Serializer.PlannedValue> values = new ArrayList<>();
        try {
            for (int index = 0; index < source.length; index++) {
                Object captured = source[index];
                if (!values.isEmpty()) {
                    context.size.add(1);
                }
                values.add(plan(captured,
                        Serializer.appendPath(path, "[" + index + "]"),
                        depth + 1,
                        context));
                if (context.limits.isExhausted() || context.size.isExceeded()) {
                    break;
                }
            }
            return new Serializer.ArrayValue(
                    values.toArray(new Serializer.PlannedValue[0]));
        } finally {
            context.active.remove(source);
        }
    }

    private static Serializer.PlannedValue planBooleanArray(
            boolean[] source, String path, int depth, Serializer.Context context) {
        ArrayList<Serializer.PlannedValue> values =
                beginPrimitiveArray(source.length, path, depth, context);
        if (values == null) {
            return Serializer.nullValue(context.size);
        }
        for (int index = 0; index < source.length; index++) {
            if (!context.limits.countNode(path, context.sink)) {
                break;
            }
            addSeparator(values, context.size);
            boolean captured = source[index];
            values.add(Serializer.booleanValue(captured, context.size));
            if (context.size.isExceeded()) {
                break;
            }
        }
        return array(values);
    }

    private static Serializer.PlannedValue planByteArray(
            byte[] source, String path, int depth, Serializer.Context context) {
        ArrayList<Serializer.PlannedValue> values =
                beginPrimitiveArray(source.length, path, depth, context);
        if (values == null) {
            return Serializer.nullValue(context.size);
        }
        for (int index = 0; index < source.length; index++) {
            if (!context.limits.countNode(path, context.sink)) {
                break;
            }
            addSeparator(values, context.size);
            byte captured = source[index];
            values.add(Serializer.numberValue(Byte.toString(captured), context.size));
            if (context.size.isExceeded()) {
                break;
            }
        }
        return array(values);
    }

    private static Serializer.PlannedValue planShortArray(
            short[] source, String path, int depth, Serializer.Context context) {
        ArrayList<Serializer.PlannedValue> values =
                beginPrimitiveArray(source.length, path, depth, context);
        if (values == null) {
            return Serializer.nullValue(context.size);
        }
        for (int index = 0; index < source.length; index++) {
            if (!context.limits.countNode(path, context.sink)) {
                break;
            }
            addSeparator(values, context.size);
            short captured = source[index];
            values.add(Serializer.numberValue(Short.toString(captured), context.size));
            if (context.size.isExceeded()) {
                break;
            }
        }
        return array(values);
    }

    private static Serializer.PlannedValue planIntArray(
            int[] source, String path, int depth, Serializer.Context context) {
        ArrayList<Serializer.PlannedValue> values =
                beginPrimitiveArray(source.length, path, depth, context);
        if (values == null) {
            return Serializer.nullValue(context.size);
        }
        for (int index = 0; index < source.length; index++) {
            if (!context.limits.countNode(path, context.sink)) {
                break;
            }
            addSeparator(values, context.size);
            int captured = source[index];
            values.add(Serializer.numberValue(Integer.toString(captured), context.size));
            if (context.size.isExceeded()) {
                break;
            }
        }
        return array(values);
    }

    private static Serializer.PlannedValue planLongArray(
            long[] source, String path, int depth, Serializer.Context context) {
        ArrayList<Serializer.PlannedValue> values =
                beginPrimitiveArray(source.length, path, depth, context);
        if (values == null) {
            return Serializer.nullValue(context.size);
        }
        for (int index = 0; index < source.length; index++) {
            if (!context.limits.countNode(path, context.sink)) {
                break;
            }
            addSeparator(values, context.size);
            long captured = source[index];
            values.add(Serializer.numberValue(Long.toString(captured), context.size));
            if (context.size.isExceeded()) {
                break;
            }
        }
        return array(values);
    }

    private static Serializer.PlannedValue planFloatArray(
            float[] source, String path, int depth, Serializer.Context context) {
        ArrayList<Serializer.PlannedValue> values =
                beginPrimitiveArray(source.length, path, depth, context);
        if (values == null) {
            return Serializer.nullValue(context.size);
        }
        for (int index = 0; index < source.length; index++) {
            if (!context.limits.countNode(path, context.sink)) {
                break;
            }
            addSeparator(values, context.size);
            float captured = source[index];
            String elementPath = Serializer.appendPath(path, "[" + index + "]");
            if (!Float.isFinite(captured)) {
                context.sink.add(ValidationIssueCode.NON_FINITE, elementPath);
                values.add(Serializer.nullValue(context.size));
            } else {
                values.add(Serializer.numberValue(Float.toString(captured), context.size));
            }
            if (context.size.isExceeded()) {
                break;
            }
        }
        return array(values);
    }

    private static Serializer.PlannedValue planDoubleArray(
            double[] source, String path, int depth, Serializer.Context context) {
        ArrayList<Serializer.PlannedValue> values =
                beginPrimitiveArray(source.length, path, depth, context);
        if (values == null) {
            return Serializer.nullValue(context.size);
        }
        for (int index = 0; index < source.length; index++) {
            if (!context.limits.countNode(path, context.sink)) {
                break;
            }
            addSeparator(values, context.size);
            double captured = source[index];
            String elementPath = Serializer.appendPath(path, "[" + index + "]");
            if (!Double.isFinite(captured)) {
                context.sink.add(ValidationIssueCode.NON_FINITE, elementPath);
                values.add(Serializer.nullValue(context.size));
            } else {
                values.add(Serializer.numberValue(Double.toString(captured), context.size));
            }
            if (context.size.isExceeded()) {
                break;
            }
        }
        return array(values);
    }

    private static ArrayList<Serializer.PlannedValue> beginPrimitiveArray(
            int length, String path, int depth, Serializer.Context context) {
        if (depth == Serializer.MAX_DEPTH) {
            context.sink.add(ValidationIssueCode.COLLECTION_LIMIT, path);
            return null;
        }
        if (length > context.limits.remainingNodes()) {
            context.sink.add(ValidationIssueCode.COLLECTION_LIMIT, path);
            return null;
        }
        context.size.add(2);
        return new ArrayList<>();
    }

    private static Serializer.PlannedValue array(
            ArrayList<Serializer.PlannedValue> values) {
        return new Serializer.ArrayValue(
                values.toArray(new Serializer.PlannedValue[0]));
    }

    private static void addSeparator(
            ArrayList<Serializer.PlannedValue> values, Serializer.SizeCounter size) {
        if (!values.isEmpty()) {
            size.add(1);
        }
    }

    private static boolean enterContainer(
            Object source, String path, int depth, Serializer.Context context) {
        if (depth == Serializer.MAX_DEPTH) {
            context.sink.add(ValidationIssueCode.COLLECTION_LIMIT, path);
            return false;
        }
        if (context.active.put(source, Boolean.TRUE) != null) {
            context.sink.add(ValidationIssueCode.CYCLE, path);
            return false;
        }
        return true;
    }

    private static Serializer.PlannedValue planBigInteger(
            BigInteger value, Serializer.SizeCounter size) {
        try {
            long lowerBound = Serializer.decimalDigitLowerBound(value.bitLength());
            if (value.signum() < 0) {
                lowerBound++;
            }
            if (lowerBound > size.remaining()) {
                size.exceed();
                return Serializer.numberValue("0", size);
            }
            BigInteger snapshot = new BigInteger(value.toByteArray());
            return Serializer.bigIntegerValue(snapshot, size);
        } catch (RuntimeException exception) {
            throw new SourceAccessException();
        }
    }

    private static Serializer.PlannedValue planBigDecimal(
            BigDecimal value, Serializer.SizeCounter size) {
        try {
            int scale = value.scale();
            BigInteger unscaled = value.unscaledValue();
            long lowerBound = Serializer.decimalDigitLowerBound(unscaled.bitLength())
                    + (unscaled.signum() < 0 ? 1L : 0L);
            if (lowerBound > size.remaining()) {
                size.exceed();
                return Serializer.numberValue("0", size);
            }
            BigInteger snapshot = new BigInteger(unscaled.toByteArray());
            return Serializer.bigDecimalValue(snapshot, scale, size);
        } catch (RuntimeException exception) {
            throw new SourceAccessException();
        }
    }

    private static int compareCodePoints(String left, String right) {
        int leftIndex = 0;
        int rightIndex = 0;
        while (leftIndex < left.length() && rightIndex < right.length()) {
            int leftCodePoint = left.codePointAt(leftIndex);
            int rightCodePoint = right.codePointAt(rightIndex);
            int comparison = Integer.compare(leftCodePoint, rightCodePoint);
            if (comparison != 0) {
                return comparison;
            }
            leftIndex += Character.charCount(leftCodePoint);
            rightIndex += Character.charCount(rightCodePoint);
        }
        return Integer.compare(left.length() - leftIndex, right.length() - rightIndex);
    }

    private static Iterator<? extends Map.Entry<?, ?>> mapIterator(Map<?, ?> source) {
        try {
            return source.entrySet().iterator();
        } catch (RuntimeException exception) {
            throw new SourceAccessException();
        }
    }

    private static Iterator<?> collectionIterator(Collection<?> source) {
        try {
            return source.iterator();
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

    private static Map.Entry<?, ?> nextEntry(
            Iterator<? extends Map.Entry<?, ?>> iterator) {
        try {
            return iterator.next();
        } catch (RuntimeException exception) {
            throw new SourceAccessException();
        }
    }

    private static Object nextValue(Iterator<?> iterator) {
        try {
            return iterator.next();
        } catch (RuntimeException exception) {
            throw new SourceAccessException();
        }
    }

    private static Object entryKey(Map.Entry<?, ?> entry) {
        try {
            return entry.getKey();
        } catch (RuntimeException exception) {
            throw new SourceAccessException();
        }
    }

    private static Object entryValue(Map.Entry<?, ?> entry) {
        try {
            return entry.getValue();
        } catch (RuntimeException exception) {
            throw new SourceAccessException();
        }
    }

    static final class Utf8Key {
        private final String value;
        private final @Nullable byte[] utf8;

        private Utf8Key(String value, @Nullable byte[] utf8) {
            this.value = value;
            this.utf8 = utf8;
        }
    }

    private static final class RawEntry {
        private final Utf8Key key;
        private final Map.Entry<?, ?> source;

        private RawEntry(Utf8Key key, Map.Entry<?, ?> source) {
            this.key = key;
            this.source = source;
        }
    }

    private static final class SourceAccessException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private SourceAccessException() {
            super(null, null, false, false);
        }
    }
}
