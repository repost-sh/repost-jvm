package sh.repost.client;

/** Applies one application-level customization while constructing client options. */
@FunctionalInterface
public interface ClientOptionsCustomizer {
    /**
     * Customizes the supplied builder.
     *
     * @param builder builder being configured
     */
    void customize(ClientOptions.Builder builder);
}
