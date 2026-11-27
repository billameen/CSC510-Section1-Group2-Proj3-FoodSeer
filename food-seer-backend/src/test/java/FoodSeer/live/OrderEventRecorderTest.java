package FoodSeer.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import FoodSeer.constant.OrderEventType;
import FoodSeer.constant.OrderStatus;
import FoodSeer.entity.DriverStats;
import FoodSeer.entity.Order;
import FoodSeer.entity.OrderEvent;
import FoodSeer.entity.User;
import FoodSeer.repositories.OrderEventRepository;

@ExtendWith ( MockitoExtension.class )
class OrderEventRecorderTest {

    @Mock
    private OrderEventRepository events;
    @Mock
    private OutboxWriter         outbox;

    private OrderEventRecorder   recorder;
    private Order                order;
    private User                 customer;
    private User                 driverUser;

    @BeforeEach
    void setUp () {
        recorder = new OrderEventRecorder( events, outbox );
        org.mockito.Mockito.lenient().when( events.save( any( OrderEvent.class ) ) ).thenAnswer( inv -> inv.getArgument( 0 ) );
        customer = User.builder().username( "carol" ).role( "ROLE_CUSTOMER" ).build();
        driverUser = User.builder().username( "dan" ).role( "ROLE_DRIVER" ).build();
        order = new Order( 7L, "Dinner" );
        order.setUser( customer );
    }

    private DriverStats driver () {
        final DriverStats stats = new DriverStats();
        stats.setUsername( "dan" );
        return stats;
    }

    @SuppressWarnings ( "unchecked" )
    private Map<String, Object> announced ( final List<String> users, final List<String> roles ) {
        final ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass( Map.class );
        final ArgumentCaptor<Collection<String>> to = ArgumentCaptor.forClass( Collection.class );
        final ArgumentCaptor<Collection<String>> toRoles = ArgumentCaptor.forClass( Collection.class );
        verify( outbox ).write( org.mockito.ArgumentMatchers.eq( OrderEventRecorder.ORDER_EVENT ), payload.capture(),
                org.mockito.ArgumentMatchers.eq( 7L ), to.capture(), toRoles.capture() );
        assertEquals( users, to.getValue().stream().filter( u -> u != null ).toList() );
        assertEquals( roles, List.copyOf( toRoles.getValue() ) );
        return payload.getValue();
    }

    @Test
    void placingTellsTheCustomerStaffAndEveryDriver () {
        final OrderEvent event = recorder.placed( order, customer );

        assertEquals( OrderEventType.PLACED, event.getType() );
        assertNull( event.getFromStatus() );
        assertEquals( OrderStatus.PLACED, event.getToStatus() );
        assertEquals( "carol", event.getActorUsername() );
        assertEquals( "CUSTOMER", event.getActorRole() );
        final Map<String, Object> payload = announced( List.of( "carol" ), List.of( "STAFF", "ADMIN", "DRIVER" ) );
        assertEquals( 7L, payload.get( "orderId" ) );
        assertEquals( "PLACED", payload.get( "event" ) );
        assertEquals( "Placed", payload.get( "status" ) );
        assertNull( payload.get( "fromStatus" ) );
        assertNull( payload.get( "driverUsername" ) );
    }

    @Test
    void aClaimStillTellsEveryDriverSoTheirListsDropIt () {
        order.setDriver( driver() );
        order.setStatus( OrderStatus.CLAIMED );

        final OrderEvent event = recorder.transitioned( order, OrderStatus.PLACED, driverUser );

        assertEquals( OrderEventType.CLAIMED, event.getType() );
        assertEquals( OrderStatus.PLACED, event.getFromStatus() );
        assertEquals( "DRIVER", event.getActorRole() );
        final Map<String, Object> payload = announced( List.of( "carol", "dan" ),
                List.of( "STAFF", "ADMIN", "DRIVER" ) );
        assertEquals( "Claimed", payload.get( "status" ) );
        assertEquals( "Placed", payload.get( "fromStatus" ) );
        assertEquals( "dan", payload.get( "driverUsername" ) );
    }

    @Test
    void laterMovesOnlyReachTheCustomerTheirDriverAndStaff () {
        order.setDriver( driver() );
        order.setStatus( OrderStatus.PICKED_UP );

        recorder.transitioned( order, OrderStatus.CLAIMED, driverUser );

        assertEquals( "Picked Up", announced( List.of( "carol", "dan" ), List.of( "STAFF", "ADMIN" ) ).get( "status" ) );
    }

    @Test
    void anAllergyAcknowledgementIsRecordedWithWhoDidIt () {
        order.setStatus( OrderStatus.CLAIMED );
        final User staff = User.builder().username( "sam" ).role( "ROLE_STAFF" ).build();

        final OrderEvent staffAck = recorder.allergyAcknowledged( order, staff, "STAFF" );
        final OrderEvent driverAck = recorder.allergyAcknowledged( order, driverUser, "DRIVER" );

        assertEquals( OrderEventType.ALLERGY_ACKNOWLEDGED, staffAck.getType() );
        assertEquals( OrderStatus.CLAIMED, staffAck.getFromStatus() );
        assertEquals( OrderStatus.CLAIMED, staffAck.getToStatus() );
        assertEquals( "Restaurant staff acknowledged the allergy warning.", staffAck.getDetail() );
        assertEquals( "Driver acknowledged the allergy warning.", driverAck.getDetail() );
        assertEquals( "STAFF", staffAck.getActorRole() );
        verify( outbox, org.mockito.Mockito.times( 2 ) ).write( anyString(), anyMap(), anyLong(), anyCollection(),
                org.mockito.ArgumentMatchers.eq( List.of( "STAFF", "ADMIN" ) ) );
    }

    @Test
    void importedHistoryIsRecordedButNotAnnounced () {
        order.setStatus( OrderStatus.DELIVERED );

        final OrderEvent event = recorder.imported( order );

        assertEquals( OrderEventType.IMPORTED, event.getType() );
        assertEquals( OrderStatus.DELIVERED, event.getToStatus() );
        assertNull( event.getActorUsername() );
        assertNull( event.getActorRole() );
        verify( outbox, never() ).write( anyString(), anyMap(), any(), anyCollection(), anyCollection() );
    }

    @Test
    void theSavedEventIsWhatComesBack () {
        final OrderEvent stored = new OrderEvent( 7L, OrderEventType.PLACED, null, OrderStatus.PLACED, "carol",
                "CUSTOMER", null );
        when( events.save( any( OrderEvent.class ) ) ).thenReturn( stored );

        assertSame( stored, recorder.placed( order, customer ) );
        assertEquals( Arrays.asList( "carol", null ),
                Arrays.asList( stored.getActorUsername(), stored.getDetail() ) );
    }
}
