package sh.repost.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.PasswordAuthentication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKeyFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import javax.crypto.spec.PBEKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Task 7.10 production mapping:
 *
 * <ul>
 *   <li>Explicit proxying: HTTPS uses one CONNECT tunnel and never a forward-form request.</li>
 *   <li>TLS: explicit trust roots, hostname verification, protocol restrictions, and mTLS
 *       identity are enforced by the production adapter.</li>
 *   <li>ALPN preference and fallback: H2 listener uses one H2 session; H1-only listener records
 *       an HTTP/1.1 negotiation without touching the H2 listener's H1 fallback path.</li>
 *   <li>Multiplexing: two held operations overlap as streams 1 and 3 on one physical session.</li>
 *   <li>Cancellation: cancelling stream 1 preserves peer stream 3 and probe stream 5.</li>
 *   <li>GOAWAY: stream 1 succeeds, stream 3 is not replayed by Apache, and one explicit later
 *       attempt succeeds as stream 1 on a replacement session.</li>
 * </ul>
 */
public final class ApacheHttpTransportHttp2ProductionTest {
    private static final byte[] REQUEST_BODY = (
            "{\"type\":\"contract.sent\",\"customerId\":\"cus_contract\","
                    + "\"timestamp\":\"2026-01-01T00:00:00.000Z\","
                    + "\"data\":{\"value\":\"jvm-h2\"}}")
            .getBytes(StandardCharsets.UTF_8);

    private static NetworkFixture fixture;

    @BeforeAll
    static void startFixture() throws Exception {
        fixture = NetworkFixture.start();
    }

    @AfterAll
    static void stopFixture() throws Exception {
        if (fixture != null) {
            fixture.close();
        }
    }

    @BeforeEach
    void resetFixture() throws Exception {
        fixture.reset();
    }

    @Test
    void routesHttpsThroughTheExplicitConnectProxy() throws Exception {
        HttpProxyOptions proxy = HttpProxyOptions.builder()
                .uri(fixture.value("proxyUrl"))
                .build();
        try (ApacheHttpTransport transport = new ApacheHttpTransport(
                HttpTransportOptions.builder()
                        .sslContext(fixture.sslContext())
                        .proxy(proxy)
                        .build())) {
            try {
                assertSuccess(send(transport, fixture.uri("trustedBaseUrl")));
            } catch (Exception failure) {
                throw new AssertionError("explicit proxy fixture state: " + fixture.state(), failure);
            }

            Map<String, Object> state = fixture.state();
            Map<String, Object> observedProxy = object(state, "proxy");
            assertEquals(1L, number(observedProxy, "connectAttempts"));
            assertEquals(1L, number(observedProxy, "tunnels"));
            assertEquals(0L, number(observedProxy, "authFailures"));
            assertEquals(0L, number(observedProxy, "deniedAuthorities"));
            assertEquals(0L, number(observedProxy, "forwardRequests"));
            assertEquals(1L, number(object(state, "trusted"), "requests"));
        }
    }

    @Test
    void splitHorizonDnsPreservesCanonicalOriginTlsAcrossDirectAndConnectModes()
            throws Exception {
        URI origin = fixture.uri("trustedBaseUrl");
        ArrayList<String> directHosts = new ArrayList<>();
        try (ApacheHttpTransport direct = new ApacheHttpTransport(
                HttpTransportOptions.builder()
                        .sslContext(fixture.sslContext())
                        .dnsResolver(host -> {
                            directHosts.add(host);
                            return Arrays.asList(InetAddress.getByName("127.0.0.1"));
                        })
                        .build())) {
            assertSuccess(send(direct, origin));
        }
        assertEquals(Arrays.asList(origin.getHost()), directHosts);

        URI proxyUri = URI.create(fixture.value("proxyUrl"));
        ArrayList<String> connectHosts = new ArrayList<>();
        try (ApacheHttpTransport throughProxy = new ApacheHttpTransport(
                HttpTransportOptions.builder()
                        .sslContext(fixture.sslContext())
                        .proxy(HttpProxyOptions.builder()
                                .uri(proxyUri.toASCIIString())
                                .build())
                        .dnsResolver(host -> {
                            connectHosts.add(host);
                            return Arrays.asList(InetAddress.getByName("127.0.0.1"));
                        })
                        .build())) {
            assertSuccess(send(throughProxy, origin));
        }
        assertEquals(Arrays.asList(proxyUri.getHost()), connectHosts);

        Map<String, Object> state = fixture.state();
        assertEquals(2L, number(object(state, "trusted"), "requests"));
        assertEquals(1L, number(object(state, "proxy"), "connectAttempts"));
        assertEquals(1L, number(object(state, "proxy"), "tunnels"));
    }

