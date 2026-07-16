package sh.repost.client;

import java.util.Arrays;
import java.util.Collections;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import sh.repost.client.error.RepostConfigurationException;
import sh.repost.client.error.RepostException;
import sh.repost.client.error.RepostPublishException;
import sh.repost.client.error.RepostSerializationException;
import sh.repost.client.error.RepostTransportException;
import sh.repost.client.error.RepostValidationException;

public final class ErrorContractTest {
    @Test
    void freezesPublicTaxonomies() {
        assertEquals(Arrays.asList(
                "CONFIGURATION", "CLOSED", "VALIDATION", "SERIALIZATION",
                "REQUEST_TOO_LARGE", "DNS", "CONNECT", "PROXY", "TLS", "IO",
                "ATTEMPT_TIMEOUT", "OPERATION_DEADLINE", "CANCELLED", "OVERLOADED",
                "RATE_LIMITED", "HTTP_REJECTED", "SERVER_FAILURE", "RESPONSE_TOO_LARGE",
                "RESPONSE_PROTOCOL", "DESCRIPTOR_VERSION"), names(RepostErrorCode.values()));
        assertEquals(Arrays.asList(
                "NOT_SENT", "POSSIBLY_SENT", "ACCEPTED", "REJECTED", "CANCELLED_UNKNOWN"),
                names(DeliveryState.values()));
        assertEquals(Arrays.asList(
                "API_KEY_PROVIDER", "DEFAULT_GENERATOR", "IDEMPOTENCY_GENERATOR",
                "RETRY_ENTROPY", "DNS_RESOLVER", "PROXY_CREDENTIAL_PROVIDER", "TLS_PROVIDER",
                "CUSTOM_TRANSPORT", "RESPONSE_BODY", "OBSERVER", "HTTP_RUNTIME",
                "TRANSPORT_CLOSE", "SCHEDULER_CLOSE", "OPERATION_EXECUTOR_CLOSE",
                "DNS_EXECUTOR_CLOSE", "PROXY_CREDENTIAL_EXECUTOR_CLOSE", "TLS_EXECUTOR_CLOSE",
                "TERMINAL_SETTLEMENT_CLOSE", "OBSERVER_CLOSE", "UNKNOWN"),
                names(RepostCauseCategory.values()));
        assertEquals(Arrays.asList(
                "DNS_NOT_FOUND", "DNS_TIMEOUT", "CONNECT_REFUSED", "CONNECT_TIMEOUT",
                "CONNECTION_RESET", "CONNECTION_CLOSED", "TLS_UNTRUSTED",
                "TLS_CERTIFICATE_EXPIRED", "TLS_CERTIFICATE_NOT_YET_VALID",
                "TLS_HOSTNAME_MISMATCH", "TLS_NEGOTIATION", "PROXY_AUTH_REQUIRED",
                "PROXY_CONNECT_FAILED", "UNKNOWN"), names(RepostFailureReason.values()));
        assertEquals("network.tls.hostname-mismatch",
                RepostFailureReason.TLS_HOSTNAME_MISMATCH.getRemediationKey());
        assertEquals(Arrays.asList(
                "REQUIRED", "NULL_NOT_ALLOWED", "TYPE_MISMATCH", "OUT_OF_RANGE",
                "NON_FINITE", "INVALID_DATETIME", "INVALID_ENUM", "INVALID_JSON",
                "INVALID_UNICODE", "COLLECTION_LIMIT", "CYCLE"),
                names(ValidationIssueCode.values()));
        assertEquals(Arrays.asList(
                "MISSING", "CONFLICT", "INVALID_VALUE", "OUT_OF_RANGE", "UNSUPPORTED",
                "RESOURCE_MISMATCH"), names(ConfigurationIssueCode.values()));
        assertEquals(24, ClientOptionKey.values().length);
    }

