package sh.repost.client.descriptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable descriptor graph consumed by schema-neutral runtime serialization. */
public final class SchemaDescriptor {
    /** Descriptor format supported by this runtime. */
    public static final int SUPPORTED_FORMAT_VERSION = 2;

    private final int descriptorFormatVersion;
    private final Map<String, Map<String, String>> enums;
    private final Map<String, ModelDescriptor> models;
    private final Map<String, Map<String, EventDescriptor>> webhooks;

    private SchemaDescriptor(Builder builder) {
        if (builder.descriptorFormatVersion < 1) {
            throw new IllegalArgumentException("descriptorFormatVersion must be positive");
        }
        this.descriptorFormatVersion = builder.descriptorFormatVersion;
        this.enums = immutableNestedStrings(builder.enums);
        this.models = Collections.unmodifiableMap(new LinkedHashMap<>(builder.models));
        this.webhooks = immutableEvents(builder.webhooks);
        validateReferences();
    }

    /**
     * Starts a schema descriptor builder.
     *
     * @param descriptorFormatVersion generated descriptor format version
     * @return schema builder
     */
    public static Builder builder(int descriptorFormatVersion) {
        return new Builder(descriptorFormatVersion);
    }

    /**
     * Returns the generated format version.
     *
     * @return descriptor format version
     */
    public int getDescriptorFormatVersion() { return descriptorFormatVersion; }
    /**
     * Returns enum wire mappings by descriptor identifier.
     *
     * @return immutable enum mappings
     */
    public Map<String, Map<String, String>> getEnums() { return enums; }
    /**
     * Returns models by descriptor identifier.
     *
     * @return immutable model mappings
     */
    public Map<String, ModelDescriptor> getModels() { return models; }
    /**
     * Returns webhook catalogs and members.
     *
     * @return immutable webhook mappings
     */
    public Map<String, Map<String, EventDescriptor>> getWebhooks() { return webhooks; }

    private void validateReferences() {
        for (ModelDescriptor model : models.values()) {
            for (FieldDescriptor field : model.getFields()) {
                if (field.getScalarKind() == ScalarKind.MODEL
                        && !models.containsKey(field.getDescriptorId())) {
                    throw new IllegalArgumentException("model field references a missing descriptor");
                }
                if (field.getScalarKind() == ScalarKind.ENUM
                        && !enums.containsKey(field.getDescriptorId())) {
                    throw new IllegalArgumentException("enum field references a missing descriptor");
                }
            }
        }
        Set<String> eventTypes = new HashSet<>();
        for (Map<String, EventDescriptor> members : webhooks.values()) {
            for (EventDescriptor event : members.values()) {
                if (!models.containsKey(event.getModelId())) {
                    throw new IllegalArgumentException("event references a missing model descriptor");
                }
                if (!eventTypes.add(event.getType())) {
                    throw new IllegalArgumentException("event types must be unique");
                }
            }
        }
    }

    private static Map<String, Map<String, String>> immutableNestedStrings(
            Map<String, LinkedHashMap<String, String>> source) {
        LinkedHashMap<String, Map<String, String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashMap<String, String>> entry : source.entrySet()) {
            copy.put(entry.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, Map<String, EventDescriptor>> immutableEvents(
            Map<String, LinkedHashMap<String, EventDescriptor>> source) {
        LinkedHashMap<String, Map<String, EventDescriptor>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashMap<String, EventDescriptor>> entry : source.entrySet()) {
            copy.put(entry.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(copy);
    }

    /** Builder for an immutable descriptor graph. */
    public static final class Builder {
        private final int descriptorFormatVersion;
        private final LinkedHashMap<String, LinkedHashMap<String, String>> enums =
                new LinkedHashMap<>();
        private final LinkedHashMap<String, ModelDescriptor> models = new LinkedHashMap<>();
        private final LinkedHashMap<String, LinkedHashMap<String, EventDescriptor>> webhooks =
                new LinkedHashMap<>();

        private Builder(int descriptorFormatVersion) {
            this.descriptorFormatVersion = descriptorFormatVersion;
        }

        /**
         * Adds one enum descriptor.
         *
         * @param id enum identifier
         * @param members declaration-ordered generated-to-wire member mappings
         * @return this builder
         */
        public Builder addEnum(String id, Map<String, String> members) {
            requireId(id, "enum id");
            Objects.requireNonNull(members, "members");
            if (enums.containsKey(id)) {
                throw new IllegalArgumentException("duplicate enum id");
            }
            LinkedHashMap<String, String> copy = new LinkedHashMap<>();
            Set<String> wireValues = new HashSet<>();
            for (Map.Entry<String, String> member : members.entrySet()) {
                String name = requireId(member.getKey(), "enum member");
                String wireValue = DescriptorText.requireText(
                        member.getValue(), "enum wire value");
                if (copy.put(name, wireValue) != null || !wireValues.add(wireValue)) {
                    throw new IllegalArgumentException("duplicate enum member or wire value");
                }
            }
            enums.put(id, copy);
            return this;
        }

        /**
         * Adds one model descriptor.
         *
         * @param model model descriptor
         * @return this builder
         */
        public Builder addModel(ModelDescriptor model) {
            Objects.requireNonNull(model, "model");
            if (models.putIfAbsent(model.getId(), model) != null) {
                throw new IllegalArgumentException("duplicate model id");
            }
            return this;
        }

        /**
         * Adds one generated webhook member.
         *
         * @param catalog generated catalog name
         * @param member generated member name
         * @param event event descriptor
         * @return this builder
         */
        public Builder addEvent(String catalog, String member, EventDescriptor event) {
            requireId(catalog, "catalog");
            requireId(member, "member");
            Objects.requireNonNull(event, "event");
            LinkedHashMap<String, EventDescriptor> members =
                    webhooks.computeIfAbsent(catalog, ignored -> new LinkedHashMap<>());
            if (members.putIfAbsent(member, event) != null) {
                throw new IllegalArgumentException("duplicate catalog member");
            }
            return this;
        }

        /**
         * Returns the validated descriptor graph.
         *
         * @return immutable schema descriptor
         */
        public SchemaDescriptor build() { return new SchemaDescriptor(this); }

        private static String requireId(String value, String name) {
            return DescriptorText.requireIdentifier(value, name);
        }
    }
}
