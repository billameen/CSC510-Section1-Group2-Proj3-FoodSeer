package FoodSeer.security;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import FoodSeer.entity.Order;
import FoodSeer.repositories.OrderRepository;

/**
 * Ownership checks for orders, used from {@code @PreAuthorize}. The principal
 * name is always the username (see CustomUserDetailsService), so these are
 * plain comparisons.
 *
 * A missing order answers false, so a customer or driver gets 403 rather than
 * learning which order ids exist. Staff and admin are checked by role before
 * these are called and still get 404 for a missing order.
 */
@Component ( "orderAccess" )
public class OrderAccess {

    private final OrderRepository orders;

    /**
     * @param orders
     *            order repository
     */
    public OrderAccess ( final OrderRepository orders ) {
        this.orders = orders;
    }

    /**
     * @param authentication
     *            the caller
     * @param orderId
     *            the order
     * @return true if the caller placed this order
     */
    public boolean isOwner ( final Authentication authentication, final Long orderId ) {
        if ( authentication == null || orderId == null ) {
            return false;
        }
        return orders.findById( orderId ).map( order -> ownedBy( order, authentication.getName() ) ).orElse( false );
    }

    /**
     * @param authentication
     *            the caller
     * @param orderId
     *            the order
     * @return true if the caller is the driver assigned to this order
     */
    public boolean isAssignedDriver ( final Authentication authentication, final Long orderId ) {
        if ( authentication == null || orderId == null ) {
            return false;
        }
        return orders.findById( orderId ).map( order -> order.getDriver() != null
                && authentication.getName().equals( order.getDriver().getUsername() ) ).orElse( false );
    }

    private static boolean ownedBy ( final Order order, final String username ) {
        return order.getUser() != null && username.equals( order.getUser().getUsername() );
    }
}
