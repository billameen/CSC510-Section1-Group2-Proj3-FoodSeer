package FoodSeer.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import FoodSeer.dto.NewOrderRequest;
import FoodSeer.entity.DriverStats;
import FoodSeer.entity.Food;
import FoodSeer.entity.OutboxEvent;
import FoodSeer.entity.User;
import FoodSeer.repositories.DriverStatsRepository;
import FoodSeer.repositories.FoodRepository;
import FoodSeer.repositories.IncidentRepository;
import FoodSeer.repositories.NotificationRepository;
import FoodSeer.repositories.OrderEventRepository;
import FoodSeer.repositories.OrderRepository;
import FoodSeer.repositories.OutboxEventRepository;
import FoodSeer.repositories.UserRepository;
import FoodSeer.service.OrderService;
import FoodSeer.service.TraceService;

/** The outbox with real commits and the real scheduled dispatcher. */
@SpringBootTest
class OutboxTest {

    /** Collects what the dispatcher sends; can be told to fail. */
    static class RecordingSink implements OutboxSink {
        final List<Long>    received     = new CopyOnWriteArrayList<>();
        final AtomicInteger failuresLeft = new AtomicInteger();

        @Override
        public void publish ( final OutboxEvent event ) {
            if ( failuresLeft.getAndUpdate( n -> Math.max( 0, n - 1 ) ) > 0 ) {
                throw new IllegalStateException( "stream down" );
            }
            received.add( event.getId() );
        }
    }

    @TestConfiguration
    static class SinkConfig {
        @Bean
        RecordingSink recordingSink () {
            return new RecordingSink();
        }
    }

    @Autowired
    private RecordingSink          sink;
    @Autowired
    private OutboxDispatcher       dispatcher;
    @Autowired
    private OrderService           orderService;
    @Autowired
    private TraceService           traceService;
    @Autowired
    private OutboxEventRepository  outbox;
    @Autowired
    private OrderEventRepository   events;
    @Autowired
    private OrderRepository        orderRepository;
    @Autowired
    private FoodRepository         foodRepository;
    @Autowired
    private UserRepository         userRepository;
    @Autowired
    private DriverStatsRepository  driverStatsRepository;
    @Autowired
    private IncidentRepository     incidentRepository;
    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private ObjectMapper           json;

    private final List<Long> orderIds = new ArrayList<>();
    private Food food;

    @BeforeEach
    void setUp () {
        userRepository.save( User.builder().username( "ob_customer" ).email( "ob_c@example.test" ).password( "x" )
                .role( "ROLE_CUSTOMER" ).dietaryRestrictions( "PEANUTS" ).build() );
        userRepository.save( User.builder().username( "ob_bystander" ).email( "ob_b@example.test" ).password( "x" )
                .role( "ROLE_CUSTOMER" ).build() );
        userRepository.save( User.builder().username( "ob_driver" ).email( "ob_d@example.test" ).password( "x" )
                .role( "ROLE_DRIVER" ).build() );
        final DriverStats stats = new DriverStats();
        stats.setUsername( "ob_driver" );
        driverStatsRepository.save( stats );
        food = foodRepository.save( new Food( "OB SATAY", 10, 5, new ArrayList<>( List.of( "PEANUTS" ) ) ) );
        sink.failuresLeft.set( 0 );
    }

    @AfterEach
    void tearDown () {
        SecurityContextHolder.clearContext();
        orderIds.forEach( id -> {
            outbox.deleteAll( outbox.findByOrderIdOrderByIdAsc( id ) );
            events.deleteAll( events.findByOrderIdOrderByIdAsc( id ) );
        } );
        notificationRepository.deleteAll( notificationRepository.findAll().stream()
                .filter( n -> "ob_customer".equals( n.getRecipientUsername() ) ).toList() );
        incidentRepository.deleteAll( incidentRepository.findAll().stream()
                .filter( i -> "ob_staff".equals( i.getReporterUsername() ) ).toList() );
        orderRepository.deleteAllById( orderIds );
        foodRepository.delete( food );
        driverStatsRepository.findByUsername( "ob_driver" ).ifPresent( driverStatsRepository::delete );
        for ( final String name : new String[] { "ob_customer", "ob_bystander", "ob_driver" } ) {
            userRepository.findByUsername( name ).ifPresent( userRepository::delete );
        }
    }

