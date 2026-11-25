package FoodSeer.dto;

import java.util.List;

import FoodSeer.entity.Food;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A dish the assistant recommends, with what the chat page needs to show its
 * card and add it to the cart.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ChatFoodDto {
    /** The dish id, the same id the menu and cart use */
    private Long id;

    /** The dish name */
    private String foodName;

    /** Price in dollars */
    private int price;

    /** Calories, or null if not recorded */
    private Integer calories;

    /** Allergen tags on the dish */
    private List<String> allergies;

    /**
     * Copies a dish.
     *
     * @param food
     *            the dish
     * @return its card data
     */
    public static ChatFoodDto of(final Food food) {
        return new ChatFoodDto(food.getId(), food.getFoodName(), food.getPrice(), food.getCalories(),
                food.getAllergies() == null ? List.of() : List.copyOf(food.getAllergies()));
    }
}
