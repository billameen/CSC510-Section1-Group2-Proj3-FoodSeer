package FoodSeer.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import FoodSeer.constant.OrderStatus;
import FoodSeer.dto.OrderDto;
import FoodSeer.dto.OrderLineDto;
import FoodSeer.entity.DriverStats;
import FoodSeer.entity.Food;
import FoodSeer.entity.Order;
import FoodSeer.entity.OrderLine;
import FoodSeer.entity.User;

public class OrderMapperTest {

    private Order order;
    private Food pizza;
    private Food burger;

    @BeforeEach
    public void setUp() {
        pizza = new Food("Pizza", 2, 15, Arrays.asList("GLUTEN", "DAIRY"));
        pizza.setId(1L);
        burger = new Food("Burger", 1, 9, Arrays.asList("GLUTEN"));
        burger.setId(2L);

        order = new Order(1L, "Test Order");
        order.setFoods(new ArrayList<>(List.of(pizza, burger)));
        order.setLines(new ArrayList<>(List.of(OrderLine.of(pizza), OrderLine.of(burger))));
        order.setCost(new BigDecimal("25.00"));
        order.setStatus(OrderStatus.PLACED);
        order.setDeliveryCost(new BigDecimal("5.00"));
        order.setRating(new BigDecimal("4.8"));
        order.setRatedFoodIds(new HashSet<>(Arrays.asList(1L, 2L)));
    }

    @Test
    public void mapsTheOrderAsPlainValues() {
        final User customer = new User();
        customer.setUsername("sid");
        order.setUser(customer);
        final DriverStats driver = new DriverStats();
        driver.setUsername("drv");
        order.setDriver(driver);

        final OrderDto dto = OrderMapper.mapToOrderDto(order);

        assertEquals(1L, dto.getId());
        assertEquals("Test Order", dto.getName());
        assertEquals(new BigDecimal("25.00"), dto.getCost());
        assertEquals(new BigDecimal("5.00"), dto.getDeliveryCost());
        assertEquals("Placed", dto.getStatus());
        assertFalse(dto.getIsFulfilled());
        assertEquals("sid", dto.getCustomerUsername());
        assertEquals("drv", dto.getDriverUsername());
        assertEquals(2, dto.getRatedFoodIds().size());
        assertEquals(new OrderLineDto(1L, "PIZZA", new BigDecimal("15"), List.of("GLUTEN", "DAIRY"), null),
                dto.getFoods().get(0));
    }

    /** History is the snapshot, not today's menu. */
    @Test
    public void laterMenuEditsDontChangeTheOrder() {
        pizza.setFoodName("PIZZA DELUXE");
        pizza.setPrice(99);
        pizza.setAllergies(new ArrayList<>(List.of("GLUTEN", "DAIRY", "EGGS")));

        final OrderLineDto line = OrderMapper.mapToOrderDto(order).getFoods().get(0);

        assertEquals("PIZZA", line.foodName());
        assertEquals(new BigDecimal("15"), line.price());
        assertEquals(List.of("GLUTEN", "DAIRY"), line.allergies());
    }

    /** Orders placed before Milestone 3 have no snapshot; they show the foods. */
    @Test
    public void oldOrdersWithoutASnapshotFallBackToTheFoods() {
        order.setLines(new ArrayList<>());

        final OrderDto dto = OrderMapper.mapToOrderDto(order);

        assertEquals(2, dto.getFoods().size());
        assertEquals("BURGER", dto.getFoods().get(1).foodName());
    }

    @Test
    public void edgeCases() {
        order.setLines(new ArrayList<>());
        order.setFoods(new ArrayList<>());
        order.setRatedFoodIds(null);

        final OrderDto dto = OrderMapper.mapToOrderDto(order);

        assertTrue(dto.getFoods().isEmpty());
        assertNotNull(dto.getRatedFoodIds());
        assertNull(dto.getDriverUsername());
        assertNull(dto.getCustomerUsername());

        final OrderLine noAllergens = new OrderLine(3L, "Salad", new BigDecimal("7"), null, 120);
        assertTrue(noAllergens.getAllergens().isEmpty());
    }

    @Test
    public void deliveredOrdersAreFulfilled() {
        order.setStatus(OrderStatus.DELIVERED);

        final OrderDto dto = OrderMapper.mapToOrderDto(order);

        assertTrue(dto.getIsFulfilled());
        assertEquals("Delivered", dto.getStatus());
    }

    @Test
    public void ratingHelpers() {
        assertTrue(order.hasFoodBeenRated(1L));
        assertFalse(order.hasFoodBeenRated(3L));

        order.addRatedFoodId(3L);
        order.addRatedFoodId(3L);

        assertEquals(3, order.getRatedFoodIds().size());
    }
}
