package FoodSeer.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import FoodSeer.config.SpringSecurityConfig;
import FoodSeer.dto.DriverStatsDto;
import FoodSeer.dto.OrderDto;
import FoodSeer.entity.AllergyAcknowledgment;
import FoodSeer.entity.DriverStats;
import FoodSeer.entity.Order;
import FoodSeer.entity.User;
import FoodSeer.repositories.OrderRepository;
import FoodSeer.repositories.UserRepository;
import FoodSeer.service.AllergenService;
import FoodSeer.service.AllergyHandoffService;
import FoodSeer.service.AuthService;
import FoodSeer.service.CalorieBudgetService;
import FoodSeer.service.ChatService;
import FoodSeer.service.DriverStatsService;
import FoodSeer.service.FoodService;
import FoodSeer.service.InventoryService;
import FoodSeer.service.NotificationService;
import FoodSeer.service.OrderService;
import FoodSeer.service.TraceService;
import FoodSeer.service.UserService;
import FoodSeer.service.impl.JwtAccessDeniedHandler;
import FoodSeer.service.impl.JwtAuthenticationEntryPoint;

/**
 * Runs every row of docs/authorization-matrix.md against the production
 * security setup: URL rules, the JWT filter, password login and method
 * security. Services and repositories are mocked, so an allowed call returns
 * 2xx and the only thing under test is who gets in.
 *
 * matrixCoversEveryEndpointAndEachHasARoleRule fails if a controller gains an
 * endpoint with no row here, or without its own @PreAuthorize.
 *
 * Each row is one endpoint. The expected column lists the result for
 * anonymous, CUSTOMER, DRIVER, STAFF and ADMIN in that order: OK means 2xx,
 * 401 and 403 mean exactly that status. Rows that test ownership use a
 * second customer or driver who does not own the order.
 */
@WebMvcTest(controllers = {
    AllergenController.class, AllergyHandoffController.class, AuthController.class, ChatController.class,
    DriverStatsController.class, FoodController.class, IncidentController.class, InventoryController.class,
    NotificationController.class, OrderController.class, UserController.class
}, properties = {
    "app.jwt-secret=Zm9vZHNlZXItdGVzdC1vbmx5LXNpZ25pbmcta2V5LTMyaXRlbXM=",
    "app.jwt-expiration-milliseconds=60000"
})
@Import({ SpringSecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class,
    AuthorizationMatrixTest.SecurityComponents.class })
class AuthorizationMatrixTest {

    /** Picks up every bean in FoodSeer.security: the JWT filter, token provider, user details and access checks. */
    @TestConfiguration
    @ComponentScan("FoodSeer.security")
    static class SecurityComponents {
    }

    private static final String PASSWORD = "matrix-test-password";

    /** Callers in the order the expected column lists them. */
    private static final String[] ROLE_COLUMNS = { null, "customer", "driver", "staff", "admin" };

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthenticationManager authenticationManager;
    @Autowired
    private FoodSeer.security.JwtTokenProvider tokenProvider;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockBean
    private UserRepository users;
    @MockBean
    private OrderRepository orders;

    @MockBean
    private AuthService authService;
    @MockBean
    private UserService userService;
    @MockBean
    private CalorieBudgetService calorieBudgetService;
    @MockBean
    private FoodService foodService;
    @MockBean
    private AllergenService allergenService;
    @MockBean
    private InventoryService inventoryService;
    @MockBean
    private OrderService orderService;
    @MockBean
    private AllergyHandoffService allergyHandoffService;
    @MockBean
    private DriverStatsService driverStatsService;
    @MockBean
    private ChatService chatService;
    @MockBean(answer = Answers.RETURNS_DEEP_STUBS)
    private TraceService traceService;
    @MockBean
    private NotificationService notificationService;

    /** One row of the matrix. */
    record Row(String method, String path, String body, String expected) {
        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    private static Row row(final String method, final String path, final String body, final String expected) {
        return new Row(method, path, body, expected);
    }

