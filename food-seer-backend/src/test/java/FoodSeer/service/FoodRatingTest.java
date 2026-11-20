package FoodSeer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import FoodSeer.dto.FoodDto;
import FoodSeer.entity.Food;
import FoodSeer.entity.Order;
import FoodSeer.repositories.FoodRepository;
import FoodSeer.repositories.InventoryRepository;
import FoodSeer.repositories.OrderRepository;
import FoodSeer.service.impl.FoodServiceImpl;

/**
 * Rating rules in the service. Who may rate (the order's own customer) is
 * checked by @PreAuthorize and covered by AuthorizationMatrixTest.
 */
@ExtendWith(MockitoExtension.class)
class FoodRatingTest {

    @Mock
    private FoodRepository foodRepository;
    @Mock
    private InventoryRepository inventoryRepository;
    @Mock
    private InventoryService inventoryService;
    @Mock
    private OrderRepository orderRepository;
    @InjectMocks
    private FoodServiceImpl foodService;

    private Food food;
    private Order order;

    @BeforeEach
    void setUp() {
        food = new Food("PAD THAI", 10, 12, new ArrayList<>());
        food.setId(7L);
        order = new Order(3L, "Dinner");
        order.setFoods(new ArrayList<>(List.of(food)));
        when(orderRepository.findById(3L)).thenReturn(Optional.of(order));
    }

    @Test
    void cannotRateBeforeDelivery() {
        for (final String status : new String[] { "Placed", "Claimed", "Picked Up" }) {
            order.setStatus(status);
            // Staff marking it fulfilled is not enough either.
            order.setIsFulfilled(true);

            assertThrows(IllegalStateException.class, () -> foodService.rateFoodInOrder(3L, 7L, 4.0), status);
        }
        verify(foodRepository, never()).saveAndFlush(any());
    }

    @Test
    void ratesADeliveredOrderOncePerDish() {
        order.setStatus("Delivered");
        order.setIsFulfilled(true);
        when(foodRepository.findById(7L)).thenReturn(Optional.of(food));
        when(foodRepository.saveAndFlush(food)).thenReturn(food);

        final FoodDto rated = foodService.rateFoodInOrder(3L, 7L, 4.0);

        assertEquals(4.0, rated.getRating());
        assertEquals(1, rated.getNumberOfRatings());
        assertThrows(IllegalStateException.class, () -> foodService.rateFoodInOrder(3L, 7L, 5.0));
    }

    @Test
    void rejectsAnOutOfRangeRatingOrADishNotInTheOrder() {
        order.setStatus("Delivered");

        assertThrows(IllegalArgumentException.class, () -> foodService.rateFoodInOrder(3L, 7L, 6.0));
        assertThrows(IllegalArgumentException.class, () -> foodService.rateFoodInOrder(3L, 99L, 4.0));
    }
}
