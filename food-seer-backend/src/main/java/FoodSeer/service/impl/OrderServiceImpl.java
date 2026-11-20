package FoodSeer.service.impl;

import FoodSeer.constant.OrderStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import FoodSeer.dto.NewOrderRequest;
import FoodSeer.dto.OrderDto;
import FoodSeer.entity.Food;
import FoodSeer.entity.Order;
import FoodSeer.entity.OrderLine;
import FoodSeer.exception.ResourceNotFoundException;
import FoodSeer.mapper.OrderMapper;
import FoodSeer.entity.User;
import FoodSeer.repositories.OrderRepository;
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

    /** Orders still on their way: placed, claimed or picked up. Not delivered or cancelled. */
    private static final List<OrderStatus> ACTIVE = List.of(OrderStatus.PLACED, OrderStatus.CLAIMED,
            OrderStatus.PICKED_UP);

    Logger logger = LoggerFactory.getLogger(OrderServiceImpl.class);

    /** Repository for orders. */
    @Autowired
    private OrderRepository orderRepository;

    /** User service for getting current user. */
    @Autowired
    private UserService userService;

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
     * @param request order to create
     * @return created order
     */
    @Override
    @Transactional
    public OrderDto createOrder(final NewOrderRequest request) {
        if (request == null || request.foods() == null || request.foods().isEmpty()) {
            throw new IllegalArgumentException("An order needs at least one item.");
        }
        final User currentUser = userService.getCurrentUser();
        if (currentUser == null) {
            throw new IllegalStateException("No authenticated user found");
        }

        // Validates every line, then decrements; prices come from the menu.
        final List<Food> foods = stockService.reserve(request.foodIds());

        final BigDecimal itemsTotal = OrderPricing.itemsTotal(foods);
        final BigDecimal deliveryCost = OrderPricing.deliveryCost(itemsTotal);

        // Create order entity directly (not using mapper to avoid creating new Food objects)
        final Order order = new Order();
        order.setName(request.name());
        order.setFoods(foods);
        // Snapshot each unit as it is now, so later menu edits don't rewrite this order.
        order.setLines(foods.stream().map(OrderLine::of).collect(Collectors.toList()));
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
    @Transactional(readOnly = true)
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
    @Transactional(readOnly = true)
    public List<OrderDto> getAllOrders() {
        final List<Order> orders = orderRepository.findAll();
        return orders.stream().map(OrderMapper::mapToOrderDto).collect(Collectors.toList());
    }

    /**
     * Returns all fulfilled orders.
     *
     * @return list of fulfilled orders
     */
    @Override
    @Transactional(readOnly = true)
    public List<OrderDto> getAllFulfilledOrders() {
        return map(orderRepository.findByStatus(OrderStatus.DELIVERED));
    }

    /**
     * Returns orders that are still active (placed, claimed or picked up).
     *
     * @return list of unfulfilled orders
     */
    @Override
    @Transactional(readOnly = true)
    public List<OrderDto> getAllUnfulfilledOrders() {
        return map(orderRepository.findByStatusIn(ACTIVE));
    }

    /**
     * Returns all orders for the current authenticated user.
     *
     * @return list of current user's orders
     */
    @Override
    @Transactional(readOnly = true)
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
    @Transactional(readOnly = true)
    public List<OrderDto> getCurrentUserFulfilledOrders() {
        final User currentUser = userService.getCurrentUser();
        if (currentUser == null) {
            throw new IllegalStateException("No authenticated user found");
        }
        
        return map(orderRepository.findByUserAndStatusIn(currentUser, List.of(OrderStatus.DELIVERED)));
    }

    /**
     * Returns the current user's orders that are still active (placed,
     * claimed or picked up).
     *
     * @return list of current user's unfulfilled orders
     */
    @Override
    @Transactional(readOnly = true)
    public List<OrderDto> getCurrentUserUnfulfilledOrders() {
        final User currentUser = userService.getCurrentUser();
        if (currentUser == null) {
            throw new IllegalStateException("No authenticated user found");
        }
        
        return map(orderRepository.findByUserAndStatusIn(currentUser, ACTIVE));
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

    private static List<OrderDto> map(final List<Order> orders) {
        return orders.stream().map(OrderMapper::mapToOrderDto).collect(Collectors.toList());
    }

    private Order load(final Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order does not exist with id " + orderId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderDto> getAvailableOrders(){
        List<Order> availableOrders = orderRepository.findByStatus(OrderStatus.PLACED);
        return availableOrders.stream().map(OrderMapper::mapToOrderDto).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
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
