package sh.repost.client.spring;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.ToDoubleFunction;
import sh.repost.client.RepostRuntime;
import sh.repost.client.RuntimeDiagnostics;

/** Micrometer gauges containing only bounded runtime diagnostic counters. */
public final class RepostRuntimeMetrics implements MeterBinder, AutoCloseable {
    private final RepostRuntime runtime;
    private final List<RegisteredMeter> meters = new ArrayList<>();

    /**
     * Creates diagnostics gauges for one borrowed runtime.
     *
     * @param runtime configured runtime
     */
    public RepostRuntimeMetrics(RepostRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    @Override
    public synchronized void bindTo(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        if (!meters.isEmpty()) {
            return;
        }
        gauge(registry, "repost.client.runtime.in.flight", "operations",
                diagnostics -> diagnostics.getInFlightOperations());
        gauge(registry, "repost.client.runtime.buffered", "bytes",
                RuntimeDiagnostics::getBufferedBytes);
        gauge(registry, "repost.client.runtime.rejections.concurrency", "rejections",
                RuntimeDiagnostics::getConcurrencyOverloadRejections);
        gauge(registry, "repost.client.runtime.rejections.request.bytes", "rejections",
                RuntimeDiagnostics::getRequestByteOverloadRejections);
        gauge(registry, "repost.client.runtime.rejections.response.bytes", "rejections",
                RuntimeDiagnostics::getResponseByteOverloadRejections);
        gauge(registry, "repost.client.runtime.failures.response.headers", "failures",
                RuntimeDiagnostics::getResponseHeaderLimitFailures);
        gauge(registry, "repost.client.runtime.rejections.executor", "rejections",
                RuntimeDiagnostics::getExecutorOverloadRejections);
        gauge(registry, "repost.client.runtime.rejections.scheduler", "rejections",
                RuntimeDiagnostics::getSchedulerOverloadRejections);
        gauge(registry, "repost.client.runtime.failures.response.close", "failures",
                RuntimeDiagnostics::getResponseCloseFailures);
        gauge(registry, "repost.client.runtime.observer.dropped", "events",
                RuntimeDiagnostics::getDroppedObserverEvents);
        gauge(registry, "repost.client.runtime.observer.failures", "failures",
                RuntimeDiagnostics::getObserverFailures);
        gauge(registry, "repost.client.runtime.telemetry.failures", "failures",
                RuntimeDiagnostics::getTelemetryFailures);
        gauge(registry, "repost.client.runtime.closed", null,
                diagnostics -> diagnostics.isClosed() ? 1.0 : 0.0);
    }

    private void gauge(
            MeterRegistry registry,
            String name,
            String baseUnit,
            ToDoubleFunction<RuntimeDiagnostics> value) {
        Gauge.Builder<RepostRuntime> builder = Gauge.builder(
                        name, runtime, current -> value.applyAsDouble(current.diagnostics()))
                .description("Redacted Repost runtime diagnostic");
        if (baseUnit != null) {
            builder.baseUnit(baseUnit);
        }
        meters.add(new RegisteredMeter(registry, builder.register(registry)));
    }

    @Override
    public synchronized void close() {
        for (RegisteredMeter registered : meters) {
            registered.registry().remove(registered.meter());
        }
        meters.clear();
    }

    private record RegisteredMeter(MeterRegistry registry, Meter meter) { }
}
