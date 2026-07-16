package sh.repost.client;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.PasswordAuthentication;
import java.net.SocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import sh.repost.internal.apache.org.apache.hc.client5.http.EndpointInfo;
import sh.repost.internal.apache.org.apache.hc.client5.http.HttpRoute;
import sh.repost.internal.apache.org.apache.hc.client5.http.async.AsyncExecCallback;
import sh.repost.internal.apache.org.apache.hc.client5.http.async.AsyncExecChain;
import sh.repost.internal.apache.org.apache.hc.client5.http.async.AsyncExecChainHandler;
import sh.repost.internal.apache.org.apache.hc.client5.http.async.AsyncExecRuntime;
import sh.repost.internal.apache.org.apache.hc.client5.http.config.RequestConfig;
import sh.repost.internal.apache.org.apache.hc.client5.http.config.TlsConfig;
import sh.repost.internal.apache.org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import sh.repost.internal.apache.org.apache.hc.client5.http.impl.auth.BasicScheme;
import sh.repost.internal.apache.org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import sh.repost.internal.apache.org.apache.hc.client5.http.impl.async.H2AsyncClientBuilder;
import sh.repost.internal.apache.org.apache.hc.client5.http.impl.async.HttpAsyncClientBuilder;
import sh.repost.internal.apache.org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import sh.repost.internal.apache.org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import sh.repost.internal.apache.org.apache.hc.client5.http.impl.routing.DefaultProxyRoutePlanner;
import sh.repost.internal.apache.org.apache.hc.client5.http.protocol.HttpClientContext;
import sh.repost.internal.apache.org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import sh.repost.internal.apache.org.apache.hc.client5.http.ssl.HostnameVerificationPolicy;
import sh.repost.internal.apache.org.apache.hc.client5.http.ssl.HttpsSupport;
import sh.repost.internal.apache.org.apache.hc.core5.concurrent.Cancellable;
import sh.repost.internal.apache.org.apache.hc.core5.concurrent.CancellableDependency;
import sh.repost.internal.apache.org.apache.hc.core5.concurrent.FutureCallback;
import sh.repost.internal.apache.org.apache.hc.core5.http.EntityDetails;
import sh.repost.internal.apache.org.apache.hc.core5.http.HttpException;
import sh.repost.internal.apache.org.apache.hc.core5.http.Header;
import sh.repost.internal.apache.org.apache.hc.core5.http.HttpHost;
import sh.repost.internal.apache.org.apache.hc.core5.http.HttpRequest;
import sh.repost.internal.apache.org.apache.hc.core5.http.HttpResponse;
import sh.repost.internal.apache.org.apache.hc.core5.http.Message;
import sh.repost.internal.apache.org.apache.hc.core5.http.config.CharCodingConfig;
import sh.repost.internal.apache.org.apache.hc.core5.http.config.Http1Config;
import sh.repost.internal.apache.org.apache.hc.core5.http.message.BasicHttpRequest;
import sh.repost.internal.apache.org.apache.hc.core5.http.nio.AsyncClientExchangeHandler;
import sh.repost.internal.apache.org.apache.hc.core5.http.nio.AsyncDataConsumer;
import sh.repost.internal.apache.org.apache.hc.core5.http.nio.AsyncEntityProducer;
import sh.repost.internal.apache.org.apache.hc.core5.http.nio.ssl.TlsStrategy;
import sh.repost.internal.apache.org.apache.hc.core5.http2.H2Error;
import sh.repost.internal.apache.org.apache.hc.core5.http2.H2StreamResetException;
import sh.repost.internal.apache.org.apache.hc.core5.http2.HttpVersionPolicy;
import sh.repost.internal.apache.org.apache.hc.core5.http2.config.H2Config;
import sh.repost.internal.apache.org.apache.hc.core5.io.CloseMode;
import sh.repost.internal.apache.org.apache.hc.core5.repost.RepostBudgetedBodyConsumer;
import sh.repost.internal.apache.org.apache.hc.core5.reactor.IOReactorConfig;
import sh.repost.internal.apache.org.apache.hc.core5.reactor.IOSession;
import sh.repost.internal.apache.org.apache.hc.core5.reactor.IOSessionListener;
import sh.repost.internal.apache.org.apache.hc.core5.reactor.ssl.SSLIOSession;
import sh.repost.internal.apache.org.apache.hc.core5.reactor.ssl.TlsDetails;
import sh.repost.internal.apache.org.apache.hc.core5.reactor.ssl.TransportSecurityLayer;
import sh.repost.internal.apache.org.apache.hc.core5.net.NamedEndpoint;
import sh.repost.internal.apache.org.apache.hc.core5.util.TimeValue;
import sh.repost.internal.apache.org.apache.hc.core5.util.Timeout;