    /** The whole table. Keep it in the same order as docs/authorization-matrix.md. */
    static List<Row> matrix() {
        return List.of(
            // Auth
            row("POST", "/auth/register",
                "{\"username\":\"newuser\",\"email\":\"new@example.test\",\"password\":\"password1\",\"role\":\"customer\"}",
                "OK OK OK OK OK"),
            row("POST", "/auth/login", "{\"username\":\"customer\",\"password\":\"" + PASSWORD + "\"}",
                "OK OK OK OK OK"),

            // Users
            row("GET", "/api/users", null, "401 403 403 403 OK"),
            row("POST", "/api/users",
                "{\"username\":\"newstaff\",\"email\":\"staff2@example.test\",\"password\":\"password1\",\"role\":\"staff\"}",
                "401 403 403 403 OK"),
            row("GET", "/api/users/1", null, "401 403 403 403 OK"),
            row("PUT", "/api/users/1/role", "{\"role\":\"STAFF\"}", "401 403 403 403 OK"),
            row("DELETE", "/api/users/1", null, "401 403 403 403 OK"),
            row("GET", "/api/users/me", null, "401 OK OK OK OK"),
            row("PUT", "/api/users/me/preferences", "{\"costPreference\":\"budget\",\"dietaryRestrictions\":\"\"}",
                "401 OK 403 403 403"),
            row("GET", "/api/users/me/calorieBudget", null, "401 OK 403 403 403"),
            row("POST", "/api/users/me/mealLog", "{\"mealType\":\"BREAKFAST\",\"calories\":300}",
                "401 OK 403 403 403"),
            row("GET", "/api/users/me/calorieHistory", null, "401 OK 403 403 403"),

            // Menu, allergens and inventory
            row("GET", "/api/foods", null, "401 OK OK OK OK"),
            row("GET", "/api/foods/1", null, "401 OK OK OK OK"),
            row("GET", "/api/foods/withinCalorieBudget", null, "401 OK 403 403 403"),
            row("POST", "/api/foods", "{\"foodName\":\"NEW DISH\",\"amount\":5,\"price\":9,\"allergies\":[]}",
                "401 403 403 OK OK"),
            row("POST", "/api/foods/updateFood", "{\"foodName\":\"PAD THAI\",\"amount\":5,\"price\":9,\"allergies\":[]}",
                "401 403 403 OK OK"),
            row("DELETE", "/api/foods/1", null, "401 403 403 OK OK"),
            row("POST", "/api/foods/orders/1/1/rate?rating=5", null, "401 OK 403 403 403"),
            row("GET", "/api/allergens", null, "401 OK OK OK OK"),
            row("POST", "/api/allergens", "{\"name\":\"KIWI\"}", "401 403 403 OK OK"),
            row("GET", "/api/inventory", null, "401 OK 403 OK OK"),
            row("POST", "/api/inventory", "{\"foods\":[]}", "401 403 403 OK OK"),

            // Orders
            row("GET", "/api/orders", null, "401 403 403 OK OK"),
            row("GET", "/api/orders/fulfilledOrders", null, "401 403 403 OK OK"),
            row("GET", "/api/orders/unfulfilledOrders", null, "401 403 403 OK OK"),
            row("GET", "/api/orders/my", null, "401 OK 403 403 403"),
            row("GET", "/api/orders/my/fulfilled", null, "401 OK 403 403 403"),
            row("GET", "/api/orders/my/unfulfilled", null, "401 OK 403 403 403"),
            row("POST", "/api/orders", "{\"name\":\"Lunch\",\"foods\":[{\"id\":1}]}", "401 OK 403 403 403"),
            row("POST", "/api/orders/fulfillOrder", "{\"id\":1}", "401 403 403 OK OK"),
            row("GET", "/api/orders/1", null, "401 OK OK OK OK"),
            row("POST", "/api/orders/1", "{\"username\":\"driver\",\"status\":\"Picked Up\"}", "401 403 OK 403 403"),
            row("POST", "/api/orders/1/claim", null, "401 403 OK 403 403"),
            row("GET", "/api/orders/availableOrders", null, "401 403 OK 403 OK"),
            row("GET", "/api/orders/activeOrders/driver", null, "401 403 OK 403 OK"),
            row("POST", "/api/orders/1/allergy-handoff/acknowledge", null, "401 403 OK OK 403"),
            row("GET", "/api/orders/1/allergy-handoff/status", null, "401 403 OK OK 403"),

            // Drivers
            row("GET", "/api/driverStats?username=driver", null, "401 403 OK 403 OK"),

            // Allergy incidents and notices
            row("POST", "/api/incidents",
                "{\"allergen\":\"PEANUTS\",\"foodIds\":[1],\"windowStart\":\"2026-11-18T12:00:00\",\"windowEnd\":\"2026-11-18T13:00:00\"}",
                "401 403 403 OK OK"),
            row("POST", "/api/incidents/reaction", "{\"orderId\":1,\"allergen\":\"PEANUTS\"}", "401 OK 403 403 403"),
            row("GET", "/api/incidents", null, "401 403 403 OK OK"),
            row("POST", "/api/incidents/1/acknowledge", "{\"remedy\":\"Free meal\"}", "401 403 403 OK OK"),
            row("POST", "/api/incidents/1/confirm", "{\"foodIds\":[1]}", "401 403 403 OK OK"),
            row("POST", "/api/incidents/1/dismiss", null, "401 403 403 OK OK"),
            row("GET", "/api/notifications/my", null, "401 OK 403 403 403"),
            row("GET", "/api/notifications/my/unread-count", null, "401 OK 403 403 403"),
            row("POST", "/api/notifications/1/acknowledge", null, "401 OK 403 403 403"),

            // Chat
            row("POST", "/api/chat", "{\"message\":\"hi\"}", "401 OK 403 OK OK"));
    }

