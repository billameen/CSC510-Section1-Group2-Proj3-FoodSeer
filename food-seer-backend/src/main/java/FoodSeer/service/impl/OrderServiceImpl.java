package FoodSeer.service.impl;

import FoodSeer.constant.OrderStatus;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import FoodSeer.dto.DriverStatsDto;
import FoodSeer.entity.DriverStats;
import FoodSeer.mapper.DriverStatsMapper;
import FoodSeer.service.AllergyHandoffService;
import FoodSeer.service.DriverStatsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import FoodSeer.dto.InventoryDto;
import FoodSeer.dto.OrderDto;
import FoodSeer.entity.Food;
import FoodSeer.entity.Order;
import FoodSeer.exception.ResourceNotFoundException;
import FoodSeer.mapper.OrderMapper;
import FoodSeer.entity.User;
import FoodSeer.repositories.FoodRepository;
import FoodSeer.repositories.OrderRepository;
import FoodSeer.service.InventoryService;
import FoodSeer.service.OrderPricing;
import FoodSeer.service.OrderService;
import FoodSeer.service.OrderStateMachine;
import FoodSeer.service.StockService;
import FoodSeer.service.UserService;

/**
 * Implementation of the OrderService interface for managing food orders.
 */
@Service
public class OrderServiceImpl implements OrderService {

    Logger logger = LoggerFactory.getLogger(OrderServiceImpl.class);
    /** Repository for food items. */
    @Autowired
    private FoodRepository foodRepository;

    /** Repository for allergy handoff */
    @Autowired
    private AllergyHandoffService allergyHandoffService;

    /** Repository for orders. */
    @Autowired
    private OrderRepository orderRepository;

    /** Inventory service for stock management. */
    @Autowired
    private InventoryService inventoryService;

    /** User service for getting current user. */
    @Autowired
    private UserService userService;

    @Autowired
    private DriverStatsService driverStatsService;

    /** Reserves stock when an order is placed. */
    @Autowired
    private StockService stockService;

    /** Every status change goes through here. */
    @Autowired
    private OrderStateMachine stateMachine;

    /**
     * Creates an order for the signed-in customer and reserves its stock, all
     * in one transaction: if any item is missing or short, nothing is
     * reserved and nothing is saved (400). Only the order's name and the ids
     * of its foods are taken from the request. Cost, delivery cost and status
     * are set here, so a client can't choose its own price or skip ahead in
     * the delivery flow.
     *
     * @param orderDto order to create
     * @return created order
     */
    @Override
    @Transactional
    public OrderDto createOrder(final OrderDto orderDto) {
        if (orderDto == null || orderDto.getFoods() == null || orderDto.getFoods().isEmpty()) {
            throw new IllegalArgumentException("An order needs at least one item.");
        }
        final User currentUser = userService.getCurrentUser();
        if (currentUser == null) {
            throw new IllegalStateException("No authenticated user found");
        }

        final List<Long> foodIds = new ArrayList<>();
        for (final Food food : orderDto.getFoods()) {
            foodIds.add(food == null ? null : food.getId());
        }
        // Validates every line, then decrements; prices come from the menu.
        final List<Food> foods = stockService.reserve(foodIds);

        final BigDecimal itemsTotal = OrderPricing.itemsTotal(foods);
        final BigDecimal deliveryCost = OrderPricing.deliveryCost(itemsTotal);

        // Create order entity directly (not using mapper to avoid creating new Food objects)
        final Order order = new Order();
        order.setName(orderDto.getName());
        order.setFoods(foods);
        order.setCost(itemsTotal.add(deliveryCost));
        order.setDeliveryCost(deliveryCost);
        order.setStatus(OrderStatus.PLACED);
        // stamp the creation time so CalorieBudgetServiceImpl can later tell which orders were placed "today"
        order.setCreatedAt(java.time.LocalDateTime.now());
        
        order.setUser(currentUser);
        order.setAllergyRestrictions(currentUser.getDietaryRestrictions());
        
        final Order savedOrder = orderRepository.save(order);
        return OrderMapper.mapToOrderDto(savedOrder);
    }

    /**
     * Returns the order with the given id.
     *
     * @param orderId order's id
     * @return the order with the given id
     * @throws ResourceNotFoundException if the order doesn't exist
     */
    @Override
    public OrderDto getOrderById(final Long orderId) {
        final Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order does not exist with id " + orderId));
        return OrderMapper.mapToOrderDto(order);
    }

    /**
     * Returns a list of all the orders.
     *
     * @return all the orders
     */
    @Override
    public List<OrderDto> getAllOrders() {
        final List<Order> orders = orderRepository.findAll();
        return orders.stream().map(OrderMapper::mapToOrderDto).collect(Collectors.toList());
    }

    /**
     * Checks if all ingredients/foods for an order item are available.
     *
     * @param inventoryDto The current inventory
     * @param food         The food item to check
     * @return true if available, false otherwise
     */
    private boolean checkFoodAvailability(final InventoryDto inventoryDto, final Food food) {
        final Map<String, Food> inventoryMap = inventoryDto.getFoods().stream()
                .collect(Collectors.toMap(Food::getFoodName, f -> f));

        final Food inventoryFood = inventoryMap.get(food.getFoodName());
        return inventoryFood != null && food.getAmount() <= inventoryFood.getAmount();
    }