/** Package-private production adapter over the relocated Apache async engine. */
final class ApacheHttpTransport implements Transport, AutoCloseable {
    private static final String EXCHANGE_CONTROL_ATTRIBUTE =
            "sh.repost.client.exchangeControl";
    private static final RequestConfig DISABLED_AMBIENT_BEHAVIOR = RequestConfig.custom()
            .setRedirectsEnabled(false)
            .setAuthenticationEnabled(false)
            .setContentCompressionEnabled(false)
            .setProtocolUpgradeEnabled(false)
            .setExpectContinueEnabled(false)
            .setHardCancellationEnabled(false)
            .build();

    private final CloseableHttpAsyncClient h2Client;
    private final CloseableHttpAsyncClient h1Client;
    private final CloseableHttpAsyncClient proxyClient;
    private final HttpHost proxyHost;
    private final ProxyCredentialsProvider proxyCredentialsProvider;
    private final DnsIsolation dnsIsolation;
    private final Executor dnsContinuationExecutor;
    private final ScheduledExecutorService dnsTimer;
    private final StandaloneDnsResources standaloneDnsResources;
    private final ConcurrentHashMap<String, List<InetAddress>> preparedDnsRoutes =
            new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ConcurrentLinkedQueue<CompletableFuture<TransportResponse>> activeResults =
            new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<String, WireProtocol> negotiatedProtocols =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> tlsOriginsBySessionId =
            new ConcurrentHashMap<>();

    ApacheHttpTransport() {
        this(HttpTransportOptions.defaults());
    }

    ApacheHttpTransport(HttpTransportOptions options) {
        this(options, new StandaloneDnsResources());
    }

    private ApacheHttpTransport(
            HttpTransportOptions options,
            StandaloneDnsResources resources) {
        this(
                options,
                256,
                System::nanoTime,
                resources.continuationExecutor,
                resources.timer,
                resources);
    }

    ApacheHttpTransport(
            HttpTransportOptions options,
            int dnsCapacity,
            MonotonicClock clock,
            Executor dnsContinuationExecutor,
            ScheduledExecutorService dnsTimer) {
        this(options, dnsCapacity, clock, dnsContinuationExecutor, dnsTimer, null);
    }

