package sh.repost.gradle;

/** Schema input mode for Repost generation. */
public enum RepostSchemaMode {
    /** Generate clients from the configured local schema and aggregate any dependency registries. */
    GENERATE,
    /** Generate an application registry only from dependency registries. */
    AGGREGATE_ONLY,
}
