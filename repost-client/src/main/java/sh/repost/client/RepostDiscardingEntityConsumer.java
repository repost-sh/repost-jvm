package sh.repost.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import sh.repost.internal.apache.org.apache.hc.core5.concurrent.FutureCallback;
import sh.repost.internal.apache.org.apache.hc.core5.http.EntityDetails;
import sh.repost.internal.apache.org.apache.hc.core5.http.Header;
import sh.repost.internal.apache.org.apache.hc.core5.http.nio.AsyncEntityConsumer;
import sh.repost.internal.apache.org.apache.hc.core5.http.nio.CapacityChannel;
import sh.repost.internal.apache.org.apache.hc.core5.repost.RepostBudgetedBodyConsumer;

/** Retains no non-success body and cancels the exchange after a fixed inspection window. */
final class RepostDiscardingEntityConsumer implements AsyncEntityConsumer<InputStream> {
    private static final int DISCARD_WINDOW_BYTES = 65_536;
    private static final int MAX_COMPRESSED_BYTES = 1_048_576;
    private static final int GZIP_FIXED_HEADER_BYTES = 10;
    private static final int GZIP_FLAG_HEADER_CRC = 0x02;
    private static final int GZIP_FLAG_EXTRA = 0x04;
    private static final int GZIP_FLAG_NAME = 0x08;
    private static final int GZIP_FLAG_COMMENT = 0x10;
    private static final int HEADER_FIXED = 0;
    private static final int HEADER_EXTRA_LENGTH_LOW = 1;
    private static final int HEADER_EXTRA_LENGTH_HIGH = 2;
    private static final int HEADER_EXTRA = 3;
    private static final int HEADER_NAME = 4;
    private static final int HEADER_COMMENT = 5;
    private static final int HEADER_CRC_LOW = 6;
    private static final int HEADER_CRC_HIGH = 7;
    private static final int HEADER_COMPLETE = 8;

    private final RepostBudgetedBodyConsumer.UpstreamControl upstream;
    private final byte[] compressedChunk = new byte[16_384];
    private final byte[] decompressedChunk = new byte[8_192];
    private FutureCallback<InputStream> callback;
    private InputStream content;
    private Inflater inflater;
    private boolean gzip;
    private boolean gzipInvalid;
    private boolean gzipFinished;
    private int fixedHeaderBytes;
    private int gzipFlags;
    private int headerState = HEADER_FIXED;
    private int extraBytes;
    private long compressedBytes;
    private long decompressedBytes;
    private boolean truncated;
    private boolean terminal;

    RepostDiscardingEntityConsumer(RepostBudgetedBodyConsumer.UpstreamControl upstream) {
        this.upstream = Objects.requireNonNull(upstream, "upstream");
    }

    @Override
    public synchronized void streamStart(
            EntityDetails entityDetails,
            FutureCallback<InputStream> resultCallback) {
        if (callback != null || terminal) {
            throw new IllegalStateException("discard consumer already started");
        }
        callback = Objects.requireNonNull(resultCallback, "resultCallback");
        gzip = isGzip(entityDetails == null ? null : entityDetails.getContentEncoding());
        if (gzip) {
            inflater = new Inflater(true);
        }
    }

    @Override
    public synchronized void updateCapacity(CapacityChannel capacityChannel) throws IOException {
        long remaining = gzip
                ? MAX_COMPRESSED_BYTES - compressedBytes
                : DISCARD_WINDOW_BYTES - decompressedBytes;
        capacityChannel.update((int) Math.min(16_384L, Math.max(0L, remaining)));
    }

    @Override
    public synchronized void consume(ByteBuffer source) throws IOException {
        if (terminal) {
            source.position(source.limit());
            return;
        }
        if (gzip) {
            consumeGzip(source);
            return;
        }
        int discarded = (int) Math.min(
                source.remaining(), DISCARD_WINDOW_BYTES - decompressedBytes);
        source.position(source.position() + discarded);
        compressedBytes += discarded;
        decompressedBytes += discarded;
        if (decompressedBytes == DISCARD_WINDOW_BYTES) {
            abortDiscardWindow();
        }
    }