    private ApacheHttpTransport(
            HttpTransportOptions options,
            int dnsCapacity,
            MonotonicClock clock,
            Executor dnsContinuationExecutor,
            ScheduledExecutorService dnsTimer,
            StandaloneDnsResources standaloneDnsResources) {
        HttpTransportOptions configured = Objects.requireNonNull(options, "options");
        this.dnsContinuationExecutor = Objects.requireNonNull(
                dnsContinuationExecutor, "dnsContinuationExecutor");
        this.dnsTimer = Objects.requireNonNull(dnsTimer, "dnsTimer");
        this.standaloneDnsResources = standaloneDnsResources;
        DnsResolver configuredResolver = configured.dnsResolver() == null
                ? host -> Arrays.asList(InetAddress.getAllByName(host))
                : configured.dnsResolver();
        this.dnsIsolation = new DnsIsolation(
                configuredResolver,
                dnsCapacity,
                Objects.requireNonNull(clock, "clock"));
        sh.repost.internal.apache.org.apache.hc.client5.http.DnsResolver engineDnsResolver =
                new EngineDnsResolver();
        int processors = Runtime.getRuntime().availableProcessors();
        IOReactorConfig reactorConfig = IOReactorConfig.custom()
                .setIoThreadCount(Math.min(8, Math.max(2, processors)))
                .build();
        ClientTlsStrategyBuilder tlsStrategy = ClientTlsStrategyBuilder.create()
                .setHostVerificationPolicy(HostnameVerificationPolicy.CLIENT)
                .setHostnameVerifier(HttpsSupport.getDefaultHostnameVerifier());
        if (configured.sslContext() != null) {
            tlsStrategy.setSslContext(configured.sslContext());
        }
        if (configured.tlsProtocols() != null) {
            tlsStrategy.setTlsVersions(configured.tlsProtocols().toArray(new String[0]));
        }
        if (configured.tlsCipherSuites() != null) {
            tlsStrategy.setCiphers(configured.tlsCipherSuites().toArray(new String[0]));
        }
        ThreadFactory threadFactory = daemonThreadFactory();
        CharCodingConfig charCodingConfig = CharCodingConfig.custom()
                .setCharset(StandardCharsets.UTF_8)
                .setMalformedInputAction(CodingErrorAction.REPORT)
                .setUnmappableInputAction(CodingErrorAction.REPORT)
                .build();
        H2AsyncClientBuilder h2Builder = HttpAsyncClients.customHttp2()
                .setTlsStrategy(new TrackingTlsStrategy(tlsStrategy.buildAsync()))
                .setDnsResolver(engineDnsResolver)
                .setIOReactorConfig(reactorConfig)
                .setIOSessionListener(new ProtocolCaptureSessionListener())
                .setIoSessionDecorator(session -> session)
                .setH2Config(http2Config())
                .setCharCodingConfig(charCodingConfig)
                .setThreadFactory(threadFactory)
                .setDefaultRequestConfig(DISABLED_AMBIENT_BEHAVIOR)
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableCookieManagement()
                .disableAuthCaching()
                .disableRequestPriority()
                .addExecInterceptorBefore(
                        "MAIN_TRANSPORT",
                        "REPOST_H2_STREAM_ISOLATION",
                        StreamIsolationExec.INSTANCE);
        HttpAsyncClientBuilder h1Builder = HttpAsyncClients.custom()
                .setConnectionManager(PoolingAsyncClientConnectionManagerBuilder.create()
                        .setTlsStrategy(new TrackingTlsStrategy(tlsStrategy.buildAsync()))
                        .setDnsResolver(engineDnsResolver)
                        .setDefaultTlsConfig(TlsConfig.custom()
                                .setVersionPolicy(HttpVersionPolicy.FORCE_HTTP_1)
                                .build())
                        .setMaxConnTotal(32)
                        .setMaxConnPerRoute(32)
                        .build())
                .setIOReactorConfig(reactorConfig)
                .setIoSessionDecorator(session -> session)
                .setHttp1Config(http1Config())
                .setCharCodingConfig(charCodingConfig)
                .setThreadFactory(threadFactory)
                .setDefaultRequestConfig(DISABLED_AMBIENT_BEHAVIOR)
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableCookieManagement()
                .disableContentCompression()
                .disableAuthCaching()
                .disableConnectionState()
                .disableRequestPriority();
        if (configured.proxy() == null) {
            this.proxyClient = null;
            this.proxyHost = null;
            this.proxyCredentialsProvider = null;
        } else {
            URI proxyUri = configured.proxy().uri();
            this.proxyHost =
                    new HttpHost(proxyUri.getScheme(), proxyUri.getHost(), proxyUri.getPort());
            this.proxyCredentialsProvider = configured.proxy().credentialsProvider();
            DefaultProxyRoutePlanner routePlanner = new DefaultProxyRoutePlanner(proxyHost);
            this.proxyClient = HttpAsyncClients.custom()
                    .setConnectionManager(PoolingAsyncClientConnectionManagerBuilder.create()
                            .setTlsStrategy(new TrackingTlsStrategy(tlsStrategy.buildAsync()))
                            .setDnsResolver(engineDnsResolver)
                            .setDefaultTlsConfig(TlsConfig.custom()
                                    .setVersionPolicy(HttpVersionPolicy.NEGOTIATE)
                                    .build())
                            .setMaxConnTotal(32)
                            .setMaxConnPerRoute(32)
                            .build())
                    .setRoutePlanner(routePlanner)
                    .setIOReactorConfig(reactorConfig)
                    .setIoSessionDecorator(session -> session)
                    .setHttp1Config(http1Config())
                    .setH2Config(http2Config())
                    .setCharCodingConfig(charCodingConfig)
                    .setThreadFactory(threadFactory)
                    .setDefaultRequestConfig(DISABLED_AMBIENT_BEHAVIOR)
                    .disableRedirectHandling()
                    .disableAutomaticRetries()
                    .disableCookieManagement()
                    .disableContentCompression()
                    .disableAuthCaching()
                    .disableConnectionState()
                    .disableRequestPriority()
                    .build();
        }
        this.h2Client = h2Builder.build();
        this.h1Client = h1Builder.build();
        this.h2Client.start();
        this.h1Client.start();
        if (this.proxyClient != null) {
            this.proxyClient.start();
        }
    }

