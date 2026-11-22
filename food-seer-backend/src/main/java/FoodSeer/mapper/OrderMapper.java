package FoodSeer.mapper;

import java.util.HashSet;
import java.util.stream.Collectors;

import FoodSeer.dto.OrderDto;
import FoodSeer.dto.OrderLineDto;
import FoodSeer.entity.Order;

/**
 * Maps an Order entity to the OrderDto sent to clients.
 */
public class OrderMapper {

    private OrderMapper() {
    }

    /**
     * Converts an Order entity to an OrderDto. Items come from the snapshot
     * taken when the order was placed; orders placed before snapshots existed
     * fall back to the foods as they are now.
     *
     * @param order the Order entity to convert
     * @return the corresponding OrderDto
     */
    public static OrderDto mapToOrderDto(final Order order) {
        final OrderDto dto = new OrderDto(order.getId(), order.getName());

        if (order.getLines() != null && !order.getLines().isEmpty()) {
            dto.setFoods(order.getLines().stream().map(OrderLineDto::of).collect(Collectors.toList()));
        } else if (order.getFoods() != null) {
            dto.setFoods(order.getFoods().stream().map(OrderLineDto::of).collect(Collectors.toList()));
        }

        dto.setIsFulfilled(order.getIsFulfilled());
        dto.setRatedFoodIds(order.getRatedFoodIds() == null ? new HashSet<>() : new HashSet<>(order.getRatedFoodIds()));
        dto.setCost(order.getCost());
        dto.setRating(order.getRating());
        dto.setStatus(order.getStatus() == null ? null : order.getStatus().label());
        dto.setDeliveryCost(order.getDeliveryCost());
        dto.setCreatedAt(order.getCreatedAt());
        dto.setAllergyRestrictions(order.getAllergyRestrictions());
        dto.setDriverUsername(order.getDriver() == null ? null : order.getDriver().getUsername());
        dto.setCustomerUsername(order.getUser() == null ? null : order.getUser().getUsername());
        return dto;
    }
}