    @Override
    public synchronized void streamEnd(List<? extends Header> trailers) {
        complete(false);
    }

    @Override
    public synchronized void failed(Exception failure) {
        if (terminal) {
            return;
        }
        terminal = true;
        closeInflater();
        FutureCallback<InputStream> current = callback;
        callback = null;
        if (current != null) {
            current.failed(failure);
        }
    }

    @Override
    public synchronized InputStream getContent() {
        return content;
    }

    @Override
    public synchronized void releaseResources() {
        terminal = true;
        callback = null;
        closeInflater();
    }

    private void consumeGzip(ByteBuffer source) throws IOException {
        while (source.hasRemaining() && !terminal) {
            if (!gzipInvalid && headerState != HEADER_COMPLETE) {
                compressedBytes++;
                consumeGzipHeader(source.get() & 0xff);
                if (compressedBytes == MAX_COMPRESSED_BYTES) {
                    abortDiscardWindow();
                }
                continue;
            }
            if (gzipInvalid || gzipFinished) {
                discardCompressed(source);
                continue;
            }

            int count = (int) Math.min(
                    Math.min(source.remaining(), compressedChunk.length),
                    MAX_COMPRESSED_BYTES - compressedBytes);
            source.get(compressedChunk, 0, count);
            compressedBytes += count;
            inflater.setInput(compressedChunk, 0, count);
            inflateDiscardWindow();
            if (compressedBytes == MAX_COMPRESSED_BYTES && !gzipFinished) {
                abortDiscardWindow();
            }
        }
    }

    private void inflateDiscardWindow() throws IOException {
        while (!inflater.needsInput() && !gzipFinished) {
            int remaining = (int) (DISCARD_WINDOW_BYTES - decompressedBytes);
            if (remaining == 0) {
                abortDiscardWindow();
            }
            int inflated;
            try {
                inflated = inflater.inflate(
                        decompressedChunk,
                        0,
                        Math.min(decompressedChunk.length, remaining));
            } catch (DataFormatException invalid) {
                markGzipInvalid();
                return;
            }
            decompressedBytes += inflated;
            if (decompressedBytes == DISCARD_WINDOW_BYTES) {
                abortDiscardWindow();
            }
            if (inflater.finished()) {
                gzipFinished = true;
                closeInflater();
                return;
            }
            if (inflater.needsDictionary() || (inflated == 0 && !inflater.needsInput())) {
                markGzipInvalid();
                return;
            }
        }
    }

    private void discardCompressed(ByteBuffer source) throws IOException {
        int discarded = (int) Math.min(
                source.remaining(), MAX_COMPRESSED_BYTES - compressedBytes);
        source.position(source.position() + discarded);
        compressedBytes += discarded;
        if (compressedBytes == MAX_COMPRESSED_BYTES) {
            abortDiscardWindow();
        }
    }

    private void consumeGzipHeader(int value) {
        if (headerState == HEADER_FIXED) {
            if ((fixedHeaderBytes == 0 && value != 0x1f)
                    || (fixedHeaderBytes == 1 && value != 0x8b)
                    || (fixedHeaderBytes == 2 && value != 8)) {
                markGzipInvalid();
                return;
            }
            if (fixedHeaderBytes == 3) {
                gzipFlags = value;
                if ((gzipFlags & 0xe0) != 0) {
                    markGzipInvalid();
                    return;
                }
            }
            fixedHeaderBytes++;
            if (fixedHeaderBytes == GZIP_FIXED_HEADER_BYTES) {
                advanceHeaderState();
            }
            return;
        }
        switch (headerState) {
            case HEADER_EXTRA_LENGTH_LOW:
                extraBytes = value;
                headerState = HEADER_EXTRA_LENGTH_HIGH;
                return;
            case HEADER_EXTRA_LENGTH_HIGH:
                extraBytes |= value << 8;
                headerState = extraBytes == 0 ? nextHeaderState() : HEADER_EXTRA;
                return;
            case HEADER_EXTRA:
                if (--extraBytes == 0) {
                    headerState = nextHeaderState();
                }
                return;
            case HEADER_NAME:
            case HEADER_COMMENT:
                if (value == 0) {
                    headerState = nextHeaderState();
                }
                return;
            case HEADER_CRC_LOW:
                headerState = HEADER_CRC_HIGH;
                return;
            case HEADER_CRC_HIGH:
                headerState = HEADER_COMPLETE;
                return;
            default:
                return;
        }
    }

