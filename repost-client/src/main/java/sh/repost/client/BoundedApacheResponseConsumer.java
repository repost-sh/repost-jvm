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
import sh.repost.internal.apache.org.apache.hc.core5.http.HttpResponse;
import sh.repost.internal.apache.org.apache.hc.core5.http.Message;
import sh.repost.internal.apache.org.apache.hc.core5.http.nio.AsyncEntityConsumer;
import sh.repost.internal.apache.org.apache.hc.core5.http.nio.AsyncResponseConsumer;
import sh.repost.internal.apache.org.apache.hc.core5.http.nio.CapacityChannel;
import sh.repost.internal.apache.org.apache.hc.core5.http.protocol.HttpContext;
import sh.repost.internal.apache.org.apache.hc.core5.repost.RepostBudgetedBodyConsumer;

/** Selects bounded success buffering or a body-free non-success discard window. */
final class BoundedApacheResponseConsumer
        implements AsyncResponseConsumer<Message<HttpResponse, InputStream>> {
    private final RepostDiscardingEntityConsumer discardConsumer;
    private final CappedRawResponseConsumer successConsumer;
    private AsyncEntityConsumer<InputStream> entityConsumer;
    private HttpResponse response;
    private FutureCallback<Message<HttpResponse, InputStream>> callback;

    BoundedApacheResponseConsumer(RepostBudgetedBodyConsumer.UpstreamControl upstream) {
        successConsumer = new CappedRawResponseConsumer(upstream);
        discardConsumer = new RepostDiscardingEntityConsumer(upstream);
    }

    @Override
    public synchronized void consumeResponse(
            HttpResponse response,
            EntityDetails entityDetails,
            HttpContext context,
            FutureCallback<Message<HttpResponse, InputStream>> resultCallback)
            throws HttpException, IOException {
        if (callback != null || this.response != null) {
            throw new IllegalStateException("response consumer already started");
        }
        this.response = Objects.requireNonNull(response, "response");
        this.callback = Objects.requireNonNull(resultCallback, "resultCallback");
        if (entityDetails == null) {
            complete(InputStream.nullInputStream());
            return;
        }
        entityConsumer = response.getCode() >= 200 && response.getCode() <= 299
                ? successConsumer : discardConsumer;
        entityConsumer.streamStart(entityDetails, new FutureCallback<InputStream>() {
            @Override
            public void completed(InputStream body) {
                complete(body);
            }

            @Override
            public void failed(Exception failure) {
                completeFailure(failure);
            }

            @Override
            public void cancelled() {
                FutureCallback<Message<HttpResponse, InputStream>> current;
                synchronized (BoundedApacheResponseConsumer.this) {
                    current = callback;
                    callback = null;
                }
                if (current != null) {
                    current.cancelled();
                }
            }
        });
    }

    @Override
    public void informationResponse(HttpResponse response, HttpContext context) {
        // Informational metadata is not retained by the transport response.
    }

    @Override
    public synchronized void updateCapacity(CapacityChannel capacityChannel) throws IOException {
        if (entityConsumer == null) {
            capacityChannel.update(0);
            return;
        }
        entityConsumer.updateCapacity(capacityChannel);
    }

    @Override
    public synchronized void consume(ByteBuffer source) throws IOException {
        if (entityConsumer == null) {
            throw new IllegalStateException("response entity was not started");
        }
        entityConsumer.consume(source);
    }

    @Override
    public synchronized void streamEnd(List<? extends Header> trailers)
            throws HttpException, IOException {
        if (entityConsumer != null) {
            entityConsumer.streamEnd(trailers);
        }
    }

    @Override
    public synchronized void failed(Exception failure) {
        if (entityConsumer != null) {
            entityConsumer.failed(failure);
            return;
        }
        completeFailure(failure);
    }

    private synchronized void completeFailure(Exception failure) {
        FutureCallback<Message<HttpResponse, InputStream>> current = callback;
        callback = null;
        if (current != null) {
            current.failed(failure);
        }
    }

    @Override
    public synchronized void releaseResources() {
        if (entityConsumer != null) {
            entityConsumer.releaseResources();
        }
        callback = null;
    }

    private synchronized void complete(InputStream body) {
        FutureCallback<Message<HttpResponse, InputStream>> current = callback;
        callback = null;
        if (current != null) {
            current.completed(new Message<>(response, body));
        }
    }
}
