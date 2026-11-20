package FoodSeer.entity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * One unit of an order as it was when the order was placed: which food, what
 * it was called, what it cost and what allergens it declared. Later menu edits
 * or deleting the food don't change it, so order history stays true.
 */
@Embeddable
public class OrderLine {

    @Column ( name = "food_id" )
    private Long foodId;

    @Column ( name = "food_name" )
    private String foodName;

    @Column ( name = "price", precision = 10, scale = 2 )
    private BigDecimal price;

    /** Comma-separated, upper case, as declared at order time. */
    @Column ( name = "allergens", length = 1000 )
    private String allergens;

    @Column ( name = "calories" )
    private Integer calories;

    /** For JPA. */
    protected OrderLine () {
    }

    /**
     * @param foodId
     *            the food
     * @param foodName
     *            its name at order time
     * @param price
     *            its price at order time
     * @param allergens
     *            its declared allergens at order time
     * @param calories
     *            its calories at order time, or null
     */
    public OrderLine ( final Long foodId, final String foodName, final BigDecimal price, final List<String> allergens,
            final Integer calories ) {
        this.foodId = foodId;
        this.foodName = foodName;
        this.price = price;
        this.allergens = allergens == null ? "" : String.join( ",", allergens );
        this.calories = calories;
    }

    /**
     * @param food
     *            the food as it is now
     * @return a snapshot of it
     */
    public static OrderLine of ( final Food food ) {
        return new OrderLine( food.getId(), food.getFoodName(), BigDecimal.valueOf( food.getPrice() ),
                food.getAllergies(), food.getCalories() );
    }

    public Long getFoodId () {
        return foodId;
    }

    public String getFoodName () {
        return foodName;
    }

    public BigDecimal getPrice () {
        return price;
    }

    public Integer getCalories () {
        return calories;
    }

    /**
     * @return the allergens declared at order time
     */
    public List<String> getAllergens () {
        if ( allergens == null || allergens.isBlank() ) {
            return new ArrayList<>();
        }
        return new ArrayList<>( Arrays.asList( allergens.split( "," ) ) );
    }
}
