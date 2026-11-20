package FoodSeer.dto;

import java.math.BigDecimal;
import java.util.List;

import FoodSeer.entity.Food;
import FoodSeer.entity.OrderLine;

/**
 * One unit of an order in API responses, as it was when the order was placed.
 * Field names match what the frontend already reads ("id", "foodName",
 * "price", "allergies", "calories").
 *
 * @param id
 *            the food's id
 * @param foodName
 *            its name at order time
 * @param price
 *            its price at order time
 * @param allergies
 *            its declared allergens at order time
 * @param calories
 *            its calories at order time, or null
 */
public record OrderLineDto ( Long id, String foodName, BigDecimal price, List<String> allergies, Integer calories ) {

    /**
     * @param line
     *            a stored snapshot
     * @return its DTO
     */
    public static OrderLineDto of ( final OrderLine line ) {
        return new OrderLineDto( line.getFoodId(), line.getFoodName(), line.getPrice(), line.getAllergens(),
                line.getCalories() );
    }

    /**
     * For orders placed before snapshots existed: the food as it is now.
     *
     * @param food
     *            the food
     * @return its DTO
     */
    public static OrderLineDto of ( final Food food ) {
        return new OrderLineDto( food.getId(), food.getFoodName(), BigDecimal.valueOf( food.getPrice() ),
                food.getAllergies(), food.getCalories() );
    }
}
