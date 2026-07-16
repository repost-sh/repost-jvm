package sh.repost.client.test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import sh.repost.client.Transport;
import sh.repost.client.TransportResponse;

/** Per-test no-network sentinel that changes no JVM-global state. */
public final class NoNetworkGuard {
    private final AtomicInteger attempts = new AtomicInteger();
    private final Transport transport = request -> {
        attempts.incrementAndGet();
        CompletableFuture<TransportResponse> failed = new CompletableFuture<>();
        failed.completeExceptionally(new NetworkAccessAttempt());
        return failed;
    };

    /** Creates a new scoped guard. */
    public NoNetworkGuard() { }

    /**
     * Returns the sentinel transport to inject into one runtime.
     *
     * @return sentinel transport
     */
    public Transport transport() {
        return transport;
    }

    /**
     * Returns attempted network operation count.
     *
     * @return attempt count
     */
    public int getAttemptCount() {
        return attempts.get();
    }

    /** Fails when a guarded runtime attempted transport access. */
    public void assertNoAccess() {
        if (attempts.get() != 0) {
            throw new NetworkAccessAttempt();
        }
    }

    /** Sanitized assertion raised when guarded transport access occurs. */
    public static final class NetworkAccessAttempt extends AssertionError {
        private static final long serialVersionUID = 1L;

        private NetworkAccessAttempt() {
            super("A no-network test runtime attempted transport access.");
        }
    }
}
