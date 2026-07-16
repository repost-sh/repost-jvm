package sh.repost.client;

/** Live telemetry handle for one transport attempt. */
public interface TelemetryAttempt {
    /**
     * Returns a scope that makes this attempt current until closed.
     *
     * @return a scope that makes this attempt current until closed
     */
    TelemetryScope makeCurrent();

    /**
     * Ends the attempt exactly once.
     *
     * @param end immutable terminal attempt metadata
     */
    void end(TelemetryAttemptEnd end);
}
