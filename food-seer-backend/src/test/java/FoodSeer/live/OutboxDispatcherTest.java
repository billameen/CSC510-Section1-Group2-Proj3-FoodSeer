package FoodSeer.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import FoodSeer.entity.OutboxEvent;
import FoodSeer.repositories.OutboxEventRepository;

@ExtendWith ( MockitoExtension.class )
class OutboxDispatcherTest {

    @Mock
    private OutboxEventRepository outbox;

    /** Records what it was sent; can be told to fail the next few sends. */
    static class FlakySink implements OutboxSink {
        final List<String> received = new ArrayList<>();
        int                failuresLeft;

        @Override
        public void publish ( final OutboxEvent event ) {
            if ( failuresLeft > 0 ) {
                failuresLeft--;
                throw new IllegalStateException( "stream down" );
            }
            received.add( event.getType() );
        }
    }

    private FlakySink        sink;
    private OutboxDispatcher dispatcher;
    private List<OutboxEvent> unsent;

    @BeforeEach
    void setUp () {
        sink = new FlakySink();
        dispatcher = new OutboxDispatcher( outbox, List.of( sink ), 24 );
        unsent = new ArrayList<>( List.of( event( "a" ), event( "b" ), event( "c" ) ) );
        // The repository returns whatever is still unsent, like the real query.
        org.mockito.Mockito.lenient().when( outbox.findTop200ByPublishedAtIsNullOrderByIdAsc() )
                .thenAnswer( inv -> unsent.stream().filter( e -> e.getPublishedAt() == null ).toList() );
    }

    private static OutboxEvent event ( final String type ) {
        return new OutboxEvent( type, "{}", 1L, List.of( "customer" ), List.of() );
    }

    @Test
    void sendsInOrderAndStampsEachMessage () {
        assertEquals( 3, dispatcher.dispatch() );

        assertEquals( List.of( "a", "b", "c" ), sink.received );
        unsent.forEach( e -> assertNotNull( e.getPublishedAt() ) );
        assertEquals( 0, dispatcher.dispatch(), "nothing left to send" );
    }

    /** Acceptance criterion: a failure after commit and before publish delays the message, never loses it. */
    @Test
    void aFailedSendIsRetriedOnTheNextRunAndNothingIsLost () {
        sink.failuresLeft = 1;

        assertEquals( 0, dispatcher.dispatch() );
        assertNull( unsent.get( 0 ).getPublishedAt(), "still waiting" );
        assertTrue( sink.received.isEmpty(), "later messages wait too, so order is kept" );
        verify( outbox, never() ).markPublished( any(), any() );

        assertEquals( 3, dispatcher.dispatch() );
        assertEquals( List.of( "a", "b", "c" ), sink.received );
    }

    @Test
    void aFailureMidBatchKeepsTheRestInOrder () {
        // a goes out, b fails, so c must not overtake it.
        final OutboxSink failOnB = e -> {
            if ( "b".equals( e.getType() ) && sink.failuresLeft-- > 0 ) {
                throw new IllegalStateException( "hiccup" );
            }
            sink.received.add( e.getType() );
        };
        sink.failuresLeft = 1;
        dispatcher = new OutboxDispatcher( outbox, List.of( failOnB ), 24 );

        assertEquals( 1, dispatcher.dispatch() );
        assertEquals( List.of( "a" ), sink.received );
        assertEquals( 2, dispatcher.dispatch() );
        assertEquals( List.of( "a", "b", "c" ), sink.received );
    }

    @Test
    void purgeForgetsSentMessagesOlderThanTheResumeWindow () {
        final LocalDateTime before = LocalDateTime.now().minusHours( 24 );

        dispatcher.purge();

        final ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass( LocalDateTime.class );
        verify( outbox ).deletePublishedBefore( cutoff.capture() );
        assertTrue( !cutoff.getValue().isBefore( before ) && cutoff.getValue().isBefore( before.plusMinutes( 1 ) ) );
    }
}
