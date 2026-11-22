package FoodSeer.live;

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
 * Writes an order's timeline. Every method needs the caller's transaction
 * (MANDATORY), so an event is saved if and only if the change it describes
 * is.
 */
@Component
public class OrderEventRecorder {

    private final OrderEventRepository events;

    /**
     * @param events
     *            timeline repository
     */
    public OrderEventRecorder ( final OrderEventRepository events ) {
        this.events = events;
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
        return save( order, OrderEventType.PLACED, null, customer, null );
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
        return save( order, OrderEventType.forStatus( order.getStatus() ), from, actor, null );
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
        return save( order, OrderEventType.ALLERGY_ACKNOWLEDGED, order.getStatus(), actor,
                ( "STAFF".equals( role ) ? "Restaurant staff" : "Driver" ) + " acknowledged the allergy warning." );
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

    private OrderEvent save ( final Order order, final OrderEventType type, final OrderStatus from,
            final User actor, final String detail ) {
        final UserRole role = actor == null ? null : UserRole.fromAuthority( actor.getRole() );
        return events.save( new OrderEvent( order.getId(), type, from, order.getStatus(),
                actor == null ? null : actor.getUsername(), role == null ? null : role.name(), detail ) );
    }
}
