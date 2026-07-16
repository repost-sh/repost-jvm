package sh.repost.client.test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Thread-safe manually released asynchronous barrier with exact byte weights. */
public final class WeightedByteBarrier {
    private final ArrayDeque<Waiter> waiters = new ArrayDeque<>();
    private long waitingBytes;

    /** Creates an empty byte barrier. */
    public WeightedByteBarrier() { }

    /**
     * Adds one waiter with the supplied nonnegative byte weight.
     *
     * @param bytes byte weight
     * @return future completed only when manually released
     */
    public synchronized CompletionStage<Void> arrive(long bytes) {
        if (bytes < 0L) {
            throw new IllegalArgumentException("bytes must not be negative");
        }
        waitingBytes = Math.addExact(waitingBytes, bytes);
        Waiter waiter = new Waiter(bytes);
        waiters.addLast(waiter);
        return waiter.release;
    }

    /**
     * Releases the oldest active waiter.
     *
     * @return whether a waiter was released
     */
    public boolean releaseOne() {
        Waiter waiter;
        synchronized (this) {
            discardCancelled();
            waiter = waiters.pollFirst();
            if (waiter == null) {
                return false;
            }
            waitingBytes -= waiter.bytes;
        }
        waiter.release.complete(null);
        return true;
    }

    /**
     * Releases every active waiter in arrival order.
     *
     * @return number of waiters released
     */
    public int releaseAll() {
        List<Waiter> released;
        synchronized (this) {
            discardCancelled();
            released = new ArrayList<>(waiters);
            waiters.clear();
            waitingBytes = 0L;
        }
        for (Waiter waiter : released) {
            waiter.release.complete(null);
        }
        return released.size();
    }

    /**
     * Returns active waiter count.
     *
     * @return active waiter count
     */
    public synchronized int getWaitingCount() {
        discardCancelled();
        return waiters.size();
    }

    /**
     * Returns the exact sum of active byte weights.
     *
     * @return waiting byte weight
     */
    public synchronized long getWaitingBytes() {
        discardCancelled();
        return waitingBytes;
    }

    private void discardCancelled() {
        Iterator<Waiter> iterator = waiters.iterator();
        while (iterator.hasNext()) {
            Waiter waiter = iterator.next();
            if (waiter.release.isCancelled()) {
                waitingBytes -= waiter.bytes;
                iterator.remove();
            }
        }
    }

    private static final class Waiter {
        private final long bytes;
        private final CompletableFuture<Void> release = new CompletableFuture<>();

        private Waiter(long bytes) {
            this.bytes = bytes;
        }
    }
}
