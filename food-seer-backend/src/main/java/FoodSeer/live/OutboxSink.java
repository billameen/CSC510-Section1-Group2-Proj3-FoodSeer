package FoodSeer.live;

import FoodSeer.entity.OutboxEvent;

/**
 * Somewhere the dispatcher delivers outbox messages, such as the live
 * stream. Throwing leaves the message unsent so the next run retries it.
 */
public interface OutboxSink {

    /**
     * @param event
     *            a committed message
     */
    void publish ( OutboxEvent event );
}
