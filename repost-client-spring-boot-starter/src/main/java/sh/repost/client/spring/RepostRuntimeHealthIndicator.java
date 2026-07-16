package sh.repost.client.spring;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import sh.repost.client.RepostRuntime;
import sh.repost.client.RuntimeDiagnostics;

/** Actuator health contribution containing only bounded runtime diagnostics. */
public final class RepostRuntimeHealthIndicator implements HealthIndicator {
    private final RepostRuntime runtime;

    /**
     * Creates a health contribution for one borrowed runtime.
     *
     * @param runtime configured runtime
     */
    public RepostRuntimeHealthIndicator(RepostRuntime runtime) {
        this.runtime = java.util.Objects.requireNonNull(runtime, "runtime");
    }

    @Override
    public Health health() {
        RuntimeDiagnostics diagnostics = runtime.diagnostics();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("inFlightOperations", diagnostics.getInFlightOperations());
        details.put("bufferedBytes", diagnostics.getBufferedBytes());
        details.put("concurrencyOverloadRejections", diagnostics.getConcurrencyOverloadRejections());
        details.put("requestByteOverloadRejections", diagnostics.getRequestByteOverloadRejections());
        details.put("responseByteOverloadRejections", diagnostics.getResponseByteOverloadRejections());
        details.put("responseHeaderLimitFailures", diagnostics.getResponseHeaderLimitFailures());
        details.put("executorOverloadRejections", diagnostics.getExecutorOverloadRejections());
        details.put("schedulerOverloadRejections", diagnostics.getSchedulerOverloadRejections());
        details.put("responseCloseFailures", diagnostics.getResponseCloseFailures());
        details.put("droppedObserverEvents", diagnostics.getDroppedObserverEvents());
        details.put("observerFailures", diagnostics.getObserverFailures());
        details.put("telemetryFailures", diagnostics.getTelemetryFailures());
        details.put("closed", diagnostics.isClosed());
        return (diagnostics.isClosed() ? Health.outOfService() : Health.up())
                .withDetails(details)
                .build();
    }
}
