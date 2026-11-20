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
        OrderDto dto = new OrderDto(null, "Lunch");
        dto.setFoods(List.of(food(5L, "COFFEE", 3)));
        when(userService.getCurrentUser()).thenReturn(null);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> orderService.createOrder(dto));
        assertEquals("No authenticated user found", ex.getMessage());
        verifyNoInteractions(stockService);
    }

    @Test
    void createOrder_success_reservesStockAndReturnsMappedDto() {
        OrderDto request = new OrderDto(null, "MyOrder");
        request.setFoods(List.of(food(5L, null, 0)));
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
        OrderDto dto = new OrderDto(null, "Nothing");

        assertThrows(IllegalArgumentException.class, () -> orderService.createOrder(dto));
        verify(orderRepository, never()).save(any(Order.class));
        verifyNoInteractions(stockService);
    }

    @Test
    void createOrder_whenStockIsShort_savesNothing() {
        OrderDto dto = new OrderDto(null, "Too much");
        dto.setFoods(List.of(food(5L, null, 0)));
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

        // Two burgers and a tea, with a forged price, delivery fee and status.
        OrderDto request = new OrderDto(null, "Cheap lunch");
        request.setFoods(List.of(food(1L, null, 0), food(1L, null, 0), food(2L, null, 0)));
        request.setCost(new BigDecimal("0.01"));
        request.setDeliveryCost(BigDecimal.ZERO);
        request.setStatus("Delivered");

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
    void getAllOrders_and_filtersFulfilled_unfulfilled() {
        Order o1 = new Order(); o1.setId(1L);
        Order o2 = new Order(); o2.setId(2L);
        when(orderRepository.findAll()).thenReturn(List.of(o1, o2));
        try (MockedStatic<OrderMapper> mocked = mockStatic(OrderMapper.class)) {
            OrderDto d1 = new OrderDto(1L, "A"); d1.setIsFulfilled(true);
            OrderDto d2 = new OrderDto(2L, "B"); d2.setIsFulfilled(false);
            mocked.when(() -> OrderMapper.mapToOrderDto(o1)).thenReturn(d1);
            mocked.when(() -> OrderMapper.mapToOrderDto(o2)).thenReturn(d2);

            List<OrderDto> all = orderService.getAllOrders();
            assertEquals(2, all.size());

            List<OrderDto> fulfilled = orderService.getAllFulfilledOrders();
            assertEquals(1, fulfilled.size());
            assertTrue(fulfilled.get(0).getIsFulfilled());

            List<OrderDto> unfulfilled = orderService.getAllUnfulfilledOrders();
            assertEquals(1, unfulfilled.size());
            assertFalse(unfulfilled.get(0).getIsFulfilled());
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
        when(orderRepository.findByUserAndIsFulfilled(user, true)).thenReturn(List.of(o));
        when(orderRepository.findByUserAndIsFulfilled(user, false)).thenReturn(List.of(o));

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

        assertThrows(
            ResourceNotFoundException.class,
            () -> orderService.updateOrder(999L, "any", "Picked Up")
        );

        verify(orderRepository, never()).save(any());
    }

//    @Test
//    void updateOrder_pickedUp_setsDriver_and_persists() {
//        Order order = new Order();
//        order.setId(200L);
//        order.setStatus("Placed");
//        order.setIsFulfilled(false);
//
//        when(orderRepository.findById(200L)).thenReturn(Optional.of(order));
//        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
//
//        DriverStatsDto statsDto = new DriverStatsDto();
//        when(driverStatsService.getDriverStats("driverA")).thenReturn(statsDto);
//
//        DriverStats mapped = new DriverStats();
//        mapped.setUsername("driverA");
//
//        try (MockedStatic<DriverStatsMapper> mocked = mockStatic(DriverStatsMapper.class)) {
//            mocked.when(() -> DriverStatsMapper.mapToDriverStats(statsDto)).thenReturn(mapped);
//
//            OrderDto dto = orderService.updateOrder(200L, "driverA", "Picked Up");
//
//            assertNotNull(dto);
//            ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
//            verify(orderRepository, times(1)).save(captor.capture());
//            Order saved = captor.getValue();
//            assertNotNull(saved.getDriver());
//            assertEquals("driverA", saved.getDriver().getUsername());
//            assertFalse(saved.getIsFulfilled());
//        }
//    }

    @Test
    void updateOrder_delivered_bySameDriver_setsFulfilled_and_updatesEarnings() {
        Order order = new Order();
        order.setId(201L);
        order.setStatus(OrderStatus.PICKED_UP);
        order.setDeliveryCost(BigDecimal.valueOf(15));

        DriverStats driver = new DriverStats();
        driver.setUsername("driverB");
        order.setDriver(driver);

        when(orderRepository.findById(201L)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        User currentUser = new User();
        currentUser.setUsername("driverB");
        currentUser.setRole("ROLE_DRIVER");

        when(userService.getCurrentUser()).thenReturn(currentUser);

        OrderDto dto = orderService.updateOrder(201L, "driverB", "Delivered");

        assertNotNull(dto);
        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository, times(1)).save(captor.capture());
        Order saved = captor.getValue();
        assertTrue(saved.getIsFulfilled());
        verify(driverStatsService, times(1)).updateTotalEarnings("driverB", saved.getDeliveryCost());
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