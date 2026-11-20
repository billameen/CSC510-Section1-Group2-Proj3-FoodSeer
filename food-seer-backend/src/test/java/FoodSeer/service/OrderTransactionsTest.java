package FoodSeer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import FoodSeer.constant.OrderStatus;
import FoodSeer.dto.OrderDto;
import FoodSeer.entity.DriverStats;
import FoodSeer.entity.Food;
import FoodSeer.entity.User;
import FoodSeer.repositories.DriverStatsRepository;
import FoodSeer.repositories.FoodRepository;
import FoodSeer.repositories.OrderRepository;
import FoodSeer.repositories.UserRepository;

/**
 * Real transactions against the test database (no @Transactional on the test,
 * so commits and rollbacks happen as they would in production).
 */
@SpringBootTest
class OrderTransactionsTest {

    @Autowired
    private OrderService orderService;
    @Autowired
    private FoodRepository foodRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private DriverStatsRepository driverStatsRepository;

    private final List<Long> foodIds = new ArrayList<>();
    private User customer;

    @BeforeEach
    void setUp() {
        customer = userRepository.save(User.builder().username("txn_customer").email("txn@example.test")
                .password("x").role("ROLE_CUSTOMER").build());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "txn_customer", null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        orderRepository.deleteAll(orderRepository.findByUser(customer));
        for (final String name : new String[] { "race_driver_a", "race_driver_b" }) {
            driverStatsRepository.findByUsername(name).ifPresent(driverStatsRepository::delete);
            userRepository.findByUsername(name).ifPresent(userRepository::delete);
        }
        foodRepository.deleteAllById(foodIds);
        userRepository.delete(customer);
    }

    private Food food(final String name, final int amount) {
        final Food saved = foodRepository.save(new Food(name, amount, 4, new ArrayList<>()));
        foodIds.add(saved.getId());
        return saved;
    }

    private static Food ref(final Food food) {
        final Food ref = new Food();
        ref.setId(food.getId());
        return ref;
    }

    /** Acceptance criterion: item 2 out of stock leaves item 1's stock unchanged. */
    @Test
    void outOfStockSecondItemRollsBackTheFirst() {
        final Food first = food("TXN FIRST", 5);
        final Food second = food("TXN SECOND", 0);
        final OrderDto order = new OrderDto(null, "Rollback");
        order.setFoods(List.of(ref(first), ref(second)));

        assertThrows(IllegalArgumentException.class, () -> orderService.createOrder(order));

        assertEquals(5, foodRepository.findById(first.getId()).orElseThrow().getAmount());
        assertEquals(0, foodRepository.findById(second.getId()).orElseThrow().getAmount());
        assertEquals(0, orderRepository.findByUser(customer).size(), "no order should be saved");
    }

    @Test
    void placingAnOrderReservesStockOnce() {
        final Food coffee = food("TXN COFFEE", 5);
        final OrderDto order = new OrderDto(null, "Two coffees");
        order.setFoods(List.of(ref(coffee), ref(coffee)));

        orderService.createOrder(order);

        assertEquals(3, foodRepository.findById(coffee.getId()).orElseThrow().getAmount());
    }

    @Test
    void cancellingReleasesTheReservedStockOnce() {
        final Food coffee = food("TXN CANCEL COFFEE", 5);
        final OrderDto order = new OrderDto(null, "Changed my mind");
        order.setFoods(List.of(ref(coffee), ref(coffee)));
        final Long id = orderService.createOrder(order).getId();
        assertEquals(3, foodRepository.findById(coffee.getId()).orElseThrow().getAmount());

        orderService.cancelOrder(id);

        assertEquals(5, foodRepository.findById(coffee.getId()).orElseThrow().getAmount());
        assertEquals(OrderStatus.CANCELLED, orderRepository.findById(id).orElseThrow().getStatus());
        assertThrows(IllegalStateException.class, () -> orderService.cancelOrder(id));
        assertEquals(5, foodRepository.findById(coffee.getId()).orElseThrow().getAmount(), "released only once");
    }

    private void driver(final String name) {
        userRepository.save(User.builder().username(name).email(name + "@example.test").password("x")
                .role("ROLE_DRIVER").build());
        final DriverStats stats = new DriverStats();
        stats.setUsername(name);
        driverStatsRepository.save(stats);
    }

    private static void signInAs(final String name, final String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(name, null,
                List.of(new SimpleGrantedAuthority(role))));
    }

    /**
     * Acceptance criterion: two threads claim the same order and exactly one
     * succeeds. The loser gets a 409, either from the @Version check (both
     * read it before either saved) or from the transition table (it read the
     * order after the winner saved). Repeated so both orderings get exercised.
     */
    @Test
    void twoDriversClaimingTheSameOrderExactlyOneWins() throws Exception {
        driver("race_driver_a");
        driver("race_driver_b");
        final Food coffee = food("RACE COFFEE", 50);

        for (int round = 0; round < 10; round++) {
            signInAs("txn_customer", "ROLE_CUSTOMER");
            final OrderDto placed = new OrderDto(null, "Race " + round);
            placed.setFoods(List.of(ref(coffee)));
            final Long orderId = orderService.createOrder(placed).getId();

            final CyclicBarrier start = new CyclicBarrier(2);
            final ExecutorService pool = Executors.newFixedThreadPool(2);
            final List<Future<String>> results = new ArrayList<>();
            for (final String name : new String[] { "race_driver_a", "race_driver_b" }) {
                results.add(pool.submit(() -> {
                    signInAs(name, "ROLE_DRIVER");
                    start.await();
                    try {
                        orderService.claimOrder(orderId);
                        return "won";
                    } catch (final ObjectOptimisticLockingFailureException | IllegalStateException e) {
                        return "409";
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                }));
            }
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

            final List<String> outcomes = new ArrayList<>();
            for (final Future<String> f : results) {
                outcomes.add(f.get());
            }
            assertEquals(1, outcomes.stream().filter("won"::equals).count(), "round " + round + ": " + outcomes);
            assertEquals(1, outcomes.stream().filter("409"::equals).count(), "round " + round + ": " + outcomes);
            assertEquals(OrderStatus.CLAIMED, orderRepository.findById(orderId).orElseThrow().getStatus());
        }
    }
}