    /**
     * Deducts the ordered food amount from the inventory.
     *
     * @param inventoryDto The inventory data
     * @param food         The food item to deduct
     */
    private void updateInventory(final InventoryDto inventoryDto, final Food food) {
        final Map<String, Food> inventoryMap = inventoryDto.getFoods().stream()
                .collect(Collectors.toMap(Food::getFoodName, f -> f));

        final Food inventoryFood = inventoryMap.get(food.getFoodName());
        if (inventoryFood != null) {
            inventoryFood.setAmount(inventoryFood.getAmount() - food.getAmount());
        }

        inventoryService.updateInventory(inventoryDto);
    }

    /**
     * Returns all fulfilled orders.
     *
     * @return list of fulfilled orders
     */
    @Override
    public List<OrderDto> getAllFulfilledOrders() {
        return getAllOrders().stream()
                .filter(OrderDto::getIsFulfilled)
                .collect(Collectors.toList());
    }

    /**
     * Returns all unfulfilled orders.
     *
     * @return list of unfulfilled orders
     */
    @Override
    public List<OrderDto> getAllUnfulfilledOrders() {
        return getAllOrders().stream()
                .filter(o -> !o.getIsFulfilled())
                .collect(Collectors.toList());
    }

    /**
     * Returns all orders for the current authenticated user.
     *
     * @return list of current user's orders
     */
    @Override
    public List<OrderDto> getCurrentUserOrders() {
        final User currentUser = userService.getCurrentUser();
        if (currentUser == null) {
            throw new IllegalStateException("No authenticated user found");
        }
        
        final List<Order> orders = orderRepository.findByUser(currentUser);
        return orders.stream().map(OrderMapper::mapToOrderDto).collect(Collectors.toList());
    }

    /**
     * Returns fulfilled orders for the current authenticated user.
     *
     * @return list of current user's fulfilled orders
     */
    @Override
    public List<OrderDto> getCurrentUserFulfilledOrders() {
        final User currentUser = userService.getCurrentUser();
        if (currentUser == null) {
            throw new IllegalStateException("No authenticated user found");
        }
        
        final List<Order> orders = orderRepository.findByUserAndIsFulfilled(currentUser, true);
        return orders.stream().map(OrderMapper::mapToOrderDto).collect(Collectors.toList());
    }

    /**
     * Returns unfulfilled orders for the current authenticated user.
     *
     * @return list of current user's unfulfilled orders
     */
    @Override
    public List<OrderDto> getCurrentUserUnfulfilledOrders() {
        final User currentUser = userService.getCurrentUser();
        if (currentUser == null) {
            throw new IllegalStateException("No authenticated user found");
        }
        
        final List<Order> orders = orderRepository.findByUserAndIsFulfilled(currentUser, false);
        return orders.stream().map(OrderMapper::mapToOrderDto).collect(Collectors.toList());
    }

    /**
     * A driver claims a placed order. If two drivers claim at once, the
     * optimistic lock lets exactly one win; the other gets 409.
     */
    @Override
    @Transactional
    public OrderDto claimOrder(final Long orderId) {
        final Order order = load(orderId);
        return OrderMapper.mapToOrderDto(stateMachine.transition(order, OrderStatus.CLAIMED,
                userService.getCurrentUser()));
    }

    /**
     * The assigned driver moves an order to PICKED_UP or DELIVERED. A missing
     * or unknown status is a 400; a move the state machine doesn't allow is a
     * 409; anyone but the assigned driver is a 403.
     *
     * @param username ignored; the signed-in user is the actor
     */
    @Override
    @Transactional
    public OrderDto updateOrder(final Long orderId, final String username, final String status) {
        final Order order = load(orderId);
        final OrderStatus to = OrderStatus.parse(status);
        if (to != OrderStatus.PICKED_UP && to != OrderStatus.DELIVERED) {
            throw new IllegalArgumentException("Drivers can set an order to Picked Up or Delivered.");
        }
        return OrderMapper.mapToOrderDto(stateMachine.transition(order, to, userService.getCurrentUser()));
    }

    /**
     * Cancels an order; the state machine checks who may cancel it and
     * releases its stock.
     */
    @Override
    @Transactional
    public OrderDto cancelOrder(final Long orderId) {
        final Order order = load(orderId);
        return OrderMapper.mapToOrderDto(stateMachine.transition(order, OrderStatus.CANCELLED,
                userService.getCurrentUser()));
    }

    private Order load(final Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order does not exist with id " + orderId));
    }

    @Override
    public List<OrderDto> getAvailableOrders(){
        List<Order> availableOrders = orderRepository.findByStatus(OrderStatus.PLACED);
        return availableOrders.stream().map(OrderMapper::mapToOrderDto).collect(Collectors.toList());
    }

    @Override
    public List<OrderDto> getActiveOrders(String username) {
        List<Order> activeOrders =
            orderRepository.findByDriverUsernameAndStatusIn(
                username,
                List.of(OrderStatus.CLAIMED, OrderStatus.PICKED_UP)
            );

        return activeOrders.stream()
            .map(OrderMapper::mapToOrderDto)
            .collect(Collectors.toList());
    }
}
