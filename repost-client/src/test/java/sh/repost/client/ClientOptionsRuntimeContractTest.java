package sh.repost.client;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import sh.repost.client.descriptor.EventDescriptor;
import sh.repost.client.descriptor.ModelDescriptor;
import sh.repost.client.descriptor.SchemaDescriptor;
import sh.repost.client.error.RepostConfigurationException;
import sh.repost.client.error.RepostException;
import sh.repost.client.error.RepostTransportException;

public final class ClientOptionsRuntimeContractTest {
    private static final java.util.regex.Pattern OPERATION_ID = java.util.regex.Pattern.compile(
            "^op_[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

    @Test
    void appliesDefaultsAndCanonicalizesTheEndpointOnce() {
        ClientOptions options = ClientOptions.builder()
                .apiKey("fixed secret")
                .baseUri("https://example.com/base/")
                .build();
        RepostRuntime runtime = RepostRuntime.create(options);
        assertEquals("https://example.com/base/v1/messages", runtime.endpointUri().toASCIIString());
        assertEquals(Duration.ofSeconds(10), options.connectTimeout());
        assertEquals(Duration.ofSeconds(30), options.attemptTimeout());
        assertEquals(Duration.ofSeconds(120), options.operationTimeout());
        assertEquals(4, options.maxAttempts());
        assertEquals(256, options.maxInFlightOperations());
        assertEquals(67_108_864L, options.maxBufferedBytes());
        assertTrue(options.httpTransportOptions() == null);
        assertTrue(HttpTransportOptions.defaults().proxy() == null);
        assertTrue(runtime.transport() instanceof ApacheHttpTransport);
        runtime.close();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("configurationConflictCases")
    void reportsFrozenConfigurationConflicts(
            String caseId,
            Runnable construction,
            List<ClientOptionKey> optionKeys) {
        RepostConfigurationException failure =
                expectThrows(RepostConfigurationException.class, construction);
        assertEquals(RepostErrorCode.CONFIGURATION, failure.getErrorCode());
        assertEquals(DeliveryState.NOT_SENT, failure.getDeliveryState());
        assertEquals(0, failure.getAttemptCount());
        assertEquals(1, failure.getIssueCount());
        assertEquals(1, failure.getConfigurationIssues().size());
        ConfigurationIssue issue = failure.getConfigurationIssues().get(0);
        assertEquals(ConfigurationIssueCode.CONFLICT, issue.getCode());
        assertEquals(optionKeys, issue.getOptionKeys());
        assertFalse(failure.getMessage().contains("sentinel"));
    }

    private static java.util.stream.Stream<Arguments> configurationConflictCases() {
        return java.util.stream.Stream.of(
                Arguments.of(
                        "configuration-provider-and-fixed-key-mutually-exclusive",
                        (Runnable) () -> ClientOptions.builder()
                                .apiKey("sentinel-fixed-key")
                                .apiKeyProvider(() -> "sentinel-provider-key")
                                .build(),
                        Arrays.asList(ClientOptionKey.API_KEY, ClientOptionKey.API_KEY_PROVIDER)),
                Arguments.of(
                        "configuration-custom-transport-built-in-options-conflict",
                        (Runnable) () -> ClientOptions.builder()
                                .transport(request -> null)
                                .httpTransportOptions(HttpTransportOptions.defaults())
                                .build(),
                        Arrays.asList(
                                ClientOptionKey.HTTP_TRANSPORT_OPTIONS,
                                ClientOptionKey.TRANSPORT)),
                Arguments.of(
                        "configuration-retry-base-above-maximum-conflict",
                        (Runnable) () -> ClientOptions.builder()
                                .retryBaseDelay(Duration.ofSeconds(2))
                                .retryMaxDelay(Duration.ofSeconds(1))
                                .build(),
                        Arrays.asList(
                                ClientOptionKey.RETRY_BASE_DELAY,
                                ClientOptionKey.RETRY_MAX_DELAY)));
    }

    @Test
    void snapshotsEnvironmentRoutingAndCredentialsButResolvesProvidersAtOperationTime() {
        HashMap<String, String> environment = new HashMap<>();
        environment.put("REPOST_API_URL", "https://environment.example/base");
        environment.put("REPOST_SEND_API_KEY", "environment-key");
        environment.put("REPOST_TOKEN", "legacy-key");
        HashMap<String, AtomicInteger> reads = new HashMap<>();
        RepostRuntime runtime = RepostRuntime.createForTesting(
                ClientOptions.builder().build(), key -> {
                    reads.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
                    return environment.get(key);
                });
        assertEquals(1, readCount(reads, "REPOST_API_URL"));
        assertEquals(1, readCount(reads, "REPOST_SEND_API_KEY"));
        assertEquals(0, readCount(reads, "REPOST_TOKEN"));
        environment.put("REPOST_API_URL", "https://mutated.example");
        environment.put("REPOST_SEND_API_KEY", "mutated-key");
        assertEquals("https://environment.example/base/v1/messages",
                runtime.endpointUri().toASCIIString());
        assertEquals("environment-key", runtime.credentialResolver().resolve().value());
        assertEquals(1, readCount(reads, "REPOST_SEND_API_KEY"));
        assertEquals(0, readCount(reads, "REPOST_TOKEN"));
        runtime.close();

        RepostRuntime explicit = RepostRuntime.createForTesting(
                ClientOptions.builder()
                        .baseUri("https://explicit.example")
                        .apiKey("explicit-key")
                        .build(),
                key -> "invalid-present-environment-value");
        assertEquals("https://explicit.example/v1/messages", explicit.endpointUri().toASCIIString());
        assertEquals("explicit-key", explicit.credentialResolver().resolve().value());
        explicit.close();

        RepostConfigurationException invalidEnvironmentBaseUri =
                expectThrows(
                        RepostConfigurationException.class,
                        () -> RepostRuntime.createForTesting(
                                ClientOptions.builder().build(),
                                key -> "REPOST_API_URL".equals(key) ? "" : null));
        assertConfigurationIssue(
                invalidEnvironmentBaseUri,
                ConfigurationIssueCode.INVALID_VALUE,
                ClientOptionKey.BASE_URI);

        AtomicInteger operationCredentialReads = new AtomicInteger();
        AtomicReference<Thread> operationCredentialThread = new AtomicReference<>();
        Thread operationCaller = Thread.currentThread();
        RepostRuntime operationRuntime = RepostRuntime.createForTesting(
                ClientOptions.builder()
                        .baseUri("https://example.com")
                        .apiKeyProvider(() -> {
                            operationCredentialReads.incrementAndGet();
                            operationCredentialThread.set(Thread.currentThread());
                            return "operation-key";
                        })
                        .transport(immediateIoTransport())
                        .build(),
                key -> null);
        assertEquals(0, operationCredentialReads.get());
        SchemaDescriptor operationSchema = schema();
        SendOperation operation = operationRuntime.sendAsync(
                operationSchema,
                event(operationSchema),
                "customer_123",
                emptyModel(),
                SendOptions.defaults());
        expectThrows(java.util.concurrent.CompletionException.class,
                () -> operation.toCompletableFuture().join());
        assertEquals(1, operationCredentialReads.get());
        assertFalse(operationCaller == operationCredentialThread.get());
        SendOperation secondOperation = operationRuntime.sendAsync(
                operationSchema,
                event(operationSchema),
                "customer_456",
                emptyModel(),
                SendOptions.defaults());
        transportFailure(secondOperation);
        assertEquals(2, operationCredentialReads.get());
        operationRuntime.close();

        AtomicInteger closedCredentialReads = new AtomicInteger();
        RepostRuntime closed = RepostRuntime.createForTesting(
                ClientOptions.builder()
                        .baseUri("https://example.com")
                        .apiKeyProvider(() -> {
                            closedCredentialReads.incrementAndGet();
                            return "closed-key";
                        })
                        .build(),
                key -> null);
        closed.close();
        SchemaDescriptor closedSchema = schema();
        closed.sendAsync(
                closedSchema,
                event(closedSchema),
                "customer_123",
                emptyModel(),
                SendOptions.defaults());
        assertEquals(0, closedCredentialReads.get());

        AtomicInteger invalidDescriptorReads = new AtomicInteger();
        RepostRuntime descriptorRuntime = RepostRuntime.createForTesting(
                ClientOptions.builder()
                        .baseUri("https://example.com")
                        .apiKeyProvider(() -> {
                            invalidDescriptorReads.incrementAndGet();
                            return "descriptor-key";
                        })
                        .build(),
                key -> null);
        SchemaDescriptor invalidSchema = SchemaDescriptor.builder(3)
                .addModel(ModelDescriptor.of("Payload", java.util.Collections.emptyList()))
                .addEvent("events", "created", EventDescriptor.of("event.created", "Payload"))
                .build();
        descriptorRuntime.sendAsync(
                invalidSchema,
                event(invalidSchema),
                "customer_123",
                emptyModel(),
                SendOptions.defaults());
        assertEquals(0, invalidDescriptorReads.get());
        descriptorRuntime.close();
    }

    @Test
    void resolvesEachCredentialSourceExactlyOnceWithStrictPrecedence() {
        AtomicInteger providerCalls = new AtomicInteger();
        OperationCredentialResolver provider = new OperationCredentialResolver(
                () -> {
                    providerCalls.incrementAndGet();
                    return "provider-key";
                },
                null,
                key -> { throw new AssertionError("environment must not be read"); });
        assertEquals("provider-key", provider.resolve().value());
        assertEquals(1, providerCalls.get());

        OperationCredentialResolver fixed = new OperationCredentialResolver(
                null,
                "fixed-key",
                key -> { throw new AssertionError("environment must not be read"); });
        assertEquals("fixed-key", fixed.resolve().value());

        HashMap<String, String> environment = new HashMap<>();
        environment.put("REPOST_SEND_API_KEY", "modern-key");
        environment.put("REPOST_TOKEN", "legacy-key");
        OperationCredentialResolver environmentSnapshot = new OperationCredentialResolver(
                null, null, environment::get);
        environment.put("REPOST_SEND_API_KEY", "mutated-key");
        assertEquals("modern-key", environmentSnapshot.resolve().value());

        environment.put("REPOST_SEND_API_KEY", "");
        AtomicInteger legacyReads = new AtomicInteger();
        expectThrows(RepostConfigurationException.class,
                () -> new OperationCredentialResolver(null, null, key -> {
                    if ("REPOST_TOKEN".equals(key)) {
                        legacyReads.incrementAndGet();
                    }
                    return environment.get(key);
                }).resolve());
        assertEquals(0, legacyReads.get());

        environment.remove("REPOST_SEND_API_KEY");
        assertEquals("legacy-key", new OperationCredentialResolver(
                null, null, environment::get).resolve().value());

        environment.clear();
        expectThrows(RepostConfigurationException.class,
                () -> new OperationCredentialResolver(null, null, environment::get).resolve());

        OperationCredentialResolver.ResolvedCredential credential =
                new OperationCredentialResolver(null, "sentinel-secret", environment::get).resolve();
        assertFalse(credential.toString().contains("sentinel-secret"));
    }

    @Test
    void rejectsProxyConfigurationForNonHttpsOrigins() {
        HttpTransportOptions proxy = HttpTransportOptions.builder()
                .proxy(HttpProxyOptions.builder().uri("http://proxy.example:8080").build())
                .build();
        expectThrows(RepostConfigurationException.class, () -> RepostRuntime.create(
                ClientOptions.builder()
                        .baseUri("http://localhost:8080")
                        .apiKey("key")
                        .httpTransportOptions(proxy)
                        .build()));
        RepostRuntime runtime = RepostRuntime.create(ClientOptions.builder()
                .baseUri("https://example.com")
                .apiKey("key")
                .httpTransportOptions(proxy)
                .build());
        runtime.close();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidOptionCases")
    void reportsFrozenInvalidOptionValues(
            String caseId,
            Runnable construction,
            ConfigurationIssueCode issueCode,
            ClientOptionKey optionKey) {
        RepostConfigurationException failure =
                expectThrows(RepostConfigurationException.class, construction);
        assertEquals(RepostErrorCode.CONFIGURATION, failure.getErrorCode());
        assertEquals(DeliveryState.NOT_SENT, failure.getDeliveryState());
        assertEquals(0, failure.getAttemptCount());
        assertEquals(1, failure.getIssueCount());
        assertEquals(1, failure.getConfigurationIssues().size());
        ConfigurationIssue issue = failure.getConfigurationIssues().get(0);
        assertEquals(issueCode, issue.getCode());
        assertEquals(java.util.Collections.singletonList(optionKey), issue.getOptionKeys());
        assertFalse(failure.getMessage().contains("sentinel"));
    }

    private static java.util.stream.Stream<Arguments> invalidOptionCases() {
        return java.util.stream.Stream.of(
                Arguments.of(
                        "configuration-fixed-credential-empty-rejected-no-fallback",
                        (Runnable) () -> ClientOptions.builder().apiKey("").build(),
                        ConfigurationIssueCode.INVALID_VALUE,
                        ClientOptionKey.API_KEY),
                Arguments.of(
                        "defaults-max-attempts-zero-rejected",
                        (Runnable) () -> ClientOptions.builder().maxAttempts(0).build(),
                        ConfigurationIssueCode.OUT_OF_RANGE,
                        ClientOptionKey.MAX_ATTEMPTS),
                Arguments.of(
                        "defaults-max-in-flight-above-maximum-rejected",
                        (Runnable) () -> ClientOptions.builder()
                                .maxInFlightOperations(65_537)
                                .build(),
                        ConfigurationIssueCode.OUT_OF_RANGE,
                        ClientOptionKey.MAX_IN_FLIGHT_OPERATIONS),
                Arguments.of(
                        "configuration-buffer-budget-below-minimum-rejected",
                        (Runnable) () -> ClientOptions.builder()
                                .maxBufferedBytes(4_194_303L)
                                .build(),
                        ConfigurationIssueCode.OUT_OF_RANGE,
                        ClientOptionKey.MAX_BUFFERED_BYTES),
                Arguments.of(
                        "configuration-duration-fractional-millisecond-rejected",
                        (Runnable) () -> ClientOptions.builder()
                                .connectTimeout(Duration.ofNanos(1))
                                .build(),
                        ConfigurationIssueCode.OUT_OF_RANGE,
                        ClientOptionKey.CONNECT_TIMEOUT),
                Arguments.of(
                        "configuration-base-url-lowercase-percent-escape-rejected",
                        (Runnable) () -> ClientOptions.builder()
                                .baseUri("https://example.com/%2f")
                                .build(),
                        ConfigurationIssueCode.INVALID_VALUE,
                        ClientOptionKey.BASE_URI),
                Arguments.of(
                        "configuration-http-non-loopback-rejected",
                        (Runnable) () -> ClientOptions.builder()
                                .baseUri("http://example.com")
                                .build(),
                        ConfigurationIssueCode.INVALID_VALUE,
                        ClientOptionKey.BASE_URI),
                Arguments.of(
                        "configuration-user-agent-suffix-leading-space-rejected",
                        (Runnable) () -> ClientOptions.builder()
                                .userAgentSuffix(" sentinel")
                                .build(),
                        ConfigurationIssueCode.INVALID_VALUE,
                        ClientOptionKey.USER_AGENT_SUFFIX));
    }

    @Test
    void snapshotsTransportOptionListsWithoutProviderCalls() {
        java.util.ArrayList<String> protocols = new java.util.ArrayList<>(
                Arrays.asList("TLSv1.3", "TLSv1.2"));
        java.util.ArrayList<String> cipherSuites = new java.util.ArrayList<>(Arrays.asList(
                "TLS_AES_128_GCM_SHA256",
                "TLS_AES_256_GCM_SHA384"));
        AtomicInteger resolverCalls = new AtomicInteger();
        HttpTransportOptions options = HttpTransportOptions.builder()
                .tlsProtocols(protocols)
                .tlsCipherSuites(cipherSuites)
                .dnsResolver(host -> {
                    resolverCalls.incrementAndGet();
                    throw new AssertionError("resolver must not run at construction or close");
                })
                .build();
        protocols.clear();
        cipherSuites.clear();
        assertEquals(Arrays.asList("TLSv1.3", "TLSv1.2"), options.tlsProtocols());
        assertEquals(Arrays.asList(
                "TLS_AES_128_GCM_SHA256",
                "TLS_AES_256_GCM_SHA384"), options.tlsCipherSuites());
        expectThrows(UnsupportedOperationException.class,
                () -> options.tlsProtocols().add("TLSv1.3"));
        expectThrows(UnsupportedOperationException.class,
                () -> options.tlsCipherSuites().clear());

        RepostRuntime runtime = RepostRuntime.create(ClientOptions.builder()
                .apiKey("key")
                .httpTransportOptions(options)
                .build());
        assertEquals(0, resolverCalls.get());
        runtime.close();
        assertEquals(0, resolverCalls.get());
    }

    @Test
    @DisplayName("admission-overloaded-before-all-side-effects")
    void rejectsSaturatedAdmissionBeforeCredentialWork() {
        AtomicInteger credentialReads = new AtomicInteger();
        QueuedExecutor executor = new QueuedExecutor();
        TrackingScheduler scheduler = new TrackingScheduler();
        RepostRuntime runtime = RepostRuntime.create(ClientOptions.builder()
                .apiKeyProvider(() -> {
                    credentialReads.incrementAndGet();
                    return "must-not-run";
                })
                .maxInFlightOperations(1)
                .executor(executor)
                .scheduler(scheduler)
                .build());
        SchemaDescriptor schema = schema();

        SendOperation admitted = runtime.sendAsync(
                schema, event(schema), "customer_1", emptyModel(), SendOptions.defaults());
        SendOperation rejected = runtime.sendAsync(
                schema, event(schema), "customer_2", emptyModel(), SendOptions.defaults());

        RepostTransportException failure = transportFailure(rejected);
        assertEquals(RepostErrorCode.OVERLOADED, failure.getErrorCode());
        assertEquals(DeliveryState.NOT_SENT, failure.getDeliveryState());
        assertEquals(0, failure.getAttemptCount());
        assertOperationId(failure.getOperationId());
        assertEquals(
                failure.getOperationId(),
                rejected.outcome().toCompletableFuture().join().getOperationId());
        assertEquals(0, credentialReads.get());
        assertEquals(1, executor.submissions);
        assertEquals(1, scheduler.submissions);
        assertEquals(1, runtime.diagnostics().getInFlightOperations());
        assertEquals(1L, runtime.diagnostics().getConcurrencyOverloadRejections());

        assertTrue(admitted.cancel(true));
        assertEquals(0, runtime.diagnostics().getInFlightOperations());
        runtime.close();
        scheduler.shutdownNow();
    }

    @Test
    @DisplayName("jvm-deadline-scheduler-rejection")
    void rejectsDeadlineRegistrationBeforeCredentialAndExecutorWork() {
        AtomicInteger credentialReads = new AtomicInteger();
        QueuedExecutor executor = new QueuedExecutor();
        RejectingScheduler scheduler = new RejectingScheduler();
        RepostRuntime runtime = RepostRuntime.create(ClientOptions.builder()
                .apiKeyProvider(() -> {
                    credentialReads.incrementAndGet();
                    return "must-not-run";
                })
                .executor(executor)
                .scheduler(scheduler)
                .build());
        SchemaDescriptor schema = schema();

        RepostTransportException failure = transportFailure(runtime.sendAsync(
                schema, event(schema), "customer_1", emptyModel(), SendOptions.defaults()));

        assertEquals(RepostErrorCode.OVERLOADED, failure.getErrorCode());
        assertEquals(DeliveryState.NOT_SENT, failure.getDeliveryState());
        assertEquals(0, failure.getAttemptCount());
        assertOperationId(failure.getOperationId());
        assertEquals(0, credentialReads.get());
        assertEquals(0, executor.submissions);
        assertEquals(0, runtime.diagnostics().getInFlightOperations());
        assertEquals(1L, runtime.diagnostics().getSchedulerOverloadRejections());
        runtime.close();
        scheduler.shutdownNow();
    }

    @Test
    @DisplayName("jvm-operation-executor-rejection")
    void releasesAdmissionWhenExecutorRejectsBeforeCredentialWork() {
        AtomicInteger credentialReads = new AtomicInteger();
        RejectingExecutor executor = new RejectingExecutor();
        TrackingScheduler scheduler = new TrackingScheduler();
        RepostRuntime runtime = RepostRuntime.create(ClientOptions.builder()
                .apiKeyProvider(() -> {
                    credentialReads.incrementAndGet();
                    return "must-not-run";
                })
                .executor(executor)
                .scheduler(scheduler)
                .build());
        SchemaDescriptor schema = schema();

        RepostTransportException failure = transportFailure(runtime.sendAsync(
                schema, event(schema), "customer_1", emptyModel(), SendOptions.defaults()));

        assertEquals(RepostErrorCode.OVERLOADED, failure.getErrorCode());
        assertEquals(DeliveryState.NOT_SENT, failure.getDeliveryState());
        assertEquals(0, failure.getAttemptCount());
        assertOperationId(failure.getOperationId());
        assertEquals(0, credentialReads.get());
        assertEquals(1, scheduler.submissions);
        assertEquals(0, runtime.diagnostics().getInFlightOperations());
        assertEquals(1L, runtime.diagnostics().getExecutorOverloadRejections());
        runtime.close();
        scheduler.shutdownNow();
    }

    @Test
    @DisplayName("jvm-inline-executor-detected-before-side-effects")
    void rejectsInlineExecutorBeforeCredentialWork() {
        AtomicInteger credentialReads = new AtomicInteger();
        TrackingExecutor executor = new TrackingExecutor();
        TrackingScheduler scheduler = new TrackingScheduler();
        RepostRuntime runtime = RepostRuntime.create(ClientOptions.builder()
                .apiKeyProvider(() -> {
                    credentialReads.incrementAndGet();
                    return "must-not-run";
                })
                .executor(executor)
                .scheduler(scheduler)
                .build());
        SchemaDescriptor schema = schema();

        RepostConfigurationException failure = configurationFailure(runtime.sendAsync(
                schema, event(schema), "customer_1", emptyModel(), SendOptions.defaults()));

        assertEquals(RepostErrorCode.CONFIGURATION, failure.getErrorCode());
        assertEquals(DeliveryState.NOT_SENT, failure.getDeliveryState());
        assertEquals(0, failure.getAttemptCount());
        assertOperationId(failure.getOperationId());
        assertEquals(0, credentialReads.get());
        assertEquals(1, scheduler.submissions);
        assertEquals(0, runtime.diagnostics().getInFlightOperations());
        assertEquals(0L, runtime.diagnostics().getExecutorOverloadRejections());
        assertEquals(1, failure.getConfigurationIssues().size());
        ConfigurationIssue issue = failure.getConfigurationIssues().get(0);
        assertEquals(ConfigurationIssueCode.UNSUPPORTED, issue.getCode());
        assertEquals(
                java.util.Collections.singletonList(ClientOptionKey.EXECUTOR),
                issue.getOptionKeys());
        runtime.close();
        scheduler.shutdownNow();
    }

    @Test
    @DisplayName("jvm-close-queued-operation-no-ghost-work")
    void closeSettlesQueuedOperationBeforePublishingClosed() {
        AtomicInteger credentialReads = new AtomicInteger();
        QueuedExecutor executor = new QueuedExecutor();
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        RepostRuntime runtime = RepostRuntime.create(ClientOptions.builder()
                .apiKeyProvider(() -> {
                    credentialReads.incrementAndGet();
                    return "must-not-run";
                })
                .executor(executor)
                .scheduler(scheduler)
                .build());
        SchemaDescriptor schema = schema();
        SendOperation operation = runtime.sendAsync(
                schema, event(schema), "customer_1", emptyModel(), SendOptions.defaults());
        assertEquals(1, runtime.diagnostics().getInFlightOperations());

        runtime.close();

        RepostTransportException failure = transportFailure(operation);
        assertEquals(RepostErrorCode.CLOSED, failure.getErrorCode());
        assertEquals(DeliveryState.NOT_SENT, failure.getDeliveryState());
        assertOperationId(failure.getOperationId());
        SendOutcome outcome = operation.outcome().toCompletableFuture().join();
        assertEquals(RepostErrorCode.CLOSED, outcome.getErrorCode());
        assertEquals(DeliveryState.NOT_SENT, outcome.getDeliveryState());
        assertEquals(failure.getOperationId(), outcome.getOperationId());
        assertEquals(0, runtime.diagnostics().getInFlightOperations());
        assertTrue(runtime.diagnostics().isClosed());
        executor.runQueued();
        assertEquals(0, credentialReads.get());
        scheduler.shutdownNow();
    }

    @Test
    void cancellationInterruptsRunningCredentialWorker() throws InterruptedException {
        CountDownLatch providerStarted = new CountDownLatch(1);
        CountDownLatch providerInterrupted = new CountDownLatch(1);
        CountDownLatch blockProvider = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        RepostRuntime runtime = RepostRuntime.create(ClientOptions.builder()
                .apiKeyProvider(() -> {
                    providerStarted.countDown();
                    try {
                        blockProvider.await();
                    } catch (InterruptedException interrupted) {
                        providerInterrupted.countDown();
                        Thread.currentThread().interrupt();
                    }
                    return "operation-key";
                })
                .executor(executor)
                .scheduler(scheduler)
                .build());
        try {
            SchemaDescriptor schema = schema();
            SendOperation operation = runtime.sendAsync(
                    schema, event(schema), "customer_1", emptyModel(), SendOptions.defaults());
            assertTrue(providerStarted.await(2, TimeUnit.SECONDS));

            assertTrue(operation.cancel(true));

            assertTrue(providerInterrupted.await(2, TimeUnit.SECONDS));
            assertTrue(operation.isCancelled());
            SendOutcome outcome = operation.outcome().toCompletableFuture().join();
            assertEquals(RepostErrorCode.CANCELLED, outcome.getErrorCode());
            assertOperationId(outcome.getOperationId());
            assertEquals(0, runtime.diagnostics().getInFlightOperations());
        } finally {
            runtime.close();
            blockProvider.countDown();
            executor.shutdownNow();
            scheduler.shutdownNow();
        }
    }

    @Test
    void assignsOneStableUniqueOperationIdPerStartedOperation() {
        RepostRuntime runtime = RepostRuntime.create(ClientOptions.builder()
                .apiKey("key")
                .transport(immediateIoTransport())
                .build());
        SchemaDescriptor schema = schema();

        SendOperation first = runtime.sendAsync(
                schema, event(schema), "customer_1", emptyModel(), SendOptions.defaults());
        RepostTransportException firstFailure = transportFailure(first);
        SendOperation second = runtime.sendAsync(
                schema, event(schema), "customer_2", emptyModel(), SendOptions.defaults());
        RepostTransportException secondFailure = transportFailure(second);

        String firstId = firstFailure.getOperationId();
        String secondId = secondFailure.getOperationId();
        assertOperationId(firstId);
        assertOperationId(secondId);
        assertFalse(firstId.equals(secondId));
        assertEquals(firstId, first.outcome().toCompletableFuture().join().getOperationId());
        assertEquals(secondId, second.outcome().toCompletableFuture().join().getOperationId());
        assertFalse(firstFailure.toString().contains(firstId));
        runtime.close();
    }

    @Test
    void assignsOperationIdToCredentialFailureButNotPreStartFailures() {
        RepostRuntime runtime = RepostRuntime.createForTesting(
                ClientOptions.builder().baseUri("https://example.com").build(),
                ignored -> null);
        SchemaDescriptor schema = schema();

        SendOperation missingCredential = runtime.sendAsync(
                schema, event(schema), "customer_1", emptyModel(), SendOptions.defaults());
        RepostConfigurationException missingFailure = configurationFailure(missingCredential);
        assertOperationId(missingFailure.getOperationId());
        assertEquals(
                missingFailure.getOperationId(),
                missingCredential.outcome().toCompletableFuture().join().getOperationId());

        SchemaDescriptor unsupportedSchema = SchemaDescriptor.builder(3)
                .addModel(ModelDescriptor.of("Payload", java.util.Collections.emptyList()))
                .addEvent("events", "created", EventDescriptor.of("event.created", "Payload"))
                .build();
        SendOperation unsupported = runtime.sendAsync(
                unsupportedSchema,
                event(unsupportedSchema),
                "customer_2",
                emptyModel(),
                SendOptions.defaults());
        assertTrue(operationFailure(unsupported).getOperationId() == null);

        runtime.close();
        SendOperation closed = runtime.sendAsync(
                schema, event(schema), "customer_3", emptyModel(), SendOptions.defaults());
        assertTrue(transportFailure(closed).getOperationId() == null);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("operationDeadlineTieCases")
    void usesOverflowSafeMonotonicDeadlineWithExclusiveEquality(
            String caseId,
            long elapsedNanos,
            RepostErrorCode expectedCode,
            int expectedCredentialReads) {
        MutableMonotonicClock clock = new MutableMonotonicClock(Long.MAX_VALUE - 500_000L);
        ManualScheduler scheduler = new ManualScheduler();
        QueuedExecutor executor = new QueuedExecutor();
        AtomicInteger credentialReads = new AtomicInteger();
        RepostRuntime runtime = RepostRuntime.create(ClientOptions.builder()
                .apiKeyProvider(() -> {
                    credentialReads.incrementAndGet();
                    return "operation-key";
                })
                .operationTimeout(Duration.ofMillis(1))
                .monotonicClock(clock)
                .executor(executor)
                .scheduler(scheduler)
                .transport(immediateIoTransport())
                .build());
        SchemaDescriptor schema = schema();
        SendOperation operation = runtime.sendAsync(
                schema, event(schema), "customer_1", emptyModel(), SendOptions.defaults());
        assertEquals(1, scheduler.submissions);

        clock.advance(elapsedNanos);
        executor.runQueued();

        RepostTransportException failure = transportFailure(operation);
        assertEquals(expectedCode, failure.getErrorCode());
        assertEquals(expectedCredentialReads, credentialReads.get());
        assertOperationId(failure.getOperationId());
        assertEquals(
                failure.getOperationId(),
                operation.outcome().toCompletableFuture().join().getOperationId());
        runtime.close();
        scheduler.shutdownNow();
    }

    private static java.util.stream.Stream<Arguments> operationDeadlineTieCases() {
        return java.util.stream.Stream.of(
                Arguments.of(
                        "operation-completion-one-nanosecond-before-deadline",
                        999_999L,
                        RepostErrorCode.IO,
                        1),
                Arguments.of(
                        "operation-completion-at-deadline-is-timeout",
                        1_000_000L,
                        RepostErrorCode.OPERATION_DEADLINE,
                        0));
    }

    @Test
    void derivesDeadlineFromMonotonicTimeInsteadOfSchedulerCallbackOrder() {
        MutableMonotonicClock clock = new MutableMonotonicClock(10L);
        ManualScheduler scheduler = new ManualScheduler();
        QueuedExecutor executor = new QueuedExecutor();
        AtomicInteger credentialReads = new AtomicInteger();
        RepostRuntime runtime = RepostRuntime.create(ClientOptions.builder()
                .apiKeyProvider(() -> {
                    credentialReads.incrementAndGet();
                    return "must-not-run";
                })
                .operationTimeout(Duration.ofMillis(1))
                .monotonicClock(clock)
                .executor(executor)
                .scheduler(scheduler)
                .build());
        SchemaDescriptor schema = schema();
        SendOperation operation = runtime.sendAsync(
                schema, event(schema), "customer_1", emptyModel(), SendOptions.defaults());

        clock.advance(999_999L);
        scheduler.runNext();
        assertFalse(operation.isDone());
        assertEquals(2, scheduler.submissions);
        assertEquals(1L, scheduler.delaysNanos.get(1));
        assertEquals(0, credentialReads.get());

        clock.advance(1L);
        scheduler.runNext();
        RepostTransportException failure = transportFailure(operation);
        assertEquals(RepostErrorCode.OPERATION_DEADLINE, failure.getErrorCode());
        assertOperationId(failure.getOperationId());
        assertEquals(
                failure.getOperationId(),
                operation.outcome().toCompletableFuture().join().getOperationId());
        executor.runQueued();
        assertEquals(0, credentialReads.get());
        runtime.close();
        scheduler.shutdownNow();
    }

    @Test
    void subtractsRegistrationTimeFromTheInitialDeadlineSchedule() {
        ScriptedMonotonicClock clock = new ScriptedMonotonicClock(
                new long[] {100L, 250_100L}, -1);
        ManualScheduler scheduler = new ManualScheduler();
        QueuedExecutor executor = new QueuedExecutor();
        RepostRuntime runtime = RepostRuntime.create(ClientOptions.builder()
                .apiKey("key")
                .operationTimeout(Duration.ofMillis(1))
                .monotonicClock(clock)
                .executor(executor)
                .scheduler(scheduler)
                .build());
        SchemaDescriptor schema = schema();

        SendOperation operation = runtime.sendAsync(
                schema, event(schema), "customer_1", emptyModel(), SendOptions.defaults());

        assertEquals(750_000L, scheduler.delaysNanos.get(0));
        assertTrue(operation.cancel(true));
        runtime.close();
        scheduler.shutdownNow();
    }

    @Test
    void settlesDeadlineWhenRegistrationSampleEqualsTheDeadline() {
        ScriptedMonotonicClock clock = new ScriptedMonotonicClock(
                new long[] {100L, 1_000_100L}, -1);
        ManualScheduler scheduler = new ManualScheduler();
        QueuedExecutor executor = new QueuedExecutor();
        AtomicInteger credentialReads = new AtomicInteger();
        RepostRuntime runtime = RepostRuntime.create(ClientOptions.builder()
                .apiKeyProvider(() -> {
                    credentialReads.incrementAndGet();
                    return "must-not-run";
                })
                .operationTimeout(Duration.ofMillis(1))
                .monotonicClock(clock)
                .executor(executor)
                .scheduler(scheduler)
                .build());
        SchemaDescriptor schema = schema();

        SendOperation operation = runtime.sendAsync(
                schema, event(schema), "customer_1", emptyModel(), SendOptions.defaults());
        RepostTransportException failure = transportFailure(operation);

        assertEquals(RepostErrorCode.OPERATION_DEADLINE, failure.getErrorCode());
        assertOperationId(failure.getOperationId());
        assertEquals(0, scheduler.submissions);
        assertEquals(0, executor.submissions);
        assertEquals(0, credentialReads.get());
        assertEquals(0, runtime.diagnostics().getInFlightOperations());
        runtime.close();
        scheduler.shutdownNow();
    }

    @Test
    void sanitizesClockFailureOnTheInitialAdmissionSample() {
        assertClockFailure(0, false);
    }

    @Test
    void sanitizesClockFailureOnALaterWorkerSample() {
        assertClockFailure(2, false);
    }

    @Test
    void sanitizesClockFailureOnALaterDeadlineCallbackSample() {
        assertClockFailure(2, true);
    }

    private static void assertClockFailure(int successfulSamples, boolean fromScheduler) {
        long[] samples = new long[successfulSamples];
        ManualScheduler scheduler = new ManualScheduler();
        QueuedExecutor executor = new QueuedExecutor();
        AtomicInteger credentialReads = new AtomicInteger();
        RepostRuntime runtime = RepostRuntime.create(ClientOptions.builder()
                .apiKeyProvider(() -> {
                    credentialReads.incrementAndGet();
                    return "must-not-run";
                })
                .operationTimeout(Duration.ofMillis(1))
                .monotonicClock(new ScriptedMonotonicClock(samples, successfulSamples))
                .executor(executor)
                .scheduler(scheduler)
                .build());
        SchemaDescriptor schema = schema();
        SendOperation operation = runtime.sendAsync(
                schema, event(schema), "customer_1", emptyModel(), SendOptions.defaults());
        if (successfulSamples > 0) {
            if (fromScheduler) {
                scheduler.runNext();
            } else {
                executor.runQueued();
            }
        }

        assertTrue(operation.isDone());
        RepostConfigurationException failure = configurationFailure(operation);
        assertEquals(RepostErrorCode.CONFIGURATION, failure.getErrorCode());
        assertEquals(DeliveryState.NOT_SENT, failure.getDeliveryState());
        assertOperationId(failure.getOperationId());
        assertEquals(1, failure.getConfigurationIssues().size());
        ConfigurationIssue issue = failure.getConfigurationIssues().get(0);
        assertEquals(ConfigurationIssueCode.INVALID_VALUE, issue.getCode());
        assertEquals(
                java.util.Collections.singletonList(ClientOptionKey.MONOTONIC_CLOCK),
                issue.getOptionKeys());
        assertTrue(failure.getCause() == null);
        assertFalse(failure.toString().contains("sentinel-clock-failure"));
        assertEquals(0, credentialReads.get());
        assertEquals(0, runtime.diagnostics().getInFlightOperations());
        assertEquals(
                failure.getOperationId(),
                operation.outcome().toCompletableFuture().join().getOperationId());
        runtime.close();
        scheduler.shutdownNow();
    }

    @Test
    void acceptsOnlyIdentityDuplicateObserverAndTelemetryAssignments() {
        RepostObserver observer = event -> { };
        RepostTelemetry telemetry = new NoopTelemetry();
        ClientOptions.builder().observer(observer).observer(observer)
                .telemetry(telemetry).telemetry(telemetry).build();

        RepostConfigurationException observerConflict =
                expectThrows(
                        RepostConfigurationException.class,
                        () -> ClientOptions.builder()
                                .observer(observer)
                                .observer(event -> { })
                                .build());
        assertConfigurationIssue(
                observerConflict,
                ConfigurationIssueCode.INVALID_VALUE,
                ClientOptionKey.OBSERVER);

        RepostConfigurationException telemetryConflict =
                expectThrows(
                        RepostConfigurationException.class,
                        () -> ClientOptions.builder()
                                .telemetry(telemetry)
                                .telemetry(new NoopTelemetry())
                                .build());
        assertConfigurationIssue(
                telemetryConflict,
                ConfigurationIssueCode.INVALID_VALUE,
                ClientOptionKey.TELEMETRY);
    }

    @Nested
    final class ResourceOwnership {
        @Test
        void closesOnlyOwnedResourcesAndPublishesReadOnlyCompletion() {
            TrackingExecutor executor = new TrackingExecutor();
            TrackingExecutor observerExecutor = new TrackingExecutor();
            ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
            TrackingTransport transport = new TrackingTransport();
            RepostRuntime borrowed = RepostRuntime.create(ClientOptions.builder()
                    .apiKey("key")
                    .executor(executor)
                    .scheduler(scheduler)
                    .observerExecutor(observerExecutor)
                    .transport(transport)
                    .build());
            CompletionStage<Void> closeStage = borrowed.closeCompletion();
            borrowed.close();
            borrowed.close();
            assertTrue(borrowed.isClosed());
            assertTrue(borrowed.diagnostics().isClosed());
            assertSame(closeStage, borrowed.closeCompletion());
            assertFalse(executor.shutdown);
            assertFalse(observerExecutor.shutdown);
            assertFalse(scheduler.isShutdown());
            assertFalse(transport.closed);
            assertTrue(closeStage.toCompletableFuture().isDone());
            assertFalse(closeStage.toCompletableFuture().cancel(true));
            scheduler.shutdownNow();

            RepostRuntime owned = RepostRuntime.create(
                    ClientOptions.builder().apiKey("key").build());
            ExecutorService ownedExecutor = owned.executor();
            java.util.concurrent.ScheduledExecutorService ownedScheduler = owned.scheduler();
            assertTrue(owned.transport() instanceof ApacheHttpTransport);
            owned.close();
            assertTrue(owned.isClosed());
            assertTrue(ownedExecutor.isShutdown());
            assertTrue(ownedScheduler.isShutdown());
        }
    }

    @Test
    void neverRendersCredentialsOrBorrowedObjects() {
        String secret = "sentinel-api-secret";
        ClientOptions options = ClientOptions.builder().apiKey(secret).build();
        RepostRuntime runtime = RepostRuntime.create(options);
        assertFalse(options.toString().contains(secret));
        assertFalse(runtime.toString().contains(secret));
        assertFalse(runtime.diagnostics().toString().contains(secret));
        runtime.close();
    }

    @Test
    void rejectsSendAfterCloseWithStableOutcome() {
        RepostRuntime runtime = RepostRuntime.create(ClientOptions.builder().apiKey("key").build());
        SchemaDescriptor schema = SchemaDescriptor.builder(2)
                .addModel(ModelDescriptor.of("Payload", java.util.Collections.emptyList()))
                .addEvent("events", "created", EventDescriptor.of("event.created", "Payload"))
                .build();
        EventDescriptor event = schema.getWebhooks().get("events").get("created");
        runtime.close();

        SendOperation operation = runtime.sendAsync(
                schema,
                event,
                "customer_123",
                new RepostModel() {
                    @Override public boolean __repostIsPresent(int fieldIndex) { return false; }
                    @Override public Object __repostValue(int fieldIndex) { return null; }
                },
                SendOptions.defaults());
        try {
            operation.toCompletableFuture().join();
            throw new AssertionError("expected closed failure");
        } catch (java.util.concurrent.CompletionException exception) {
            RepostTransportException failure = (RepostTransportException) exception.getCause();
            assertEquals(RepostErrorCode.CLOSED, failure.getErrorCode());
            assertEquals(DeliveryState.NOT_SENT, failure.getDeliveryState());
        }
        SendOutcome outcome = operation.outcome().toCompletableFuture().join();
        assertEquals(RepostErrorCode.CLOSED, outcome.getErrorCode());
        assertEquals(DeliveryState.NOT_SENT, outcome.getDeliveryState());
    }

    private static SchemaDescriptor schema() {
        return SchemaDescriptor.builder(2)
                .addModel(ModelDescriptor.of("Payload", java.util.Collections.emptyList()))
                .addEvent("events", "created", EventDescriptor.of("event.created", "Payload"))
                .build();
    }

    private static EventDescriptor event(SchemaDescriptor schema) {
        return schema.getWebhooks().get("events").get("created");
    }

    private static RepostModel emptyModel() {
        return new RepostModel() {
            @Override public boolean __repostIsPresent(int fieldIndex) { return false; }
            @Override public Object __repostValue(int fieldIndex) { return null; }
        };
    }

    private static Transport immediateIoTransport() {
        return request -> {
            throw new IllegalStateException("deterministic test transport failure");
        };
    }

    private static RepostTransportException transportFailure(SendOperation operation) {
        return (RepostTransportException) operationFailure(operation);
    }

    private static RepostException operationFailure(SendOperation operation) {
        try {
            operation.toCompletableFuture().join();
            throw new AssertionError("expected operation failure");
        } catch (java.util.concurrent.CompletionException failure) {
            return (RepostException) failure.getCause();
        }
    }

    private static RepostConfigurationException configurationFailure(SendOperation operation) {
        try {
            operation.toCompletableFuture().join();
            throw new AssertionError("expected configuration failure");
        } catch (java.util.concurrent.CompletionException failure) {
            return (RepostConfigurationException) failure.getCause();
        }
    }

    private static void assertOperationId(String value) {
        if (value == null || !OPERATION_ID.matcher(value).matches()) {
            throw new AssertionError("expected SDK-local UUIDv4 operation ID");
        }
    }

    private static void assertConfigurationIssue(
            RepostConfigurationException failure,
            ConfigurationIssueCode code,
            ClientOptionKey... optionKeys) {
        assertEquals(RepostErrorCode.CONFIGURATION, failure.getErrorCode());
        assertEquals(DeliveryState.NOT_SENT, failure.getDeliveryState());
        assertEquals(1, failure.getIssueCount());
        assertEquals(1, failure.getConfigurationIssues().size());
        ConfigurationIssue issue = failure.getConfigurationIssues().get(0);
        assertEquals(code, issue.getCode());
        assertEquals(Arrays.asList(optionKeys), issue.getOptionKeys());
    }

    private static int readCount(
            HashMap<String, AtomicInteger> reads,
            String key) {
        AtomicInteger count = reads.get(key);
        return count == null ? 0 : count.get();
    }

    private static final class TrackingTransport implements Transport, AutoCloseable {
        private boolean closed;

        @Override
        public CompletionStage<TransportResponse> execute(TransportRequest request) {
            throw new AssertionError("not used");
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class TrackingExecutor extends AbstractExecutorService {
        private boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public java.util.List<Runnable> shutdownNow() {
            shutdown = true;
            return java.util.Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }

    private static final class QueuedExecutor extends AbstractExecutorService {
        private int submissions;
        private Runnable queued;

        @Override public void shutdown() { }
        @Override public java.util.List<Runnable> shutdownNow() {
            return java.util.Collections.emptyList();
        }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return false; }

        @Override
        public void execute(Runnable command) {
            if (queued != null) {
                throw new AssertionError("test executor supports one queued command");
            }
            submissions++;
            queued = command;
        }

        private void runQueued() {
            if (queued == null) {
                throw new AssertionError("expected one queued command");
            }
            Runnable command = queued;
            queued = null;
            command.run();
        }
    }

    private static final class RejectingExecutor extends AbstractExecutorService {
        @Override public void shutdown() { }
        @Override public java.util.List<Runnable> shutdownNow() {
            return java.util.Collections.emptyList();
        }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return false; }

        @Override
        public void execute(Runnable command) {
            throw new RejectedExecutionException("sentinel-executor-rejection");
        }
    }

    private static final class RejectingScheduler extends ScheduledThreadPoolExecutor {
        private RejectingScheduler() {
            super(1);
        }

        @Override
        public java.util.concurrent.ScheduledFuture<?> schedule(
                Runnable command,
                long delay,
                TimeUnit unit) {
            throw new RejectedExecutionException("sentinel-scheduler-rejection");
        }
    }

    private static final class TrackingScheduler extends ScheduledThreadPoolExecutor {
        private int submissions;

        private TrackingScheduler() {
            super(1);
        }

        @Override
        public java.util.concurrent.ScheduledFuture<?> schedule(
                Runnable command,
                long delay,
                TimeUnit unit) {
            submissions++;
            return super.schedule(command, delay, unit);
        }
    }

    private static final class MutableMonotonicClock implements MonotonicClock {
        private long current;

        private MutableMonotonicClock(long current) {
            this.current = current;
        }

        @Override
        public long nanoTime() {
            return current;
        }

        private void advance(long nanos) {
            current += nanos;
        }
    }

    private static final class ScriptedMonotonicClock implements MonotonicClock {
        private final long[] samples;
        private final int failureIndex;
        private int index;

        private ScriptedMonotonicClock(long[] samples, int failureIndex) {
            this.samples = samples.clone();
            this.failureIndex = failureIndex;
        }

        @Override
        public long nanoTime() {
            if (index == failureIndex) {
                throw new IllegalStateException("sentinel-clock-failure");
            }
            if (index >= samples.length) {
                throw new AssertionError("unexpected monotonic-clock sample");
            }
            return samples[index++];
        }
    }

    private static final class ManualScheduler extends AbstractExecutorService
            implements ScheduledExecutorService {
        private final ArrayList<ManualScheduledTask<?>> tasks = new ArrayList<>();
        private final ArrayList<Long> delaysNanos = new ArrayList<>();
        private int submissions;
        private boolean shutdown;

        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() {
            shutdown = true;
            ArrayList<Runnable> pending = new ArrayList<>(tasks);
            tasks.clear();
            return pending;
        }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown && tasks.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) {
            return isTerminated();
        }
        @Override public void execute(Runnable command) { schedule(command, 0L, TimeUnit.NANOSECONDS); }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            ManualScheduledTask<Void> task = new ManualScheduledTask<>(command, null);
            submissions++;
            delaysNanos.add(unit.toNanos(delay));
            tasks.add(task);
            return task;
        }

        @Override
        public <V> ScheduledFuture<V> schedule(
                Callable<V> callable,
                long delay,
                TimeUnit unit) {
            ManualScheduledTask<V> task = new ManualScheduledTask<>(callable);
            submissions++;
            delaysNanos.add(unit.toNanos(delay));
            tasks.add(task);
            return task;
        }

        @Override public ScheduledFuture<?> scheduleAtFixedRate(
                Runnable command, long initialDelay, long period, TimeUnit unit) {
            throw new UnsupportedOperationException("periodic scheduling is not used");
        }

        @Override public ScheduledFuture<?> scheduleWithFixedDelay(
                Runnable command, long initialDelay, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException("periodic scheduling is not used");
        }

        private void runNext() {
            while (!tasks.isEmpty()) {
                ManualScheduledTask<?> task = tasks.remove(0);
                if (!task.isCancelled()) {
                    task.run();
                    return;
                }
            }
            throw new AssertionError("expected one scheduled task");
        }
    }

    private static final class ManualScheduledTask<V> extends FutureTask<V>
            implements ScheduledFuture<V> {
        private ManualScheduledTask(Runnable runnable, V result) {
            super(runnable, result);
        }

        private ManualScheduledTask(Callable<V> callable) {
            super(callable);
        }

        @Override public long getDelay(TimeUnit unit) { return 0L; }
        @Override public int compareTo(Delayed other) { return this == other ? 0 : -1; }
    }

    private static final class NoopTelemetry implements RepostTelemetry {
        @Override
        public CapturedTelemetryContext captureContext() {
            return new CapturedTelemetryContext() { };
        }

        @Override
        public TelemetryOperation startOperation(
                CapturedTelemetryContext parent,
                TelemetryOperationStart start) {
            return new TelemetryOperation() {
                @Override
                public TelemetryScope makeCurrent() {
                    return () -> { };
                }

                @Override
                public TelemetryAttempt startAttempt(TelemetryAttemptStart attemptStart) {
                    throw new AssertionError("not used");
                }

                @Override
                public void end(TelemetryOperationEnd end) { }
            };
        }
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected " + expected + " but got " + actual);
        }
    }

    private static void assertSame(Object expected, Object actual) {
        if (expected != actual) {
            throw new AssertionError("expected identical objects");
        }
    }

    private static void assertTrue(boolean value) {
        if (!value) {
            throw new AssertionError("expected true");
        }
    }

    private static void assertFalse(boolean value) {
        if (value) {
            throw new AssertionError("expected false");
        }
    }

    private static <T extends Throwable> T expectThrows(Class<T> type, Runnable action) {
        try {
            action.run();
        } catch (Throwable throwable) {
            if (type.isInstance(throwable)) {
                return type.cast(throwable);
            }
            throw new AssertionError("expected " + type.getName() + " but got " + throwable, throwable);
        }
        throw new AssertionError("expected " + type.getName());
    }
}
