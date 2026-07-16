package sh.repost.client;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLPeerUnverifiedException;
import sh.repost.internal.apache.org.apache.hc.client5.http.ConnectTimeoutException;
import sh.repost.internal.apache.org.apache.hc.core5.http.ConnectionClosedException;
import sh.repost.internal.apache.org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import sh.repost.internal.apache.org.apache.hc.core5.http2.H2StreamResetException;

/** Closed type-only mapping from production engine failures to the public transport vocabulary. */
final class ApacheTransportFailureClassifier {
    private static final int MAX_CAUSE_DEPTH = 32;

    private ApacheTransportFailureClassifier() { }

    static TransportFailure classify(Exception failure, boolean proxied, boolean bodyStarted) {
        RequestCommitState state = bodyStarted
                ? RequestCommitState.COMMITTED : RequestCommitState.NOT_COMMITTED;
        boolean certificateFailure = false;
        boolean tlsFailure = false;
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof SSLPeerUnverifiedException) {
                return failure(
                        RepostErrorCode.TLS,
                        state,
                        RepostFailureReason.TLS_HOSTNAME_MISMATCH);
            }
            if (current instanceof CertificateExpiredException
                    || hasCertPathReason(current, CertPathValidatorException.BasicReason.EXPIRED)) {
                return failure(
                        RepostErrorCode.TLS,
                        state,
                        RepostFailureReason.TLS_CERTIFICATE_EXPIRED);
            }
            if (current instanceof CertificateNotYetValidException
                    || hasCertPathReason(
                            current, CertPathValidatorException.BasicReason.NOT_YET_VALID)) {
                return failure(
                        RepostErrorCode.TLS,
                        state,
                        RepostFailureReason.TLS_CERTIFICATE_NOT_YET_VALID);
            }
            if (current instanceof UnknownHostException) {
                return failure(
                        RepostErrorCode.DNS,
                        state,
                        RepostFailureReason.DNS_NOT_FOUND);
            }
            if (current instanceof ConnectTimeoutException) {
                return proxied
                        ? failure(
                                RepostErrorCode.PROXY,
                                state,
                                RepostFailureReason.PROXY_CONNECT_FAILED)
                        : failure(
                                RepostErrorCode.CONNECT,
                                state,
                                RepostFailureReason.CONNECT_TIMEOUT);
            }
            if (current instanceof ConnectException) {
                return proxied
                        ? failure(
                                RepostErrorCode.PROXY,
                                state,
                                RepostFailureReason.PROXY_CONNECT_FAILED)
                        : failure(
                                RepostErrorCode.CONNECT,
                                state,
                                RepostFailureReason.CONNECT_REFUSED);
            }
            if (current instanceof NoRouteToHostException) {
                return failure(
                        proxied ? RepostErrorCode.PROXY : RepostErrorCode.CONNECT,
                        state,
                        RepostFailureReason.UNKNOWN);
            }
            if (current instanceof SocketTimeoutException
                    || current instanceof ConnectionRequestTimeoutException) {
                return failure(
                        RepostErrorCode.ATTEMPT_TIMEOUT,
                        state,
                        RepostFailureReason.UNKNOWN);
            }
            if (current instanceof ConnectionClosedException) {
                return failure(
                        RepostErrorCode.IO,
                        state,
                        RepostFailureReason.CONNECTION_CLOSED);
            }
            if (current instanceof H2StreamResetException) {
                return failure(
                        RepostErrorCode.IO,
                        state,
                        RepostFailureReason.CONNECTION_RESET);
            }
            certificateFailure |= current instanceof CertificateException;
            tlsFailure |= current instanceof SSLException;
            Throwable next = current.getCause();
            if (next == current) {
                break;
            }
            current = next;
        }
        if (certificateFailure) {
            return failure(
                    RepostErrorCode.TLS,
                    state,
                    RepostFailureReason.TLS_UNTRUSTED);
        }
        if (tlsFailure) {
            return failure(
                    RepostErrorCode.TLS,
                    state,
                    RepostFailureReason.TLS_NEGOTIATION);
        }
        return failure(RepostErrorCode.IO, state, RepostFailureReason.UNKNOWN);
    }

    private static boolean hasCertPathReason(
            Throwable failure,
            CertPathValidatorException.BasicReason reason) {
        return failure instanceof CertPathValidatorException
                && ((CertPathValidatorException) failure).getReason() == reason;
    }

    private static TransportFailure failure(
            RepostErrorCode code,
            RequestCommitState state,
            RepostFailureReason reason) {
        return TransportFailure.of(code, state, reason);
    }
}