    @Override
    public CompletionStage<TransportResponse> execute(TransportRequest request) {
        Objects.requireNonNull(request, "request");
        if (closed.get()) {
            CompletableFuture<TransportResponse> rejected = new CompletableFuture<>();
            rejected.completeExceptionally(BuiltinTransportFailure.closed());
            return rejected;
        }
        String origin = origin(request.getUri());
        WireProtocol protocol = "https".equalsIgnoreCase(request.getUri().getScheme())
                ? negotiatedProtocols.getOrDefault(origin, WireProtocol.H2)
                : WireProtocol.H1;
        CompletableFuture<TransportResponse> result = new CompletableFuture<>();
        activeResults.add(result);
        ExchangeControl exchange = new ExchangeControl();
        String dnsHost = proxyHost == null
                ? request.getUri().getHost() : proxyHost.getHostName();
        Duration dnsTimeout = request.getConnectTimeout().compareTo(request.getAttemptTimeout()) <= 0
                ? request.getConnectTimeout() : request.getAttemptTimeout();
        CompletableFuture<List<InetAddress>> lookup = dnsIsolation.resolve(
                dnsHost, dnsTimeout, dnsTimer);
        result.whenComplete((response, failure) -> {
            activeResults.remove(result);
            if (result.isCancelled()) {
                exchange.cancelQuietly();
                lookup.cancel(true);
            }
        });
        lookup.whenComplete((addresses, failure) -> dispatchDnsCompletion(
                protocol,
                origin,
                dnsHost,
                request,
                exchange,
                result,
                addresses,
                failure));
        return result;
    }

    private void dispatchDnsCompletion(
            WireProtocol protocol,
            String origin,
            String dnsHost,
            TransportRequest request,
            ExchangeControl exchange,
            CompletableFuture<TransportResponse> result,
            List<InetAddress> addresses,
            Throwable failure) {
        if (result.isDone()) {
            return;
        }
        try {
            dnsContinuationExecutor.execute(() -> {
                if (result.isDone()) {
                    return;
                }
                if (failure != null) {
                    result.completeExceptionally(classifyDnsFailure(failure));
                    return;
                }
                preparedDnsRoutes.put(dnsHost.toLowerCase(Locale.ROOT), addresses);
                execute(protocol, origin, request, exchange, result);
            });
        } catch (RejectedExecutionException rejected) {
            result.completeExceptionally(closed.get()
                    ? BuiltinTransportFailure.closed()
                    : BuiltinTransportFailure.overloaded());
        }
    }

    private static Throwable classifyDnsFailure(Throwable rawFailure) {
        Throwable failure = rawFailure;
        while (failure instanceof CompletionException && failure.getCause() != null) {
            failure = failure.getCause();
        }
        if (!(failure instanceof DnsLookupFailure)) {
            return failure;
        }
        switch (((DnsLookupFailure) failure).kind()) {
            case NOT_FOUND:
                return BuiltinTransportFailure.dnsNotFound();
            case TIMEOUT:
                return BuiltinTransportFailure.dnsTimeout();
            case OVERLOADED:
                return BuiltinTransportFailure.overloaded();
            case CLOSED:
                return BuiltinTransportFailure.closed();
            case CLOCK:
                return BuiltinTransportFailure.monotonicClock();
            case PROVIDER:
            default:
                return BuiltinTransportFailure.dnsProvider();
        }
    }