    @Test
    void authenticatesTheFirstConnectWithoutAChallengeReplay() throws Exception {
        AtomicInteger credentialCalls = new AtomicInteger();
        char[] password = Files.readString(
                        Path.of(fixture.asset("proxyCredentialPath")), StandardCharsets.UTF_8)
                .stripTrailing()
                .toCharArray();
        HttpProxyOptions proxy = HttpProxyOptions.builder()
                .uri(fixture.value("authenticatedProxyUrl"))
                .credentialsProvider(() -> {
                    credentialCalls.incrementAndGet();
                    return new PasswordAuthentication(
                            fixture.value("proxyUsername"), password.clone());
                })
                .build();
        try (ApacheHttpTransport transport = new ApacheHttpTransport(
                HttpTransportOptions.builder()
                        .sslContext(fixture.sslContext())
                        .proxy(proxy)
                        .build())) {
            assertSuccess(send(transport, fixture.uri("trustedBaseUrl")));

            Map<String, Object> state = fixture.state();
            Map<String, Object> observedProxy = object(state, "authenticatedProxy");
            assertEquals(1, credentialCalls.get());
            assertEquals(1L, number(observedProxy, "connectAttempts"));
            assertEquals(1L, number(observedProxy, "tunnels"));
            assertEquals(0L, number(observedProxy, "authFailures"));
            assertEquals(0L, number(observedProxy, "deniedAuthorities"));
            assertEquals(0L, number(observedProxy, "forwardRequests"));
            assertEquals(1L, number(object(state, "trusted"), "requests"));
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    @Test
    void returnsProxyGenerated407WithoutPublishingToTheOrigin() throws Exception {
        HttpProxyOptions proxy = HttpProxyOptions.builder()
                .uri(fixture.value("authenticatedProxyUrl"))
                .build();
        try (ApacheHttpTransport transport = new ApacheHttpTransport(
                HttpTransportOptions.builder()
                        .sslContext(fixture.sslContext())
                        .proxy(proxy)
                        .build());
                TransportResponse response = send(
                                transport, fixture.uri("trustedBaseUrl"))
                        .get(10, TimeUnit.SECONDS)) {
            assertEquals(407, response.getStatusCode());
        }

        Map<String, Object> state = fixture.state();
        Map<String, Object> observedProxy = object(state, "authenticatedProxy");
        assertEquals(1L, number(observedProxy, "connectAttempts"));
        assertEquals(0L, number(observedProxy, "tunnels"));
        assertEquals(1L, number(observedProxy, "authFailures"));
        assertEquals(0L, number(object(state, "trusted"), "requests"));
    }

    @Test
    void enforcesCustomTrustAndRejectsHostnameMismatchBeforeHttpPublication() throws Exception {
        try (ApacheHttpTransport trusted = transport()) {
            assertSuccess(send(trusted, fixture.uri("trustedBaseUrl")));
        }
        assertEquals(1L, number(object(fixture.state(), "trusted"), "requests"));

        try (ApacheHttpTransport untrusted = new ApacheHttpTransport()) {
            TransportFailure failure = assertTransportFailure(
                    send(untrusted, fixture.uri("trustedBaseUrl")));
            assertFailure(
                    failure,
                    RepostErrorCode.TLS,
                    RequestCommitState.NOT_COMMITTED,
                    RepostFailureReason.TLS_UNTRUSTED);
        }
        assertEquals(1L, number(object(fixture.state(), "trusted"), "requests"));

        try (ApacheHttpTransport mismatched = transport()) {
            TransportFailure failure = assertTransportFailure(
                    send(mismatched, fixture.uri("hostnameMismatchBaseUrl")));
            assertFailure(
                    failure,
                    RepostErrorCode.TLS,
                    RequestCommitState.NOT_COMMITTED,
                    RepostFailureReason.TLS_HOSTNAME_MISMATCH);
        }
        assertEquals(0L, number(object(fixture.state(), "hostnameMismatch"), "requests"));
    }

    @Test
    void classifiesAResponseHeaderTimeoutAfterRequestPublication() throws Exception {
        try (ApacheHttpTransport transport = transport()) {
            CompletableFuture<TransportResponse> response = send(
                    transport,
                    fixture.uri("heldResponseBaseUrl"),
                    Duration.ofMillis(250));
            fixture.await(() -> fixture.number("barriers", "responseHeaders", "waiting") == 1L);

            TransportFailure failure = assertTransportFailure(response);
            assertFailure(
                    failure,
                    RepostErrorCode.ATTEMPT_TIMEOUT,
                    RequestCommitState.COMMITTED,
                    RepostFailureReason.UNKNOWN);
            fixture.await(() -> fixture.number("barriers", "responseHeaders", "waiting") == 0L);
        }
        assertEquals(1L, number(object(fixture.state(), "trusted"), "requests"));
    }

    @Test
    void restrictsTlsNegotiationToTheConfiguredProtocol() throws Exception {
        try (ApacheHttpTransport transport = new ApacheHttpTransport(
                HttpTransportOptions.builder()
                        .sslContext(fixture.sslContext())
                        .tlsProtocols(Arrays.asList("TLSv1.2"))
                        .build())) {
            assertSuccess(send(transport, fixture.uri("tls12OnlyBaseUrl")));
        }
        assertEquals(1L, number(object(fixture.state(), "tls12Only"), "requests"));
    }

    @Test
    void presentsTheConfiguredClientIdentityForMutualTls() throws Exception {
        try (ApacheHttpTransport transport = new ApacheHttpTransport(
                HttpTransportOptions.builder()
                        .sslContext(fixture.mtlsSslContext())
                        .build())) {
            assertSuccess(send(transport, fixture.uri("mtlsBaseUrl")));
        }
        Map<String, Object> observed = object(fixture.state(), "mtls");
        assertEquals(1L, number(observed, "requests"));
        assertEquals(1L, number(observed, "authorizedRequests"));
    }

    @Test
    void prefersH2ViaAlpnAndFallsBackOnlyOnTheDedicatedH1Endpoint() throws Exception {
        try (ApacheHttpTransport transport = transport()) {
            assertSuccess(send(transport, fixture.uri("http2BaseUrl")));
            assertSuccess(send(transport, fixture.uri("http2FallbackBaseUrl")));

            Map<String, Object> state = fixture.state();
            Map<String, Object> http2 = object(state, "http2");
            assertEquals(1L, number(http2, "sessions"));
            assertEquals(1L, number(http2, "streams"));
            assertEquals(0L, number(http2, "http1FallbackRequests"));
            Map<String, Object> http1 = object(state, "http1Only");
            assertEquals(1L, number(http1, "connections"));
            assertEquals(1L, number(http1, "requests"));
            assertEquals(1L, number(http1, "negotiatedHttp1"));
            assertEquals(Arrays.asList("http/1.1"), array(http1, "negotiatedProtocols"));
        }
    }

    @Test
    void multiplexesTwoConcurrentOperationsAsStreamsOneAndThreeOnOneSession()
            throws Exception {
        try (ApacheHttpTransport transport = transport()) {
            CompletableFuture<TransportResponse> left =
                    send(transport, fixture.uri("http2HeldBaseUrl"));
            CompletableFuture<TransportResponse> right =
                    send(transport, fixture.uri("http2HeldBaseUrl"));

            fixture.await(() -> fixture.number("http2Barriers", "streams", "waiting") == 2L);
            Map<String, Object> state = fixture.state();
            Map<String, Object> http2 = object(state, "http2");
            assertEquals(1L, number(http2, "sessions"));
            assertEquals(Arrays.asList("1:1", "1:3"), array(http2, "streamIds"));
            assertEquals(2L, number(http2, "activeStreams"));
            assertEquals(2L, number(http2, "maxConcurrentStreams"));
            assertEquals(1L, number(http2, "sameSessionOverlapCount"));

            fixture.releaseHttp2Streams();
            assertSuccess(left);
            assertSuccess(right);
        }
    }

    @Test
    void cancellingOneH2StreamPreservesItsPeerAndALaterProbeOnTheSameSession()
            throws Exception {
        try (ApacheHttpTransport transport = transport()) {
            CompletableFuture<TransportResponse> cancelled =
                    send(transport, fixture.uri("http2HeldBaseUrl"));
            fixture.await(() -> fixture.number("http2Barriers", "streams", "waiting") == 1L);
            CompletableFuture<TransportResponse> peer = send(
                    transport,
                    fixture.operationUri("http2BaseUrl", "peer"));

            fixture.await(() -> fixture.number("http2Barriers", "streams", "waiting") == 2L);
            assertTrue(cancelled.cancel(true));
            assertThrows(CancellationException.class, cancelled::join);
            fixture.await(() -> fixture.number("http2", "cancelledStreams") == 1L);

            fixture.releaseHttp2Streams();
            assertSuccess(peer);
            assertSuccess(send(
                    transport,
                    fixture.operationUri("http2BaseUrl", "probe")));

            Map<String, Object> http2 = object(fixture.state(), "http2");
            assertEquals(1L, number(http2, "sessions"));
            assertEquals(Arrays.asList("1:1", "1:3", "1:5"), array(http2, "streamIds"));
            assertEquals(1L, number(http2, "peerSuccesses"));
            assertEquals(1L, number(http2, "postIsolationProbeSuccesses"));
            assertEquals(0L, number(http2, "collateralStreamFailures"));
        }
    }

    @Test
    void classifiesServerH2ResetAfterPublicationAndCleansOnlyThatStream()
            throws Exception {
        try (ApacheHttpTransport transport = transport()) {
            CompletableFuture<TransportResponse> reset =
                    send(transport, fixture.uri("http2ResetBaseUrl"));
            fixture.await(() -> fixture.number("http2", "streams") == 1L);
            CompletableFuture<TransportResponse> peer = send(
                    transport,
                    fixture.operationUri("http2BaseUrl", "peer"));

            TransportFailure failure = assertTransportFailure(reset);
            assertFailure(
                    failure,
                    RepostErrorCode.IO,
                    RequestCommitState.COMMITTED,
                    RepostFailureReason.CONNECTION_RESET);
            fixture.await(() -> fixture.number("http2Barriers", "streams", "waiting") == 1L);

            fixture.releaseHttp2Streams();
            assertSuccess(peer);
            assertSuccess(send(
                    transport,
                    fixture.operationUri("http2BaseUrl", "probe")));
            fixture.await(() -> fixture.number("http2", "activeStreams") == 0L);

            Map<String, Object> http2 = object(fixture.state(), "http2");
            assertEquals(1L, number(http2, "resetStreams"));
            assertEquals(1L, number(http2, "sessions"));
            assertEquals(Arrays.asList("1:1", "1:3", "1:5"), array(http2, "streamIds"));
            assertEquals(REQUEST_BODY.length * 3L, number(http2, "requestBodyBytes"));
            assertEquals(1L, number(http2, "peerSuccesses"));
            assertEquals(1L, number(http2, "postIsolationProbeSuccesses"));
            assertEquals(0L, number(http2, "collateralStreamFailures"));
        }
    }

    @Test
    void goawayDoesNotReplayStreamThreeAndALaterExplicitAttemptUsesAReplacementSession()
            throws Exception {
        try (ApacheHttpTransport transport = transport()) {
            CompletableFuture<TransportResponse> accepted =
                    send(transport, fixture.uri("http2GoawayBaseUrl"));
            fixture.await(() -> fixture.number("http2", "streams") == 1L);
            CompletableFuture<TransportResponse> unprocessed =
                    send(transport, fixture.uri("http2GoawayRetryBaseUrl"));

            assertSuccess(accepted);
            assertFailure(unprocessed);
            Map<String, Object> beforeRetry = object(fixture.state(), "http2");
            assertEquals(Arrays.asList("1:1", "1:3"), array(beforeRetry, "streamIds"));
            assertEquals(0L, number(object(beforeRetry, "goaway"), "retrySuccesses"));

            assertSuccess(send(transport, fixture.uri("http2GoawayRetryBaseUrl")));
            Map<String, Object> http2 = object(fixture.state(), "http2");
            assertEquals(Arrays.asList(1L, 2L), array(http2, "sessionIds"));
            assertEquals(Arrays.asList("1:1", "1:3", "2:1"), array(http2, "streamIds"));
            Map<String, Object> goaway = object(http2, "goaway");
            assertEquals(1L, number(goaway, "sent"));
            assertEquals(1L, number(goaway, "lastStreamId"));
            assertEquals(1L, number(goaway, "acceptedStreamId"));
            assertEquals(3L, number(goaway, "unprocessedStreamId"));
            assertEquals(2L, number(goaway, "retrySessionId"));
            assertEquals(1L, number(goaway, "retryStreamId"));
            assertEquals(1L, number(goaway, "retrySuccesses"));
        }
    }

    private static ApacheHttpTransport transport() {
        return new ApacheHttpTransport(HttpTransportOptions.builder()
                .sslContext(fixture.sslContext())
                .build());
    }

    private static CompletableFuture<TransportResponse> send(
            ApacheHttpTransport transport,
            URI uri) {
        return send(transport, uri, Duration.ofSeconds(10));
    }

    private static CompletableFuture<TransportResponse> send(
            ApacheHttpTransport transport,
            URI uri,
            Duration attemptTimeout) {
        TransportRequest request = new TransportRequest(
                uri,
                Arrays.asList(
                        TransportHeaderField.of("Authorization", "Bearer test-key"),
                        TransportHeaderField.of("Content-Type", "application/json"),
                        TransportHeaderField.of("Idempotency-Key", "msg_h2")),
                ByteBuffer.wrap(REQUEST_BODY),
                1,
                Duration.ofSeconds(2),
                attemptTimeout);
        return transport.execute(request).toCompletableFuture();
    }

    private static void assertSuccess(CompletableFuture<TransportResponse> response)
            throws Exception {
        try (TransportResponse acquired = response.get(10, TimeUnit.SECONDS)) {
            assertEquals(202, acquired.getStatusCode());
            Map<String, Object> body = parseObject(
                    new String(acquired.getBody().readAllBytes(), StandardCharsets.UTF_8));
            assertEquals("msg_network_fixture", string(body, "id"));
            assertEquals("contract.sent", string(body, "type"));
            assertEquals("cus_contract", string(body, "customerId"));
            assertEquals("2026-01-01T00:00:00.000Z", string(body, "timestamp"));
        }
    }

    private static void assertFailure(CompletableFuture<TransportResponse> response)
            throws Exception {
        try {
            TransportResponse unexpected = response.get(10, TimeUnit.SECONDS);
            unexpected.close();
            throw new AssertionError("unprocessed GOAWAY stream must fail without replay");
        } catch (ExecutionException | CancellationException expected) {
            // Task 7.8 owns stable failure classification; this group proves wire behavior.
        }
    }

    private static TransportFailure assertTransportFailure(
            CompletableFuture<TransportResponse> response)
            throws Exception {
        try {
            TransportResponse unexpected = response.get(10, TimeUnit.SECONDS);
            unexpected.close();
            throw new AssertionError("transport attempt must fail before HTTP publication");
        } catch (ExecutionException expected) {
            assertTrue(expected.getCause() instanceof TransportFailure);
            return (TransportFailure) expected.getCause();
        }
    }

    private static void assertFailure(
            TransportFailure failure,
            RepostErrorCode code,
            RequestCommitState commitState,
            RepostFailureReason reason) {
        assertEquals(code, failure.getErrorCode());
        assertEquals(commitState, failure.getCommitState());
        assertEquals(reason, failure.getFailureReason());
    }

    private static Map<String, Object> parseObject(String value) throws IOException {
        try (JsonParser parser = new JsonFactory().createParser(value)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("JSON root must be an object");
            }
            return readCurrentObject(parser);
        }
    }

    private static Map<String, Object> readCurrentObject(JsonParser parser) throws IOException {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String name = parser.currentName();
            parser.nextToken();
            values.put(name, readValue(parser));
        }
        return values;
    }

