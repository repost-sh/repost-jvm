package sh.repost.client;

import org.jspecify.annotations.Nullable;

/** Indexed bridge implemented by immutable generated models without reflection. */
public interface RepostModel {
    /**
     * Reports whether a generated field was explicitly assigned.
     *
     * @param fieldIndex descriptor field index
     * @return {@code true} for present values, including explicit null
     */
    boolean __repostIsPresent(int fieldIndex);

    /**
     * Returns the snapshotted generated field value.
     *
     * @param fieldIndex descriptor field index
     * @return field value, including {@code null} for explicit null
     */
    @Nullable Object __repostValue(int fieldIndex);
}
