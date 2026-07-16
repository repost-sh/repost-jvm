package sh.repost.client.test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import sh.repost.client.Transport;
import sh.repost.client.TransportFailure;
import sh.repost.client.TransportHeaderField;
import sh.repost.client.TransportRequest;
import sh.repost.client.TransportResponse;

/** Thread-safe ordered transport scripts for consumer tests without network access. */
public final class StubTransport implements Transport {
    private final ConcurrentLinkedQueue<ResponseScript> scripts = new ConcurrentLinkedQueue<>();
    private final CopyOnWriteArrayList<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    private final Object requestMonitor = new Object();

    /** Creates an empty transport ready for response scripting. */
    public StubTransport() { }

    /**
     * Adds one JSON UTF-8 response consumed by the next transport attempt.
     *
     * @param statusCode HTTP status from 200 through 599
     * @param utf8Body exact UTF-8 response body
     * @return this transport
     */
    public StubTransport enqueueResponse(int statusCode, String utf8Body) {
        return enqueueResponse(
                statusCode,
                Collections.singletonList(
                        TransportHeaderField.of("Content-Type", "application/json")),
                Objects.requireNonNull(utf8Body, "utf8Body").getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Adds one response with exact ordered headers and body bytes.
     *
     * @param statusCode HTTP status from 200 through 599
     * @param headers ordered response header fields
     * @param body exact response body bytes
     * @return this transport
     */
    public StubTransport enqueueResponse(
            int statusCode,
            List<TransportHeaderField> headers,
            byte[] body) {
        List<TransportHeaderField> capturedHeaders = List.copyOf(
                Objects.requireNonNull(headers, "headers"));
        byte[] capturedBody = Objects.requireNonNull(body, "body").clone();
        return enqueue(request -> CompletableFuture.completedFuture(TransportResponse.of(
                statusCode,
                capturedHeaders,
                new ByteArrayInputStream(capturedBody))));
    }

    /**
     * Adds an acquired response backed by the supplied stream.
     *
     * <p>The public {@link TransportResponse} factory runs at execution time, so invalid
     * status/header scripts exercise the real structural failure and close behavior.</p>
     *
     * @param statusCode raw response status
     * @param headers raw ordered headers
     * @param body owned response stream
     * @return this transport
     */
    public StubTransport enqueueFactoryResponse(
            int statusCode,
            List<TransportHeaderField> headers,
            InputStream body) {
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(body, "body");
        return enqueue(request -> CompletableFuture.completedFuture(
                TransportResponse.of(statusCode, headers, body)));
    }

    /**
     * Adds an asynchronously surfaced response-factory result.
     *
     * <p>Structural factory failures complete the returned transport stage exceptionally instead
     * of escaping synchronously from {@link #execute(TransportRequest)}.</p>
     *
     * @param statusCode raw response status
     * @param headers raw ordered headers
     * @param body owned response stream
     * @return this transport
     */
    public StubTransport enqueueAsyncFactoryResponse(
            int statusCode,
            List<TransportHeaderField> headers,
            InputStream body) {
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(body, "body");
        return enqueue(request -> {
            try {
                return CompletableFuture.completedFuture(
                        TransportResponse.of(statusCode, headers, body));
            } catch (RuntimeException failure) {
                return failed(failure);
            }
        });
    }

    /**
     * Adds a synchronous public-factory invocation with a null top-level header list.
     *
     * @param statusCode raw response status
     * @param body body transferred to the public factory and closed by its failure path
     * @return this transport
     */
    public StubTransport enqueueNullHeadersFactoryResponse(
            int statusCode,
            InputStream body) {
        Objects.requireNonNull(body, "body");
        return enqueue(request -> CompletableFuture.completedFuture(
                TransportResponse.of(statusCode, null, body)));
    }

    /**
     * Adds an asynchronously surfaced public-factory failure for null top-level headers.
     *
     * @param statusCode raw response status
     * @param body body transferred to the public factory and closed by its failure path
     * @return this transport
     */
    public StubTransport enqueueAsyncNullHeadersFactoryResponse(
            int statusCode,
            InputStream body) {
        Objects.requireNonNull(body, "body");
        return enqueue(request -> {
            try {
                return CompletableFuture.completedFuture(
                        TransportResponse.of(statusCode, null, body));
            } catch (RuntimeException failure) {
                return failed(failure);
            }
        });
    }

    /**
     * Adds one structured custom-transport failure.
     *
     * @param failure public structured failure
     * @return this transport
     */
    public StubTransport enqueueFailure(TransportFailure failure) {
        Objects.requireNonNull(failure, "failure");
        return enqueue(request -> failed(failure));
    }

    /**
     * Adds one caller-controlled completion stage.
     *
     * @param stage response stage
     * @return this transport
     */
    public StubTransport enqueueStage(CompletionStage<TransportResponse> stage) {
        Objects.requireNonNull(stage, "stage");
        return enqueue(request -> stage);
    }

    /**
     * Adds a raw synchronous transport defect.
     *
     * @param failure defect thrown from {@link #execute(TransportRequest)}
     * @return this transport
     */
    public StubTransport enqueueSynchronousFailure(RuntimeException failure) {
        Objects.requireNonNull(failure, "failure");
        return enqueue(request -> { throw failure; });
    }

    /**
     * Adds a raw null-stage transport defect.
     *
     * @return this transport
     */
    public StubTransport enqueueNullStage() {
        return enqueue(request -> null);
    }

    /**
     * Adds a transport stage that completes successfully with a null response.
     *
     * @return this transport
     */
    public StubTransport enqueueNullResponse() {
        return enqueue(request -> CompletableFuture.completedFuture(null));
    }

    /**
     * Adds an already-cancelled transport stage.
     *
     * @return this transport
     */
    public StubTransport enqueueCancelledResponse() {
        return enqueue(request -> {
            CompletableFuture<TransportResponse> cancelled = new CompletableFuture<>();
            cancelled.cancel(false);
            return cancelled;
        });
    }

    /**
     * Adds one pending script and returns its completion controller.
     *
     * @return response controller
     */
    public ControlledResponse enqueuePending() {
        ControlledResponse controlled = new ControlledResponse();
        enqueueStage(controlled.future);
        return controlled;
    }

    /**
     * Returns an immutable snapshot of captured credential-free requests.
     *
     * @return captured requests in attempt order
     */
    public List<RecordedRequest> getRequests() {
        return List.copyOf(requests);
    }

    /**
     * Waits until at least the requested number of attempts has entered this transport.
     *
     * @param expectedCount nonnegative request count
     * @param timeout positive maximum wait
     * @return whether the count was reached before the timeout
     */
    public boolean awaitRequestCount(int expectedCount, Duration timeout) {
        if (expectedCount < 0) {
            throw new IllegalArgumentException("expectedCount must not be negative");
        }
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        long timeoutNanos = timeout.toNanos();
        long startedAt = System.nanoTime();
        synchronized (requestMonitor) {
            while (requests.size() < expectedCount) {
                long elapsed = Math.max(0L, System.nanoTime() - startedAt);
                long remaining = timeoutNanos - elapsed;
                if (remaining <= 0L) {
                    return false;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(requestMonitor, remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    @Override
    public CompletionStage<TransportResponse> execute(TransportRequest request) {
        requests.add(RecordedRequest.capture(Objects.requireNonNull(request, "request")));
        synchronized (requestMonitor) {
            requestMonitor.notifyAll();
        }
        ResponseScript script = scripts.poll();
        if (script == null) {
            return failed(new IllegalStateException("StubTransport has no scripted response"));
        }
        return script.execute(request);
    }

    @Override
    public String toString() {
        return "StubTransport[REDACTED]";
    }

    private StubTransport enqueue(ResponseScript script) {
        scripts.add(script);
        return this;
    }

    private static CompletableFuture<TransportResponse> failed(Throwable failure) {
        CompletableFuture<TransportResponse> future = new CompletableFuture<>();
        future.completeExceptionally(failure);
        return future;
    }

    @FunctionalInterface
    private interface ResponseScript {
        CompletionStage<TransportResponse> execute(TransportRequest request);
    }

    /** Thread-safe controller for a pending scripted response. */
    public static final class ControlledResponse {
        private final CompletableFuture<TransportResponse> future = new CompletableFuture<>();

        private ControlledResponse() { }

        /**
         * Completes with one acquired response.
         *
         * @param statusCode HTTP status
         * @param headers ordered response headers
         * @param body exact body bytes
         * @return whether this call completed the script
         */
        public boolean completeResponse(
                int statusCode,
                List<TransportHeaderField> headers,
                byte[] body) {
            try {
                return future.complete(TransportResponse.of(
                        statusCode,
                        List.copyOf(Objects.requireNonNull(headers, "headers")),
                        new ByteArrayInputStream(Objects.requireNonNull(body, "body").clone())));
            } catch (RuntimeException failure) {
                return future.completeExceptionally(failure);
            }
        }

        /**
         * Completes with a structured transport failure.
         *
         * @param failure structured failure
         * @return whether this call completed the script
         */
        public boolean completeFailure(TransportFailure failure) {
            return future.completeExceptionally(Objects.requireNonNull(failure, "failure"));
        }

        /**
         * Completes successfully with an invalid null response.
         *
         * @return whether this call completed the script
         */
        public boolean completeNull() {
            return future.complete(null);
        }

        /**
         * Cancels the pending transport stage.
         *
         * @param mayInterruptIfRunning cancellation interruption hint
         * @return whether this call cancelled the script
         */
        public boolean cancel(boolean mayInterruptIfRunning) {
            return future.cancel(mayInterruptIfRunning);
        }

        /**
         * Reports whether the script has completed.
         *
         * @return completion state
         */
        public boolean isDone() {
            return future.isDone();
        }

        /**
         * Reports whether the script was cancelled.
         *
         * @return cancellation state
         */
        public boolean isCancelled() {
            return future.isCancelled();
        }
    }
}
