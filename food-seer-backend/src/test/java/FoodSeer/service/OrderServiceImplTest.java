// java
package FoodSeer.service;

import FoodSeer.constant.OrderStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.ArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.ArgumentCaptor;

import FoodSeer.TestUtils;
import FoodSeer.dto.NewOrderRequest;
import FoodSeer.dto.OrderDto;
import FoodSeer.dto.DriverStatsDto;
import FoodSeer.entity.Order;
import FoodSeer.entity.Food;
import FoodSeer.entity.User;
import FoodSeer.entity.DriverStats;
import FoodSeer.exception.ResourceNotFoundException;
import FoodSeer.mapper.OrderMapper;
import FoodSeer.mapper.DriverStatsMapper;
import FoodSeer.repositories.FoodRepository;
import FoodSeer.repositories.OrderRepository;
import FoodSeer.service.impl.OrderServiceImpl;

@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    @Mock
    private FoodRepository foodRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private InventoryService inventoryService;

    @Mock
    private UserService userService;

    @Mock
    private DriverStatsService driverStatsService;

    @Mock
    private StockService stockService;

    @Mock
    private OrderStateMachine stateMachine;

    @InjectMocks
    private OrderServiceImpl orderService;

    private static Food food(long id, String name, int price) {
        Food f = new Food();
        f.setId(id);
        f.setFoodName(name);
        f.setPrice(price);
        return f;
    }

    @Test
    void createOrder_whenNoAuthenticatedUser_throwsBeforeReservingStock() {
        NewOrderRequest dto = NewOrderRequest.of("Lunch", TestUtils.foodIds(List.of(food(5L, "COFFEE", 3))));
        when(userService.getCurrentUser()).thenReturn(null);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> orderService.createOrder(dto));
        assertEquals("No authenticated user found", ex.getMessage());
        verifyNoInteractions(stockService);
    }

    @Test
    void createOrder_success_reservesStockAndReturnsMappedDto() {
        NewOrderRequest request = NewOrderRequest.of("MyOrder", TestUtils.foodIds(List.of(food(5L, null, 0))));
        Food managedFood = food(5L, "COFFEE", 3);
        when(stockService.reserve(List.of(5L))).thenReturn(List.of(managedFood));
        User currentUser = new User();
        currentUser.setUsername("customer");
        when(userService.getCurrentUser()).thenReturn(currentUser);
        Order saved = new Order(100L, "MyOrder");
        when(orderRepository.save(any(Order.class))).thenReturn(saved);

        try (MockedStatic<OrderMapper> mocked = mockStatic(OrderMapper.class)) {
            mocked.when(() -> OrderMapper.mapToOrderDto(saved)).thenReturn(new OrderDto(100L, "MyOrder"));

            OrderDto result = orderService.createOrder(request);

            assertEquals(100L, result.getId());
            verify(stockService).reserve(List.of(5L));
            verify(orderRepository, times(1)).save(any(Order.class));
        }
    }

    @Test
    void createOrder_rejectsAnEmptyOrder() {
        NewOrderRequest dto = NewOrderRequest.of("Nothing", List.of());

        assertThrows(IllegalArgumentException.class, () -> orderService.createOrder(dto));
        assertThrows(IllegalArgumentException.class, () -> orderService.createOrder(new NewOrderRequest("x", null)));
        verify(orderRepository, never()).save(any(Order.class));
        verifyNoInteractions(stockService);
    }

    @Test
    void createOrder_whenStockIsShort_savesNothing() {
        NewOrderRequest dto = NewOrderRequest.of("Too much", TestUtils.foodIds(List.of(food(5L, null, 0))));
        User currentUser = new User();
        currentUser.setUsername("customer");
        when(userService.getCurrentUser()).thenReturn(currentUser);
        when(stockService.reserve(anyList())).thenThrow(new IllegalArgumentException("Not enough COFFEE in stock"));

        assertThrows(IllegalArgumentException.class, () -> orderService.createOrder(dto));
        verify(orderRepository, never()).save(any(Order.class));
    }

    /**
     * The browser used to send cost, deliveryCost and status, and the server
     * stored them. They are now ignored and computed from the menu.
     */
    @Test
    void createOrder_ignoresClientPriceAndStatus() {
        Food burger = food(1L, "BURGER", 10);
        Food tea = food(2L, "TEA", 2);
        when(stockService.reserve(List.of(1L, 1L, 2L))).thenReturn(List.of(burger, burger, tea));
        User currentUser = new User();
        currentUser.setUsername("customer");
        when(userService.getCurrentUser()).thenReturn(currentUser);
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        // Two burgers and a tea. The request type has no price, delivery fee
        // or status at all, so there is nothing to forge (see the JSON test
        // in OrderControllerTest for a request that tries anyway).
        NewOrderRequest request = NewOrderRequest.of("Cheap lunch", List.of(1L, 1L, 2L));

        orderService.createOrder(request);

        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(saved.capture());
        // Items 10 + 10 + 2 = 22.00; delivery 30% = 6.60; total 28.60.
        assertEquals(new BigDecimal("6.60"), saved.getValue().getDeliveryCost());
        assertEquals(new BigDecimal("28.60"), saved.getValue().getCost());
        assertEquals(OrderStatus.PLACED, saved.getValue().getStatus());
        assertFalse(saved.getValue().getIsFulfilled());
    }

    @Test
    void createOrder_recordsWhoWhatAndWhen() {
        Food soup = food(3L, "SOUP", 6);
        when(stockService.reserve(List.of(3L))).thenReturn(List.of(soup));
        User currentUser = new User();
        currentUser.setUsername("customer");
        currentUser.setDietaryRestrictions("PEANUTS");
        when(userService.getCurrentUser()).thenReturn(currentUser);
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        final java.time.LocalDateTime before = java.time.LocalDateTime.now();

        orderService.createOrder(NewOrderRequest.of("Soup night", List.of(3L)));

        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(saved.capture());
        Order order = saved.getValue();
        assertEquals("Soup night", order.getName());
        assertEquals(List.of(soup), order.getFoods());
        assertEquals(1, order.getLines().size());
        assertEquals("SOUP", order.getLines().get(0).getFoodName());
        assertSame(currentUser, order.getUser());
        assertEquals("PEANUTS", order.getAllergyRestrictions());
        assertNotNull(order.getCreatedAt());
        assertFalse(order.getCreatedAt().isBefore(before));
    }

    @Test
    void createOrder_byNonCustomer_isRefusedBeforeTouchingStock() {
        User staff = new User();
        staff.setUsername("staff");
        staff.setRole("ROLE_STAFF");
        when(userService.getCurrentUser()).thenReturn(staff);

        assertThrows(SecurityException.class,
                () -> orderService.createOrder(NewOrderRequest.of("Nope", List.of(1L))));
        verify(stockService, never()).reserve(anyList());
    }

    @Test
    void getAllOrders_mapsEveryOrder() {
        Order a = new Order(1L, "A");
        Order b = new Order(2L, "B");
        when(orderRepository.findAll()).thenReturn(List.of(a, b));

        List<OrderDto> all = orderService.getAllOrders();

        assertEquals(List.of(1L, 2L), all.stream().map(OrderDto::getId).toList());
    }

    @Test
    void getOrderById_whenNotFound_throwsResourceNotFound() {
        when(orderRepository.findById(77L)).thenReturn(Optional.empty());
        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> orderService.getOrderById(77L));
        assertTrue(ex.getMessage().contains("Order does not exist with id 77"));
    }

    @Test
    void getOrderById_mapsAndReturnsDto() {
        Order order = new Order();
        order.setId(2L);
        when(orderRepository.findById(2L)).thenReturn(Optional.of(order));
        try (MockedStatic<OrderMapper> mocked = mockStatic(OrderMapper.class)) {
            mocked.when(() -> OrderMapper.mapToOrderDto(order)).thenReturn(new OrderDto(2L, "X"));
            OrderDto dto = orderService.getOrderById(2L);
            assertEquals(2L, dto.getId());
        }
    }

    @Test
    void fulfilledAndActiveListsComeFromStatusQueries() {
        Order delivered = new Order(1L, "A");
        Order active = new Order(2L, "B");
        when(orderRepository.findByStatus(OrderStatus.DELIVERED)).thenReturn(List.of(delivered));
        when(orderRepository.findByStatusIn(List.of(OrderStatus.PLACED, OrderStatus.CLAIMED, OrderStatus.PICKED_UP)))
                .thenReturn(List.of(active));
        try (MockedStatic<OrderMapper> mocked = mockStatic(OrderMapper.class)) {
            mocked.when(() -> OrderMapper.mapToOrderDto(delivered)).thenReturn(new OrderDto(1L, "A"));
            mocked.when(() -> OrderMapper.mapToOrderDto(active)).thenReturn(new OrderDto(2L, "B"));

            assertEquals(1L, orderService.getAllFulfilledOrders().get(0).getId());
            // Active excludes CANCELLED as well as DELIVERED.
            assertEquals(2L, orderService.getAllUnfulfilledOrders().get(0).getId());
        }
    }

    @Test
    void getCurrentUserOrders_and_variants_requireAuthentication() {
        // No user -> expect exceptions for current-user methods
        when(userService.getCurrentUser()).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> orderService.getCurrentUserOrders());
        assertThrows(IllegalStateException.class, () -> orderService.getCurrentUserFulfilledOrders());
        assertThrows(IllegalStateException.class, () -> orderService.getCurrentUserUnfulfilledOrders());

        // With user present, repository calls should be forwarded and mapped
        User user = new User();
        user.setUsername("u1");
        when(userService.getCurrentUser()).thenReturn(user);

        Order o = new Order(); o.setId(7L);
        when(orderRepository.findByUser(user)).thenReturn(List.of(o));
        when(orderRepository.findByUserAndStatusIn(user, List.of(OrderStatus.DELIVERED))).thenReturn(List.of(o));
        when(orderRepository.findByUserAndStatusIn(user,
                List.of(OrderStatus.PLACED, OrderStatus.CLAIMED, OrderStatus.PICKED_UP))).thenReturn(List.of(o));

        try (MockedStatic<OrderMapper> mocked = mockStatic(OrderMapper.class)) {
            mocked.when(() -> OrderMapper.mapToOrderDto(o)).thenReturn(new OrderDto(7L, "UOrder"));
            assertEquals(1, orderService.getCurrentUserOrders().size());
            assertEquals(1, orderService.getCurrentUserFulfilledOrders().size());
            assertEquals(1, orderService.getCurrentUserUnfulfilledOrders().size());
        }
    }

    @Test
    void updateOrder_whenNotFound_throwsException() {
        when(orderRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> orderService.updateOrder(999L, "any", "Picked Up"));
        verifyNoInteractions(stateMachine);
    }

    @Test
    void updateOrder_parsesTheStatusAndUsesTheStateMachine() {
        Order order = new Order(201L, "Dinner");
        User driver = new User();
        driver.setUsername("driverB");
        when(orderRepository.findById(201L)).thenReturn(Optional.of(order));
        when(userService.getCurrentUser()).thenReturn(driver);
        when(stateMachine.transition(order, OrderStatus.DELIVERED, driver)).thenReturn(order);

        try (MockedStatic<OrderMapper> mocked = mockStatic(OrderMapper.class)) {
            mocked.when(() -> OrderMapper.mapToOrderDto(order)).thenReturn(new OrderDto(201L, "Dinner"));

            // The old label and the enum name both work.
            assertEquals(201L, orderService.updateOrder(201L, "driverB", "Delivered").getId());
            assertEquals(201L, orderService.updateOrder(201L, "driverB", "DELIVERED").getId());
        }
        verify(stateMachine, times(2)).transition(order, OrderStatus.DELIVERED, driver);
    }

    @Test
    void updateOrder_rejectsMissingUnknownAndNonDriverStatuses() {
        when(orderRepository.findById(5L)).thenReturn(Optional.of(new Order(5L, "x")));

        assertThrows(IllegalArgumentException.class, () -> orderService.updateOrder(5L, "d", null));
        assertThrows(IllegalArgumentException.class, () -> orderService.updateOrder(5L, "d", "Teleported"));
        assertThrows(IllegalArgumentException.class, () -> orderService.updateOrder(5L, "d", "Cancelled"));
        assertThrows(IllegalArgumentException.class, () -> orderService.updateOrder(5L, "d", "Claimed"));
        verifyNoInteractions(stateMachine);
    }

    @Test
    void claimOrder_usesTheStateMachine() {
        Order order = new Order(300L, "Lunch");
        User driver = new User();
        driver.setUsername("drv");
        when(orderRepository.findById(300L)).thenReturn(Optional.of(order));
        when(userService.getCurrentUser()).thenReturn(driver);
        when(stateMachine.transition(order, OrderStatus.CLAIMED, driver)).thenReturn(order);

        try (MockedStatic<OrderMapper> mocked = mockStatic(OrderMapper.class)) {
            mocked.when(() -> OrderMapper.mapToOrderDto(order)).thenReturn(new OrderDto(300L, "Lunch"));
            assertEquals(300L, orderService.claimOrder(300L).getId());
        }
    }

    @Test
    void cancelOrder_usesTheStateMachine() {
        Order order = new Order(310L, "Lunch");
        User customer = new User();
        customer.setUsername("cust");
        when(orderRepository.findById(310L)).thenReturn(Optional.of(order));
        when(userService.getCurrentUser()).thenReturn(customer);
        when(stateMachine.transition(order, OrderStatus.CANCELLED, customer)).thenReturn(order);

        try (MockedStatic<OrderMapper> mocked = mockStatic(OrderMapper.class)) {
            mocked.when(() -> OrderMapper.mapToOrderDto(order)).thenReturn(new OrderDto(310L, "Lunch"));
            assertEquals(310L, orderService.cancelOrder(310L).getId());
        }
        verify(stateMachine).transition(order, OrderStatus.CANCELLED, customer);
    }

    @Test
    void claimOrder_unknownOrderIsNotFound() {
        when(orderRepository.findById(404L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> orderService.claimOrder(404L));
    }

    @Test
    void getAvailableOrders_and_getActiveOrders_mapResults() {
        Order p1 = new Order(); p1.setId(300L);
        when(orderRepository.findByStatus(OrderStatus.PLACED)).thenReturn(List.of(p1));

        Order active = new Order(); active.setId(301L);
        when(orderRepository.findByDriverUsernameAndStatusIn(
            eq("drv"),
            eq(List.of(OrderStatus.CLAIMED, OrderStatus.PICKED_UP))
        )).thenReturn(List.of(active));

        try (MockedStatic<OrderMapper> mocked = mockStatic(OrderMapper.class)) {
            mocked.when(() -> OrderMapper.mapToOrderDto(p1)).thenReturn(new OrderDto(300L, "Placed"));
            mocked.when(() -> OrderMapper.mapToOrderDto(active)).thenReturn(new OrderDto(301L, "Active"));

            List<OrderDto> avail = orderService.getAvailableOrders();
            assertEquals(1, avail.size());
            assertEquals(300L, avail.get(0).getId());

            List<OrderDto> act = orderService.getActiveOrders("drv");
            assertEquals(1, act.size());
            assertEquals(301L, act.get(0).getId());
        }
    }
}