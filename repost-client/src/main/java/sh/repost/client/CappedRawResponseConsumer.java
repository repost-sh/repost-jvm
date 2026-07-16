package sh.repost.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import sh.repost.internal.apache.org.apache.hc.core5.concurrent.FutureCallback;
import sh.repost.internal.apache.org.apache.hc.core5.http.EntityDetails;
import sh.repost.internal.apache.org.apache.hc.core5.http.Header;
import sh.repost.internal.apache.org.apache.hc.core5.http.HttpException;
import sh.repost.internal.apache.org.apache.hc.core5.http.nio.AsyncEntityConsumer;
import sh.repost.internal.apache.org.apache.hc.core5.http.nio.CapacityChannel;
import sh.repost.internal.apache.org.apache.hc.core5.repost.RepostBudgetedBodyConsumer;
import sh.repost.internal.apache.org.apache.hc.core5.repost.RepostByteBudget;

/** Fixed-chunk raw response consumer with one response-local 1 MiB reservation. */
final class CappedRawResponseConsumer implements AsyncEntityConsumer<InputStream> {
    private static final int RESPONSE_LIMIT = RepostBudgetedBodyConsumer.MAX_BODY_BYTES;

    private final RepostBudgetedBodyConsumer body;
    private FutureCallback<InputStream> callback;
    private InputStream content;

    CappedRawResponseConsumer(RepostBudgetedBodyConsumer.UpstreamControl upstream) {
        RepostByteBudget budget = new RepostByteBudget(RESPONSE_LIMIT);
        RepostByteBudget.Reservation reservation =
                Objects.requireNonNull(budget.tryReserve(RESPONSE_LIMIT));
        this.body = new RepostBudgetedBodyConsumer(RESPONSE_LIMIT, reservation, upstream);
    }

    @Override
    public synchronized void streamStart(
            EntityDetails entityDetails,
            FutureCallback<InputStream> resultCallback) throws IOException {
        if (callback != null || body.isTerminal()) {
            throw new IllegalStateException("response consumer already started");
        }
        callback = Objects.requireNonNull(resultCallback, "resultCallback");
        if (entityDetails != null && entityDetails.getContentLength() > RESPONSE_LIMIT) {
            body.abort();
            throw new IOException("response body exceeds configured byte limit");
        }
    }

    @Override
    public synchronized void updateCapacity(CapacityChannel capacityChannel) throws IOException {
        long remaining = RESPONSE_LIMIT - body.consumedBytes();
        int increment = (int) Math.min(
                RepostBudgetedBodyConsumer.MAX_CHUNK_BYTES,
                Math.max(0L, remaining) + 1L);
        capacityChannel.update(increment);
    }

    @Override
    public synchronized void consume(ByteBuffer source) throws IOException {
        body.accept(source);
    }

    @Override
    public synchronized void streamEnd(List<? extends Header> trailers)
            throws HttpException, IOException {
        if (content != null) {
            return;
        }
        content = body.complete();
        FutureCallback<InputStream> resultCallback = callback;
        callback = null;
        if (resultCallback != null) {
            resultCallback.completed(content);
        }
    }

    @Override
    public synchronized void failed(Exception cause) {
        body.abort();
        FutureCallback<InputStream> resultCallback = callback;
        callback = null;
        if (resultCallback != null) {
            resultCallback.failed(cause);
        }
    }

    @Override
    public synchronized InputStream getContent() {
        return content;
    }

    @Override
    public synchronized void releaseResources() {
        body.abort();
        callback = null;
    }
}
