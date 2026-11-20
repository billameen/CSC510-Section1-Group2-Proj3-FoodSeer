package FoodSeer.mapper;

import FoodSeer.constant.OrderStatus;
import java.util.stream.Collectors;
import java.util.ArrayList; // Added simple list handling

import FoodSeer.dto.OrderDto;
import FoodSeer.dto.FoodDto;
import FoodSeer.entity.Order;
import FoodSeer.entity.Food;

/**
 * Mapper class for converting between Order and OrderDto.
 */
public class OrderMapper {

    /**
     * Converts an Order entity to an OrderDto.
     *
     * @param order the Order entity to convert
     * @return the corresponding OrderDto
     */
    public static OrderDto mapToOrderDto(final Order order) {
        final OrderDto dto = new OrderDto(order.getId(), order.getName());

        // Map Food entities to FoodDto and add them
        // Note: This complex stream recreates Food objects. 
        // Ideally, ensure these new Food objects have IDs so the frontend can use them!
        dto.setFoods(order.getFoods().stream()
                .map(FoodMapper::mapToFoodDto)
                .map(foodDto -> {
                    // We recreate the Food entity here to put inside the DTO
                    Food f = new Food(
                        foodDto.getFoodName(),
                        foodDto.getAmount(),
                        foodDto.getPrice(),
                        foodDto.getAllergies()
                    );
                    // CRITICAL: We must ensure the ID is preserved,
                    // otherwise the frontend cannot rate specific items!
                    f.setId(foodDto.getId());
                    // The 4-arg Food constructor above doesn't set these -
                    // copy them through so order history shows real values
                    // instead of silently dropping them.
                    // fixes a pre-existing data-loss bug: rating was silently dropped before
                    f.setRating(foodDto.getRating());
                    // same fix, for numberOfRatings
                    f.setNumberOfRatings(foodDto.getNumberOfRatings());
                    // same fix, for the new calories field
                    f.setCalories(foodDto.getCalories());
                    return f;
                })
                .collect(Collectors.toList()));

        dto.setIsFulfilled(order.getIsFulfilled());
        
        // --- ADDED THIS LINE ---
        // Copies the "Checklist" of rated items to the DTO
        dto.setRatedFoodIds(order.getRatedFoodIds());
        // -----------------------

        dto.setCost(order.getCost());
        dto.setStatus(order.getStatus() == null ? null : order.getStatus().label());
        dto.setDeliveryCost(order.getDeliveryCost());

        dto.setAllergyRestrictions(order.getAllergyRestrictions());

//        dto.setDriverStats(DriverStatsMapper.mapToDriverStatsDto(order.getDriver()));

        return dto;
    }
}
