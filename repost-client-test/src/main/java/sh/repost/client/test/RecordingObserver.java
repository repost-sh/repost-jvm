package sh.repost.client.test;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import sh.repost.client.ObserverEvent;
import sh.repost.client.RepostObserver;

/** Thread-safe observer recorder with deterministic event-by-event quiescence control. */
public final class RecordingObserver implements RepostObserver {
    private final CopyOnWriteArrayList<ObserverEvent> events = new CopyOnWriteArrayList<>();
    private final LinkedBlockingQueue<ObserverEvent> unread = new LinkedBlockingQueue<>();

    /** Creates an empty recorder. */
    public RecordingObserver() { }

    @Override
    public void onEvent(ObserverEvent event) {
        ObserverEvent captured = Objects.requireNonNull(event, "event");
        events.add(captured);
        unread.add(captured);
    }

    /**
     * Returns an immutable event snapshot in callback order.
     *
     * @return captured event snapshot
     */
    public List<ObserverEvent> getEvents() {
        return List.copyOf(events);
    }

    /**
     * Waits for and consumes the next unread event.
     *
     * @param timeout positive maximum wait
     * @return next event in callback order
     * @throws IllegalStateException when the timeout elapses or the thread is interrupted
     */
    public ObserverEvent awaitNext(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        try {
            ObserverEvent event = unread.poll(timeout.toNanos(), TimeUnit.NANOSECONDS);
            if (event == null) {
                throw new IllegalStateException("RecordingObserver timed out waiting for an event");
            }
            return event;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "RecordingObserver interrupted while waiting for an event", interrupted);
        }
    }

    /** Clears captured and unread events. */
    public void clear() {
        events.clear();
        unread.clear();
    }
}
