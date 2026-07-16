package cert;

import cert.generated.java.Book;
import cert.generated.java.JavaClient;
import cert.generated.java.JavaClientFactory;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.runtime.server.EmbeddedServer;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import sh.repost.client.ClientOptions;
import sh.repost.client.DefaultValueGenerators;
import sh.repost.client.RepostRuntime;
import sh.repost.client.test.StubTransport;

public final class MicronautCertification {
    private static final String TIMESTAMP = "2026-01-01T00:00:00.000Z";
    private static final AtomicReference<RepostRuntime> RUNTIME = new AtomicReference<>();

    public static void main(String[] args) throws Exception {
        int before = liveThreads();
        try (ApplicationContext context = ApplicationContext.run(Map.of("micronaut.server.port", -1))) {
            EmbeddedServer server = context.getBean(EmbeddedServer.class).start();
            JavaClient client = context.getBean(JavaClient.class);
            if (!"msg_loopback".equals(client.webhooks().book().createdAsync(
                    "customer_123", book()).toCompletableFuture().get(10, TimeUnit.SECONDS).getId())) {
                throw new AssertionError("loopback send failed");
            }

            StubTransport stub = new StubTransport().enqueueResponse(202, accepted("msg_stub"));
            try (RepostRuntime runtime = RepostRuntime.create(options(stub))) {
                if (!"msg_stub".equals(JavaClientFactory.INSTANCE.create(runtime).webhooks().book()
                        .createdAsync("customer_123", book()).toCompletableFuture()
                        .get(10, TimeUnit.SECONDS).getId())) {
                    throw new AssertionError("stub send failed");
                }
            }
        }

        RepostRuntime runtime = RUNTIME.get();
        if (runtime == null || !runtime.diagnostics().isClosed()) {
            throw new AssertionError("Micronaut did not close the runtime bean");
        }
        System.out.println("CERTIFICATION micronaut=" + System.getProperty("cert.micronautVersion")
                + " jdk=" + Runtime.version().feature()
                + " stub=passed loopback=passed"
                + " manual-core-beans=passed shutdown=passed thread-delta=" + (liveThreads() - before));
    }

    @Factory
    static class Beans {
        @Bean(preDestroy = "close")
        @Singleton
        RepostRuntime runtime(EmbeddedServer server) {
            RepostRuntime runtime = RepostRuntime.create(ClientOptions.builder()
                    .apiKey("key_certification")
                    .baseUri(server.getURI().toString())
                    .maxAttempts(1)
                    .defaultValueGenerators(defaults())
                    .build());
            RUNTIME.set(runtime);
            return runtime;
        }

        @Singleton
        JavaClient client(RepostRuntime runtime) {
            return JavaClientFactory.INSTANCE.create(runtime);
        }
    }

    @Controller("/v1/messages")
    static class Messages {
        @Post(produces = MediaType.APPLICATION_JSON)
        HttpResponse<String> send() {
            return HttpResponse.<String>accepted().body(accepted("msg_loopback"));
        }
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
        return "{\"id\":\"" + id + "\",\"type\":\"book.created\",\"customerId\":"
                + "\"customer_123\",\"timestamp\":\"" + TIMESTAMP + "\"}";
    }

    private static int liveThreads() {
        return (int) Thread.getAllStackTraces().keySet().stream().filter(Thread::isAlive).count();
    }
}