    private void execute(
            WireProtocol protocol,
            String origin,
            TransportRequest request,
            ExchangeControl exchange,
            CompletableFuture<TransportResponse> result) {
        if (result.isDone()) {
            return;
        }
        BasicHttpRequest apacheRequest = new BasicHttpRequest("POST", request.getUri());
        for (TransportHeaderField field : request.getHeaderFields()) {
            apacheRequest.addHeader(field.getName(), field.getValue());
        }
        OneShotRequestProducer producer =
                new OneShotRequestProducer(apacheRequest, request.getBody());
        BoundedApacheResponseConsumer consumer = new BoundedApacheResponseConsumer(exchange);
        HttpClientContext context = HttpClientContext.create();
        context.setAttribute(EXCHANGE_CONTROL_ATTRIBUTE, exchange);
        context.setRequestConfig(requestConfig(request));

        boolean proxied = proxyClient != null;
        char[] proxyPassword;
        try {
            proxyPassword = applyProxyCredentials(context);
        } catch (RuntimeException credentialFailure) {
            result.completeExceptionally(TransportFailure.of(
                    RepostErrorCode.PROXY, RequestCommitState.NOT_COMMITTED));
            return;
        }
        CloseableHttpAsyncClient selected = proxied
                ? proxyClient
                : protocol == WireProtocol.H2 ? h2Client : h1Client;
        Future<Message<HttpResponse, InputStream>> nativeFuture;
        try {
            nativeFuture = selected.execute(producer, consumer, context,
                new FutureCallback<Message<HttpResponse, InputStream>>() {
                    @Override
                    public void completed(Message<HttpResponse, InputStream> message) {
                        clear(proxyPassword);
                        negotiatedProtocols.put(origin, protocol);
                        HttpResponse head = message.getHead();
                        List<TransportHeaderField> fields = copyHeaders(head.getHeaders());
                        InputStream responseBody = message.getBody();
                        TransportResponse response = TransportResponse.of(
                                head.getCode(),
                                fields,
                                responseBody == null ? InputStream.nullInputStream() : responseBody);
                        if (!result.complete(response)) {
                            response.close();
                        }
                    }

                    @Override
                    public void failed(Exception failure) {
                        clear(proxyPassword);
                        if (!proxied
                                && protocol == WireProtocol.H2
                                && !producer.wasBodyStarted()
                                && negotiatedProtocols.get(origin) == WireProtocol.H1
                                && !result.isDone()) {
                            execute(WireProtocol.H1, origin, request, exchange, result);
                            return;
                        }
                        result.completeExceptionally(ApacheTransportFailureClassifier.classify(
                                failure,
                                proxied,
                                producer.wasBodyStarted()));
                    }

                    @Override
                    public void cancelled() {
                        clear(proxyPassword);
                        result.cancel(false);
                    }
                });
        } catch (RuntimeException executionFailure) {
            clear(proxyPassword);
            throw executionFailure;
        }
        exchange.attach(nativeFuture);
    }

    private char[] applyProxyCredentials(HttpClientContext context) {
        if (proxyCredentialsProvider == null) {
            return null;
        }
        PasswordAuthentication supplied = Objects.requireNonNull(
                proxyCredentialsProvider.getCredentials(), "proxy credentials");
        char[] password = Objects.requireNonNull(supplied.getPassword(), "proxy password").clone();
        BasicScheme scheme = new BasicScheme();
        scheme.initPreemptive(new UsernamePasswordCredentials(supplied.getUserName(), password));
        context.resetAuthExchange(proxyHost, scheme);
        return password;
    }

