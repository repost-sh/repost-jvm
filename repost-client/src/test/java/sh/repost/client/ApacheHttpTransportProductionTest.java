package sh.repost.client;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;
import sh.repost.client.descriptor.EventDescriptor;
import sh.repost.client.descriptor.ModelDescriptor;
import sh.repost.client.descriptor.SchemaDescriptor;
import sh.repost.client.error.RepostException;
import sh.repost.internal.apache.org.apache.hc.client5.http.protocol.HttpClientContext;
import sh.repost.internal.apache.org.apache.hc.core5.http.message.BasicHttpRequest;
import sh.repost.internal.apache.org.apache.hc.core5.http.nio.DataStreamChannel;

public final class ApacheHttpTransportProductionTest {
    @Test
    void defaultRuntimeOwnsTheProductionHttpTransport() {
        RepostRuntime runtime = RepostRuntime.createForTesting(
                ClientOptions.builder()
                        .apiKey("test-key")
                        .baseUri("http://127.0.0.1:1")
                        .build(),
                key -> null);
        try {
            assertNotNull(runtime.transport(), "default runtime must install the production transport");
        } finally {
            runtime.close();
        }
    }

    @Test
    void productionPathDisablesAmbientRedirectRetryAuthUpgradeCompressionAndLoggingHooks()
            throws Exception {
        assertSingleRawExchange(
                302,
                "Location: /redirect-target\r\n",
                new byte[0]);
        assertSingleRawExchange(
                503,
                "Retry-After: 0\r\n",
                new byte[0]);
        assertSingleRawExchange(
                401,
                "WWW-Authenticate: Basic realm=\"fixture\"\r\n",
                new byte[0]);
        assertSingleRawExchange(
                200,
                "Content-Encoding: gzip\r\n",
                new byte[] {0x1f, (byte) 0x8b, 0x08, 0x00});
    }

    @Test
    void productionPathAcceptsTheExactRawResponseLimit() throws Exception {
        byte[] body = new byte[1_048_576];
        Arrays.fill(body, (byte) 'a');
        assertSingleRawExchange(200, "", body);
    }

    @Test
    void productionPathAppliesTheRawLimitToCompressedBytes() throws Exception {
        byte[] exact = new byte[1_048_576];
        Arrays.fill(exact, (byte) 'g');
        assertSingleRawExchange(200, "Content-Encoding: gzip\r\n", exact);

        try (OverflowPeer peer = new OverflowPeer("Content-Encoding: gzip\r\n")) {
            assertRawOverflowCancels(peer);
        }
    }

    @Test
    void productionPathNeverExposesANonSuccessBody() throws Exception {
        assertSingleRawExchange(
                400,
                "Content-Type: application/json\r\n",
                "remote-secret-payload".getBytes(StandardCharsets.US_ASCII),
                true);
    }

    @Test
    void requestBodyHasOneNonRepeatablePublication() throws Exception {
        OneShotRequestProducer producer = new OneShotRequestProducer(
                new BasicHttpRequest("POST", URI.create("http://127.0.0.1/v1/messages")),
                ByteBuffer.wrap(new byte[] {'{', '}'}));
        AtomicInteger publications = new AtomicInteger();
        producer.sendRequest(
                (request, entity, context) -> publications.incrementAndGet(),
                HttpClientContext.create());

        OneByteDataStreamChannel channel = new OneByteDataStreamChannel();
        while (producer.available() > 0) {
            producer.produce(channel);
        }

        assertFalse(producer.isRepeatable());
        assertEquals(1, publications.get());
        assertArrayEquals(new byte[] {'{', '}'}, channel.body.toByteArray());
        assertEquals(1, channel.endCount.get());
        assertThrows(
                IOException.class,
                () -> producer.sendRequest(
                        (request, entity, context) -> publications.incrementAndGet(),
                        HttpClientContext.create()));
        assertEquals(1, publications.get(), "a replay must fail before a second publication");
    }

    @Test
    void productionPathCancelsAtFirstByteBeyondRawResponseLimit() throws Exception {
        try (OverflowPeer peer = new OverflowPeer()) {
            assertRawOverflowCancels(peer);
        }
    }

