package sh.repost.client;

/** Live telemetry handle for one admitted operation. */
public interface TelemetryOperation {
    /**
     * Returns a scope that makes this operation current until closed.
     *
     * @return a scope that makes this operation current until closed
     */
    TelemetryScope makeCurrent();

    /**
     * Starts one transport-attempt telemetry handle.
     *
     * @param start immutable attempt start metadata
     * @return attempt telemetry handle
     */
    TelemetryAttempt startAttempt(TelemetryAttemptStart start);

    /**
     * Ends the operation exactly once.
     *
     * @param end immutable terminal operation metadata
     */
    void end(TelemetryOperationEnd end);
}
