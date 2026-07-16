package sh.repost.client;

/** Restores the previously current telemetry context when closed. */
public interface TelemetryScope extends AutoCloseable {
    @Override
    void close();
}
