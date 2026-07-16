package sh.repost.client;

/** Live, non-droppable context and span lifecycle integration. */
public interface RepostTelemetry {
    /**
     * Returns an opaque snapshot of the caller telemetry context.
     *
     * @return an opaque snapshot of the caller telemetry context
     */
    CapturedTelemetryContext captureContext();

    /**
     * Starts telemetry for an admitted operation.
     *
     * @param parent captured caller context
     * @param start immutable operation start metadata
     * @return operation telemetry handle
     */
    TelemetryOperation startOperation(
            CapturedTelemetryContext parent,
            TelemetryOperationStart start);
}
