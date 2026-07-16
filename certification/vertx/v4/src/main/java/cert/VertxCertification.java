package cert;

import cert.generated.java.Book;
import cert.generated.java.JavaClient;
import cert.generated.java.JavaClientFactory;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import sh.repost.client.ClientOptions;
import sh.repost.client.DefaultValueGenerators;
import sh.repost.client.RepostRuntime;
import sh.repost.client.SendOperation;
import sh.repost.client.test.StubTransport;

public final class VertxCertification {
    private static final String VERSION = System.getProperty("cert.vertxVersion");
    private static final String TIMESTAMP = "2026-01-01T00:00:00.000Z";

    public static void main(String[] args) throws Exception {
        int threadsBefore = liveThreads();
        Vertx vertx = Vertx.vertx();
        HttpServer server = vertx.createHttpServer()
                .requestHandler(request -> request.body().onSuccess(body -> request.response()
                        .setStatusCode(202)
                        .putHeader("Content-Type", "application/json")
                        .end(accepted("msg_loopback", body.toString()))))
                .listen(0, "127.0.0.1")
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<String> observerThread = new AtomicReference<>();
        RepostRuntime runtime = RepostRuntime.create(ClientOptions.builder()
                .apiKey("key_certification")
                .baseUri("http://127.0.0.1:" + server.actualPort())
                .observer(event -> observerThread.set(Thread.currentThread().getName()))
                .maxAttempts(1)
                .defaultValueGenerators(defaults())
                .build());
        JavaClient client = JavaClientFactory.INSTANCE.create(runtime);
        CountDownLatch completed = new CountDownLatch(1);

        try {
            Context context = vertx.getOrCreateContext();
            context.runOnContext(ignored -> {
                try {
                    long started = System.nanoTime();
                    SendOperation operation = client.webhooks().book().createdAsync(
                            "customer_123", Book.builder().title("Dune").build());
                    if (Duration.ofNanos(System.nanoTime() - started).toMillis() >= 250) {
                        throw new AssertionError("SDK submission blocked the Vert.x event loop");
                    }
                    Future.fromCompletionStage(operation, context).onComplete(result -> {
                        try {
                            if (Vertx.currentContext() != context) {
                                throw new AssertionError("Vert.x context was not preserved");
                            }
                            if (result.failed() || !"msg_loopback".equals(result.result().getId())) {
                                throw new AssertionError("loopback send failed", result.cause());
                            }
                        } catch (Throwable throwable) {
                            failure.set(throwable);
                        } finally {
                            completed.countDown();
                        }
                    });
                } catch (Throwable throwable) {
                    failure.set(throwable);
                    completed.countDown();
                }
            });
            if (!completed.await(10, TimeUnit.SECONDS)) throw new AssertionError("send timed out");
            if (failure.get() != null) throw new AssertionError(failure.get());

            StubTransport stub = new StubTransport().enqueueResponse(202, accepted("msg_stub"));
            try (RepostRuntime stubRuntime = RepostRuntime.create(options(stub))) {
                if (!"msg_stub".equals(JavaClientFactory.INSTANCE.create(stubRuntime)
                        .webhooks().book().createdAsync("customer_123", book())
                        .toCompletableFuture().get(10, TimeUnit.SECONDS).getId())) {
                    throw new AssertionError("stub send failed");
                }
            }

            StubTransport pending = new StubTransport();
            StubTransport.ControlledResponse controlled = pending.enqueuePending();
            try (RepostRuntime cancelRuntime = RepostRuntime.create(options(pending))) {
                SendOperation operation = JavaClientFactory.INSTANCE.create(cancelRuntime)
                        .webhooks().book().createdAsync("customer_123", book());
                pending.awaitRequestCount(1, Duration.ofSeconds(2));
                Future.fromCompletionStage(operation);
                operation.cancel(true);
                if (!controlled.isCancelled()) throw new AssertionError("cancellation did not cross boundary");
            }

            String callback = observerThread.get();
            if (callback == null || callback.contains("vert.x-eventloop-thread")) {
                throw new AssertionError("observer ran on Vert.x event loop: " + callback);
            }
        } finally {
            runtime.close();
            server.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }

        if (!runtime.diagnostics().isClosed()) throw new AssertionError("runtime remained open");
        System.out.println("CERTIFICATION vertx=" + VERSION
                + " jdk=" + Runtime.version().feature()
                + " stub=passed loopback=passed context=passed cancellation-boundary=passed"
                + " event-loop=nonblocking shutdown=passed thread-delta=" + (liveThreads() - threadsBefore));
    }

    private static ClientOptions options(StubTransport transport) {
        return ClientOptions.builder().apiKey("key_certification").transport(transport)
                .maxAttempts(1).defaultValueGenerators(defaults()).build();
    }

    private static DefaultValueGenerators defaults() {
        return DefaultValueGenerators.fixed(Instant.parse(TIMESTAMP),
                "00000000-0000-4000-8000-000000000001", "csequence000000000000001");
    }

    private static Book book() { return Book.builder().title("Dune").build(); }

    private static String accepted(String id) {
        return accepted(id, "{\"type\":\"book.created\",\"customerId\":\"customer_123\","
                + "\"timestamp\":\"" + TIMESTAMP + "\"}");
    }

    private static String accepted(String id, String request) {
        return "{\"id\":\"" + id + "\",\"type\":\"" + field(request, "type")
                + "\",\"customerId\":\"" + field(request, "customerId")
                + "\",\"timestamp\":\"" + field(request, "timestamp") + "\"}";
    }

    private static String field(String json, String name) {
        String prefix = "\"" + name + "\":\"";
        int start = json.indexOf(prefix);
        if (start < 0) throw new AssertionError("missing request field " + name);
        start += prefix.length();
        int end = json.indexOf('"', start);
        if (end < 0) throw new AssertionError("unterminated request field " + name);
        return json.substring(start, end);
    }

    private static int liveThreads() {
        return (int) Thread.getAllStackTraces().keySet().stream().filter(Thread::isAlive).count();
    }
}
