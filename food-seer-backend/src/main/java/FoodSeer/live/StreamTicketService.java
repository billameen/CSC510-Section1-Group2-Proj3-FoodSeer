package FoodSeer.live;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Short-lived, single-use tickets for opening the live stream. The browser's
 * EventSource can't send an Authorization header, and putting the JWT in the
 * URL would leak a 7-day credential into logs and history. So a signed-in
 * client trades its JWT for a ticket that works once, for 30 seconds, for that
 * user only.
 */
@Service
public class StreamTicketService {

    /** A redeemed or expired ticket can't be used again. */
    private record Ticket(String username, Instant expiresAt) {
    }

    private final SecureRandom        random  = new SecureRandom();
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
    private final Clock               clock;
    private final long                ttlSeconds;

    /**
     * @param ttlSeconds
     *            how long a ticket is valid
     */
    @Autowired
    public StreamTicketService ( @Value ( "${app.stream.ticket-ttl-seconds:30}" ) final long ttlSeconds ) {
        this( Clock.systemUTC(), ttlSeconds );
    }

    /**
     * @param clock
     *            time source (tests move it forward)
     * @param ttlSeconds
     *            how long a ticket is valid
     */
    public StreamTicketService ( final Clock clock, final long ttlSeconds ) {
        this.clock = clock;
        this.ttlSeconds = ttlSeconds;
    }

    /**
     * @param username
     *            the signed-in user
     * @return a new ticket for them
     */
    public String issue ( final String username ) {
        final Instant now = clock.instant();
        tickets.values().removeIf( t -> !t.expiresAt().isAfter( now ) );
        final byte[] bytes = new byte[32];
        random.nextBytes( bytes );
        final String token = Base64.getUrlEncoder().withoutPadding().encodeToString( bytes );
        tickets.put( token, new Ticket( username, now.plusSeconds( ttlSeconds ) ) );
        return token;
    }

    /**
     * Uses up a ticket.
     *
     * @param token
     *            the ticket
     * @return whose it was, or empty if it is unknown, used or expired
     */
    public Optional<String> redeem ( final String token ) {
        if ( token == null || token.isBlank() ) {
            return Optional.empty();
        }
        final Ticket ticket = tickets.remove( token );
        if ( ticket == null || !ticket.expiresAt().isAfter( clock.instant() ) ) {
            return Optional.empty();
        }
        return Optional.of( ticket.username() );
    }

    /**
     * @return seconds a new ticket stays valid
     */
    public long getTtlSeconds () {
        return ttlSeconds;
    }
}