    private static void signInAs ( final String name, final String role ) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken( name, null, List.of( new SimpleGrantedAuthority( role ) ) ) );
    }

    private Long place () {
        signInAs( "ob_customer", "ROLE_CUSTOMER" );
        final Long id = orderService.createOrder( NewOrderRequest.of( "Outbox", List.of( food.getId() ) ) ).getId();
        orderIds.add( id );
        return id;
    }

    private JsonNode payload ( final OutboxEvent event ) throws Exception {
        return json.readTree( event.getPayload() );
    }

    @Test
    void placingAnOrderQueuesOneMessageForTheCustomerStaffAndAllDrivers () throws Exception {
        final Long id = place();

        final List<OutboxEvent> messages = outbox.findByOrderIdOrderByIdAsc( id );
        assertEquals( 1, messages.size() );
        final OutboxEvent placed = messages.get( 0 );
        assertEquals( "order.event", placed.getType() );
        assertEquals( List.of( "ob_customer" ), placed.getTargetUsers() );
        assertEquals( List.of( "STAFF", "ADMIN", "DRIVER" ), placed.getTargetRoles() );
        assertEquals( "PLACED", payload( placed ).get( "event" ).asText() );
        assertEquals( "Placed", payload( placed ).get( "status" ).asText() );
        assertTrue( placed.isFor( "ob_customer", "CUSTOMER" ) );
        assertTrue( placed.isFor( "anyone", "DRIVER" ) );
        assertFalse( placed.isFor( "ob_bystander", "CUSTOMER" ), "other customers hear nothing" );
        assertFalse( payload( placed ).has( "customerUsername" ), "drivers see no customer details" );
    }

    /** Acceptance criterion: every change writes exactly one event and one message; a refused one writes neither. */
    @Test
    void eachChangeWritesOneEventAndOneMessageAndARefusedOneWritesNeither () {
        final Long id = place();
        signInAs( "ob_driver", "ROLE_DRIVER" );
        assertThrows( IllegalStateException.class, () -> orderService.updateOrder( id, "ob_driver", "Delivered" ) );
        orderService.claimOrder( id );
        assertThrows( IllegalStateException.class, () -> orderService.claimOrder( id ) );

        assertEquals( 2, events.findByOrderIdOrderByIdAsc( id ).size() );
        final List<OutboxEvent> messages = outbox.findByOrderIdOrderByIdAsc( id );
        assertEquals( 2, messages.size() );

        final OutboxEvent claimed = messages.get( 1 );
        assertEquals( List.of( "ob_customer", "ob_driver" ), claimed.getTargetUsers() );
        assertEquals( List.of( "STAFF", "ADMIN", "DRIVER" ), claimed.getTargetRoles(),
                "every driver hears it left the available pool" );
    }

    @Test
    void onceClaimedOtherDriversStopHearingAboutIt () {
        final Long id = place();
        signInAs( "ob_driver", "ROLE_DRIVER" );
        orderService.claimOrder( id );
        // No allergy hand-off needed? The customer has restrictions, so cancel instead.
        signInAs( "ob_staff", "ROLE_STAFF" );
        userRepository.save( User.builder().username( "ob_staff" ).email( "ob_s@example.test" ).password( "x" )
                .role( "ROLE_STAFF" ).build() );
        try {
            orderService.cancelOrder( id );
        }
        finally {
            userRepository.findByUsername( "ob_staff" ).ifPresent( userRepository::delete );
        }

        final OutboxEvent cancelled = outbox.findByOrderIdOrderByIdAsc( id ).get( 2 );
        assertEquals( List.of( "STAFF", "ADMIN" ), cancelled.getTargetRoles() );
        assertTrue( cancelled.isFor( "ob_driver", "DRIVER" ), "the assigned driver still hears" );
        assertFalse( cancelled.isFor( "another_driver", "DRIVER" ) );
    }

    @Test
    void theDispatcherDeliversEveryMessageAndStampsIt () throws Exception {
        final Long id = place();
        final Long messageId = outbox.findByOrderIdOrderByIdAsc( id ).get( 0 ).getId();

        dispatcher.dispatchAll();

        assertTrue( sink.received.contains( messageId ) );
        assertNotNull( outbox.findById( messageId ).orElseThrow().getPublishedAt() );
        final int before = sink.received.size();
        dispatcher.dispatchAll();
        assertEquals( before, sink.received.size(), "a sent message isn't sent again" );
    }

    /** Acceptance criterion: the stream failing after commit only delays delivery. */
    @Test
    void aMessageThatFailsToSendIsDeliveredOnALaterRun () throws Exception {
        sink.failuresLeft.set( 3 );
        final Long id = place();
        final Long messageId = outbox.findByOrderIdOrderByIdAsc( id ).get( 0 ).getId();

        for ( int run = 0; run < 3; run++ ) {
            assertEquals( 0, dispatcher.dispatch(), "the stream is down" );
            assertFalse( sink.received.contains( messageId ) );
        }
        dispatcher.dispatchAll();

        assertTrue( sink.received.contains( messageId ) );
        assertNotNull( outbox.findById( messageId ).orElseThrow().getPublishedAt() );
    }

    @Test
    void anAllergyAlertGoesOnlyToTheExposedCustomer () throws Exception {
        final Long id = place();

        traceService.reportRestaurantIncident( "ob_staff", "PEANUTS", List.of( food.getId() ),
                LocalDateTime.now().minusHours( 1 ), LocalDateTime.now().plusHours( 1 ) );

        final List<OutboxEvent> notices = outbox.findByOrderIdOrderByIdAsc( id ).stream()
                .filter( e -> "safety.notice".equals( e.getType() ) ).toList();
        assertEquals( 1, notices.size() );
        assertEquals( List.of( "ob_customer" ), notices.get( 0 ).getTargetUsers() );
        assertTrue( notices.get( 0 ).getTargetRoles().isEmpty() );
        assertTrue( payload( notices.get( 0 ) ).get( "message" ).asText().contains( "OB SATAY" ) );
        final boolean staffTold = outbox.findAll().stream()
                .anyMatch( e -> "incident.reported".equals( e.getType() ) && e.isFor( "x", "STAFF" ) );
        assertTrue( staffTold );
    }
}
