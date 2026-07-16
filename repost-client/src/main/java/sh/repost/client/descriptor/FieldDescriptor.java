package sh.repost.client.descriptor;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Immutable declaration-ordered descriptor for one generated model field. */
public final class FieldDescriptor {
    private final int fieldIndex;
    private final String schemaName;
    private final String wireName;
    private final ScalarKind scalarKind;
    private final String descriptorId;
    private final boolean requiredInInput;
    private final boolean nullableInInput;
    private final boolean list;
    private final DefaultSpec defaultSpec;

    private FieldDescriptor(Builder builder) {
        if (builder.fieldIndex < 0 || builder.fieldIndex >= 512) {
            throw new IllegalArgumentException("fieldIndex must be within 0..511");
        }
        this.fieldIndex = builder.fieldIndex;
        this.schemaName = DescriptorText.requireIdentifier(builder.schemaName, "schemaName");
        this.wireName = DescriptorText.requireText(builder.wireName, "wireName");
        this.scalarKind = Objects.requireNonNull(builder.scalarKind, "scalarKind");
        boolean referencesDescriptor = scalarKind == ScalarKind.ENUM || scalarKind == ScalarKind.MODEL;
        if (referencesDescriptor != (builder.descriptorId != null)) {
            throw new IllegalArgumentException("descriptorId is required only for enum/model fields");
        }
        this.descriptorId = builder.descriptorId == null
                ? null : DescriptorText.requireIdentifier(builder.descriptorId, "descriptorId");
        this.requiredInInput = builder.requiredInInput;
        this.nullableInInput = builder.nullableInInput;
        this.list = builder.list;
        if (list && nullableInInput) {
            // List elements are non-null, but the list field itself may still be nullable.
        }
        if (builder.defaultSpec != null && requiredInInput) {
            throw new IllegalArgumentException("a defaulted field cannot be required in input");
        }
        this.defaultSpec = builder.defaultSpec;
    }

    /**
     * Starts a field descriptor builder.
     *
     * @param fieldIndex zero-based declaration index
     * @param schemaName generated schema-facing name
     * @param wireName serialized field name
     * @param scalarKind field scalar category
     * @return field builder
     */
    public static Builder builder(
            int fieldIndex,
            String schemaName,
            String wireName,
            ScalarKind scalarKind) {
        return new Builder(fieldIndex, schemaName, wireName, scalarKind);
    }

    /**
     * Returns the declaration index.
     *
     * @return field index
     */
    public int getFieldIndex() { return fieldIndex; }
    /**
     * Returns the schema-facing name.
     *
     * @return schema name
     */
    public String getSchemaName() { return schemaName; }
    /**
     * Returns the serialized name.
     *
     * @return wire name
     */
    public String getWireName() { return wireName; }
    /**
     * Returns the scalar category.
     *
     * @return scalar kind
     */
    public ScalarKind getScalarKind() { return scalarKind; }
    /**
     * Returns the referenced descriptor.
     *
     * @return enum/model identifier or {@code null}
     */
    public @Nullable String getDescriptorId() { return descriptorId; }
    /**
     * Reports whether callers must supply the field.
     *
     * @return required-input flag
     */
    public boolean isRequiredInInput() { return requiredInInput; }
    /**
     * Reports whether explicit null is allowed.
     *
     * @return nullable-input flag
     */
    public boolean isNullableInInput() { return nullableInInput; }
    /**
     * Reports whether the value is a list.
     *
     * @return list flag
     */
    public boolean isList() { return list; }
    /**
     * Returns the default descriptor.
     *
     * @return default descriptor or {@code null}
     */
    public @Nullable DefaultSpec getDefaultSpec() { return defaultSpec; }

    /** Builder for an immutable field descriptor. */
    public static final class Builder {
        private final int fieldIndex;
        private final String schemaName;
        private final String wireName;
        private final ScalarKind scalarKind;
        private String descriptorId;
        private boolean requiredInInput;
        private boolean nullableInInput;
        private boolean list;
        private DefaultSpec defaultSpec;

        private Builder(int fieldIndex, String schemaName, String wireName, ScalarKind scalarKind) {
            this.fieldIndex = fieldIndex;
            this.schemaName = schemaName;
            this.wireName = wireName;
            this.scalarKind = scalarKind;
        }

        /**
         * Sets the referenced enum/model descriptor.
         *
         * @param value identifier or {@code null}
         * @return this builder
         */
        public Builder descriptorId(@Nullable String value) { this.descriptorId = value; return this; }
        /**
         * Sets required-input semantics.
         *
         * @param value required flag
         * @return this builder
         */
        public Builder requiredInInput(boolean value) { this.requiredInInput = value; return this; }
        /**
         * Sets explicit-null semantics.
         *
         * @param value nullable flag
         * @return this builder
         */
        public Builder nullableInInput(boolean value) { this.nullableInInput = value; return this; }
        /**
         * Sets list semantics.
         *
         * @param value list flag
         * @return this builder
         */
        public Builder list(boolean value) { this.list = value; return this; }
        /**
         * Sets the field default.
         *
         * @param value default descriptor or {@code null}
         * @return this builder
         */
        public Builder defaultSpec(@Nullable DefaultSpec value) { this.defaultSpec = value; return this; }
        /**
         * Builds a validated descriptor.
         *
         * @return immutable field descriptor
         */
        public FieldDescriptor build() { return new FieldDescriptor(this); }
    }
}
