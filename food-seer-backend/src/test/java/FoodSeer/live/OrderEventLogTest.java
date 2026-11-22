package FoodSeer.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import FoodSeer.constant.OrderStatus;
import FoodSeer.dto.NewOrderRequest;
import FoodSeer.dto.OrderEventDto;
import FoodSeer.entity.DriverStats;
import FoodSeer.entity.Food;
import FoodSeer.entity.Order;
import FoodSeer.entity.User;
import FoodSeer.exception.ResourceNotFoundException;
import FoodSeer.repositories.AllergyAcknowledgmentRepository;
import FoodSeer.repositories.DriverStatsRepository;
import FoodSeer.repositories.FoodRepository;
import FoodSeer.repositories.OrderEventRepository;
import FoodSeer.repositories.OrderRepository;
import FoodSeer.repositories.UserRepository;
import FoodSeer.service.AllergyHandoffService;
import FoodSeer.service.OrderService;

/**
 * The timeline with real commits and rollbacks (no @Transactional on the
 * test).
 */
@SpringBootTest
class OrderEventLogTest {

    @Autowired
    private OrderService          orderService;
    @Autowired
    private AllergyHandoffService handoff;
    @Autowired
    private OrderEventBackfill    backfill;
    @Autowired
    private OrderEventRepository  events;
    @Autowired
    private OrderRepository       orderRepository;
    @Autowired
    private FoodRepository        foodRepository;
    @Autowired
    private UserRepository        userRepository;
    @Autowired
    private DriverStatsRepository driverStatsRepository;
    @Autowired
    private AllergyAcknowledgmentRepository acknowledgments;

    private final List<Long> orderIds = new ArrayList<>();
    private Food food;

    @BeforeEach
    void setUp () {
        userRepository.save( User.builder().username( "log_customer" ).email( "log_c@example.test" ).password( "x" )
                .role( "ROLE_CUSTOMER" ).dietaryRestrictions( "PEANUTS" ).build() );
        userRepository.save( User.builder().username( "log_staff" ).email( "log_s@example.test" ).password( "x" )
                .role( "ROLE_STAFF" ).build() );
        userRepository.save( User.builder().username( "log_driver" ).email( "log_d@example.test" ).password( "x" )
                .role( "ROLE_DRIVER" ).build() );
        final DriverStats stats = new DriverStats();
        stats.setUsername( "log_driver" );
        driverStatsRepository.save( stats );
        food = foodRepository.save( new Food( "LOG SOUP", 10, 5, new ArrayList<>() ) );
    }

    @AfterEach
    void tearDown () {
        SecurityContextHolder.clearContext();
        orderIds.forEach( id -> events.deleteAll( events.findByOrderIdOrderByIdAsc( id ) ) );
        acknowledgments.deleteAll( acknowledgments.findAll().stream()
                .filter( a -> orderIds.contains( a.getOrder().getId() ) ).toList() );
        orderRepository.deleteAllById( orderIds );
        foodRepository.delete( food );
        driverStatsRepository.findByUsername( "log_driver" ).ifPresent( driverStatsRepository::delete );
        for ( final String name : new String[] { "log_customer", "log_staff", "log_driver" } ) {
            userRepository.findByUsername( name ).ifPresent( userRepository::delete );
        }
    }

    private static void signInAs ( final String name, final String role ) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken( name, null, List.of( new SimpleGrantedAuthority( role ) ) ) );
    }

    private Long place () {
        signInAs( "log_customer", "ROLE_CUSTOMER" );
        final Long id = orderService.createOrder( NewOrderRequest.of( "Logged", List.of( food.getId() ) ) ).getId();
        orderIds.add( id );
        return id;
    }

    @Test
    void aDeliveredOrderHasItsWholeHistoryInOrder () {
        final Long id = place();
        signInAs( "log_driver", "ROLE_DRIVER" );
        orderService.claimOrder( id );
        signInAs( "log_staff", "ROLE_STAFF" );
        handoff.acknowledge( id, "STAFF" );
        signInAs( "log_driver", "ROLE_DRIVER" );
        handoff.acknowledge( id, "DRIVER" );
        orderService.updateOrder( id, "log_driver", "Picked Up" );
        orderService.updateOrder( id, "log_driver", "Delivered" );

        final List<OrderEventDto> timeline = orderService.getOrderEvents( id );

        assertEquals( List.of( "PLACED", "CLAIMED", "ALLERGY_ACKNOWLEDGED", "ALLERGY_ACKNOWLEDGED", "PICKED_UP",
                "DELIVERED" ), timeline.stream().map( OrderEventDto::type ).toList() );
        assertEquals( List.of( "log_customer", "log_driver", "log_staff", "log_driver", "log_driver", "log_driver" ),
                timeline.stream().map( OrderEventDto::actorUsername ).toList() );
        assertNull( timeline.get( 0 ).fromStatus() );
        assertEquals( "Placed", timeline.get( 1 ).fromStatus() );
        assertEquals( "Claimed", timeline.get( 1 ).toStatus() );
        assertEquals( "Picked Up", timeline.get( 5 ).fromStatus() );
        assertEquals( "Delivered", timeline.get( 5 ).toStatus() );
        assertEquals( "STAFF", timeline.get( 2 ).actorRole() );
    }

    @Test
    void aRefusedMoveAddsNothingToTheTimeline () {
        final Long id = place();
        signInAs( "log_driver", "ROLE_DRIVER" );

        // Delivered straight from Placed isn't in the transition table (409).
        assertThrows( IllegalStateException.class, () -> orderService.updateOrder( id, "log_driver", "Delivered" ) );
        // Pickup without the allergy hand-off is refused by its guard.
        orderService.claimOrder( id );
        assertThrows( IllegalStateException.class, () -> orderService.updateOrder( id, "log_driver", "Picked Up" ) );

        assertEquals( List.of( "PLACED", "CLAIMED" ),
                orderService.getOrderEvents( id ).stream().map( OrderEventDto::type ).toList() );
    }

    @Test
    void aCancelIsRecordedWithWhoCancelled () {
        final Long id = place();

        orderService.cancelOrder( id );

        final OrderEventDto cancel = orderService.getOrderEvents( id ).get( 1 );
        assertEquals( "CANCELLED", cancel.type() );
        assertEquals( "log_customer", cancel.actorUsername() );
        assertEquals( "CUSTOMER", cancel.actorRole() );
    }

    @Test
    void ordersFromBeforeTheLogGetOneImportedEventOnce () {
        final Order legacy = new Order();
        legacy.setName( "Legacy" );
        legacy.setStatus( OrderStatus.DELIVERED );
        legacy.setUser( userRepository.findByUsername( "log_customer" ).orElseThrow() );
        final Long id = orderRepository.save( legacy ).getId();
        orderIds.add( id );

        backfill.backfill();
        final int secondRun = backfill.backfill();

        final List<OrderEventDto> timeline = orderService.getOrderEvents( id );
        assertEquals( 1, timeline.size() );
        assertEquals( "IMPORTED", timeline.get( 0 ).type() );
        assertEquals( "Delivered", timeline.get( 0 ).toStatus() );
        assertNull( timeline.get( 0 ).actorUsername() );
        assertEquals( 0, secondRun );
    }

    @Test
    void theTimelineOfAMissingOrderIs404 () {
        assertThrows( ResourceNotFoundException.class, () -> orderService.getOrderEvents( -1L ) );
    }
}
