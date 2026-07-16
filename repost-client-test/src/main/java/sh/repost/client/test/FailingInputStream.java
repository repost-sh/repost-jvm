package sh.repost.client.test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic stream that emits a prefix and then throws one configured I/O failure. */
public final class FailingInputStream extends InputStream {
    private final byte[] prefix;
    private final IOException failure;
    private final AtomicInteger closeCount = new AtomicInteger();
    private int index;

    /**
     * Creates a failing stream.
     *
     * @param prefix bytes returned before failure
     * @param failure failure thrown after the prefix
     */
    public FailingInputStream(byte[] prefix, IOException failure) {
        this.prefix = Objects.requireNonNull(prefix, "prefix").clone();
        this.failure = Objects.requireNonNull(failure, "failure");
    }

    @Override
    public synchronized int read() throws IOException {
        if (index < prefix.length) {
            return prefix[index++] & 0xff;
        }
        throw failure;
    }

    @Override
    public void close() {
        closeCount.incrementAndGet();
    }

    /**
     * Returns how many times callers invoked close.
     *
     * @return close invocation count
     */
    public int getCloseCount() {
        return closeCount.get();
    }
}