    @Test
    void productionRuntimeKeepsKnownStatusAndCancelsAfterTheNonSuccessDiscardWindow()
            throws Exception {
        try (NonSuccessDiscardPeer peer = new NonSuccessDiscardPeer()) {
            RepostRuntime runtime = RepostRuntime.create(ClientOptions.builder()
                    .apiKey("test-key")
                    .baseUri(peer.baseUri())
                    .maxAttempts(1)
                    .attemptTimeout(Duration.ofSeconds(2))
                    .operationTimeout(Duration.ofSeconds(3))
                    .build());
            try {
                RepostException failure = sendFailure(runtime);
                assertEquals(RepostErrorCode.SERVER_FAILURE, failure.getErrorCode());
                assertEquals(Integer.valueOf(503), failure.getHttpStatus());
                assertEquals(DeliveryState.POSSIBLY_SENT, failure.getDeliveryState());
                assertEquals(1, failure.getAttemptCount());
                peer.awaitClientClose();
            } finally {
                runtime.close();
            }
        }
    }

    @Test
    void productionRuntimeReportsTheCompletedDecodedGzipErrorBody() throws Exception {
        byte[] decoded = "{\"remote\":\"ignored\"}".getBytes(StandardCharsets.UTF_8);
        byte[] encoded = gzip(decoded);
        try (SingleExchangePeer peer = new SingleExchangePeer(
                400,
                "Content-Encoding: gzip\r\n",
                encoded,
                true)) {
            RepostRuntime runtime = responseRuntime(peer.baseUri());
            try {
                RepostException failure = sendFailure(runtime);
                assertEquals(RepostErrorCode.HTTP_REJECTED, failure.getErrorCode());
                assertEquals(Integer.valueOf(400), failure.getHttpStatus());
                assertEquals(DeliveryState.REJECTED, failure.getDeliveryState());
                assertEquals(encoded.length, failure.getCompressedBytes());
                assertEquals(decoded.length, failure.getDecompressedBytes());
                assertFalse(failure.isTruncated());
                peer.await();
            } finally {
                runtime.close();
            }
        }
    }

    @Test
    void productionRuntimeReportsTheDecodedGzipErrorDiscardWindow() throws Exception {
        byte[] decoded = new byte[131_072];
        int random = 0x13579bdf;
        for (int index = 0; index < decoded.length; index++) {
            random ^= random << 13;
            random ^= random >>> 17;
            random ^= random << 5;
            decoded[index] = (byte) random;
        }
        byte[] encoded = gzip(decoded);
        try (SingleExchangePeer peer = new SingleExchangePeer(
                400,
                "Content-Encoding: gzip\r\n",
                encoded,
                true,
                true)) {
            RepostRuntime runtime = responseRuntime(peer.baseUri());
            try {
                RepostException failure = sendFailure(runtime);
                assertEquals(RepostErrorCode.HTTP_REJECTED, failure.getErrorCode());
                assertEquals(Integer.valueOf(400), failure.getHttpStatus());
                assertEquals(DeliveryState.REJECTED, failure.getDeliveryState());
                assertEquals(true, failure.getCompressedBytes() > 0);
                assertEquals(true, failure.getCompressedBytes() <= encoded.length);
                assertEquals(65_536L, failure.getDecompressedBytes());
                assertEquals(true, failure.isTruncated());
                peer.await();
            } finally {
                runtime.close();
            }
        }
    }

    @Test
    void productionRuntimeAcceptsTheExactDecompressedGzipLimit() throws Exception {
        byte[] responseBody = gzip(exactAcceptedResponse(1_048_576));
        try (SingleExchangePeer peer = new SingleExchangePeer(
                202,
                "Content-Type: application/json\r\nContent-Encoding: gzip\r\n",
                responseBody,
                true)) {
            RepostRuntime runtime = responseRuntime(peer.baseUri());
            try {
                SendOperation operation = send(runtime);
                peer.await();
                SendResult result = operation.get(5, TimeUnit.SECONDS);
                assertEquals("msg_gzip_limit", result.getId());
            } finally {
                runtime.close();
            }
        }
    }

    @Test
    void productionRuntimeRejectsTheFirstByteBeyondTheDecompressedGzipLimit()
            throws Exception {
        byte[] responseBody = gzip(exactAcceptedResponse(1_048_577));
        assertProductionResponseFailure(
                responseBody, RepostErrorCode.RESPONSE_TOO_LARGE);
    }

