package sh.repost.client;

/**
 * Reflection-free factory emitted for one generated schema-specific client type.
 *
 * @param <T> concrete generated client type
 */
public interface GeneratedRepostClientFactory<T> {
    /**
     * Returns the concrete generated client type.
     *
     * @return the concrete generated client type
     */
    Class<T> clientType();

    /**
     * Creates a cheap typed facade over an existing runtime.
     *
     * @param runtime shared runtime used by the generated client
     * @return generated client facade
     */
    T create(RepostRuntime runtime);
}
