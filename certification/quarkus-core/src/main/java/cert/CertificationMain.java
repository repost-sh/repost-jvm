package cert;

import cert.generated.java.Book;
import cert.generated.java.JavaClient;
import cert.generated.java.JavaClientFactory;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.ServerSocket;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import sh.repost.client.ClientOptions;
import sh.repost.client.DefaultValueGenerators;
import sh.repost.client.RepostRuntime;
import sh.repost.client.test.StubTransport;

@QuarkusMain
public final class CertificationMain {
    static final String TIMESTAMP = "2026-01-01T00:00:00.000Z";
    static int threadsBefore;

    public static void main(String[] args) throws Exception {
        threadsBefore = liveThreads();
        try (ServerSocket socket = new ServerSocket(0)) {
            System.setProperty("quarkus.http.host", "127.0.0.1");
            System.setProperty("quarkus.http.port", Integer.toString(socket.getLocalPort()));
        }
        Quarkus.run(App.class, args);
    }

    public static final class App implements QuarkusApplication {
        @Inject JavaClient client;

        @Override
        public int run(String... args) throws Exception {
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
            return 0;
        }
    }

    @ApplicationScoped
    public static class Beans {
        @Produces
        @Singleton
        RepostRuntime runtime() {
            return RepostRuntime.create(ClientOptions.builder()
                    .apiKey("key_certification")
                    .baseUri("http://127.0.0.1:" + System.getProperty("quarkus.http.port"))
                    .maxAttempts(1)
                    .defaultValueGenerators(defaults())
                    .build());
        }

        void close(@Disposes RepostRuntime runtime) {
            runtime.close();
            if (!runtime.diagnostics().isClosed()) throw new AssertionError("runtime remained open");
            System.out.println("CERTIFICATION quarkus=" + System.getProperty("cert.quarkusVersion")
                + " jdk=" + Runtime.version().feature()
                    + " stub=passed loopback=passed"
                    + " manual-core-beans=passed shutdown=passed thread-delta="
                    + (liveThreads() - threadsBefore));
        }

        @Produces
        @Singleton
        JavaClient client(RepostRuntime runtime) {
            return JavaClientFactory.INSTANCE.create(runtime);
        }
    }

    @Path("/v1/messages")
    @ApplicationScoped
    public static class Messages {
        @POST
        @jakarta.ws.rs.Produces(MediaType.APPLICATION_JSON)
        public Response send() {
            return Response.accepted(accepted("msg_loopback")).build();
        }
    }

    static ClientOptions options(StubTransport transport) {
        return ClientOptions.builder().apiKey("key_certification").transport(transport)
                .maxAttempts(1).defaultValueGenerators(defaults()).build();
    }

    static DefaultValueGenerators defaults() {
        return DefaultValueGenerators.fixed(Instant.parse(TIMESTAMP),
                "00000000-0000-4000-8000-000000000001", "csequence000000000000001");
    }

    static Book book() { return Book.builder().title("Dune").build(); }

    static String accepted(String id) {
        return "{\"id\":\"" + id + "\",\"type\":\"book.created\",\"customerId\":"
                + "\"customer_123\",\"timestamp\":\"" + TIMESTAMP + "\"}";
    }

    static int liveThreads() {
        return (int) Thread.getAllStackTraces().keySet().stream().filter(Thread::isAlive).count();
    }
}
