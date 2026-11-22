package FoodSeer.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class StreamTicketServiceTest {

    /** A clock the test moves by hand. */
    static class ManualClock extends Clock {
        Instant now = Instant.parse( "2026-11-22T12:00:00Z" );

        @Override
        public Instant instant () {
            return now;
        }

        @Override
        public ZoneOffset getZone () {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone ( final java.time.ZoneId zone ) {
            return this;
        }
    }

    private final ManualClock         clock   = new ManualClock();
    private final StreamTicketService tickets = new StreamTicketService( clock, 30 );

    @Test
    void aTicketWorksOnceForItsOwner () {
        final String ticket = tickets.issue( "alice" );

        assertEquals( Optional.of( "alice" ), tickets.redeem( ticket ) );
        assertEquals( Optional.empty(), tickets.redeem( ticket ), "a ticket can't be reused" );
    }

    @Test
    void aTicketExpiresAfterItsLifetime () {
        final String early = tickets.issue( "alice" );
        final String late = tickets.issue( "alice" );

        clock.now = clock.now.plusSeconds( 29 );
        assertEquals( Optional.of( "alice" ), tickets.redeem( early ) );
        clock.now = clock.now.plusSeconds( 1 );
        assertEquals( Optional.empty(), tickets.redeem( late ), "30 seconds is too late" );
    }

    @Test
    void missingAndMadeUpTicketsAreRejected () {
        assertEquals( Optional.empty(), tickets.redeem( null ) );
        assertEquals( Optional.empty(), tickets.redeem( " " ) );
        assertEquals( Optional.empty(), tickets.redeem( "not-a-ticket" ) );
    }

    @Test
    void ticketsAreLongRandomAndDistinct () {
        final String a = tickets.issue( "alice" );
        final String b = tickets.issue( "alice" );

        assertNotEquals( a, b );
        assertTrue( a.length() >= 43, "256 bits, URL-safe base64" );
        assertTrue( a.matches( "[A-Za-z0-9_-]+" ) );
    }

    @Test
    void reportsHowLongTicketsLast () {
        assertEquals( 30, tickets.getTtlSeconds() );
    }
}
