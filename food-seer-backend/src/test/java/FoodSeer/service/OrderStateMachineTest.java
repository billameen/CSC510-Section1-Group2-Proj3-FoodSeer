package FoodSeer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import FoodSeer.constant.OrderStatus;
import FoodSeer.entity.DriverStats;
import FoodSeer.entity.Order;
import FoodSeer.entity.User;
import FoodSeer.repositories.DriverStatsRepository;
import FoodSeer.repositories.OrderRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderStateMachineTest {

    @Mock
    private OrderRepository orders;
    @Mock
    private DriverStatsRepository driverStats;
    @Mock
    private DriverStatsService earnings;
    @Mock
    private AllergyHandoffService handoff;
    @Mock
    private StockService stock;
    @Mock
    private FoodSeer.live.OrderEventRecorder timeline;

    @InjectMocks
    private OrderStateMachine machine;

    private User customer;
    private User otherCustomer;
    private User driver;
    private User otherDriver;
    private User staff;
    private User admin;

    private static User user(final String name, final String role) {
        return User.builder().username(name).email(name + "@x.test").password("h").role(role).build();
    }

    @BeforeEach
    void setUp() {
        customer = user("cust", "ROLE_CUSTOMER");
        otherCustomer = user("other", "ROLE_CUSTOMER");
        driver = user("drv", "ROLE_DRIVER");
        otherDriver = user("drv2", "ROLE_DRIVER");
        staff = user("staff", "ROLE_STAFF");
        admin = user("admin", "ROLE_ADMIN");
        final DriverStats stats = new DriverStats();
        stats.setUsername("drv");
        lenient().when(driverStats.findByUsername("drv")).thenReturn(Optional.of(stats));
        lenient().when(orders.saveAndFlush(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /** An order of the given status that belongs to "cust" and, once claimed, to driver "drv". */
    private Order order(final OrderStatus status) {
        final Order order = new Order(1L, "Dinner");
        order.setUser(customer);
        order.setStatus(status);
        order.setDeliveryCost(new BigDecimal("4.50"));
        if (status != OrderStatus.PLACED) {
            final DriverStats assigned = new DriverStats();
            assigned.setUsername("drv");
            order.setDriver(assigned);
        }
        return order;
    }

    /** Someone who may make the move to {@code to}, so only the table decides. */
    private User allowedActorFor(final OrderStatus to) {
        return to == OrderStatus.CANCELLED ? admin : driver;
    }

    private static final Set<String> ALLOWED = Set.of(
            "PLACED>CLAIMED", "PLACED>CANCELLED",
            "CLAIMED>PICKED_UP", "CLAIMED>CANCELLED",
            "PICKED_UP>DELIVERED", "PICKED_UP>CANCELLED");

    static Stream<Arguments> everyPair() {
        return Stream.of(OrderStatus.values())
                .flatMap(from -> Stream.of(OrderStatus.values()).map(to -> Arguments.of(from, to)));
    }

    /** Acceptance criterion: every (from, to) pair; allowed pairs succeed, all others are 409. */
    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("everyPair")
    void everyPairInTheTransitionTable(final OrderStatus from, final OrderStatus to) {
        final Order order = order(from);
        final User actor = allowedActorFor(to);

        if (ALLOWED.contains(from + ">" + to)) {
            assertEquals(to, machine.transition(order, to, actor).getStatus());
            // Exactly one timeline entry, recording where it came from.
            verify(timeline).transitioned(order, from, actor);
        } else {
            assertThrows(IllegalStateException.class, () -> machine.transition(order, to, actor));
            assertEquals(from, order.getStatus(), "a refused move leaves the order as it was");
            verify(orders, never()).saveAndFlush(any());
            verify(timeline, never()).transitioned(any(), any(), any());
        }
    }

    @Test
    void onlyDriversClaimAndTheyBecomeTheAssignedDriver() {
        assertThrows(SecurityException.class, () -> machine.transition(order(OrderStatus.PLACED), OrderStatus.CLAIMED, customer));
        assertThrows(SecurityException.class, () -> machine.transition(order(OrderStatus.PLACED), OrderStatus.CLAIMED, staff));

        final Order claimed = machine.transition(order(OrderStatus.PLACED), OrderStatus.CLAIMED, driver);
        assertEquals("drv", claimed.getDriver().getUsername());
    }

    @Test
    void onlyTheAssignedDriverPicksUpAndDelivers() {
        assertThrows(SecurityException.class, () -> machine.transition(order(OrderStatus.CLAIMED), OrderStatus.PICKED_UP, otherDriver));
        assertThrows(SecurityException.class, () -> machine.transition(order(OrderStatus.CLAIMED), OrderStatus.PICKED_UP, admin));
        assertThrows(SecurityException.class, () -> machine.transition(order(OrderStatus.PICKED_UP), OrderStatus.DELIVERED, otherDriver));
        assertThrows(SecurityException.class, () -> machine.transition(order(OrderStatus.PICKED_UP), OrderStatus.DELIVERED, staff));
    }

    @Test
    void pickupNeedsBothAllergyAcknowledgementsWhenTheOrderHasRestrictions() {
        final Order order = order(OrderStatus.CLAIMED);
        order.setAllergyRestrictions("PEANUTS");

        when(handoff.isAcknowledged(1L, "STAFF")).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> machine.transition(order, OrderStatus.PICKED_UP, driver));

        when(handoff.isAcknowledged(1L, "STAFF")).thenReturn(true);
        when(handoff.isAcknowledged(1L, "DRIVER")).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> machine.transition(order, OrderStatus.PICKED_UP, driver));

        when(handoff.isAcknowledged(1L, "DRIVER")).thenReturn(true);
        assertEquals(OrderStatus.PICKED_UP, machine.transition(order, OrderStatus.PICKED_UP, driver).getStatus());
    }

    @Test
    void deliveryPaysTheDriverExactlyOnce() {
        final Order order = order(OrderStatus.PICKED_UP);

        machine.transition(order, OrderStatus.DELIVERED, driver);
        // A repeated "Delivered" is refused by the table, so nothing is paid twice.
        assertThrows(IllegalStateException.class, () -> machine.transition(order, OrderStatus.DELIVERED, driver));

        verify(earnings, times(1)).updateTotalEarnings("drv", new BigDecimal("4.50"));
        verify(stock, never()).release(any());
    }

    @Test
    void anOrderWithNoDeliveryCostPaysZeroInsteadOfFailing() {
        final Order order = order(OrderStatus.PICKED_UP);
        order.setDeliveryCost(null);

        machine.transition(order, OrderStatus.DELIVERED, driver);

        verify(earnings).updateTotalEarnings("drv", BigDecimal.ZERO);
    }

    @Test
    void customersCancelTheirOwnPlacedOrdersOnly() {
        final Order own = order(OrderStatus.PLACED);
        assertEquals(OrderStatus.CANCELLED, machine.transition(own, OrderStatus.CANCELLED, customer).getStatus());
        verify(stock).release(own);

        assertThrows(SecurityException.class, () -> machine.transition(order(OrderStatus.PLACED), OrderStatus.CANCELLED, otherCustomer));
        assertThrows(IllegalStateException.class, () -> machine.transition(order(OrderStatus.CLAIMED), OrderStatus.CANCELLED, customer));
        assertThrows(SecurityException.class, () -> machine.transition(order(OrderStatus.PLACED), OrderStatus.CANCELLED, driver));
    }

    @Test
    void staffAndAdminCancelAnythingNotDeliveredAndStockComesBack() {
        for (final OrderStatus from : new OrderStatus[] { OrderStatus.PLACED, OrderStatus.CLAIMED, OrderStatus.PICKED_UP }) {
            final Order order = order(from);
            machine.transition(order, OrderStatus.CANCELLED, staff);
            verify(stock).release(order);
        }
        assertThrows(IllegalStateException.class, () -> machine.transition(order(OrderStatus.DELIVERED), OrderStatus.CANCELLED, admin));
        verify(earnings, never()).updateTotalEarnings(anyString(), any());
    }

    @Test
    void nobodySignedInIsRefused() {
        assertThrows(SecurityException.class, () -> machine.transition(order(OrderStatus.PLACED), OrderStatus.CLAIMED, null));
    }

    @Test
    void aMoveTheActorMayNotMakeRecordsNothing() {
        final Order order = order(OrderStatus.PLACED);

        assertThrows(SecurityException.class,
                () -> machine.transition(order, OrderStatus.CLAIMED, user("staff", "ROLE_STAFF")));

        verify(timeline, never()).transitioned(any(), any(), any());
    }

    @Test
    void theEventIsRecordedAfterTheSaveSoItSharesItsTransaction() {
        final Order order = order(OrderStatus.PLACED);
        final User driver = allowedActorFor(OrderStatus.CLAIMED);

        machine.transition(order, OrderStatus.CLAIMED, driver);

        final org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(orders, timeline);
        inOrder.verify(orders).saveAndFlush(order);
        inOrder.verify(timeline).transitioned(order, OrderStatus.PLACED, driver);
    }

    @Test
    void aDriverWithoutStatsCantClaim() {
        final Order order = order(OrderStatus.PLACED);
        when(driverStats.findByUsername("nostats")).thenReturn(java.util.Optional.empty());

        assertThrows(FoodSeer.exception.ResourceNotFoundException.class,
                () -> machine.transition(order, OrderStatus.CLAIMED, user("nostats", "ROLE_DRIVER")));
        verify(timeline, never()).transitioned(any(), any(), any());
    }

    @Test
    void sharingTheAssignedDriversUsernameIsNotEnoughWithoutTheDriverRole() {
        final Order order = order(OrderStatus.CLAIMED);
        final String assigned = order.getDriver().getUsername();

        assertThrows(SecurityException.class,
                () -> machine.transition(order, OrderStatus.PICKED_UP, user(assigned, "ROLE_STAFF")));
    }
}
