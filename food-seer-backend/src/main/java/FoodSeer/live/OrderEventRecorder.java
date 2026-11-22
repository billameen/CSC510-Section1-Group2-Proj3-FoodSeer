package FoodSeer.live;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import FoodSeer.constant.OrderEventType;
import FoodSeer.constant.OrderStatus;
import FoodSeer.constant.UserRole;
import FoodSeer.entity.Order;
import FoodSeer.entity.OrderEvent;
import FoodSeer.entity.User;
import FoodSeer.repositories.OrderEventRepository;

/**
 * Writes an order's timeline and queues the matching live update. Every
 * method needs the caller's transaction (MANDATORY), so the event and its
 * outbox message are saved if and only if the change they describe is.
 *
 * Who hears about an order: its customer, its driver, and all staff and
 * admins. All drivers also hear when an order enters or leaves the pool of
 * available orders (placed, or claimed or cancelled while placed), so their
 * Available Orders list stays current. The message carries no customer
 * details, only the order id, what happened and its status.
 */
@Component
public class OrderEventRecorder {

    /** Live message type for anything that happens to an order. */
    public static final String         ORDER_EVENT = "order.event";

    private static final List<String>  STAFF       = List.of( "STAFF", "ADMIN" );
    private static final List<String>  STAFF_AND_DRIVERS = List.of( "STAFF", "ADMIN", "DRIVER" );

    private final OrderEventRepository events;
    private final OutboxWriter         outbox;

    /**
     * @param events
     *            timeline repository
     * @param outbox
     *            queues live updates
     */
    public OrderEventRecorder ( final OrderEventRepository events, final OutboxWriter outbox ) {
        this.events = events;
        this.outbox = outbox;
    }

    /**
     * @param order
     *            the order just saved
     * @param customer
     *            who placed it
     * @return the event
     */
    @Transactional ( propagation = Propagation.MANDATORY )
    public OrderEvent placed ( final Order order, final User customer ) {
        return announce( order, save( order, OrderEventType.PLACED, null, customer, null ), true );
    }

    /**
     * @param order
     *            the order, already moved to its new status
     * @param from
     *            the status it moved from
     * @param actor
     *            who moved it
     * @return the event
     */
    @Transactional ( propagation = Propagation.MANDATORY )
    public OrderEvent transitioned ( final Order order, final OrderStatus from, final User actor ) {
        return announce( order, save( order, OrderEventType.forStatus( order.getStatus() ), from, actor, null ),
                from == OrderStatus.PLACED );
    }

    /**
     * @param order
     *            the order
     * @param actor
     *            who acknowledged
     * @param role
     *            STAFF or DRIVER
     * @return the event
     */
    @Transactional ( propagation = Propagation.MANDATORY )
    public OrderEvent allergyAcknowledged ( final Order order, final User actor, final String role ) {
        return announce( order, save( order, OrderEventType.ALLERGY_ACKNOWLEDGED, order.getStatus(), actor,
                ( "STAFF".equals( role ) ? "Restaurant staff" : "Driver" ) + " acknowledged the allergy warning." ),
                false );
    }

    /**
     * Starts the timeline of an order that predates the event log.
     *
     * @param order
     *            the order
     * @return the event
     */
    @Transactional ( propagation = Propagation.MANDATORY )
    public OrderEvent imported ( final Order order ) {
        return save( order, OrderEventType.IMPORTED, null, null, "History before this point wasn't recorded." );
    }

    /** Old history isn't news, so imported events are never announced. */
    private OrderEvent announce ( final Order order, final OrderEvent event, final boolean allDrivers ) {
        final Map<String, Object> payload = new LinkedHashMap<>();
        payload.put( "orderId", order.getId() );
        payload.put( "event", event.getType().name() );
        payload.put( "status", order.getStatus().label() );
        payload.put( "fromStatus", event.getFromStatus() == null ? null : event.getFromStatus().label() );
        payload.put( "driverUsername", order.getDriver() == null ? null : order.getDriver().getUsername() );
        payload.put( "eventId", event.getId() );
        payload.put( "at", event.getOccurredAt() );
        outbox.write( ORDER_EVENT, payload, order.getId(),
                Arrays.asList( order.getUser() == null ? null : order.getUser().getUsername(),
                        order.getDriver() == null ? null : order.getDriver().getUsername() ),
                allDrivers ? STAFF_AND_DRIVERS : STAFF );
        return event;
    }

    private OrderEvent save ( final Order order, final OrderEventType type, final OrderStatus from,
            final User actor, final String detail ) {
        final UserRole role = actor == null ? null : UserRole.fromAuthority( actor.getRole() );
        return events.save( new OrderEvent( order.getId(), type, from, order.getStatus(),
                actor == null ? null : actor.getUsername(), role == null ? null : role.name(), detail ) );
    }
}
