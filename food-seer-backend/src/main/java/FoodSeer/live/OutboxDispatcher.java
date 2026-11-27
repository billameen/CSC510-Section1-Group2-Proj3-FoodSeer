package FoodSeer.live;

import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import FoodSeer.entity.OutboxEvent;
import FoodSeer.repositories.OutboxEventRepository;

/**
 * Sends committed outbox messages to the live stream, oldest first, and
 * stamps each one as sent. Delivery is at least once: if sending fails the
 * row stays unsent and the next run retries it, so a message can arrive twice
 * but is never lost. Clients drop repeats by event id.
 */
@Component
public class OutboxDispatcher {

    private static final Logger         LOG = LoggerFactory.getLogger( OutboxDispatcher.class );

    private final OutboxEventRepository outbox;
    private final List<OutboxSink>      sinks;
    private final long                  retentionHours;

    /**
     * @param outbox
     *            outbox repository
     * @param sinks
     *            where messages go (the live stream)
     * @param retentionHours
     *            how long sent messages are kept for clients resuming a
     *            stream
     */
    public OutboxDispatcher ( final OutboxEventRepository outbox, final List<OutboxSink> sinks,
            @Value ( "${app.outbox.retention-hours:24}" ) final long retentionHours ) {
        this.outbox = outbox;
        this.sinks = sinks;
        this.retentionHours = retentionHours;
    }

    /** Runs every 200 ms by default (app.outbox.dispatch-interval-ms). */
    @Scheduled ( fixedDelayString = "${app.outbox.dispatch-interval-ms:200}" )
    public void scheduledDispatch () {
        dispatch();
    }

    /**
     * Sends everything waiting, batch by batch.
     *
     * @return how many messages were sent
     */
    public int dispatchAll () {
        int total = 0;
        int sent;
        do {
            sent = dispatch();
            total += sent;
        } while ( sent > 0 );
        return total;
    }

    /**
     * Sends the next batch. Stops at the first failure so messages never go
     * out of order.
     *
     * @return how many messages were sent
     */
    public synchronized int dispatch () {
        int sent = 0;
        for ( final OutboxEvent event : outbox.findTop200ByPublishedAtIsNullOrderByIdAsc() ) {
            try {
                for ( final OutboxSink sink : sinks ) {
                    sink.publish( event );
                }
            }
            catch ( final RuntimeException e ) {
                LOG.warn( "Couldn't send outbox message {}; will retry: {}", event.getId(), e.getMessage() );
                break;
            }
            final LocalDateTime now = LocalDateTime.now();
            event.setPublishedAt( now );
            outbox.markPublished( event.getId(), now );
            sent++;
        }
        return sent;
    }

    /**
     * Hourly: forget sent messages older than the resume window.
     *
     * @return how many were removed
     */
    @Scheduled ( fixedDelayString = "${app.outbox.purge-interval-ms:3600000}" )
    public int purge () {
        final int removed = outbox.deletePublishedBefore( LocalDateTime.now().minusHours( retentionHours ) );
        LOG.debug( "Removed {} sent outbox messages older than {} h.", removed, retentionHours );
        return removed;
    }
}
