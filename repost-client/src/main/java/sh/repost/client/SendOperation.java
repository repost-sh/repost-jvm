package sh.repost.client;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;

/** Cancellable asynchronous send with stable reconciliation outcome metadata. */
public interface SendOperation extends CompletionStage<SendResult>, Future<SendResult> {
    /**
     * Returns a non-cancellable stage that settles with the best-known delivery outcome.
     *
     * @return a non-cancellable stage that settles with the best-known delivery outcome
     */
    CompletionStage<SendOutcome> outcome();
}