    private static Object readValue(JsonParser parser) throws IOException {
        switch (parser.currentToken()) {
            case START_OBJECT: return readCurrentObject(parser);
            case START_ARRAY: {
                ArrayList<Object> values = new ArrayList<>();
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    values.add(readValue(parser));
                }
                return values;
            }
            case VALUE_STRING: return parser.getText();
            case VALUE_NUMBER_INT: return parser.getLongValue();
            case VALUE_TRUE: return Boolean.TRUE;
            case VALUE_FALSE: return Boolean.FALSE;
            case VALUE_NULL: return null;
            default: throw new IOException("unsupported fixture JSON token");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Map<String, Object> values, String name) {
        return (Map<String, Object>) values.get(name);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(Map<String, Object> values, String name) {
        return (List<Object>) values.get(name);
    }

    private static long number(Map<String, Object> values, String name) {
        return ((Number) values.get(name)).longValue();
    }

    private static String string(Map<String, Object> values, String name) {
        return (String) values.get(name);
    }

    private static final class NetworkFixture implements AutoCloseable {
        private static final HttpClient CONTROL_CLIENT = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        private final Process process;
        private final Map<String, Object> handshake;
        private final SSLContext sslContext;

        private NetworkFixture(
                Process process,
                Map<String, Object> handshake,
                SSLContext sslContext) {
            this.process = process;
            this.handshake = handshake;
            this.sslContext = sslContext;
        }

        private static NetworkFixture start() throws Exception {
            Path script = fixtureScript();
            Process process = new ProcessBuilder("node", script.toString())
                    .directory(script.getParent().getParent().getParent().toFile())
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();
            ExecutorService reader = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "repost-network-fixture-handshake");
                thread.setDaemon(true);
                return thread;
            });
            try {
                BufferedReader output = new BufferedReader(new InputStreamReader(
                        process.getInputStream(), StandardCharsets.UTF_8));
                Future<String> line = reader.submit(output::readLine);
                String handshakeLine = line.get(10, TimeUnit.SECONDS);
                if (handshakeLine == null) {
                    throw new IOException("network fixture exited before its handshake");
                }
                Map<String, Object> handshake = parseObject(handshakeLine);
                assertEquals("repost-enterprise-network-fixture", string(handshake, "protocol"));
                assertEquals(2L, ApacheHttpTransportHttp2ProductionTest.number(
                        handshake, "contractVersion"));
                Map<String, Object> assets = object(handshake, "assets");
                SSLContext sslContext = trust(string(assets, "caCertPath"));
                return new NetworkFixture(process, handshake, sslContext);
            } catch (Throwable failure) {
                process.destroyForcibly();
                throw failure;
            } finally {
                reader.shutdownNow();
            }
        }

        private SSLContext sslContext() {
            return sslContext;
        }

        private SSLContext mtlsSslContext() throws Exception {
            char[] password = Files.readString(
                            Path.of(asset("clientKeyPasswordPath")), StandardCharsets.UTF_8)
                    .stripTrailing()
                    .toCharArray();
            try {
                Certificate clientCertificate = certificate(asset("clientCertPath"));
                Certificate caCertificate = certificate(asset("caCertPath"));
                PrivateKey clientKey = privateKey(asset("clientKeyPath"), password);
                KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
                keyStore.load(null, null);
                keyStore.setKeyEntry(
                        "repost-network-fixture-client",
                        clientKey,
                        password,
                        new Certificate[] {clientCertificate, caCertificate});
                KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(
                        KeyManagerFactory.getDefaultAlgorithm());
                keyManagers.init(keyStore, password);

                KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
                trustStore.load(null, null);
                trustStore.setCertificateEntry("repost-network-fixture", caCertificate);
                TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(
                        TrustManagerFactory.getDefaultAlgorithm());
                trustManagers.init(trustStore);

                SSLContext context = SSLContext.getInstance("TLS");
                context.init(keyManagers.getKeyManagers(), trustManagers.getTrustManagers(), null);
                return context;
            } finally {
                Arrays.fill(password, '\0');
            }
        }

        private String value(String name) {
            return string(handshake, name);
        }

        private String asset(String name) {
            return string(object(handshake, "assets"), name);
        }

        private URI uri(String name) {
            return URI.create(string(handshake, name).replaceAll("/+$", "") + "/v1/messages");
        }

        private URI operationUri(String name, String operation) {
            return URI.create(string(handshake, name).replaceAll("/+$", "")
                    + "/" + operation + "/v1/messages");
        }

        private void reset() throws Exception {
            post("reset", "{}");
        }

        private void releaseHttp2Streams() throws Exception {
            post("release", "{\"barrier\":\"http2Streams\"}");
        }

        private Map<String, Object> state() throws Exception {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(string(handshake, "controlUrl") + "/state"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = CONTROL_CLIENT.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(200, response.statusCode());
            return parseObject(response.body());
        }

        private long number(String first, String second, String third) {
            try {
                return ApacheHttpTransportHttp2ProductionTest.number(
                        object(object(state(), first), second), third);
            } catch (Exception failure) {
                throw new AssertionError("fixture state query failed", failure);
            }
        }

        private long number(String first, String second) {
            try {
                return ApacheHttpTransportHttp2ProductionTest.number(
                        object(state(), first), second);
            } catch (Exception failure) {
                throw new AssertionError("fixture state query failed", failure);
            }
        }

        private void await(BooleanSupplier condition) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!condition.getAsBoolean()) {
                if (System.nanoTime() >= deadline) {
                    throw new AssertionError(
                            "fixture condition did not become true within 5 seconds: " + state());
                }
                Thread.sleep(10L);
            }
        }

        private void post(String operation, String body) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(string(handshake, "controlUrl") + "/" + operation))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = CONTROL_CLIENT.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(200, response.statusCode());
        }

        @Override
        public void close() throws Exception {
            try {
                if (process.isAlive()) {
                    post("close", "{}");
                    assertTrue(process.waitFor(5, TimeUnit.SECONDS),
                            "network fixture did not stop after control close");
                    assertEquals(0, process.exitValue());
                }
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                }
            }
        }

        private static SSLContext trust(String certificatePath) throws Exception {
            Certificate certificate = certificate(certificatePath);
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            trustStore.setCertificateEntry("repost-network-fixture", certificate);
            TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(trustStore);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustManagers.getTrustManagers(), null);
            return context;
        }

        private static Certificate certificate(String path) throws Exception {
            try (java.io.InputStream input = Files.newInputStream(Path.of(path))) {
                return CertificateFactory.getInstance("X.509").generateCertificate(input);
            }
        }

        private static PrivateKey privateKey(String path, char[] password) throws Exception {
            String pem = Files.readString(Path.of(path), StandardCharsets.US_ASCII);
            byte[] encryptedBytes = Base64.getMimeDecoder().decode(pem
                    .replace("-----BEGIN ENCRYPTED PRIVATE KEY-----", "")
                    .replace("-----END ENCRYPTED PRIVATE KEY-----", ""));
            PBEKeySpec passwordSpec = new PBEKeySpec(password);
            try {
                EncryptedPrivateKeyInfo encrypted = new EncryptedPrivateKeyInfo(encryptedBytes);
                SecretKeyFactory keyFactory = SecretKeyFactory.getInstance(encrypted.getAlgName());
                Cipher cipher = Cipher.getInstance(encrypted.getAlgName());
                cipher.init(
                        Cipher.DECRYPT_MODE,
                        keyFactory.generateSecret(passwordSpec),
                        encrypted.getAlgParameters());
                PKCS8EncodedKeySpec keySpec = encrypted.getKeySpec(cipher);
                return KeyFactory.getInstance("RSA").generatePrivate(keySpec);
            } finally {
                passwordSpec.clearPassword();
                Arrays.fill(encryptedBytes, (byte) 0);
            }
        }

        private static Path fixtureScript() {
            Path current = Paths.get(System.getProperty("user.dir"));
            for (Path candidate : Arrays.asList(
                    current.resolve("sdk/jvm/certification/transport/network-fixture.js"),
                    current.resolve("certification/transport/network-fixture.js"),
                    current.resolve("../certification/transport/network-fixture.js").normalize())) {
                if (Files.isRegularFile(candidate)) {
                    return candidate.toAbsolutePath().normalize();
                }
            }
            throw new AssertionError("network fixture is not reachable from the test working directory");
        }
    }
}
