package sh.repost.client.descriptor;

/** Immutable mapping from a generated event member to its wire type and model. */
public final class EventDescriptor {
    private final String type;
    private final String modelId;

    private EventDescriptor(String type, String modelId) {
        this.type = DescriptorText.requireText(type, "type");
        this.modelId = DescriptorText.requireIdentifier(modelId, "modelId");
    }

    /**
     * Creates an event descriptor.
     *
     * @param type wire event type
     * @param modelId referenced model identifier
     * @return immutable event descriptor
     */
    public static EventDescriptor of(String type, String modelId) {
        return new EventDescriptor(type, modelId);
    }

    /**
     * Returns the wire event type.
     *
     * @return event type
     */
    public String getType() { return type; }
    /**
     * Returns the referenced model identifier.
     *
     * @return model identifier
     */
    public String getModelId() { return modelId; }

}