    @Test
    void validatesIssueShapesAndDefensiveCopies() {
        ValidationIssue validation = ValidationIssue.of(
                ValidationIssueCode.NULL_NOT_ALLOWED, "$.authors[2].name");
        assertEquals("$.authors[2].name", validation.getPath());
        assertFalse(validation.toString().contains("payload"));
        expectThrows(IllegalArgumentException.class,
                () -> ValidationIssue.of(ValidationIssueCode.INVALID_JSON, "$.metadata[secret]"));

        java.util.ArrayList<ClientOptionKey> keys = new java.util.ArrayList<>(Arrays.asList(
                ClientOptionKey.API_KEY, ClientOptionKey.API_KEY_PROVIDER));
        ConfigurationIssue issue = ConfigurationIssue.of(ConfigurationIssueCode.CONFLICT, keys);
        keys.clear();
        assertEquals(2, issue.getOptionKeys().size());
        expectThrows(IllegalArgumentException.class, () -> ConfigurationIssue.of(
                ConfigurationIssueCode.CONFLICT,
                Arrays.asList(ClientOptionKey.API_KEY_PROVIDER, ClientOptionKey.API_KEY)));
        expectThrows(IllegalArgumentException.class, () -> ConfigurationIssue.of(
                ConfigurationIssueCode.MISSING,
                Arrays.asList(ClientOptionKey.API_KEY, ClientOptionKey.API_KEY_PROVIDER)));
    }

    @Test
    void buildsBoundedStructuredDetails() {
        ValidationIssue issue = ValidationIssue.of(ValidationIssueCode.REQUIRED, "$.title");
        RepostErrorDetails details = RepostErrorDetails.builder(
                        RepostErrorCode.VALIDATION, DeliveryState.NOT_SENT)
                .operationId("op_12345678-1234-4234-9234-123456789abc")
                .validationIssues(Collections.singletonList(issue), 1, false)
                .build();
        assertEquals(Collections.singletonList(issue), details.getValidationIssues());
        assertEquals(1, details.getIssueCount());
        assertFalse(details.isIssuesTruncated());
        assertTrue(details.getConfigurationIssues().isEmpty());

        expectThrows(IllegalArgumentException.class, () -> RepostErrorDetails.builder(
                        RepostErrorCode.VALIDATION, DeliveryState.NOT_SENT)
                .validationIssues(Collections.singletonList(issue), 2, false)
                .build());
        expectThrows(IllegalArgumentException.class, () -> RepostErrorDetails.builder(
                        RepostErrorCode.IO, DeliveryState.NOT_SENT)
                .validationIssues(Collections.singletonList(issue), 1, false)
                .build());
        expectThrows(IllegalArgumentException.class, () -> RepostErrorDetails.builder(
                        RepostErrorCode.IO, DeliveryState.NOT_SENT)
                .closeFailures(Arrays.asList(
                        RepostCauseCategory.SCHEDULER_CLOSE,
                        RepostCauseCategory.SCHEDULER_CLOSE))
                .build());
    }

    @Test
    void enforcesExceptionSubclassMappingsAndSafeRendering() {
        RepostErrorDetails configuration = RepostErrorDetails.builder(
                        RepostErrorCode.CONFIGURATION, DeliveryState.NOT_SENT)
                .configurationIssues(
                        Collections.singletonList(ConfigurationIssue.of(
                                ConfigurationIssueCode.MISSING,
                                Collections.singletonList(ClientOptionKey.API_KEY))),
                        1,
                        false)
                .build();
        RepostException exception = new RepostConfigurationException(configuration);
        assertEquals(RepostErrorCode.CONFIGURATION, exception.getErrorCode());
        assertEquals(DeliveryState.NOT_SENT, exception.getDeliveryState());
        assertEquals(null, exception.getCause());
        assertEquals(0, exception.getSuppressed().length);
        assertEquals("RepostConfigurationException[code=CONFIGURATION, delivery=NOT_SENT]",
                exception.toString());
        assertFalse(exception.getMessage().contains("API_KEY"));

        expectThrows(IllegalArgumentException.class,
                () -> new RepostValidationException(configuration));
        expectThrows(IllegalArgumentException.class,
                () -> new RepostPublishException(configuration));

        RepostErrorDetails closeFailure = RepostErrorDetails.builder(
                        RepostErrorCode.IO, DeliveryState.NOT_SENT)
                .closeFailures(Arrays.asList(
                        RepostCauseCategory.SCHEDULER_CLOSE,
                        RepostCauseCategory.TRANSPORT_CLOSE))
                .build();
        RepostTransportException transport = new RepostTransportException(closeFailure);
        assertEquals(2, transport.getCloseFailureCount());
        assertEquals(RepostCauseCategory.SCHEDULER_CLOSE,
                transport.getCloseFailureCategories().get(0));
    }

