package sh.repost.client.test;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** One-reader input stream whose pending read is deterministically released by close. */
public final class BlockingInputStream extends InputStream {
    private final CountDownLatch readStarted = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);
    private final AtomicBoolean readerClaimed = new AtomicBoolean();
    private final AtomicInteger closeCount = new AtomicInteger();
    private volatile boolean closed;

    /** Creates a blocking stream. */
    public BlockingInputStream() { }

    @Override
    public int read() throws IOException {
        if (!readerClaimed.compareAndSet(false, true)) {
            throw new IOException("BlockingInputStream supports one reader");
        }
        readStarted.countDown();
        try {
            released.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("BlockingInputStream read interrupted");
        }
        if (closed) {
            return -1;
        }
        throw new IOException("BlockingInputStream released without close");
    }

    @Override
    public void close() {
        closeCount.incrementAndGet();
        closed = true;
        released.countDown();
    }

    /**
     * Waits until a reader has entered {@link #read()}.
     *
     * @param timeout positive maximum wait
     * @return whether the read started before the timeout
     */
    public boolean awaitReadStarted(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        try {
            return readStarted.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
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
