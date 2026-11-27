package FoodSeer.live;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import FoodSeer.entity.OutboxEvent;
import FoodSeer.repositories.OutboxEventRepository;

/**
 * Holds the open live streams and sends each outbox message to the users it
 * is for. Assumes a single backend instance; several would need a shared
 * broker.
 *
 * Resuming: a client that reconnects with the id of the last event it saw gets
 * everything sent since, replayed from the outbox. Ids are handed out at
 * insert but transactions can commit in a different order, so the replay
 * starts {@value #REPLAY_OVERLAP} ids early; anything the client already has
 * comes again and it drops it by id. While the replay runs, live messages for
 * that client wait in a queue, so nothing slips between the two.
 */
@Component
public class StreamBroker implements OutboxSink {

    /** How far back a resume starts, to cover out-of-order commits. */
    static final int                        REPLAY_OVERLAP = 50;

    private static final Logger             LOG            = LoggerFactory.getLogger( StreamBroker.class );

    private final Map<String, Subscriber>   subscribers    = new ConcurrentHashMap<>();
    private final OutboxEventRepository     outbox;
    private final int                       maxPerUser;
    private final long                      timeoutMs;

    /**
     * @param outbox
     *            outbox repository, for replays
     * @param maxPerUser
     *            open streams allowed per user
     * @param timeoutMs
     *            how long a stream stays open before the client must reconnect
     */
    public StreamBroker ( final OutboxEventRepository outbox,
            @Value ( "${app.stream.max-per-user:3}" ) final int maxPerUser,
            @Value ( "${app.stream.timeout-ms:1800000}" ) final long timeoutMs ) {
        this.outbox = outbox;
        this.maxPerUser = maxPerUser;
        this.timeoutMs = timeoutMs;
    }

    /**
     * Opens a stream.
     *
     * @param username
     *            who it is for
     * @param role
     *            their role without the ROLE_ prefix
     * @param lastEventId
     *            the last event id the client saw, or null for a fresh start
     * @return the stream
     * @throws TooManyStreamsException
     *             if the user already has the maximum open
     */
    public SseEmitter open ( final String username, final String role, final Long lastEventId ) {
        synchronized ( this ) {
            final long open = subscribers.values().stream().filter( s -> s.username.equals( username ) ).count();
            if ( open >= maxPerUser ) {
                throw new TooManyStreamsException( maxPerUser );
            }
            final Subscriber subscriber = new Subscriber( username, role, new SseEmitter( timeoutMs ) );
            subscribers.put( subscriber.id, subscriber );
            subscriber.emitter.onCompletion( () -> subscribers.remove( subscriber.id ) );
            subscriber.emitter.onTimeout( () -> subscribers.remove( subscriber.id ) );
            subscriber.emitter.onError( e -> subscribers.remove( subscriber.id ) );
            return start( subscriber, lastEventId );
        }
    }

    private SseEmitter start ( final Subscriber subscriber, final Long lastEventId ) {
        try {
            subscriber.hello();
            if ( lastEventId != null ) {
                long after = Math.max( 0, lastEventId - REPLAY_OVERLAP );
                List<OutboxEvent> page;
                do {
                    page = outbox.findTop500ByIdGreaterThanAndPublishedAtIsNotNullOrderByIdAsc( after );
                    for ( final OutboxEvent event : page ) {
                        if ( event.isFor( subscriber.username, subscriber.role ) ) {
                            subscriber.replay( event );
                        }
                        after = event.getId();
                    }
                } while ( page.size() == 500 );
            }
            subscriber.goLive();
        }
        catch ( final IOException | IllegalStateException e ) {
            drop( subscriber, e );
        }
        return subscriber.emitter;
    }

    /** Called by the dispatcher for each committed message, in id order. */
    @Override
    public void publish ( final OutboxEvent event ) {
        for ( final Subscriber subscriber : subscribers.values() ) {
            if ( event.isFor( subscriber.username, subscriber.role ) ) {
                try {
                    subscriber.deliver( event );
                }
                catch ( final IOException | IllegalStateException e ) {
                    // A dead connection is the client's problem, not the
                    // outbox's: it resumes from its last event id.
                    drop( subscriber, e );
                }
            }
        }
    }

    /** Keeps idle streams from being closed by proxies (every 15 s by default). */
    @Scheduled ( fixedRateString = "${app.stream.heartbeat-ms:15000}" )
    public void heartbeat () {
        for ( final Subscriber subscriber : subscribers.values() ) {
            try {
                subscriber.ping();
            }
            catch ( final IOException | IllegalStateException e ) {
                drop( subscriber, e );
            }
        }
    }

    /**
     * @return open streams right now
     */
    public int openStreams () {
        return subscribers.size();
    }

    private void drop ( final Subscriber subscriber, final Exception cause ) {
        subscribers.remove( subscriber.id );
        LOG.debug( "Closed the live stream for {}: {}", subscriber.username, cause.getMessage() );
        try {
            subscriber.emitter.complete();
        }
        catch ( final RuntimeException ignored ) {
            // Already closed.
        }
    }

    /** One open stream. Sends are serialised per stream. */
    private static final class Subscriber {
        private final String             id     = UUID.randomUUID().toString();
        private final String             username;
        private final String             role;
        private final SseEmitter         emitter;
        private final Deque<OutboxEvent> queued = new ArrayDeque<>();
        /** Ids already sent on this stream, so a replayed message isn't sent twice. */
        private final Set<Long>          sent   = Collections.newSetFromMap( new LinkedHashMap<>() {
                                                    private static final long serialVersionUID = 1L;

                                                    @Override
                                                    protected boolean removeEldestEntry (
                                                            final Map.Entry<Long, Boolean> eldest ) {
                                                        return size() > 2000;
                                                    }
                                                } );
        private boolean                  live;

        Subscriber ( final String username, final String role, final SseEmitter emitter ) {
            this.username = username;
            this.role = role;
            this.emitter = emitter;
        }

        synchronized void hello () throws IOException {
            emitter.send( SseEmitter.event().name( "ready" ).reconnectTime( 3000 ).data( "{\"connected\":true}",
                    MediaType.APPLICATION_JSON ) );
        }

        synchronized void replay ( final OutboxEvent event ) throws IOException {
            send( event );
        }

        synchronized void goLive () throws IOException {
            live = true;
            while ( !queued.isEmpty() ) {
                send( queued.poll() );
            }
        }

        synchronized void deliver ( final OutboxEvent event ) throws IOException {
            if ( live ) {
                send( event );
            }
            else {
                queued.add( event );
            }
        }

        synchronized void ping () throws IOException {
            emitter.send( SseEmitter.event().comment( "keep-alive" ) );
        }

        private void send ( final OutboxEvent event ) throws IOException {
            if ( sent.add( event.getId() ) ) {
                emitter.send( SseEmitter.event().id( String.valueOf( event.getId() ) ).name( event.getType() )
                        .data( event.getPayload(), MediaType.APPLICATION_JSON ) );
            }
        }
    }
}
