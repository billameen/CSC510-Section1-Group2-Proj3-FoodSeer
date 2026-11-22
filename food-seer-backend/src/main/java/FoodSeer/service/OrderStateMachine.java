package FoodSeer.service;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import FoodSeer.constant.OrderStatus;
import FoodSeer.constant.UserRole;
import FoodSeer.entity.DriverStats;
import FoodSeer.entity.Order;
import FoodSeer.entity.User;
import FoodSeer.exception.ResourceNotFoundException;
import FoodSeer.live.OrderEventRecorder;
import FoodSeer.repositories.DriverStatsRepository;
import FoodSeer.repositories.OrderRepository;

/**
 * The only code that changes an order's status. Every move is checked in this
 * order:
 * <ol>
 * <li>the transition table (OrderStatus): a move it doesn't allow is a 409</li>
 * <li>who is asking: the wrong role or the wrong driver is a 403</li>
 * <li>the move's own guard, such as the allergy hand-off before pickup</li>
 * </ol>
 * and then its side effect happens exactly once: stock is released on cancel,
 * and the driver is paid on delivery. Each move is recorded in the order's
 * timeline in the same transaction. The save is flushed here, so if another
 * request changed the order first, the @Version check fails now (409).
 */
@Component
public class OrderStateMachine {

    private final OrderRepository orders;
    private final DriverStatsRepository driverStats;
    private final DriverStatsService earnings;
    private final AllergyHandoffService handoff;
    private final StockService stock;
    private final OrderEventRecorder timeline;

    /**
     * @param orders
     *            order repository
     * @param driverStats
     *            driver stats repository
     * @param earnings
     *            credits a driver's earnings
     * @param handoff
     *            allergy hand-off acknowledgements
     * @param stock
     *            reserves and releases stock
     * @param timeline
     *            records each move in the order's timeline
     */
    public OrderStateMachine ( final OrderRepository orders, final DriverStatsRepository driverStats,
            final DriverStatsService earnings, final AllergyHandoffService handoff, final StockService stock,
            final OrderEventRecorder timeline ) {
        this.orders = orders;
        this.driverStats = driverStats;
        this.earnings = earnings;
        this.handoff = handoff;
        this.stock = stock;
        this.timeline = timeline;
    }

    /**
     * Moves an order to a new status.
     *
     * @param order
     *            the order, loaded in the caller's transaction
     * @param to
     *            the new status
     * @param actor
     *            the signed-in user making the change
     * @return the saved order
     * @throws IllegalStateException
     *             (409) if the move isn't allowed from the current status
     * @throws SecurityException
     *             (403) if this user may not make this move
     */
    @Transactional
    public Order transition ( final Order order, final OrderStatus to, final User actor ) {
        final OrderStatus from = order.getStatus();
        from.requireTransitionTo( to );
        final UserRole role = actor == null ? null : UserRole.fromAuthority( actor.getRole() );
        if ( role == null ) {
            throw new SecurityException( "Sign in to change an order." );
        }

        switch ( to ) {
            case CLAIMED:
                requireRole( role, UserRole.DRIVER, "Only drivers can claim orders." );
                order.setDriver( driverStats.findByUsername( actor.getUsername() ).orElseThrow(
                        () -> new ResourceNotFoundException( "No driver statistics for " + actor.getUsername() ) ) );
                break;
            case PICKED_UP:
                requireAssignedDriver( order, actor, role );
                requireAllergyHandoff( order );
                break;
            case DELIVERED:
                requireAssignedDriver( order, actor, role );
                break;
            case CANCELLED:
                requireMayCancel( order, actor, role );
                stock.release( order );
                break;
            default:
                // PLACED is never a target (the table has no move into it).
                throw new IllegalStateException( "Orders can't be moved back to " + to.label() + "." );
        }

        order.setStatus( to );
        final Order saved = orders.saveAndFlush( order );
        // Same transaction as the save: a move that rolls back leaves no event.
        timeline.transitioned( saved, from, actor );

        if ( to == OrderStatus.DELIVERED ) {
            // DELIVERED is final, so this runs once per order. Older orders may
            // have no delivery cost; they pay nothing rather than failing.
            final BigDecimal fee = order.getDeliveryCost() == null ? BigDecimal.ZERO : order.getDeliveryCost();
            earnings.updateTotalEarnings( actor.getUsername(), fee );
        }
        return saved;
    }

    private static void requireRole ( final UserRole role, final UserRole needed, final String message ) {
        if ( role != needed ) {
            throw new SecurityException( message );
        }
    }

    private static void requireAssignedDriver ( final Order order, final User actor, final UserRole role ) {
        requireRole( role, UserRole.DRIVER, "Only the assigned driver can update delivery status." );
        if ( order.getDriver() == null || !actor.getUsername().equals( order.getDriver().getUsername() ) ) {
            throw new SecurityException( "You are not assigned to this order." );
        }
    }

    private void requireAllergyHandoff ( final Order order ) {
        final boolean hasRestrictions = order.getAllergyRestrictions() != null
                && !order.getAllergyRestrictions().isBlank();
        if ( !hasRestrictions ) {
            return;
        }
        if ( !handoff.isAcknowledged( order.getId(), "STAFF" ) ) {
            throw new IllegalStateException( "Restaurant staff must acknowledge the allergy warning first." );
        }
        if ( !handoff.isAcknowledged( order.getId(), "DRIVER" ) ) {
            throw new IllegalStateException( "The driver must acknowledge the allergy warning before pickup." );
        }
    }

    /**
     * A customer may cancel their own order until a driver claims it. Staff
     * and admin may cancel any order that isn't delivered (the table already
     * rules out DELIVERED and CANCELLED).
     */
    private static void requireMayCancel ( final Order order, final User actor, final UserRole role ) {
        if ( role == UserRole.STAFF || role == UserRole.ADMIN ) {
            return;
        }
        if ( role == UserRole.CUSTOMER ) {
            if ( order.getUser() == null || !actor.getUsername().equals( order.getUser().getUsername() ) ) {
                throw new SecurityException( "You can only cancel your own orders." );
            }
            if ( order.getStatus() != OrderStatus.PLACED ) {
                throw new IllegalStateException(
                        "A driver has already claimed this order, so it can't be cancelled here. Contact the restaurant." );
            }
            return;
        }
        throw new SecurityException( "Drivers can't cancel orders." );
    }
}
