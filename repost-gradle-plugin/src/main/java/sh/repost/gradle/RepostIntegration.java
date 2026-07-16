package sh.repost.gradle;

/** Build-time generated-client integration selection. */
public enum RepostIntegration {
    /** Select from exactly one declared supported adapter, or use plain core when none is declared. */
    AUTO,
    /** Generate only the framework-neutral registry. */
    NONE,
    /** Generate Spring Boot direct-reference glue. */
    SPRING_BOOT,
    /** Generate Jakarta CDI direct-reference glue. */
    CDI,
}
