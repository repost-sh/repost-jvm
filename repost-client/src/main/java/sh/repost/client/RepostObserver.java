package sh.repost.client;

/** Receives bounded, credential-free runtime events outside send execution threads. */
@FunctionalInterface
public interface RepostObserver {
    /**
     * Consumes one immutable event.
     *
     * @param event runtime event
     */
    void onEvent(ObserverEvent event);
}