    private static void clear(char[] secret) {
        if (secret != null) {
            Arrays.fill(secret, '\0');
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (CompletableFuture<TransportResponse> result : activeResults) {
            result.completeExceptionally(BuiltinTransportFailure.closed());
        }
        activeResults.clear();
        negotiatedProtocols.clear();
        tlsOriginsBySessionId.clear();
        preparedDnsRoutes.clear();
        try {
            h2Client.close(CloseMode.IMMEDIATE);
        } finally {
            try {
                h1Client.close(CloseMode.IMMEDIATE);
            } finally {
                try {
                    if (proxyClient != null) {
                        proxyClient.close(CloseMode.IMMEDIATE);
                    }
                } finally {
                    dnsIsolation.close();
                    if (standaloneDnsResources != null) {
                        standaloneDnsResources.close();
                    }
                }
            }
        }
    }

    private static String origin(URI uri) {
        int port = uri.getPort();
        if (port < 0) {
            port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        }
        return origin(uri.getScheme(), uri.getHost(), port);
    }

    private void recordTlsProtocol(
            NamedEndpoint endpoint,
            TransportSecurityLayer sessionLayer) {
        TlsDetails details = sessionLayer.getTlsDetails();
        WireProtocol protocol = details == null
                ? null
                : WireProtocol.fromApplicationProtocol(details.getApplicationProtocol());
        if (protocol != null && endpoint != null) {
            int port = endpoint.getPort() >= 0 ? endpoint.getPort() : 443;
            negotiatedProtocols.put(origin("https", endpoint.getHostName(), port), protocol);
        }
    }

    private void rememberTlsOrigin(
            NamedEndpoint endpoint,
            TransportSecurityLayer sessionLayer) {
        if (endpoint instanceof HttpHost && sessionLayer instanceof IOSession) {
            int port = endpoint.getPort() >= 0 ? endpoint.getPort() : 443;
            tlsOriginsBySessionId.put(
                    ((IOSession) sessionLayer).getId(),
                    origin("https", endpoint.getHostName(), port));
        }
    }

    private void recordTlsProtocol(IOSession session) {
        String origin = tlsOriginsBySessionId.remove(session.getId());
        if (origin == null || !(session instanceof SSLIOSession)) {
            return;
        }
        TlsDetails details = ((SSLIOSession) session).getTlsDetails();
        WireProtocol protocol = details == null
                ? null
                : WireProtocol.fromApplicationProtocol(details.getApplicationProtocol());
        if (protocol != null) {
            negotiatedProtocols.put(origin, protocol);
        }
    }

    private void forgetTlsOrigin(TransportSecurityLayer sessionLayer) {
        if (sessionLayer instanceof IOSession) {
            tlsOriginsBySessionId.remove(((IOSession) sessionLayer).getId());
        }
    }

    private static String origin(String scheme, String host, int port) {
        return scheme.toLowerCase(Locale.ROOT)
                + "://"
                + host.toLowerCase(Locale.ROOT)
                + ":"
                + port;
    }

    private static RequestConfig requestConfig(TransportRequest request) {
        return RequestConfig.copy(DISABLED_AMBIENT_BEHAVIOR)
                .setConnectionRequestTimeout(timeout(request.getConnectTimeout()))
                .setConnectTimeout(timeout(request.getConnectTimeout()))
                .setResponseTimeout(timeout(request.getAttemptTimeout()))
                .setHardCancellationEnabled(true)
                .build();
    }

    private static Timeout timeout(Duration duration) {
        return Timeout.ofMilliseconds(Math.max(1L, duration.toMillis()));
    }

    private static Http1Config http1Config() {
        return Http1Config.custom()
                .setBufferSize(8_192)
                .setChunkSizeHint(8_192)
                .setMaxLineLength(8_453)
                .setMaxHeaderCount(100)
                .setMaxEmptyLineCount(0)
                .setInitialWindowSize(65_535)
                .build();
    }

    private static H2Config http2Config() {
        return H2Config.custom()
                .setHeaderTableSize(16_384)
                .setPushEnabled(false)
                .setMaxConcurrentStreams(128)
                .setInitialWindowSize(65_535)
                .setMaxFrameSize(16_384)
                .setMaxHeaderListSize(73_728)
                .setCompressionEnabled(true)
                .setMaxContinuations(8)
                .build();
    }

    private static List<TransportHeaderField> copyHeaders(Header[] headers) {
        if (headers.length == 0) {
            return Collections.emptyList();
        }
        ArrayList<TransportHeaderField> result = new ArrayList<>(headers.length);
        for (Header header : headers) {
            result.add(TransportHeaderField.of(header.getName(), header.getValue()));
        }
        return result;
    }

    private static ThreadFactory daemonThreadFactory() {
        AtomicLong sequence = new AtomicLong();
        return runnable -> {
            Thread thread = new Thread(runnable, "repost-http-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            thread.setContextClassLoader(null);
            return thread;
        };
    }

    private final class EngineDnsResolver
            implements sh.repost.internal.apache.org.apache.hc.client5.http.DnsResolver {
        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            List<InetAddress> addresses = preparedDnsRoutes.get(
                    host.toLowerCase(Locale.ROOT));
            if (addresses == null) {
                throw new UnknownHostException("DNS route was not prepared");
            }
            return addresses.toArray(new InetAddress[0]);
        }

        @Override
        public String resolveCanonicalHostname(String host) {
            return host;
        }
    }

    private static final class StandaloneDnsResources implements AutoCloseable {
        private final ExecutorService continuationExecutor = Executors.newFixedThreadPool(
                2, isolatedThreadFactory("repost-transport-init"));
        private final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(
                1, isolatedThreadFactory("repost-transport-timer"));

        private StandaloneDnsResources() {
            timer.setRemoveOnCancelPolicy(true);
            timer.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
            timer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        }

        @Override
        public void close() {
            timer.shutdownNow();
            continuationExecutor.shutdownNow();
        }

        private static ThreadFactory isolatedThreadFactory(String prefix) {
            AtomicLong sequence = new AtomicLong();
            return runnable -> {
                Thread thread = new Thread(
                        runnable, prefix + "-" + sequence.incrementAndGet());
                thread.setDaemon(true);
                thread.setContextClassLoader(null);
                return thread;
            };
        }
    }

    private static final class ExchangeControl
            implements RepostBudgetedBodyConsumer.UpstreamControl {
        private final ConcurrentLinkedQueue<Cancellable> operations = new ConcurrentLinkedQueue<>();
        private final AtomicBoolean cancelRequested = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();

        private void attach(Future<?> nativeFuture) {
            attach(() -> nativeFuture.cancel(true));
        }

        private void attach(Cancellable operation) {
            operations.add(operation);
            if (cancelRequested.get()) {
                operation.cancel();
            }
        }

        @Override
        public void cancel() {
            cancelRequested.set(true);
            for (Cancellable operation : operations) {
                operation.cancel();
            }
        }

        private void cancelQuietly() {
            cancel();
        }

        @Override
        public void close() {
            closed.compareAndSet(false, true);
        }
    }

    private final class TrackingTlsStrategy implements TlsStrategy {
        private final TlsStrategy delegate;

        private TrackingTlsStrategy(TlsStrategy delegate) {
            this.delegate = delegate;
        }

        @Override
        @SuppressWarnings("deprecation")
        public boolean upgrade(
                TransportSecurityLayer sessionLayer,
                HttpHost host,
                SocketAddress localAddress,
                SocketAddress remoteAddress,
                Object attachment,
                Timeout handshakeTimeout) {
            rememberTlsOrigin(host, sessionLayer);
            boolean upgraded = delegate.upgrade(
                    sessionLayer,
                    host,
                    localAddress,
                    remoteAddress,
                    attachment,
                    handshakeTimeout);
            if (upgraded) {
                recordTlsProtocol(host, sessionLayer);
                forgetTlsOrigin(sessionLayer);
            }
            return upgraded;
        }

        @Override
        public void upgrade(
                TransportSecurityLayer sessionLayer,
                NamedEndpoint endpoint,
                Object attachment,
                Timeout handshakeTimeout,
                FutureCallback<TransportSecurityLayer> callback) {
            rememberTlsOrigin(endpoint, sessionLayer);
            delegate.upgrade(
                    sessionLayer,
                    endpoint,
                    attachment,
                    handshakeTimeout,
                    new FutureCallback<TransportSecurityLayer>() {
                        @Override
                        public void completed(TransportSecurityLayer result) {
                            TransportSecurityLayer completed = result == null ? sessionLayer : result;
                            recordTlsProtocol(endpoint, completed);
                            forgetTlsOrigin(sessionLayer);
                            if (callback != null) {
                                callback.completed(completed);
                            }
                        }

                        @Override
                        public void failed(Exception failure) {
                            forgetTlsOrigin(sessionLayer);
                            if (callback != null) {
                                callback.failed(failure);
                            }
                        }

                        @Override
                        public void cancelled() {
                            forgetTlsOrigin(sessionLayer);
                            if (callback != null) {
                                callback.cancelled();
                            }
                        }
                    });
        }
    }

    private final class ProtocolCaptureSessionListener implements IOSessionListener {
        @Override
        public void connected(IOSession session) {
            recordTlsProtocol(session);
        }

        @Override
        public void startTls(IOSession session) {
        }

        @Override
        public void inputReady(IOSession session) {
        }

        @Override
        public void outputReady(IOSession session) {
        }

        @Override
        public void timeout(IOSession session) {
        }

        @Override
        public void exception(IOSession session, Exception failure) {
            recordTlsProtocol(session);
        }

        @Override
        public void disconnected(IOSession session) {
            tlsOriginsBySessionId.remove(session.getId());
        }
    }

    private enum WireProtocol {
        H1,
        H2;

        private static WireProtocol fromApplicationProtocol(String value) {
            if ("h2".equals(value)) {
                return H2;
            }
            if ("http/1.1".equals(value)) {
                return H1;
            }
            return null;
        }
    }

    private enum StreamIsolationExec implements AsyncExecChainHandler {
        INSTANCE;

        @Override
        public void execute(
                HttpRequest request,
                AsyncEntityProducer entityProducer,
                AsyncExecChain.Scope scope,
                AsyncExecChain chain,
                AsyncExecCallback callback) throws HttpException, java.io.IOException {
            AsyncExecChain.Scope isolated = new AsyncExecChain.Scope(
                    scope.exchangeId,
                    scope.route,
                    scope.originalRequest,
                    scope.cancellableDependency,
                    scope.clientContext,
                    new StreamLocalRuntime(
                            scope.execRuntime,
                            (ExchangeControl) scope.clientContext.getAttribute(
                                    EXCHANGE_CONTROL_ATTRIBUTE)),
                    scope.scheduler,
                    scope.execCount);
            chain.proceed(
                    request,
                    entityProducer,
                    isolated,
                    new StreamIsolationCallback(
                            scope.cancellableDependency,
                            scope.execRuntime,
                            callback));
        }
    }

    private static final class StreamIsolationCallback implements AsyncExecCallback {
        private final CancellableDependency operation;
        private final AsyncExecRuntime endpointRuntime;
        private final AsyncExecCallback delegate;

        private StreamIsolationCallback(
                CancellableDependency operation,
                AsyncExecRuntime endpointRuntime,
                AsyncExecCallback delegate) {
            this.operation = operation;
            this.endpointRuntime = endpointRuntime;
            this.delegate = delegate;
        }

        @Override
        public AsyncDataConsumer handleResponse(
                HttpResponse response,
                EntityDetails entityDetails) throws HttpException, java.io.IOException {
            return delegate.handleResponse(response, entityDetails);
        }

        @Override
        public void handleInformationResponse(HttpResponse response)
                throws HttpException, java.io.IOException {
            delegate.handleInformationResponse(response);
        }

        @Override
        public void completed() {
            delegate.completed();
        }

        @Override
        public void failed(Exception cause) {
            if (operation.isCancelled()
                    && cause instanceof H2StreamResetException
                    && ((H2StreamResetException) cause).getCode() == H2Error.CANCEL.getCode()) {
                delegate.completed();
                return;
            }
            if (cause instanceof H2StreamResetException) {
                // The outer Apache failure path discards any endpoint still leased here.
                // Release this stream-local lease first so sibling H2 streams keep the session.
                endpointRuntime.releaseEndpoint();
            }
            delegate.failed(cause);
        }
    }

    private static final class StreamLocalRuntime implements AsyncExecRuntime {
        private final AsyncExecRuntime delegate;
        private final ExchangeControl exchange;

        private StreamLocalRuntime(AsyncExecRuntime delegate, ExchangeControl exchange) {
            this.delegate = delegate;
            this.exchange = exchange;
        }

        @Override
        public boolean isEndpointAcquired() {
            return delegate.isEndpointAcquired();
        }

        @Override
        public Cancellable acquireEndpoint(
                String id,
                HttpRoute route,
                Object state,
                HttpClientContext context,
                FutureCallback<AsyncExecRuntime> callback) {
            return delegate.acquireEndpoint(id, route, state, context, callback);
        }

        @Override
        public void releaseEndpoint() {
            delegate.releaseEndpoint();
        }

        @Override
        public void discardEndpoint() {
            delegate.discardEndpoint();
        }

        @Override
        public boolean isEndpointConnected() {
            return delegate.isEndpointConnected();
        }

        @Override
        public Cancellable connectEndpoint(
                HttpClientContext context,
                FutureCallback<AsyncExecRuntime> callback) {
            return delegate.connectEndpoint(context, callback);
        }

        @Override
        public void disconnectEndpoint() {
            delegate.disconnectEndpoint();
        }

        @Override
        public void upgradeTls(HttpClientContext context) {
            delegate.upgradeTls(context);
        }

        @Override
        public void upgradeTls(
                HttpClientContext context,
                FutureCallback<AsyncExecRuntime> callback) {
            delegate.upgradeTls(context, callback);
        }

        @Override
        public EndpointInfo getEndpointInfo() {
            return delegate.getEndpointInfo();
        }

        @Override
        public boolean validateConnection() {
            return delegate.validateConnection();
        }

        @Override
        public Cancellable execute(
                String id,
                AsyncClientExchangeHandler exchangeHandler,
                HttpClientContext context) {
            Cancellable operation = delegate.execute(id, exchangeHandler, context);
            exchange.attach(operation);
            return operation;
        }

        @Override
        public void markConnectionReusable(Object state, TimeValue validityTime) {
            delegate.markConnectionReusable(state, validityTime);
        }

        @Override
        public void markConnectionNonReusable() {
            // A failed or cancelled H2 stream is not evidence that its physical session is bad.
        }

        @Override
        public AsyncExecRuntime fork() {
            return new StreamLocalRuntime(delegate.fork(), exchange);
        }
    }
}