    private void advanceHeaderState() {
        headerState = nextHeaderState();
    }

    private int nextHeaderState() {
        if ((gzipFlags & GZIP_FLAG_EXTRA) != 0) {
            gzipFlags &= ~GZIP_FLAG_EXTRA;
            return HEADER_EXTRA_LENGTH_LOW;
        }
        if ((gzipFlags & GZIP_FLAG_NAME) != 0) {
            gzipFlags &= ~GZIP_FLAG_NAME;
            return HEADER_NAME;
        }
        if ((gzipFlags & GZIP_FLAG_COMMENT) != 0) {
            gzipFlags &= ~GZIP_FLAG_COMMENT;
            return HEADER_COMMENT;
        }
        if ((gzipFlags & GZIP_FLAG_HEADER_CRC) != 0) {
            gzipFlags &= ~GZIP_FLAG_HEADER_CRC;
            return HEADER_CRC_LOW;
        }
        return HEADER_COMPLETE;
    }

    private void markGzipInvalid() {
        gzipInvalid = true;
        closeInflater();
    }

    private void abortDiscardWindow() throws IOException {
        truncated = true;
        complete(true);
        throw new IOException("non-success response discard window reached");
    }

    private void complete(boolean cancelUpstream) {
        if (terminal) {
            return;
        }
        terminal = true;
        closeInflater();
        content = new DiscardedBodyInputStream(
                compressedBytes, decompressedBytes, truncated);
        FutureCallback<InputStream> current = callback;
        callback = null;
        if (current != null) {
            current.completed(content);
        }
        if (cancelUpstream) {
            try {
                upstream.cancel();
            } catch (IOException ignored) {
                // The known HTTP status remains primary if cancellation cleanup fails.
            }
        }
    }

    private void closeInflater() {
        if (inflater != null) {
            inflater.end();
            inflater = null;
        }
    }

    private static boolean isGzip(String value) {
        if (value == null) {
            return false;
        }
        int start = 0;
        int end = value.length();
        while (start < end && isOws(value.charAt(start))) {
            start++;
        }
        while (end > start && isOws(value.charAt(end - 1))) {
            end--;
        }
        return end - start == 4
                && asciiLower(value.charAt(start)) == 'g'
                && asciiLower(value.charAt(start + 1)) == 'z'
                && asciiLower(value.charAt(start + 2)) == 'i'
                && asciiLower(value.charAt(start + 3)) == 'p';
    }

    private static char asciiLower(char value) {
        return value >= 'A' && value <= 'Z' ? (char) (value + ('a' - 'A')) : value;
    }

    private static boolean isOws(char value) {
        return value == ' ' || value == '\t';
    }

    private static final class DiscardedBodyInputStream extends InputStream
            implements TransportResponse.BodyDiagnostics {
        private final long compressedBytes;
        private final long decompressedBytes;
        private final boolean truncated;

        private DiscardedBodyInputStream(
                long compressedBytes,
                long decompressedBytes,
                boolean truncated) {
            this.compressedBytes = compressedBytes;
            this.decompressedBytes = decompressedBytes;
            this.truncated = truncated;
        }

        @Override
        public int read() {
            return -1;
        }

        @Override
        public long compressedBytes() {
            return compressedBytes;
        }

        @Override
        public long decompressedBytes() {
            return decompressedBytes;
        }

        @Override
        public boolean truncated() {
            return truncated;
        }
    }
}
