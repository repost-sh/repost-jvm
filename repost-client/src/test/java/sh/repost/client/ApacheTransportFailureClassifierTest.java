package sh.repost.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;
import sh.repost.internal.apache.org.apache.hc.client5.http.ConnectTimeoutException;
import sh.repost.internal.apache.org.apache.hc.core5.http.ConnectionClosedException;

final class ApacheTransportFailureClassifierTest {
    @Test
    void classifiesUnknownHostWithoutRetainingResolverText() {
        TransportFailure classified = ApacheTransportFailureClassifier.classify(
                new UnknownHostException("sentinel-dns-host-and-secret"), false, false);

        assertFailure(
                classified,
                RepostErrorCode.DNS,
                RequestCommitState.NOT_COMMITTED,
                RepostFailureReason.DNS_NOT_FOUND);
        assertFalse(classified.getMessage().contains("sentinel"));
        assertFalse(classified.toString().contains("sentinel"));
    }

    @Test
    void distinguishesDirectConnectTimeoutFromProxyConnectFailure() {
        assertFailure(
                ApacheTransportFailureClassifier.classify(
                        new ConnectTimeoutException("sentinel-connect-timeout"), false, false),
                RepostErrorCode.CONNECT,
                RequestCommitState.NOT_COMMITTED,
                RepostFailureReason.CONNECT_TIMEOUT);
        assertFailure(
                ApacheTransportFailureClassifier.classify(
                        new ConnectException("sentinel-proxy-secret"), true, false),
                RepostErrorCode.PROXY,
                RequestCommitState.NOT_COMMITTED,
                RepostFailureReason.PROXY_CONNECT_FAILED);
    }

    @Test
    void distinguishesResponseTimeoutAndConnectionCloseByCommitment() {
        assertFailure(
                ApacheTransportFailureClassifier.classify(
                        new SocketTimeoutException("sentinel-response-timeout"), false, true),
                RepostErrorCode.ATTEMPT_TIMEOUT,
                RequestCommitState.COMMITTED,
                RepostFailureReason.UNKNOWN);
        assertFailure(
                ApacheTransportFailureClassifier.classify(
                        new ConnectionClosedException("sentinel-remote-close"), false, true),
                RepostErrorCode.IO,
                RequestCommitState.COMMITTED,
                RepostFailureReason.CONNECTION_CLOSED);
    }

    @Test
    void distinguishesCertificateValidityFromOtherTlsNegotiation() {
        SSLHandshakeException expired = new SSLHandshakeException("sentinel-expired-certificate");
        expired.initCause(new CertificateExpiredException("sentinel-certificate-subject"));
        assertFailure(
                ApacheTransportFailureClassifier.classify(expired, false, false),
                RepostErrorCode.TLS,
                RequestCommitState.NOT_COMMITTED,
                RepostFailureReason.TLS_CERTIFICATE_EXPIRED);

        SSLHandshakeException notYetValid =
                new SSLHandshakeException("sentinel-not-yet-valid-certificate");
        notYetValid.initCause(
                new CertificateNotYetValidException("sentinel-certificate-subject"));
        assertFailure(
                ApacheTransportFailureClassifier.classify(notYetValid, false, false),
                RepostErrorCode.TLS,
                RequestCommitState.NOT_COMMITTED,
                RepostFailureReason.TLS_CERTIFICATE_NOT_YET_VALID);

        assertFailure(
                ApacheTransportFailureClassifier.classify(
                        new SSLException("sentinel-negotiation-detail"), false, false),
                RepostErrorCode.TLS,
                RequestCommitState.NOT_COMMITTED,
                RepostFailureReason.TLS_NEGOTIATION);
    }

    @Test
    void ambiguousEngineFailureUsesUnknownAndRetainsNoThrowableText() {
        TransportFailure classified = ApacheTransportFailureClassifier.classify(
                new SentinelEngineFailure("sentinel-payload-host-path-and-secret"),
                false,
                true);

        assertFailure(
                classified,
                RepostErrorCode.IO,
                RequestCommitState.COMMITTED,
                RepostFailureReason.UNKNOWN);
        assertNull(classified.getCause());
        assertEquals(0, classified.getSuppressed().length);
        assertFalse(classified.getMessage().contains("sentinel"));
        assertFalse(classified.toString().contains("sentinel"));
        for (StackTraceElement frame : classified.getStackTrace()) {
            assertFalse(frame.getClassName().contains("SentinelEngineFailure"));
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

    private static final class SentinelEngineFailure extends IOException {
        private static final long serialVersionUID = 1L;

        private SentinelEngineFailure(String message) {
            super(message);
        }
    }
}
