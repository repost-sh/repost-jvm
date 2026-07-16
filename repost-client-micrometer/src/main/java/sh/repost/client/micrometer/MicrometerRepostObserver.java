package sh.repost.client.micrometer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Locale;
import java.util.Objects;
import sh.repost.client.AttemptSummary;
import sh.repost.client.ObserverEvent;
import sh.repost.client.ObserverEventKind;
import sh.repost.client.RepostObserver;

/** Stateless Micrometer bridge derived only from bounded terminal observer records. */
public final class MicrometerRepostObserver implements RepostObserver {
    private final MeterRegistry registry;

    private MicrometerRepostObserver(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * Creates a thread-safe bridge over one borrowed registry.
     *
     * @param registry application-owned registry
     * @return new observer bridge
     */
    public static RepostObserver create(MeterRegistry registry) {
        return new MicrometerRepostObserver(registry);
    }

    @Override
    public void onEvent(ObserverEvent event) {
        Objects.requireNonNull(event, "event");
        if (event.getKind() == ObserverEventKind.RETRY_DELAY) {
            Timer.builder("repost.client.retry.delay")
                    .description("Scheduled Repost client retry delay")
                    .tags(tags(
                            "retryable_failure",
                            value(event.getErrorCode()),
                            value(event.getDeliveryState()),
                            value(event.getHttpStatusClass())))
                    .register(registry)
                    .record(Objects.requireNonNull(event.getRetryDelay(), "retry delay"));
            return;
        }
        if (event.getKind() != ObserverEventKind.OPERATION_END) {
            return;
        }
        String[] operationTags = tags(
                value(event.getOutcome()),
                value(event.getErrorCode()),
                value(event.getDeliveryState()),
                value(event.getHttpStatusClass()));
        Counter.builder("repost.client.operations")
                .description("Completed Repost client operations")
                .baseUnit("operations")
                .tags(operationTags)
                .register(registry)
                .increment();
        Timer.builder("repost.client.operation.duration")
                .description("Repost client operation duration")
                .tags(operationTags)
                .register(registry)
                .record(Objects.requireNonNull(event.getDuration(), "terminal duration"));

        for (AttemptSummary attempt : event.getAttemptSummaries()) {
            String[] attemptTags = tags(
                    value(attempt.getOutcome()),
                    value(attempt.getErrorCode()),
                    value(attempt.getDeliveryState()),
                    value(attempt.getHttpStatusClass()));
            Counter.builder("repost.client.attempts")
                    .description("Completed Repost client transport attempts")
                    .baseUnit("attempts")
                    .tags(attemptTags)
                    .register(registry)
                    .increment();
            Timer.builder("repost.client.attempt.duration")
                    .description("Repost client transport-attempt duration")
                    .tags(attemptTags)
                    .register(registry)
                    .record(attempt.getDuration());
        }
    }

    private static String[] tags(
            String outcome,
            String errorCode,
            String deliveryState,
            String httpStatusClass) {
        return new String[] {
            "outcome", outcome,
            "error.code", errorCode,
            "delivery.state", deliveryState,
            "http.status.class", httpStatusClass
        };
    }

    private static String value(Enum<?> value) {
        return value == null ? "none" : value.name().toLowerCase(Locale.ROOT);
    }
}
