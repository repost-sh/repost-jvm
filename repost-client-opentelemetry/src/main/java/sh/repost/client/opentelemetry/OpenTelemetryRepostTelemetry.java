package sh.repost.client.opentelemetry;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import sh.repost.client.CapturedTelemetryContext;
import sh.repost.client.HttpStatusClass;
import sh.repost.client.ObserverOutcome;
import sh.repost.client.RepostTelemetry;
import sh.repost.client.TelemetryAttempt;
import sh.repost.client.TelemetryAttemptEnd;
import sh.repost.client.TelemetryAttemptStart;
import sh.repost.client.TelemetryOperation;
import sh.repost.client.TelemetryOperationEnd;
import sh.repost.client.TelemetryOperationStart;
import sh.repost.client.TelemetryScope;

/** Live OpenTelemetry bridge with one internal operation span and one child per attempt. */
public final class OpenTelemetryRepostTelemetry implements RepostTelemetry {
    private static final AttributeKey<String> HTTP_METHOD =
            AttributeKey.stringKey("http.request.method");
    private static final AttributeKey<Long> RETRY_ATTEMPT =
            AttributeKey.longKey("repost.retry.attempt");
    private static final AttributeKey<String> NETWORK_PROTOCOL_NAME =
            AttributeKey.stringKey("network.protocol.name");
    private static final AttributeKey<Long> HTTP_STATUS =
            AttributeKey.longKey("http.response.status_code");
    private static final AttributeKey<String> ERROR_TYPE =
            AttributeKey.stringKey("error.type");

    private final Tracer tracer;

    private OpenTelemetryRepostTelemetry(OpenTelemetry openTelemetry) {
        this.tracer = Objects.requireNonNull(openTelemetry, "openTelemetry")
                .tracerBuilder("sh.repost.client")
                .setInstrumentationVersion("1.0.0")
                .build();
    }

    /**
     * Creates a thread-safe bridge over one borrowed provider.
     *
     * @param openTelemetry application-owned OpenTelemetry provider
     * @return new live telemetry bridge
     */
    public static RepostTelemetry create(OpenTelemetry openTelemetry) {
        return new OpenTelemetryRepostTelemetry(openTelemetry);
    }

    @Override
    public CapturedTelemetryContext captureContext() {
        return new CapturedContext(Context.current());
    }

    @Override
    public TelemetryOperation startOperation(
            CapturedTelemetryContext parent,
            TelemetryOperationStart start) {
        CapturedContext captured = requireCaptured(parent);
        Instant startedAt = Objects.requireNonNull(start, "start").getStartedAt();
        Span span = tracer.spanBuilder("repost.send")
                .setSpanKind(SpanKind.INTERNAL)
                .setParent(captured.context)
                .setStartTimestamp(epochNanos(startedAt), TimeUnit.NANOSECONDS)
                .startSpan();
        return new Operation(span, captured.context.with(span), startedAt);
    }

    private static CapturedContext requireCaptured(CapturedTelemetryContext context) {
        if (!(Objects.requireNonNull(context, "parent") instanceof CapturedContext)) {
            throw new IllegalArgumentException("captured telemetry context is not owned by this bridge");
        }
        return (CapturedContext) context;
    }

    private static long epochNanos(Instant instant) {
        try {
            return Math.addExact(
                    Math.multiplyExact(instant.getEpochSecond(), 1_000_000_000L),
                    instant.getNano());
        } catch (ArithmeticException overflow) {
            return instant.isBefore(Instant.EPOCH) ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
    }

    private static long endNanos(Instant start, Duration duration) {
        try {
            return epochNanos(start.plus(duration));
        } catch (RuntimeException overflow) {
            return duration.isNegative() ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
    }

    private static void setError(Span span, ObserverOutcome outcome, Enum<?> errorCode) {
        if (errorCode != null) {
            span.setAttribute(ERROR_TYPE, errorCode.name().toLowerCase(java.util.Locale.ROOT));
        }
        if (outcome != ObserverOutcome.ACCEPTED) {
            span.setStatus(StatusCode.ERROR);
        }
    }

    private final class Operation implements TelemetryOperation {
        private final Span span;
        private final Context context;
        private final Instant startedAt;

        private Operation(Span span, Context context, Instant startedAt) {
            this.span = span;
            this.context = context;
            this.startedAt = startedAt;
        }

        @Override
        public TelemetryScope makeCurrent() {
            Scope scope = context.makeCurrent();
            return scope::close;
        }

        @Override
        public TelemetryAttempt startAttempt(TelemetryAttemptStart start) {
            Objects.requireNonNull(start, "start");
            Span attempt = tracer.spanBuilder("repost.send.attempt")
                    .setSpanKind(SpanKind.INTERNAL)
                    .setParent(context)
                    .setStartTimestamp(epochNanos(start.getStartedAt()), TimeUnit.NANOSECONDS)
                    .startSpan();
            attempt.setAttribute(HTTP_METHOD, "POST");
            attempt.setAttribute(RETRY_ATTEMPT, (long) start.getAttemptNumber());
            attempt.setAttribute(NETWORK_PROTOCOL_NAME, "http");
            return new Attempt(attempt, context.with(attempt), start.getStartedAt());
        }

        @Override
        public void end(TelemetryOperationEnd end) {
            Objects.requireNonNull(end, "end");
            setError(span, end.getOutcome(), end.getErrorCode());
            span.end(endNanos(startedAt, end.getDuration()), TimeUnit.NANOSECONDS);
        }
    }

    private static final class Attempt implements TelemetryAttempt {
        private final Span span;
        private final Context context;
        private final Instant startedAt;

        private Attempt(Span span, Context context, Instant startedAt) {
            this.span = span;
            this.context = context;
            this.startedAt = startedAt;
        }

        @Override
        public TelemetryScope makeCurrent() {
            Scope scope = context.makeCurrent();
            return scope::close;
        }

        @Override
        public void end(TelemetryAttemptEnd end) {
            Objects.requireNonNull(end, "end");
            if (end.getHttpStatusClass() == HttpStatusClass.SUCCESS) {
                // The Repost send protocol accepts with HTTP 202. Other status classes are
                // deliberately not guessed from their bounded class-only telemetry record.
                span.setAttribute(HTTP_STATUS, 202L);
            }
            setError(span, end.getOutcome(), end.getErrorCode());
            span.end(endNanos(startedAt, end.getDuration()), TimeUnit.NANOSECONDS);
        }
    }

    private static final class CapturedContext implements CapturedTelemetryContext {
        private final Context context;

        private CapturedContext(Context context) {
            this.context = Objects.requireNonNull(context, "context");
        }
    }
}