    /**
     * Ownership rows: the caller has the right role but is not the order's
     * customer or driver, or asks about another driver.
     */
    static Stream<Arguments> ownershipCases() {
        return Stream.of(
            Arguments.of("customer2", "GET", "/api/orders/1", null, 403),
            Arguments.of("driver2", "GET", "/api/orders/1", null, 403),
            Arguments.of("customer2", "POST", "/api/foods/orders/1/1/rate?rating=5", null, 403),
            Arguments.of("driver2", "GET", "/api/orders/activeOrders/driver", null, 403),
            Arguments.of("driver2", "GET", "/api/driverStats?username=driver", null, 403),
            // The customer's own order, reached by logging in with their email address.
            Arguments.of("customer@example.test", "GET", "/api/orders/1", null, 200),
            Arguments.of("driver@example.test", "GET", "/api/driverStats?username=driver", null, 200));
    }

    /** Every (row, role) pair. */
    static Stream<Arguments> cells() {
        final List<Arguments> cells = new ArrayList<>();
        for (final Row r : matrix()) {
            final String[] expected = r.expected().split(" ");
            if (expected.length != ROLE_COLUMNS.length) {
                throw new IllegalStateException("Row " + r + " needs " + ROLE_COLUMNS.length + " results");
            }
            for (int i = 0; i < ROLE_COLUMNS.length; i++) {
                final String caller = ROLE_COLUMNS[i] == null ? "anonymous" : ROLE_COLUMNS[i];
                cells.add(Arguments.of(r, caller, expected[i]));
            }
        }
        return cells.stream();
    }

    @BeforeEach
    void setUp() {
        final String hash = passwordEncoder.encode(PASSWORD);
        for (final String name : new String[] { "customer", "customer2", "driver", "driver2", "staff", "admin" }) {
            final String role = "ROLE_" + name.replaceAll("[0-9]", "").toUpperCase();
            final User user = User.builder().id(name.hashCode()).username(name).email(name + "@example.test")
                    .password(hash).role(role).build();
            when(users.findByUsernameOrEmail(name, name)).thenReturn(Optional.of(user));
            when(users.findByUsernameOrEmail(user.getEmail(), user.getEmail())).thenReturn(Optional.of(user));
            when(users.findByUsername(name)).thenReturn(Optional.of(user));
        }

        // Order 1 belongs to "customer" and is assigned to "driver".
        final User owner = users.findByUsername("customer").orElseThrow();
        final DriverStats assigned = new DriverStats();
        assigned.setUsername("driver");
        final Order order = new Order(1L, "Lunch");
        order.setUser(owner);
        order.setDriver(assigned);
        order.setStatus("Delivered");
        order.setIsFulfilled(true);
        when(orders.findById(1L)).thenReturn(Optional.of(order));

        // Happy-path stubs so an allowed call returns 2xx.
        final User me = users.findByUsername("customer").orElseThrow();
        when(userService.getCurrentUser()).thenReturn(me);
        when(userService.findById(anyLong())).thenReturn(me);
        when(userService.updateUserRole(anyLong(), anyString())).thenReturn(me);
        when(userService.updateUserPreferences(anyString(), any(), any(), any())).thenReturn(me);
        when(foodService.isValidFood(any())).thenReturn(true);
        when(foodService.isDuplicateName("PAD THAI")).thenReturn(true);
        final OrderDto orderDto = new OrderDto(1L, "Lunch");
        when(orderService.getOrderById(1L)).thenReturn(orderDto);
        when(orderService.fulfillOrder(anyLong())).thenReturn(orderDto);
        when(driverStatsService.getDriverStats("driver"))
                .thenReturn(new DriverStatsDto("driver", 1, BigDecimal.ONE, BigDecimal.ONE, 0));
        final AllergyAcknowledgment ack = mock(AllergyAcknowledgment.class);
        when(ack.getActorRole()).thenReturn("STAFF");
        when(ack.getActorUsername()).thenReturn("staff");
        when(ack.getAcknowledgedAt()).thenReturn(Instant.now());
        when(allergyHandoffService.acknowledge(eq(1L), anyString())).thenReturn(ack);
    }

