package sh.repost.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class RepostValuesTest {
    @Test
    void snapshotsJsonRecursivelyInCanonicalKeyOrder() {
        ArrayList<Object> nested = new ArrayList<>(Arrays.asList("first", 2L));
        LinkedHashMap<String, Object> source = new LinkedHashMap<>();
        source.put("z", nested);
        source.put("a", true);

        @SuppressWarnings("unchecked")
        Map<String, Object> snapshot = (Map<String, Object>) RepostValues.snapshotJson(source);
        nested.clear();
        source.clear();

        assertEquals(Arrays.asList("a", "z"), new ArrayList<>(snapshot.keySet()));
        assertEquals(Arrays.asList("first", 2L), snapshot.get("z"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.clear());
        @SuppressWarnings("unchecked")
        List<Object> nestedSnapshot = (List<Object>) snapshot.get("z");
        assertThrows(UnsupportedOperationException.class, nestedSnapshot::clear);
    }

    @Test
    void snapshotsGeneratedListsWithoutCallerMutationOrPartialPublication() {
        ArrayList<String> source = new ArrayList<>(Arrays.asList("a", "b"));
        List<String> snapshot = RepostValues.snapshotList(source);
        source.clear();

        assertEquals(Arrays.asList("a", "b"), snapshot);
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
        assertEquals(Arrays.asList("x", "y"),
                RepostValues.snapshotList(new String[] {"x", "y"}));
        assertThrows(IllegalArgumentException.class,
                () -> RepostValues.snapshotList(Arrays.asList("x", null)));
    }

    @Test
    void rejectsUnsupportedJsonBeforeTraversingMapValues() {
        LinkedHashMap<Object, Object> invalidKey = new LinkedHashMap<>();
        invalidKey.put(1, new Object());
        assertThrows(IllegalArgumentException.class,
                () -> RepostValues.snapshotJson(invalidKey));

        Iterable<Integer> infinite = () -> new java.util.Iterator<Integer>() {
            @Override public boolean hasNext() { return true; }
            @Override public Integer next() { return 1; }
        };
        assertThrows(IllegalArgumentException.class, () -> RepostValues.snapshotJson(infinite));
        assertThrows(IllegalArgumentException.class,
                () -> RepostValues.snapshotJson(Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> RepostValues.snapshotJson(new Number() {
                    private static final long serialVersionUID = 1L;
                    @Override public int intValue() { return 0; }
                    @Override public long longValue() { return 0L; }
                    @Override public float floatValue() { return 0.0f; }
                    @Override public double doubleValue() { return 0.0d; }
                }));
        assertThrows(IllegalArgumentException.class,
                () -> RepostValues.snapshotJson("\ud800"));

        assertEquals(Arrays.asList(1, 2, 3), RepostValues.snapshotJson(new int[] {1, 2, 3}));
    }

    @Test
    void permitsSharedContainersButRejectsCyclesAndExcessiveDepth() {
        LinkedHashMap<String, Object> shared = new LinkedHashMap<>();
        shared.put("leaf", 1);
        LinkedHashMap<String, Object> siblings = new LinkedHashMap<>();
        siblings.put("left", shared);
        siblings.put("right", shared);
        assertEquals(
                "{left={leaf=1}, right={leaf=1}}",
                RepostValues.snapshotJson(siblings).toString());

        LinkedHashMap<String, Object> cycle = new LinkedHashMap<>();
        cycle.put("self", cycle);
        assertThrows(IllegalArgumentException.class, () -> RepostValues.snapshotJson(cycle));

        Object deep = "leaf";
        for (int index = 0; index < RepostValues.MAX_SNAPSHOT_DEPTH + 1; index++) {
            deep = Collections.singletonList(deep);
        }
        Object excessive = deep;
        assertThrows(IllegalArgumentException.class, () -> RepostValues.snapshotJson(excessive));
    }

    @Test
    void enforcesNodeAndUtf8LowerBoundCapsAtExactBoundaries() {
        Object exactNodes = RepostValues.snapshotJson(
                Collections.nCopies(RepostValues.MAX_SNAPSHOT_NODES - 1, 1));
        assertEquals(RepostValues.MAX_SNAPSHOT_NODES - 1, ((List<?>) exactNodes).size());
        assertThrows(IllegalArgumentException.class, () -> RepostValues.snapshotJson(
                Collections.nCopies(RepostValues.MAX_SNAPSHOT_NODES, 1)));

        String exactBytes = "a".repeat(RepostValues.MAX_SNAPSHOT_UTF8_BYTES - 2);
        assertEquals(exactBytes, RepostValues.snapshotJson(exactBytes));
        String oversized = exactBytes + "a";
        assertThrows(IllegalArgumentException.class, () -> RepostValues.snapshotJson(oversized));
    }

    @Test
    void rejectsKnownOversizeCollectionsBeforeIteration() {
        CollectionProbe probe = new CollectionProbe();
        assertThrows(IllegalArgumentException.class, () -> RepostValues.snapshotJson(probe));
        assertEquals(0, probe.iteratorCalls);
    }

    @Test
    void boundsAndSanitizesLyingAndThrowingCollectionMetadata() {
        IllegalArgumentException throwing = assertThrows(
                IllegalArgumentException.class,
                () -> RepostValues.snapshotList(new ThrowingSizeCollection()));
        assertFalse(throwing.getMessage().contains("sentinel-size-failure"));

        LyingSizeCollection lying = new LyingSizeCollection();
        assertEquals(Arrays.asList("a", "b", "c"), RepostValues.snapshotList(lying));
        assertEquals(Arrays.asList("a", "b", "c"), RepostValues.snapshotJson(lying));

        CountingSizeMap map = new CountingSizeMap();
        map.put("z", 1L);
        map.put("a", 2L);
        map.sizeCalls = 0;
        assertEquals("{a=2, z=1}", RepostValues.snapshotJson(map).toString());
        assertEquals(1, map.sizeCalls);
    }

    @Test
    void comparesHashesAndRendersPresenceWithoutValues() {
        IndexedModel absent = new IndexedModel(new boolean[] {false}, new Object[] {null});
        IndexedModel explicitNull = new IndexedModel(new boolean[] {true}, new Object[] {null});
        assertFalse(RepostValues.fieldsEqual(absent, explicitNull, 1));
        assertNotEquals(
                RepostValues.fieldsHashCode(absent, 1),
                RepostValues.fieldsHashCode(explicitNull, 1));

        LinkedHashMap<String, Object> forward = new LinkedHashMap<>();
        forward.put("a", 1L);
        forward.put("z", Arrays.asList("same", true));
        LinkedHashMap<String, Object> reverse = new LinkedHashMap<>();
        reverse.put("z", Arrays.asList("same", true));
        reverse.put("a", 1L);
        IndexedModel left = new IndexedModel(
                new boolean[] {true, true},
                new Object[] {Arrays.asList("x"), RepostValues.snapshotJson(forward)});
        IndexedModel right = new IndexedModel(
                new boolean[] {true, true},
                new Object[] {Arrays.asList("x"), RepostValues.snapshotJson(reverse)});
        assertTrue(RepostValues.fieldsEqual(left, right, 2));
        assertEquals(
                RepostValues.fieldsHashCode(left, 2),
                RepostValues.fieldsHashCode(right, 2));

        IndexedModel firstSlot = new IndexedModel(
                new boolean[] {true, false}, new Object[] {"x", null});
        IndexedModel secondSlot = new IndexedModel(
                new boolean[] {false, true}, new Object[] {null, "x"});
        assertNotEquals(
                RepostValues.fieldsHashCode(firstSlot, 2),
                RepostValues.fieldsHashCode(secondSlot, 2));

        IndexedModel redacted = new IndexedModel(
                new boolean[] {true, false, true},
                new Object[] {"sentinel-secret", null, forward});
        String rendered = RepostValues.redactedToString(
                "Payload", redacted, new String[] {"first", "second", "metadata"});
        assertEquals("Payload[setFields=[first, metadata]]", rendered);
        assertFalse(rendered.contains("sentinel-secret"));
    }

    private static final class CollectionProbe extends AbstractCollection<Object> {
        private int iteratorCalls;

        @Override
        public Iterator<Object> iterator() {
            iteratorCalls++;
            throw new AssertionError("oversize collection was iterated");
        }

        @Override
        public int size() {
            return RepostValues.MAX_SNAPSHOT_NODES;
        }
    }

    private static final class ThrowingSizeCollection extends AbstractCollection<String> {
        @Override
        public Iterator<String> iterator() {
            return Collections.emptyIterator();
        }

        @Override
        public int size() {
            throw new IllegalStateException("sentinel-size-failure");
        }
    }

    private static final class LyingSizeCollection extends AbstractCollection<String> {
        @Override
        public Iterator<String> iterator() {
            return Arrays.asList("a", "b", "c").iterator();
        }

        @Override
        public int size() {
            return 0;
        }
    }

    private static final class CountingSizeMap extends LinkedHashMap<String, Object> {
        private static final long serialVersionUID = 1L;
        private int sizeCalls;

        @Override
        public int size() {
            sizeCalls++;
            return super.size();
        }
    }

    private static final class IndexedModel implements RepostModel {
        private final boolean[] present;
        private final Object[] values;

        private IndexedModel(boolean[] present, Object[] values) {
            this.present = present;
            this.values = values;
        }

        @Override
        public boolean __repostIsPresent(int fieldIndex) {
            return present[fieldIndex];
        }

        @Override
        public Object __repostValue(int fieldIndex) {
            return values[fieldIndex];
        }
    }
}