    @Test
    void productionRuntimeRejectsGzipAmplificationAndCorruption() throws Exception {
        byte[] amplified = gzip(repetitiveAcceptedResponse(262_144));
        assertProductionResponseFailure(amplified, RepostErrorCode.RESPONSE_TOO_LARGE);

        byte[] corrupt = gzip(exactAcceptedResponse(16_384));
        corrupt[corrupt.length - 1] ^= 0x01;
        assertProductionResponseFailure(corrupt, RepostErrorCode.RESPONSE_PROTOCOL);
    }

    @Test
    void productionPathRoutesOnlyThroughThePreparedBorrowedDnsSnapshot() throws Exception {
        try (SingleExchangePeer peer = new SingleExchangePeer(200, "", new byte[0]);
                ApacheHttpTransport transport = new ApacheHttpTransport(
                        HttpTransportOptions.builder()
                                .dnsResolver(host -> {
                                    assertEquals("split-horizon.invalid", host);
                                    return Collections.singletonList(
                                            InetAddress.getByName("127.0.0.1"));
                                })
                                .build())) {
            URI uri = URI.create("http://split-horizon.invalid:"
                    + peer.server.getLocalPort() + "/v1/messages");
            TransportRequest request = new TransportRequest(
                    uri,
                    Arrays.asList(
                            TransportHeaderField.of("Authorization", "Bearer test-key"),
                            TransportHeaderField.of("Content-Type", "application/json"),
                            TransportHeaderField.of("Accept-Encoding", "gzip"),
                            TransportHeaderField.of("User-Agent", "repost-java/1.0.0"),
                            TransportHeaderField.of("Idempotency-Key", "msg_dns_route")),
                    ByteBuffer.wrap(new byte[] {'{', '}'}),
                    1,
                    Duration.ofSeconds(2),
                    Duration.ofSeconds(5));

            try (TransportResponse response = transport.execute(request)
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS)) {
                assertEquals(200, response.getStatusCode());
            }
            peer.await();
            assertEquals(1, peer.primaryHits.get());
        }
    }

    @Test
    void productionRuntimePublishesExactDnsNotFoundSemantics() throws Exception {
        AtomicInteger resolverCalls = new AtomicInteger();
        AtomicReference<Thread> resolverThread = new AtomicReference<>();
        RepostRuntime runtime = RepostRuntime.create(ClientOptions.builder()
                .apiKey("test-key")
                .baseUri("https://missing-dns-contract.invalid:443")
                .httpTransportOptions(HttpTransportOptions.builder()
                        .dnsResolver(host -> {
                            resolverCalls.incrementAndGet();
                            resolverThread.set(Thread.currentThread());
                            throw new java.net.UnknownHostException(
                                    "remote-host-and-resolver-secret");
                        })
                        .build())
                .maxAttempts(1)
                .build());
        try {
            RepostException failure = sendFailure(runtime);
            assertEquals(RepostErrorCode.DNS, failure.getErrorCode());
            assertEquals(RepostFailureReason.DNS_NOT_FOUND, failure.getFailureReason());
            assertEquals(RepostCauseCategory.DNS_RESOLVER, failure.getCauseCategory());
            assertEquals(DeliveryState.NOT_SENT, failure.getDeliveryState());
            assertEquals(1, failure.getAttemptCount());
            assertEquals(1, resolverCalls.get());
            assertTrueDnsThread(resolverThread.get());
        } finally {
            runtime.close();
        }
    }

    @Test
    void productionRuntimePublishesExactDnsTimeoutSemantics() throws Exception {
        CountDownLatch resolverEntered = new CountDownLatch(1);
        CountDownLatch releaseResolver = new CountDownLatch(1);
        AtomicReference<Thread> resolverThread = new AtomicReference<>();
        RepostRuntime runtime = RepostRuntime.create(ClientOptions.builder()
                .apiKey("test-key")
                .baseUri("https://slow-dns-contract.invalid:443")
                .httpTransportOptions(HttpTransportOptions.builder()
                        .dnsResolver(host -> {
                            resolverThread.set(Thread.currentThread());
                            resolverEntered.countDown();
                            awaitIgnoringInterrupts(releaseResolver);
                            return Collections.singletonList(
                                    InetAddress.getByName("127.0.0.1"));
                        })
                        .build())
                .connectTimeout(Duration.ofSeconds(1))
                .attemptTimeout(Duration.ofSeconds(2))
                .operationTimeout(Duration.ofSeconds(3))
                .maxAttempts(1)
                .build());
        try {
            SendOperation operation = send(runtime);
            assertEquals(true, resolverEntered.await(2, TimeUnit.SECONDS));
            RepostException failure = operationFailure(operation);
            assertEquals(RepostErrorCode.ATTEMPT_TIMEOUT, failure.getErrorCode());
            assertEquals(RepostFailureReason.DNS_TIMEOUT, failure.getFailureReason());
            assertEquals(RepostCauseCategory.DNS_RESOLVER, failure.getCauseCategory());
            assertEquals(DeliveryState.NOT_SENT, failure.getDeliveryState());
            assertEquals(1, failure.getAttemptCount());
            assertTrueDnsThread(resolverThread.get());
        } finally {
            releaseResolver.countDown();
            runtime.close();
        }
    }

    @Test
    void connectModeResolvesOnlyTheExplicitProxyHost() throws Exception {
        AtomicReference<String> resolvedHost = new AtomicReference<>();
        AtomicInteger resolverCalls = new AtomicInteger();
        RepostRuntime runtime = RepostRuntime.create(ClientOptions.builder()
                .apiKey("test-key")
                .baseUri("https://origin-must-not-resolve.invalid:443")
                .httpTransportOptions(HttpTransportOptions.builder()
                        .proxy(HttpProxyOptions.builder()
                                .uri("http://proxy-only.invalid:8443")
                                .build())
                        .dnsResolver(host -> {
                            resolverCalls.incrementAndGet();
                            resolvedHost.set(host);
                            throw new java.net.UnknownHostException("proxy-resolution-sentinel");
                        })
                        .build())
                .maxAttempts(1)
                .build());
        try {
            RepostException failure = sendFailure(runtime);
            assertEquals(RepostErrorCode.DNS, failure.getErrorCode());
            assertEquals(RepostFailureReason.DNS_NOT_FOUND, failure.getFailureReason());
            assertEquals("proxy-only.invalid", resolvedHost.get());
            assertEquals(1, resolverCalls.get());
        } finally {
            runtime.close();
        }
    }

    @Test
    void cancelledNoncooperativeDnsCannotExceedCapacityAndNextSendIsOverloaded()
            throws Exception {
        CountDownLatch resolverEntered = new CountDownLatch(1);
        CountDownLatch releaseResolver = new CountDownLatch(1);
        AtomicInteger resolverCalls = new AtomicInteger();
        RepostRuntime runtime = RepostRuntime.create(ClientOptions.builder()
                .apiKey("test-key")
                .baseUri("https://blocked-dns-contract.invalid:443")
                .httpTransportOptions(HttpTransportOptions.builder()
                        .dnsResolver(host -> {
                            resolverCalls.incrementAndGet();
                            resolverEntered.countDown();
                            awaitIgnoringInterrupts(releaseResolver);
                            return Collections.singletonList(
                                    InetAddress.getByName("127.0.0.1"));
                        })
                        .build())
                .maxInFlightOperations(1)
                .maxAttempts(1)
                .build());
        try {
            SendOperation cancelled = send(runtime);
            assertEquals(true, resolverEntered.await(2, TimeUnit.SECONDS));
            assertEquals(true, cancelled.cancel(false));
            assertEquals(true, cancelled.outcome().toCompletableFuture().isDone());

            RepostException overloaded = sendFailure(runtime);
            assertEquals(RepostErrorCode.OVERLOADED, overloaded.getErrorCode());
            assertEquals(DeliveryState.NOT_SENT, overloaded.getDeliveryState());
            assertEquals(RepostCauseCategory.HTTP_RUNTIME, overloaded.getCauseCategory());
            assertEquals(0, overloaded.getAttemptCount());
            assertEquals(1, resolverCalls.get());
        } finally {
            releaseResolver.countDown();
            runtime.close();
        }
    }

    private static void assertSingleRawExchange(
            int status,
            String responseHeaders,
            byte[] responseBody) throws Exception {
        assertSingleRawExchange(status, responseHeaders, responseBody, false);
    }

    private static void assertSingleRawExchange(
            int status,
            String responseHeaders,
            byte[] responseBody,
            boolean bodyDiscarded) throws Exception {
        try (SingleExchangePeer peer = new SingleExchangePeer(status, responseHeaders, responseBody)) {
            RepostRuntime runtime = RepostRuntime.createForTesting(
                    ClientOptions.builder()
                            .apiKey("test-key")
                            .baseUri(peer.baseUri())
                            .build(),
                    key -> null);
            try {
                Transport transport = runtime.transport();
                assertNotNull(transport);
                TransportRequest request = new TransportRequest(
                        peer.uri(),
                        Arrays.asList(
                                TransportHeaderField.of("Authorization", "Bearer test-key"),
                                TransportHeaderField.of("Content-Type", "application/json"),
                                TransportHeaderField.of("Accept-Encoding", "gzip"),
                                TransportHeaderField.of("User-Agent", "repost-java/1.0.0"),
                                TransportHeaderField.of("Idempotency-Key", "msg_test")),
                        ByteBuffer.wrap(new byte[] {'{', '}'}),
                        1,
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(5));
                try (TransportResponse response = transport.execute(request)
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS)) {
                    assertEquals(status, response.getStatusCode());
                    assertArrayEquals(
                            bodyDiscarded ? new byte[0] : responseBody,
                            response.getBody().readAllBytes());
                }
                peer.await();
                assertEquals(1, peer.primaryHits.get());
                assertEquals(0, peer.unexpectedHits.get());
            } finally {
                runtime.close();
            }
        }
    }

    private static void assertRawOverflowCancels(OverflowPeer peer) throws Exception {
        ApacheHttpTransport transport = new ApacheHttpTransport();
        try {
            TransportRequest request = new TransportRequest(
                    peer.uri(),
                    Arrays.asList(
                            TransportHeaderField.of("Content-Type", "application/json"),
                            TransportHeaderField.of("Idempotency-Key", "msg_limit")),
                    ByteBuffer.wrap(new byte[] {'{', '}'}),
                    1,
                    Duration.ofSeconds(2),
                    Duration.ofSeconds(10));
            Future<TransportResponse> response = transport.execute(request).toCompletableFuture();

            peer.awaitClientClose();
            try {
                TransportResponse unexpected = response.get(2, TimeUnit.SECONDS);
                unexpected.close();
                fail("response larger than 1 MiB must not complete successfully");
            } catch (CancellationException | ExecutionException expected) {
                // The stable response-too-large mapping is owned by Task 7.8.
            }
        } finally {
            transport.close();
        }
    }

    private static RepostException sendFailure(RepostRuntime runtime) {
        return operationFailure(send(runtime));
    }

    private static RepostRuntime responseRuntime(String baseUri) {
        return RepostRuntime.create(ClientOptions.builder()
                .apiKey("test-key")
                .baseUri(baseUri)
                .defaultValueGenerators(DefaultValueGenerators.fixed(
                        Instant.parse("2026-01-01T00:00:00Z"),
                        "12345678-1234-4234-9234-123456789abc",
                        "abcdefghijklmnopqrstuvwx"))
                .maxAttempts(1)
                .build());
    }

    private static void assertProductionResponseFailure(
            byte[] responseBody,
            RepostErrorCode expectedCode) throws Exception {
        try (SingleExchangePeer peer = new SingleExchangePeer(
                202,
                "Content-Type: application/json\r\nContent-Encoding: gzip\r\n",
                responseBody,
                true)) {
            RepostRuntime runtime = responseRuntime(peer.baseUri());
            try {
                RepostException failure = sendFailure(runtime);
                assertEquals(expectedCode, failure.getErrorCode());
                assertEquals(Integer.valueOf(202), failure.getHttpStatus());
                assertEquals(DeliveryState.POSSIBLY_SENT, failure.getDeliveryState());
                assertEquals(1, failure.getAttemptCount());
                peer.await();
            } finally {
                runtime.close();
            }
        }
    }

    private static byte[] exactAcceptedResponse(int byteLength) {
        String prefix = "{\"id\":\"msg_gzip_limit\",\"type\":\"contract.sent\","
                + "\"customerId\":\"customer_dns_contract\","
                + "\"timestamp\":\"2026-01-01T00:00:00.000Z\",\"padding\":[";
        String suffix = "]}";
        StringBuilder body = new StringBuilder(byteLength);
        body.append(prefix);
        int arrayBytes = byteLength - prefix.length() - suffix.length();
        int strings = Math.max(1, (arrayBytes + 8_003) / 8_003);
        int characters = arrayBytes - (3 * strings - 1);
        if (characters < 0 || characters > strings * 8_000) {
            throw new IllegalArgumentException("byteLength cannot fit response structure");
        }
        int sequence = 0;
        for (int stringIndex = 0; stringIndex < strings; stringIndex++) {
            if (stringIndex > 0) {
                body.append(',');
            }
            body.append('"');
            int count = Math.min(8_000, characters);
            characters -= count;
            for (int index = 0; index < count; index++) {
                sequence = sequence * 1_103_515_245 + 12_345;
                body.append((char) ('a' + ((sequence >>> 16) & 15)));
            }
            body.append('"');
        }
        body.append(suffix);
        assertEquals(byteLength, body.length());
        return body.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] repetitiveAcceptedResponse(int minimumLength) {
        String prefix = "{\"id\":\"msg_gzip_limit\",\"type\":\"contract.sent\","
                + "\"customerId\":\"customer_dns_contract\","
                + "\"timestamp\":\"2026-01-01T00:00:00.000Z\",\"padding\":[";
        StringBuilder body = new StringBuilder(minimumLength + 8_192);
        body.append(prefix);
        while (body.length() < minimumLength) {
            if (body.charAt(body.length() - 1) != '[') {
                body.append(',');
            }
            body.append('"').append("a".repeat(8_000)).append('"');
        }
        body.append("]}");
        return body.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] gzip(byte[] body) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(body);
        }
        return output.toByteArray();
    }

    private static SendOperation send(RepostRuntime runtime) {
        SchemaDescriptor schema = SchemaDescriptor.builder(2)
                .addModel(ModelDescriptor.of("Payload", Collections.emptyList()))
                .addEvent(
                        "events",
                        "created",
                        EventDescriptor.of("contract.sent", "Payload"))
                .build();
        EventDescriptor event = schema.getWebhooks().get("events").get("created");
        RepostModel model = new RepostModel() {
            @Override
            public boolean __repostIsPresent(int fieldIndex) {
                return false;
            }

            @Override
            public Object __repostValue(int fieldIndex) {
                return null;
            }
        };
        return runtime.sendAsync(
                schema,
                event,
                "customer_dns_contract",
                model,
                SendOptions.builder().idempotencyKey("idem_dns_contract").build());
    }

    private static RepostException operationFailure(SendOperation operation) {
        try {
            operation.get(3, TimeUnit.SECONDS);
            throw new AssertionError("expected operation failure");
        } catch (ExecutionException failure) {
            return (RepostException) failure.getCause();
        } catch (Exception failure) {
            throw new AssertionError("operation did not publish a bounded failure", failure);
        }
    }

    private static void assertTrueDnsThread(Thread thread) {
        assertNotNull(thread);
        assertEquals(true, thread.getName().startsWith("repost-dns-"));
        assertEquals(true, thread.isDaemon());
        assertEquals(null, thread.getContextClassLoader());
    }

    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException failure) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class SingleExchangePeer implements AutoCloseable {
        private final ServerSocket server = new ServerSocket();
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final AtomicInteger primaryHits = new AtomicInteger();
        private final AtomicInteger unexpectedHits = new AtomicInteger();
        private final boolean runtimeRequest;
        private final Future<?> result;

        private SingleExchangePeer(int status, String headers, byte[] body) throws IOException {
            this(status, headers, body, false);
        }

        private SingleExchangePeer(
                int status,
                String headers,
                byte[] body,
                boolean runtimeRequest) throws IOException {
            this(status, headers, body, runtimeRequest, false);
        }

        private SingleExchangePeer(
                int status,
                String headers,
                byte[] body,
                boolean runtimeRequest,
                boolean responseMayReset) throws IOException {
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            this.runtimeRequest = runtimeRequest;
            result = executor.submit(() -> serve(status, headers, body, responseMayReset));
        }

        private String baseUri() {
            return "http://127.0.0.1:" + server.getLocalPort();
        }

        private URI uri() {
            return URI.create(baseUri() + "/v1/messages");
        }

        private void await() throws Exception {
            result.get(5, TimeUnit.SECONDS);
        }

        private void serve(int status, String headers, byte[] body, boolean responseMayReset) {
            try {
                server.setSoTimeout(5_000);
                servePrimary(server.accept(), status, headers, body, responseMayReset);
                server.setSoTimeout(500);
                try (Socket unexpected = server.accept()) {
                    assertNotNull(unexpected);
                    unexpectedHits.incrementAndGet();
                } catch (SocketTimeoutException expected) {
                    // The bounded quiet period proves no hidden exchange was attempted.
                }
            } catch (IOException failure) {
                throw new AssertionError("scenario peer failed", failure);
            }
        }

        private void servePrimary(
                Socket socket,
                int status,
                String headers,
                byte[] body,
                boolean responseMayReset)
                throws IOException {
            try (Socket owned = socket) {
                owned.setSoTimeout(5_000);
                InputStream input = owned.getInputStream();
                String requestHeaders = readHeaders(input);
                String requestLine = requestHeaders.substring(0, requestHeaders.indexOf("\r\n"));
                assertEquals("POST /v1/messages HTTP/1.1", requestLine);
                assertEquals(0, headerCount(requestHeaders, "Expect"));
                assertEquals(0, headerCount(requestHeaders, "Upgrade"));
                assertEquals(0, headerCount(requestHeaders, "HTTP2-Settings"));
                assertEquals(0, headerCount(requestHeaders, "Cookie"));
                assertEquals(0, headerCount(requestHeaders, "Proxy-Authorization"));
                assertEquals(1, headerCount(requestHeaders, "Authorization"));
                assertEquals(1, headerCount(requestHeaders, "Content-Type"));
                assertEquals(1, headerCount(requestHeaders, "Accept-Encoding"));
                assertEquals(1, headerCount(requestHeaders, "User-Agent"));
                assertEquals(1, headerCount(requestHeaders, "Idempotency-Key"));
                assertEquals(0, headerCount(requestHeaders, "Transfer-Encoding"));
                int requestLength = contentLength(requestHeaders);
                if (runtimeRequest) {
                    assertEquals(true, requestLength > 0);
                    assertEquals(requestLength, input.readNBytes(requestLength).length);
                } else {
                    assertEquals(2, requestLength);
                    assertArrayEquals(new byte[] {'{', '}'}, input.readNBytes(2));
                }
                primaryHits.incrementAndGet();

                String reason = status == 200 ? "OK" : status == 302 ? "Found"
                        : status == 401 ? "Unauthorized" : "Service Unavailable";
                String response = "HTTP/1.1 " + status + " " + reason + "\r\n"
                        + headers
                        + "Content-Length: " + body.length + "\r\n"
                        + "Connection: close\r\n\r\n";
                try {
                    owned.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
                    owned.getOutputStream().write(body);
                    owned.getOutputStream().flush();
                } catch (java.net.SocketException reset) {
                    if (!responseMayReset) {
                        throw reset;
                    }
                }
            }
        }

        @Override
        public void close() throws IOException {
            server.close();
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("scenario peer did not terminate");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while closing scenario peer", interrupted);
            }
        }
    }

    private static final class OneByteDataStreamChannel implements DataStreamChannel {
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private final AtomicInteger endCount = new AtomicInteger();

        @Override
        public void requestOutput() {
        }

        @Override
        public int write(ByteBuffer source) {
            if (!source.hasRemaining()) {
                return 0;
            }
            body.write(source.get());
            return 1;
        }

        @Override
        public void endStream() {
            endCount.incrementAndGet();
        }

        @Override
        public void endStream(List<? extends sh.repost.internal.apache.org.apache.hc.core5.http.Header> trailers) {
            endCount.incrementAndGet();
        }
    }

    private static final class OverflowPeer implements AutoCloseable {
        private static final int RESPONSE_LIMIT = 1_048_576;

        private final ServerSocket server = new ServerSocket();
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final String responseHeaders;
        private final Future<?> result;

        private OverflowPeer() throws IOException {
            this("");
        }

        private OverflowPeer(String responseHeaders) throws IOException {
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            this.responseHeaders = responseHeaders;
            result = executor.submit(this::serve);
        }

        private URI uri() {
            return URI.create("http://127.0.0.1:" + server.getLocalPort() + "/v1/messages");
        }

        private void awaitClientClose() throws Exception {
            result.get(3, TimeUnit.SECONDS);
        }

        private void serve() {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(2_000);
                InputStream input = socket.getInputStream();
                String headers = readHeaders(input);
                assertEquals(2, contentLength(headers));
                assertArrayEquals(new byte[] {'{', '}'}, input.readNBytes(2));

                socket.getOutputStream().write((
                        "HTTP/1.1 200 OK\r\n"
                                + responseHeaders
                                + "Transfer-Encoding: chunked\r\n"
                                + "Connection: keep-alive\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                byte[] chunk = new byte[16_384];
                Arrays.fill(chunk, (byte) 'a');
                for (int sent = 0; sent < RESPONSE_LIMIT; sent += chunk.length) {
                    socket.getOutputStream().write("4000\r\n".getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().write(chunk);
                    socket.getOutputStream().write("\r\n".getBytes(StandardCharsets.US_ASCII));
                }
                socket.getOutputStream().write("1\r\nx\r\n".getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();

                try {
                    if (input.read() != -1) {
                        throw new AssertionError("client wrote bytes after the request body");
                    }
                } catch (java.net.SocketException expected) {
                    // A reset also proves prompt cancellation at the overflow boundary.
                }
            } catch (SocketTimeoutException failure) {
                throw new AssertionError("client did not close promptly after response overflow", failure);
            } catch (IOException failure) {
                throw new AssertionError("overflow scenario peer failed", failure);
            }
        }

        @Override
        public void close() throws IOException {
            server.close();
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("overflow scenario peer did not terminate");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while closing overflow peer", interrupted);
            }
        }
    }

    private static final class NonSuccessDiscardPeer implements AutoCloseable {
        private static final int DISCARD_WINDOW_BYTES = 65_536;

        private final ServerSocket server = new ServerSocket();
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final Future<?> result;

        private NonSuccessDiscardPeer() throws IOException {
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            result = executor.submit(this::serve);
        }

        private String baseUri() {
            return "http://127.0.0.1:" + server.getLocalPort();
        }

        private void awaitClientClose() throws Exception {
            result.get(3, TimeUnit.SECONDS);
        }

        private void serve() {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(2_000);
                InputStream input = socket.getInputStream();
                String headers = readHeaders(input);
                int requestLength = contentLength(headers);
                assertEquals(true, requestLength > 0);
                assertEquals(requestLength, input.readNBytes(requestLength).length);

                socket.getOutputStream().write((
                        "HTTP/1.1 503 Service Unavailable\r\n"
                                + "Transfer-Encoding: chunked\r\n"
                                + "Connection: keep-alive\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                byte[] chunk = new byte[16_384];
                Arrays.fill(chunk, (byte) 's');
                int sent = 0;
                try {
                    while (sent < DISCARD_WINDOW_BYTES) {
                        socket.getOutputStream().write("4000\r\n".getBytes(StandardCharsets.US_ASCII));
                        socket.getOutputStream().write(chunk);
                        sent += chunk.length;
                        socket.getOutputStream().write("\r\n".getBytes(StandardCharsets.US_ASCII));
                    }
                    socket.getOutputStream().flush();
                } catch (java.net.SocketException reset) {
                    if (sent < DISCARD_WINDOW_BYTES) {
                        throw reset;
                    }
                    return;
                }

                try {
                    if (input.read() != -1) {
                        throw new AssertionError("client wrote bytes after the request body");
                    }
                } catch (java.net.SocketException expected) {
                    // A reset is also a prompt close of the discarded response stream.
                }
            } catch (SocketTimeoutException failure) {
                throw new AssertionError(
                        "client did not close after the non-success discard window", failure);
            } catch (IOException failure) {
                throw new AssertionError("non-success discard peer failed", failure);
            }
        }

        @Override
        public void close() throws IOException {
            server.close();
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("non-success discard peer did not terminate");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(
                        "interrupted while closing non-success discard peer", interrupted);
            }
        }
    }

    private static String readHeaders(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int matched = 0;
        while (bytes.size() < 16_384) {
            int value = input.read();
            if (value == -1) {
                throw new IOException("request ended before headers");
            }
            bytes.write(value);
            matched = value == "\r\n\r\n".charAt(matched) ? matched + 1 : 0;
            if (matched == 4) {
                return new String(bytes.toByteArray(), StandardCharsets.US_ASCII);
            }
        }
        throw new IOException("request headers exceeded scenario bound");
    }

    private static int headerCount(String headers, String name) {
        int count = 0;
        String prefix = name + ':';
        for (String line : headers.split("\r\n")) {
            if (line.regionMatches(true, 0, prefix, 0, prefix.length())) {
                count++;
            }
        }
        return count;
    }

    private static int contentLength(String headers) {
        List<String> lines = Arrays.asList(headers.split("\r\n"));
        int result = -1;
        for (String line : lines) {
            if (line.regionMatches(true, 0, "Content-Length:", 0, 15)) {
                assertEquals(-1, result, "duplicate content length");
                result = Integer.parseInt(line.substring(15).trim());
            }
        }
        return result;
    }
}
