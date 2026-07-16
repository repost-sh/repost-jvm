package sh.repost.client.test;

import java.util.AbstractList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import sh.repost.client.TransportHeaderField;

/** Lazy counted header list for proving bounded single-pass response validation. */
public final class LazyHeaderFields extends AbstractList<TransportHeaderField> {
    private final List<TransportHeaderField> prefix;
    private final int totalSize;
    private final TransportHeaderField tail;
    private final AtomicInteger accessCount = new AtomicInteger();
    private final AtomicInteger highestAccessedIndex = new AtomicInteger(-1);

    private LazyHeaderFields(
            List<TransportHeaderField> prefix,
            int totalSize,
            TransportHeaderField tail) {
        this.prefix = List.copyOf(prefix);
        if (totalSize < this.prefix.size()) {
            throw new IllegalArgumentException("totalSize must include the prefix");
        }
        this.totalSize = totalSize;
        this.tail = Objects.requireNonNull(tail, "tail");
    }

    /**
     * Creates a lazy list with an explicit prefix and one repeated tail field.
     *
     * @param prefix eagerly retained prefix
     * @param totalSize total reported list size
     * @param tail field returned after the prefix
     * @return lazy counted list
     */
    public static LazyHeaderFields of(
            List<TransportHeaderField> prefix,
            int totalSize,
            TransportHeaderField tail) {
        return new LazyHeaderFields(
                Objects.requireNonNull(prefix, "prefix"), totalSize, tail);
    }

    @Override
    public TransportHeaderField get(int index) {
        if (index < 0 || index >= totalSize) {
            throw new IndexOutOfBoundsException("index: " + index);
        }
        accessCount.incrementAndGet();
        highestAccessedIndex.accumulateAndGet(index, Math::max);
        return index < prefix.size() ? prefix.get(index) : tail;
    }

    @Override
    public int size() {
        return totalSize;
    }

    /**
     * Returns total indexed element accesses.
     *
     * @return indexed access count
     */
    public int getAccessCount() {
        return accessCount.get();
    }

    /**
     * Returns the highest accessed index, or {@code -1} before traversal.
     *
     * @return highest accessed index
     */
    public int getHighestAccessedIndex() {
        return highestAccessedIndex.get();
    }
}
