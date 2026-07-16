package sh.repost.client;

import java.util.List;

/** Deterministic registry of direct generated-client factory references. */
public interface GeneratedRepostClientRegistry {
    /**
     * Returns immutable factories in deterministic registry order.
     *
     * @return immutable factories in deterministic registry order
     */
    List<GeneratedRepostClientFactory<?>> factories();
}