    @Test
    void exposesOnlyClosedCustomTransportFailures() {
        TransportFailure failure = TransportFailure.of(
                RepostErrorCode.CONNECT,
                RequestCommitState.UNKNOWN,
                RepostFailureReason.CONNECT_TIMEOUT);
        assertEquals(RepostErrorCode.CONNECT, failure.getErrorCode());
        assertEquals(RequestCommitState.UNKNOWN, failure.getCommitState());
        assertEquals(RepostFailureReason.CONNECT_TIMEOUT, failure.getFailureReason());
        assertEquals(RepostCauseCategory.CUSTOM_TRANSPORT, failure.getCauseCategory());
        assertEquals(null, failure.getCause());
        assertFalse(failure.toString().contains("CONNECT_TIMEOUT"));
        expectThrows(IllegalArgumentException.class, () -> TransportFailure.of(
                RepostErrorCode.CONFIGURATION, RequestCommitState.NOT_COMMITTED));
    }

    @Test
    void customTransportFailuresDoNotCaptureCallerStacks() {
        TransportFailure failure = SentinelTransportFailureFactory.create();
        assertEquals(0, failure.getStackTrace().length);
        StringWriter output = new StringWriter();
        failure.printStackTrace(new PrintWriter(output));
        assertFalse(output.toString().contains("SentinelTransportFailureFactory"));
        assertFalse(output.toString().contains("ErrorContractTest"));
        assertEquals(null, failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
    }

    @Test
    void mapsEveryCodeToExactlyOnePublicSubclass() {
        for (RepostErrorCode code : RepostErrorCode.values()) {
            RepostException exception = RepostExceptionFactory.create(validDetails(code));
            Class<? extends RepostException> expected;
            switch (code) {
                case CONFIGURATION:
                    expected = RepostConfigurationException.class;
                    break;
                case VALIDATION:
                    expected = RepostValidationException.class;
                    break;
                case SERIALIZATION:
                case REQUEST_TOO_LARGE:
                    expected = RepostSerializationException.class;
                    break;
                case RATE_LIMITED:
                case HTTP_REJECTED:
                case SERVER_FAILURE:
                case RESPONSE_TOO_LARGE:
                case RESPONSE_PROTOCOL:
                    expected = RepostPublishException.class;
                    break;
                case DESCRIPTOR_VERSION:
                    expected = sh.repost.client.error.RepostDescriptorVersionException.class;
                    break;
                default:
                    expected = RepostTransportException.class;
                    break;
            }
            assertEquals(expected, exception.getClass());
        }
    }

    @Test
    void rejectsImpossibleSemanticCombinations() {
        expectThrows(IllegalArgumentException.class, () -> RepostErrorDetails.builder(
                        RepostErrorCode.RATE_LIMITED, DeliveryState.NOT_SENT)
                .attemptCount(1).httpStatus(429).retryable(true).build());
        expectThrows(IllegalArgumentException.class, () -> RepostErrorDetails.builder(
                        RepostErrorCode.SERVER_FAILURE, DeliveryState.POSSIBLY_SENT)
                .attemptCount(1).httpStatus(404).retryable(true).build());
        expectThrows(IllegalArgumentException.class, () -> RepostErrorDetails.builder(
                        RepostErrorCode.TLS, DeliveryState.POSSIBLY_SENT)
                .attemptCount(1).failureReason(RepostFailureReason.CONNECT_REFUSED)
                .retryable(false).build());
        expectThrows(IllegalArgumentException.class, () -> RepostErrorDetails.builder(
                        RepostErrorCode.VALIDATION, DeliveryState.POSSIBLY_SENT)
                .validationIssues(
                        Collections.singletonList(ValidationIssue.of(
                                ValidationIssueCode.REQUIRED, "$.title")), 1, false)
                .build());
        expectThrows(IllegalArgumentException.class, () -> SendOutcome.failed(
                "op_12345678-1234-4234-9234-123456789abc",
                DeliveryState.REJECTED,
                RepostErrorCode.RESPONSE_PROTOCOL,
                null,
                RepostCauseCategory.RESPONSE_BODY,
                1,
                "key",
                200));
        expectThrows(IllegalArgumentException.class, () -> TransportFailure.of(
                RepostErrorCode.DNS,
                RequestCommitState.NOT_COMMITTED,
                RepostFailureReason.TLS_NEGOTIATION));
    }

    private static RepostErrorDetails validDetails(RepostErrorCode code) {
        RepostErrorDetails.Builder builder;
        switch (code) {
            case CONFIGURATION:
                return RepostErrorDetails.builder(code, DeliveryState.NOT_SENT)
                        .configurationIssues(
                                Collections.singletonList(ConfigurationIssue.of(
                                        ConfigurationIssueCode.MISSING,
                                        Collections.singletonList(ClientOptionKey.API_KEY))),
                                1,
                                false)
                        .build();
            case VALIDATION:
                return RepostErrorDetails.builder(code, DeliveryState.NOT_SENT)
                        .validationIssues(
                                Collections.singletonList(ValidationIssue.of(
                                        ValidationIssueCode.REQUIRED, "$.title")),
                                1,
                                false)
                        .build();
            case DNS:
                return networkDetails(code, RepostFailureReason.DNS_NOT_FOUND, true);
            case CONNECT:
                return networkDetails(code, RepostFailureReason.CONNECT_REFUSED, true);
            case PROXY:
                return networkDetails(code, RepostFailureReason.PROXY_CONNECT_FAILED, true);
            case TLS:
                return networkDetails(code, RepostFailureReason.TLS_NEGOTIATION, false);
            case IO:
                return networkDetails(code, RepostFailureReason.CONNECTION_RESET, true);
            case ATTEMPT_TIMEOUT:
                return networkDetails(code, RepostFailureReason.CONNECT_TIMEOUT, true);
            case RATE_LIMITED:
                return RepostErrorDetails.builder(code, DeliveryState.REJECTED)
                        .attemptCount(1).httpStatus(429).retryable(true).build();
            case HTTP_REJECTED:
                return RepostErrorDetails.builder(code, DeliveryState.REJECTED)
                        .attemptCount(1).httpStatus(400).retryable(false).build();
            case SERVER_FAILURE:
                return RepostErrorDetails.builder(code, DeliveryState.POSSIBLY_SENT)
                        .attemptCount(1).httpStatus(500).retryable(true).build();
            case RESPONSE_TOO_LARGE:
                return RepostErrorDetails.builder(code, DeliveryState.POSSIBLY_SENT)
                        .attemptCount(1).httpStatus(200).retryable(false)
                        .causeCategory(RepostCauseCategory.RESPONSE_BODY).build();
            case RESPONSE_PROTOCOL:
                return RepostErrorDetails.builder(code, DeliveryState.POSSIBLY_SENT)
                        .attemptCount(1).httpStatus(200).retryable(true)
                        .causeCategory(RepostCauseCategory.RESPONSE_BODY).build();
            default:
                builder = RepostErrorDetails.builder(code, DeliveryState.NOT_SENT);
                return builder.build();
        }
    }

    private static RepostErrorDetails networkDetails(
            RepostErrorCode code,
            RepostFailureReason reason,
            boolean retryable) {
        return RepostErrorDetails.builder(code, DeliveryState.NOT_SENT)
                .attemptCount(1)
                .failureReason(reason)
                .causeCategory(RepostCauseCategory.CUSTOM_TRANSPORT)
                .retryable(retryable)
                .build();
    }

    private static java.util.List<String> names(Enum<?>[] values) {
        java.util.ArrayList<String> names = new java.util.ArrayList<>(values.length);
        for (Enum<?> value : values) {
            names.add(value.name());
        }
        return names;
    }

    private static final class SentinelTransportFailureFactory {
        private static TransportFailure create() {
            return TransportFailure.of(
                    RepostErrorCode.CONNECT,
                    RequestCommitState.NOT_COMMITTED,
                    RepostFailureReason.CONNECT_REFUSED);
        }
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError("expected " + expected + " but got " + actual);
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

    private static <T extends Throwable> void expectThrows(Class<T> type, Runnable action) {
        try {
            action.run();
        } catch (Throwable throwable) {
            if (type.isInstance(throwable)) {
                return;
            }
            throw new AssertionError("expected " + type.getName() + " but got " + throwable, throwable);
        }
        throw new AssertionError("expected " + type.getName());
    }
}
