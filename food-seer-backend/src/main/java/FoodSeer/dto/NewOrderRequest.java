package FoodSeer.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * What a customer sends to place an order: a name and the ids of the foods,
 * one entry per unit. Price, delivery cost and status are decided on the
 * server, so they aren't part of the request at all.
 *
 * @param name
 *            the order's name
 * @param foods
 *            the items, e.g. [{"id": 3}, {"id": 3}] for two of food 3
 */
public record NewOrderRequest ( String name, List<Item> foods ) {

    /**
     * One unit of a food.
     *
     * @param id
     *            the food's id
     */
    public record Item ( Long id ) {
    }

    /**
     * @param name
     *            the order's name
     * @param foodIds
     *            one id per unit
     * @return the request
     */
    public static NewOrderRequest of ( final String name, final List<Long> foodIds ) {
        final List<Item> items = new ArrayList<>();
        for ( final Long id : foodIds ) {
            items.add( new Item( id ) );
        }
        return new NewOrderRequest( name, items );
    }

    /**
     * @return the food ids, one per unit (null entries kept, so they can be rejected)
     */
    public List<Long> foodIds () {
        final List<Long> ids = new ArrayList<>();
        if ( foods != null ) {
            for ( final Item item : foods ) {
                ids.add( item == null ? null : item.id() );
            }
        }
        return ids;
    }
}
