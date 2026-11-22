package FoodSeer.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import FoodSeer.constant.OrderStatus;
import FoodSeer.dto.NewOrderRequest;
import FoodSeer.dto.OrderEventDto;
import FoodSeer.entity.DriverStats;
import FoodSeer.entity.Food;
import FoodSeer.entity.User;
import FoodSeer.repositories.DriverStatsRepository;
import FoodSeer.repositories.FoodRepository;
import FoodSeer.repositories.OrderEventRepository;
import FoodSeer.repositories.OrderRepository;
import FoodSeer.repositories.UserRepository;
import FoodSeer.service.DriverStatsService;
import FoodSeer.service.OrderService;

/**
 * Acceptance criterion: a transition that fails after its event is written
 * leaves no event. Paying the driver runs after the DELIVERED event is
 * recorded, so making it fail must roll the event back with the order.
 */
@SpringBootTest
class OrderEventRollbackTest {

    @MockBean
    private DriverStatsService    earnings;

    @Autowired
    private OrderService          orderService;
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

    private final List<Long> orderIds = new ArrayList<>();
    private Food food;

    @AfterEach
    void tearDown () {
        SecurityContextHolder.clearContext();
        orderIds.forEach( id -> events.deleteAll( events.findByOrderIdOrderByIdAsc( id ) ) );
        orderRepository.deleteAllById( orderIds );
        if ( food != null ) {
            foodRepository.delete( food );
        }
        driverStatsRepository.findByUsername( "rb_driver" ).ifPresent( driverStatsRepository::delete );
        userRepository.findByUsername( "rb_driver" ).ifPresent( userRepository::delete );
        userRepository.findByUsername( "rb_customer" ).ifPresent( userRepository::delete );
    }

    private static void signInAs ( final String name, final String role ) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken( name, null, List.of( new SimpleGrantedAuthority( role ) ) ) );
    }

    @Test
    void aDeliveryThatFailsAfterItsEventLeavesNoEvent () {
        userRepository.save( User.builder().username( "rb_customer" ).email( "rb_c@example.test" ).password( "x" )
                .role( "ROLE_CUSTOMER" ).build() );
        userRepository.save( User.builder().username( "rb_driver" ).email( "rb_d@example.test" ).password( "x" )
                .role( "ROLE_DRIVER" ).build() );
        final DriverStats stats = new DriverStats();
        stats.setUsername( "rb_driver" );
        driverStatsRepository.save( stats );
        food = foodRepository.save( new Food( "RB SOUP", 10, 5, new ArrayList<>() ) );

        signInAs( "rb_customer", "ROLE_CUSTOMER" );
        final Long id = orderService.createOrder( NewOrderRequest.of( "Rollback", List.of( food.getId() ) ) ).getId();
        orderIds.add( id );
        signInAs( "rb_driver", "ROLE_DRIVER" );
        orderService.claimOrder( id );
        orderService.updateOrder( id, "rb_driver", "Picked Up" );
        doThrow( new IllegalStateException( "payments are down" ) ).when( earnings )
                .updateTotalEarnings( anyString(), any() );

        assertThrows( IllegalStateException.class, () -> orderService.updateOrder( id, "rb_driver", "Delivered" ) );

        assertEquals( OrderStatus.PICKED_UP, orderRepository.findById( id ).orElseThrow().getStatus() );
        assertEquals( List.of( "PLACED", "CLAIMED", "PICKED_UP" ),
                orderService.getOrderEvents( id ).stream().map( OrderEventDto::type ).toList() );
    }
}