    @ParameterizedTest(name = "{0} as {1} -> {2}")
    @MethodSource("cells")
    void matrixCell(final Row row, final String caller, final String expected) throws Exception {
        final int status = perform(caller, row.method(), row.path(), row.body());
        assertStatus(row + " as " + caller, expected, status);
    }

    @ParameterizedTest(name = "{1} {2} as {0} -> {4}")
    @MethodSource("ownershipCases")
    void ownership(final String caller, final String method, final String path, final String body,
            final int expected) throws Exception {
        final int status = perform(caller, method, path, body);
        assertStatus(method + " " + path + " as " + caller, expected == 200 ? "OK" : String.valueOf(expected),
                status);
    }

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    /**
     * Every controller endpoint has at least one row in the matrix, and every
     * endpoint outside /auth has its own @PreAuthorize. Adding an endpoint
     * without either fails here.
     */
    @Test
    void matrixCoversEveryEndpointAndEachHasARoleRule() {
        final AntPathMatcher matcher = new AntPathMatcher();
        final List<String> problems = new ArrayList<>();
        handlerMapping.getHandlerMethods().forEach((info, handler) -> {
            if (!handler.getBeanType().getPackageName().equals("FoodSeer.controller")) {
                return;
            }
            for (final String pattern : info.getPatternValues()) {
                for (final RequestMethod method : info.getMethodsCondition().getMethods()) {
                    final boolean covered = matrix().stream()
                            .anyMatch(r -> r.method().equals(method.name())
                                    && matcher.match(pattern, r.path().split("\\?")[0]));
                    if (!covered) {
                        problems.add("no matrix row for " + method + " " + pattern);
                    }
                    if (!pattern.startsWith("/auth/")
                            && handler.getMethodAnnotation(PreAuthorize.class) == null) {
                        problems.add("no @PreAuthorize on " + method + " " + pattern);
                    }
                }
            }
        });
        if (!problems.isEmpty()) {
            throw new AssertionError(String.join("\n", problems));
        }
    }

    private int perform(final String caller, final String method, final String path, final String body)
            throws Exception {
        MockHttpServletRequestBuilder req = request(HttpMethod.valueOf(method), path);
        if (body != null) {
            req = req.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        if (!"anonymous".equals(caller)) {
            req = req.header("Authorization", bearer(caller));
        }
        return mvc.perform(req).andReturn().getResponse().getStatus();
    }

    private static void assertStatus(final String what, final String expected, final int actual) {
        if ("OK".equals(expected)) {
            if (actual < 200 || actual > 299) {
                throw new AssertionError(what + ": expected 2xx but was " + actual);
            }
        } else if (Integer.parseInt(expected) != actual) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }

    /** A real signed token from a real password login, as the frontend gets it. */
    private String bearer(final String login) {
        final var authentication = authenticationManager
                .authenticate(new UsernamePasswordAuthenticationToken(login, PASSWORD));
        return "Bearer " + tokenProvider.generateToken(authentication);
    }
}
