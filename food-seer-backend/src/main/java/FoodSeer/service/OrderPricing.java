package FoodSeer.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import FoodSeer.entity.Food;

/**
 * The server-side price of an order. The browser used to send cost and
 * delivery cost, and the server stored them as given, so a customer could set
 * their own price. Orders are now priced here from the menu's prices.
 *
 * An order's food list has one entry per unit, so two coffees appear twice.
 */
public final class OrderPricing {

    /** Delivery fee as a share of the items total. Matches what the checkout page showed before. */
    public static final BigDecimal DELIVERY_RATE = new BigDecimal( "0.30" );

    private OrderPricing () {
    }

    /**
     * @param foods
     *            the order's foods, one entry per unit, loaded from the database
     * @return the sum of the menu prices
     */
    public static BigDecimal itemsTotal ( final List<Food> foods ) {
        BigDecimal total = BigDecimal.ZERO;
        for ( final Food food : foods ) {
            total = total.add( BigDecimal.valueOf( food.getPrice() ) );
        }
        return total.setScale( 2, RoundingMode.HALF_UP );
    }

    /**
     * @param itemsTotal
     *            the items total
     * @return the delivery fee: 30% of the items total, to the cent
     */
    public static BigDecimal deliveryCost ( final BigDecimal itemsTotal ) {
        return itemsTotal.multiply( DELIVERY_RATE ).setScale( 2, RoundingMode.HALF_UP );
    }
}
