package sh.repost.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import sh.repost.internal.apache.org.apache.hc.core5.http.EntityDetails;
import sh.repost.internal.apache.org.apache.hc.core5.http.HttpException;
import sh.repost.internal.apache.org.apache.hc.core5.http.HttpRequest;
import sh.repost.internal.apache.org.apache.hc.core5.http.nio.AsyncRequestProducer;
import sh.repost.internal.apache.org.apache.hc.core5.http.nio.DataStreamChannel;
import sh.repost.internal.apache.org.apache.hc.core5.http.nio.RequestChannel;
import sh.repost.internal.apache.org.apache.hc.core5.http.protocol.HttpContext;

/** Non-repeatable request producer over the request's existing capped body storage. */
final class OneShotRequestProducer implements AsyncRequestProducer, EntityDetails {
    private final HttpRequest request;
    private final long contentLength;
    private ByteBuffer body;
    private boolean published;
    private boolean bodyStarted;
    private boolean ended;
    private boolean released;

    OneShotRequestProducer(HttpRequest request, ByteBuffer body) {
        this.request = Objects.requireNonNull(request, "request");
        this.body = Objects.requireNonNull(body, "body").asReadOnlyBuffer();
        this.contentLength = this.body.remaining();
    }

    @Override
    public synchronized void sendRequest(RequestChannel channel, HttpContext context)
            throws HttpException, IOException {
        if (published || released) {
            throw new IOException("request body publication cannot be replayed");
        }
        published = true;
        channel.sendRequest(request, this, context);
    }

    @Override
    public synchronized int available() {
        return released || ended ? 0 : Math.max(1, body.remaining());
    }

    @Override
    public synchronized void produce(DataStreamChannel channel) throws IOException {
        if (released || ended) {
            return;
        }
        if (body.hasRemaining()) {
            bodyStarted = true;
            channel.write(body);
        }
        if (!body.hasRemaining()) {
            ended = true;
            channel.endStream();
        }
    }

    @Override
    public boolean isRepeatable() {
        return false;
    }

    synchronized boolean wasBodyStarted() {
        return bodyStarted;
    }

    @Override
    public void failed(Exception cause) {
        releaseResources();
    }

    @Override
    public synchronized void releaseResources() {
        if (!released) {
            released = true;
            body.position(body.limit());
        }
    }

    @Override
    public long getContentLength() {
        return contentLength;
    }

    @Override
    public String getContentType() {
        return null;
    }

    @Override
    public String getContentEncoding() {
        return null;
    }

    @Override
    public boolean isChunked() {
        return false;
    }

    @Override
    public Set<String> getTrailerNames() {
        return Collections.emptySet();
    }
}
