package FoodSeer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import FoodSeer.dto.OrderDto;
import FoodSeer.entity.Food;
import FoodSeer.entity.User;
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
}
