package FoodSeer.controller;

import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import FoodSeer.constant.UserRole;
import FoodSeer.entity.User;
import FoodSeer.live.StreamBroker;
import FoodSeer.live.StreamTicketService;
import FoodSeer.live.TooManyStreamsException;
import FoodSeer.repositories.UserRepository;

/**
 * Live updates over Server-Sent Events. A signed-in client first gets a
 * one-time ticket with its JWT, then opens the stream with the ticket, since
 * EventSource can't send headers. See docs/live-updates.md.
 */
@CrossOrigin ( "*" )
@RestController
@RequestMapping ( "/api/stream" )
public class StreamController {

    private final StreamTicketService tickets;
    private final StreamBroker        broker;
    private final UserRepository      users;

    /**
     * @param tickets
     *            issues and redeems stream tickets
     * @param broker
     *            the open streams
     * @param users
     *            user repository
     */
    public StreamController ( final StreamTicketService tickets, final StreamBroker broker,
            final UserRepository users ) {
        this.tickets = tickets;
        this.broker = broker;
        this.users = users;
    }

    /**
     * Trades the caller's JWT for a single-use stream ticket.
     *
     * @param authentication
     *            the signed-in user
     * @return the ticket and how long it is valid
     */
    @PreAuthorize ( "isAuthenticated()" )
    @PostMapping ( "/ticket" )
    public Map<String, Object> ticket ( final Authentication authentication ) {
        return Map.of( "ticket", tickets.issue( authentication.getName() ), "expiresInSeconds",
                tickets.getTtlSeconds() );
    }

    /**
     * Opens the live stream. Open to anonymous requests at the filter level;
     * the ticket is the credential.
     *
     * @param ticket
     *            a ticket from POST /api/stream/ticket
     * @param lastEventIdParam
     *            the last event id seen, when reconnecting by hand
     * @param lastEventIdHeader
     *            the same, as EventSource sends it on automatic reconnects
     * @return the stream, 401 for a bad ticket, or 429 if the user already
     *         has too many open
     */
    @PreAuthorize ( "permitAll()" )
    @GetMapping ( produces = MediaType.TEXT_EVENT_STREAM_VALUE )
    public ResponseEntity<SseEmitter> stream ( @RequestParam ( name = "ticket", required = false ) final String ticket,
            @RequestParam ( name = "lastEventId", required = false ) final Long lastEventIdParam,
            @RequestHeader ( name = "Last-Event-ID", required = false ) final Long lastEventIdHeader ) {
        final Optional<User> user = tickets.redeem( ticket ).flatMap( users::findByUsername ).filter( User::isActive );
        if ( user.isEmpty() ) {
            return ResponseEntity.status( HttpStatus.UNAUTHORIZED ).build();
        }
        final UserRole role = UserRole.fromAuthority( user.get().getRole() );
        final Long lastEventId = lastEventIdHeader != null ? lastEventIdHeader : lastEventIdParam;
        try {
            final SseEmitter emitter = broker.open( user.get().getUsername(), role.name(), lastEventId );
            return ResponseEntity.ok().header( "Cache-Control", "no-cache" ).header( "X-Accel-Buffering", "no" )
                    .body( emitter );
        }
        catch ( final TooManyStreamsException e ) {
            return ResponseEntity.status( HttpStatus.TOO_MANY_REQUESTS ).build();
        }
    }
}
