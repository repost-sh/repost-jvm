package sh.repost.client.descriptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable declaration-ordered descriptor for one generated model. */
public final class ModelDescriptor {
    private final String id;
    private final List<FieldDescriptor> fields;

    private ModelDescriptor(String id, List<FieldDescriptor> fields) {
        this.id = requireId(id);
        Objects.requireNonNull(fields, "fields");
        if (fields.size() > 512) {
            throw new IllegalArgumentException("models support at most 512 fields");
        }
        ArrayList<FieldDescriptor> copy = new ArrayList<>(fields.size());
        Set<String> wireNames = new HashSet<>();
        Set<String> schemaNames = new HashSet<>();
        int expectedIndex = 0;
        for (FieldDescriptor field : fields) {
            Objects.requireNonNull(field, "fields contains null");
            if (field.getFieldIndex() != expectedIndex++) {
                throw new IllegalArgumentException("field indices must be contiguous declaration order");
            }
            if (!wireNames.add(field.getWireName()) || !schemaNames.add(field.getSchemaName())) {
                throw new IllegalArgumentException("field names must be unique within a model");
            }
            copy.add(field);
        }
        this.fields = Collections.unmodifiableList(copy);
    }

    /**
     * Creates a model descriptor with contiguous field indices.
     *
     * @param id model identifier
     * @param fields declaration-ordered fields
     * @return immutable model descriptor
     */
    public static ModelDescriptor of(String id, List<FieldDescriptor> fields) {
        return new ModelDescriptor(id, fields);
    }

    /**
     * Returns the model identifier.
     *
     * @return model identifier
     */
    public String getId() { return id; }
    /**
     * Returns fields in declaration order.
     *
     * @return immutable field list
     */
    public List<FieldDescriptor> getFields() { return fields; }

    private static String requireId(String value) {
        return DescriptorText.requireIdentifier(value, "model id");
    }
}
