package sh.repost.client.test;

import java.util.concurrent.CompletionStage;

/** Thread-safe manually released asynchronous barrier for concurrent operations. */
public final class ConcurrencyBarrier {
    private final WeightedByteBarrier delegate = new WeightedByteBarrier();

    /** Creates an empty concurrency barrier. */
    public ConcurrencyBarrier() { }

    /**
     * Adds one waiter.
     *
     * @return future completed only when manually released
     */
    public CompletionStage<Void> arrive() {
        return delegate.arrive(1L);
    }

    /**
     * Releases the oldest active waiter.
     *
     * @return whether a waiter was released
     */
    public boolean releaseOne() {
        return delegate.releaseOne();
    }

    /**
     * Releases every active waiter in arrival order.
     *
     * @return number of waiters released
     */
    public int releaseAll() {
        return delegate.releaseAll();
    }

    /**
     * Returns active waiter count.
     *
     * @return active waiter count
     */
    public int getWaitingCount() {
        return delegate.getWaitingCount();
    }
}
