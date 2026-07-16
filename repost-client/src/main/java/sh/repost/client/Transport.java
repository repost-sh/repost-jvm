package sh.repost.client;

import java.util.concurrent.CompletionStage;

/** Executes exactly one transport attempt. */
@FunctionalInterface
public interface Transport {
    /**
     * Executes exactly one request attempt without SDK-level retry.
     *
     * @param request immutable request snapshot
     * @return stage completed with an owned response or transport failure
     */
    CompletionStage<TransportResponse> execute(TransportRequest request);
}
