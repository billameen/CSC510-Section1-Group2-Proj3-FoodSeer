package FoodSeer.controller;

import java.util.List;

import FoodSeer.dto.UpdateOrderDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.parameters.P;
import org.springframework.web.bind.annotation.*;

import FoodSeer.dto.OrderDto;
import FoodSeer.exception.ResourceNotFoundException;
import FoodSeer.service.OrderService;

/**
 * Controller for Orders in the FoodSeer system.
 * Provides endpoints for managing and fulfilling food orders.
 */
@CrossOrigin("*")
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    Logger logger = LoggerFactory.getLogger(OrderController.class);
    /** Connection to OrderService */
    @Autowired
    private OrderService orderService;

    /**
     * Retrieves all orders in the system. Staff and admin only: orders carry
     * every customer's allergy restrictions.
     *
     * @return JSON list of all orders
     */
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    @GetMapping
    public List<OrderDto> getOrders() {
        return orderService.getAllOrders();
    }

    /**
     * Retrieves all fulfilled orders.
     *
     * @return JSON list of fulfilled orders
     */
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    @GetMapping("/fulfilledOrders")
    public List<OrderDto> getFulfilledOrders() {
        return orderService.getAllFulfilledOrders();
    }

    /**
     * Retrieves all unfulfilled orders.
     *
     * @return JSON list of unfulfilled orders
     */
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    @GetMapping("/unfulfilledOrders")
    public List<OrderDto> getUnfulfilledOrders() {
        return orderService.getAllUnfulfilledOrders();
    }

    /**
     * Creates a new order for the signed-in customer.
     *
     * @param orderDto the order to create
     * @return ResponseEntity containing the created order
     */
    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping
    public ResponseEntity<OrderDto> createOrder(@RequestBody final OrderDto orderDto) {
        logger.info("Creating order: {}", orderDto.toString());
        final OrderDto savedOrderDto = orderService.createOrder(orderDto);
        return ResponseEntity.ok(savedOrderDto);
    }

    /**
     * Retrieves a specific order by ID: staff and admin, the customer who
     * placed it, or the driver assigned to it.
     *
     * @param id the ID of the order
     * @return ResponseEntity containing the order or 404 if not found
     */
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')"
            + " or (hasRole('CUSTOMER') and @orderAccess.isOwner(authentication, #id))"
            + " or (hasRole('DRIVER') and @orderAccess.isAssignedDriver(authentication, #id))")
    @GetMapping("/{id}")
    public ResponseEntity<OrderDto> getOrder(@P("id") @PathVariable("id") final Long id) {
        try {
            final OrderDto orderDto = orderService.getOrderById(id);
            return ResponseEntity.ok(orderDto);
        } catch (final ResourceNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
        }
    }

    /**
     * Retrieves all orders for the current authenticated user.
     *
     * @return JSON list of current user's orders
     */
    @PreAuthorize("hasRole('CUSTOMER')")
    @GetMapping("/my")
    public List<OrderDto> getMyOrders() {
        return orderService.getCurrentUserOrders();
    }

    /**
     * Retrieves fulfilled orders for the current authenticated user.
     *
     * @return JSON list of current user's fulfilled orders
     */
    @PreAuthorize("hasRole('CUSTOMER')")
    @GetMapping("/my/fulfilled")
    public List<OrderDto> getMyFulfilledOrders() {
        return orderService.getCurrentUserFulfilledOrders();
    }

    /**
     * Retrieves unfulfilled orders for the current authenticated user.
     *
     * @return JSON list of current user's unfulfilled orders
     */
    @PreAuthorize("hasRole('CUSTOMER')")
    @GetMapping("/my/unfulfilled")
    public List<OrderDto> getMyUnfulfilledOrders() {
        return orderService.getCurrentUserUnfulfilledOrders();
    }

    /**
     * Delivery status update (Picked Up, Delivered). Drivers only; the service
     * checks that the caller is the driver assigned to this order (403 if not).
     *
     * @param id the order
     * @param updateOrderDto the new status
     * @return the updated order
     */
    @PreAuthorize("hasRole('DRIVER')")
    @PostMapping("/{id}")
    public ResponseEntity<?> updateOrderStatus(@PathVariable("id") final Long id, @RequestBody UpdateOrderDto updateOrderDto){
        logger.info("Updating order status for order ID: {}", id);
        final OrderDto updatedOrderDto = orderService.updateOrder(id, updateOrderDto.username(), updateOrderDto.status());
        return ResponseEntity.ok(updatedOrderDto);
    }

    @PreAuthorize("hasRole('DRIVER')")
    @PostMapping("/{id}/claim")
    public ResponseEntity<OrderDto> claimOrder(@PathVariable("id") final Long id) {
        // 404 unknown order, 409 already claimed (including a lost race), 403 not a driver
        return ResponseEntity.ok(orderService.claimOrder(id));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'DRIVER')")
    @GetMapping("/availableOrders")
    public ResponseEntity<?> getAvailableOrders(){
        logger.info("Fetching all placed orders which are not picked up yet");
        List<OrderDto> availableOrders = orderService.getAvailableOrders();
        return ResponseEntity.ok(availableOrders);
    }

    // The principal is the username even after an email login, so ownership is a plain comparison.
    @PreAuthorize("hasRole('ADMIN') or (hasRole('DRIVER') and authentication.name == #username)")
    @GetMapping("/activeOrders/{username}")
    public ResponseEntity<?> getActiveOrders(@P("username") @PathVariable String username){
        logger.info("Fetching all picked up orders for the driver: {}", username);
        List<OrderDto> activeOrders = orderService.getActiveOrders(username);
        return ResponseEntity.ok(activeOrders);
    }
}
