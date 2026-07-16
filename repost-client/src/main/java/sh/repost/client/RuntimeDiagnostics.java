package sh.repost.client;

/** Immutable credential- and payload-free runtime diagnostics snapshot. */
public final class RuntimeDiagnostics {
    /** Largest exactly portable counter value across supported language SDKs. */
    public static final long MAX_COUNTER_VALUE = 9_007_199_254_740_991L;

    private final int inFlightOperations;
    private final long bufferedBytes;
    private final long concurrencyOverloadRejections;
    private final long requestByteOverloadRejections;
    private final long responseByteOverloadRejections;
    private final long responseHeaderLimitFailures;
    private final long executorOverloadRejections;
    private final long schedulerOverloadRejections;
    private final long responseCloseFailures;
    private final long droppedObserverEvents;
    private final long observerFailures;
    private final long telemetryFailures;
    private final boolean closed;

    RuntimeDiagnostics(
            int inFlightOperations,
            long bufferedBytes,
            long concurrencyOverloadRejections,
            long requestByteOverloadRejections,
            long responseByteOverloadRejections,
            long responseHeaderLimitFailures,
            long executorOverloadRejections,
            long schedulerOverloadRejections,
            long responseCloseFailures,
            long droppedObserverEvents,
            long observerFailures,
            long telemetryFailures,
            boolean closed) {
        this.inFlightOperations = requireNonnegative(inFlightOperations, "inFlightOperations");
        this.bufferedBytes = requireCounter(bufferedBytes, "bufferedBytes");
        this.concurrencyOverloadRejections = requireCounter(
                concurrencyOverloadRejections, "concurrencyOverloadRejections");
        this.requestByteOverloadRejections = requireCounter(
                requestByteOverloadRejections, "requestByteOverloadRejections");
        this.responseByteOverloadRejections = requireCounter(
                responseByteOverloadRejections, "responseByteOverloadRejections");
        this.responseHeaderLimitFailures = requireCounter(
                responseHeaderLimitFailures, "responseHeaderLimitFailures");
        this.executorOverloadRejections = requireCounter(
                executorOverloadRejections, "executorOverloadRejections");
        this.schedulerOverloadRejections = requireCounter(
                schedulerOverloadRejections, "schedulerOverloadRejections");
        this.responseCloseFailures = requireCounter(responseCloseFailures, "responseCloseFailures");
        this.droppedObserverEvents = requireCounter(droppedObserverEvents, "droppedObserverEvents");
        this.observerFailures = requireCounter(observerFailures, "observerFailures");
        this.telemetryFailures = requireCounter(telemetryFailures, "telemetryFailures");
        if (closed && (inFlightOperations != 0 || bufferedBytes != 0L)) {
            throw new IllegalArgumentException("closed diagnostics must have zero live resources");
        }
        this.closed = closed;
    }

    /**
     * Returns currently admitted operations.
     *
     * @return currently admitted operations
     */
    public int getInFlightOperations() { return inFlightOperations; }
    /**
     * Returns bytes currently reserved by admitted operations.
     *
     * @return bytes currently reserved by admitted operations
     */
    public long getBufferedBytes() { return bufferedBytes; }
    /**
     * Returns saturated concurrency-admission rejection count.
     *
     * @return saturated concurrency-admission rejection count
     */
    public long getConcurrencyOverloadRejections() { return concurrencyOverloadRejections; }
    /**
     * Returns saturated request-byte admission rejection count.
     *
     * @return saturated request-byte admission rejection count
     */
    public long getRequestByteOverloadRejections() { return requestByteOverloadRejections; }
    /**
     * Returns saturated response-byte admission rejection count.
     *
     * @return saturated response-byte admission rejection count
     */
    public long getResponseByteOverloadRejections() { return responseByteOverloadRejections; }
    /**
     * Returns saturated response-header limit failure count.
     *
     * @return saturated response-header limit failure count
     */
    public long getResponseHeaderLimitFailures() { return responseHeaderLimitFailures; }
    /**
     * Returns saturated operation-executor rejection count.
     *
     * @return saturated operation-executor rejection count
     */
    public long getExecutorOverloadRejections() { return executorOverloadRejections; }
    /**
     * Returns saturated scheduler rejection count.
     *
     * @return saturated scheduler rejection count
     */
    public long getSchedulerOverloadRejections() { return schedulerOverloadRejections; }
    /**
     * Returns saturated response-body close failure count.
     *
     * @return saturated response-body close failure count
     */
    public long getResponseCloseFailures() { return responseCloseFailures; }
    /**
     * Returns saturated observer event drop count.
     *
     * @return saturated observer event drop count
     */
    public long getDroppedObserverEvents() { return droppedObserverEvents; }
    /**
     * Returns saturated observer callback failure count.
     *
     * @return saturated observer callback failure count
     */
    public long getObserverFailures() { return observerFailures; }
    /**
     * Returns saturated live telemetry callback failure count.
     *
     * @return saturated live telemetry callback failure count
     */
    public long getTelemetryFailures() { return telemetryFailures; }
    /**
     * Returns whether the runtime is closed.
     *
     * @return whether the runtime is closed
     */
    public boolean isClosed() { return closed; }

    @Override
    public String toString() {
        return "RuntimeDiagnostics[inFlightOperations=" + inFlightOperations
                + ", bufferedBytes=" + bufferedBytes
                + ", closed=" + closed + "]";
    }

    private static int requireNonnegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    private static long requireCounter(long value, String name) {
        if (value < 0L || value > MAX_COUNTER_VALUE) {
            throw new IllegalArgumentException(name + " is outside the portable counter range");
        }
        return value;
    }
}
