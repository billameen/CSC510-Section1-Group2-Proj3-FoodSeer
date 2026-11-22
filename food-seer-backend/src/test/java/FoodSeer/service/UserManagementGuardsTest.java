package FoodSeer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import FoodSeer.entity.DriverStats;
import FoodSeer.entity.User;
import FoodSeer.exception.ResourceNotFoundException;
import FoodSeer.repositories.DriverStatsRepository;
import FoodSeer.repositories.MealLogRepository;
import FoodSeer.repositories.OrderRepository;
import FoodSeer.repositories.UserRepository;
import FoodSeer.service.impl.UserServiceImpl;

/** Role changes and deletes that would break user management are refused. */
@ExtendWith(MockitoExtension.class)
class UserManagementGuardsTest {

    @Mock
    private UserRepository users;
    @Mock
    private OrderRepository orders;
    @Mock
    private MealLogRepository mealLogs;
    @Mock
    private DriverStatsRepository driverStats;
    @InjectMocks
    private UserServiceImpl service;

    private User admin;
    private User staff;

    @BeforeEach
    void setUp() {
        admin = User.builder().id(1).username("admin").email("a@x.test").password("h").role("ROLE_ADMIN").build();
        staff = User.builder().id(2).username("staff").email("s@x.test").password("h").role("ROLE_STAFF").build();
        lenient().when(users.findById(1L)).thenReturn(Optional.of(admin));
        lenient().when(users.findById(2L)).thenReturn(Optional.of(staff));
        lenient().when(users.findByUsername("admin")).thenReturn(Optional.of(admin));
        lenient().when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(orders.findByUser(any())).thenReturn(List.of());
        lenient().when(mealLogs.findByUser(any())).thenReturn(List.of());
        // Signed in as the admin.
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void adminCannotDeleteThemselves() {
        // Refused before the admin count matters.

        assertThrows(IllegalStateException.class, () -> service.deleteUser(1L));
        assertTrue(admin.isActive());
    }

    @Test
    void lastAdminCannotBeDeleted() {
        final User other = User.builder().id(3).username("admin2").email("b@x.test").password("h").role("ROLE_ADMIN")
                .build();
        when(users.findById(3L)).thenReturn(Optional.of(other));
        when(users.countByRoleAndActiveTrue("ROLE_ADMIN")).thenReturn(1L);

        assertThrows(IllegalStateException.class, () -> service.deleteUser(3L));
        assertTrue(other.isActive());
    }

    @Test
    void deletingDeactivatesTheAccountAndKeepsItsOrders() {
        service.deleteUser(2L);

        // Milestone 3: soft delete. The row and its orders stay for history and trace-back.
        assertFalse(staff.isActive());
        verify(users).save(staff);
        verify(users, never()).deleteById(anyLong());
        verifyNoInteractions(orders, mealLogs);
    }

    @Test
    void aDeletedUserIsNotFoundAgain() {
        staff.setActive(false);

        assertThrows(ResourceNotFoundException.class, () -> service.deleteUser(2L));
        assertEquals(null, service.findById(2L));
    }

    @Test
    void deletingAMissingUserIsNotFound() {
        when(users.findById(9L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.deleteUser(9L));
    }

    @Test
    void lastAdminCannotBeDemoted() {
        when(users.countByRoleAndActiveTrue("ROLE_ADMIN")).thenReturn(1L);

        assertThrows(IllegalStateException.class, () -> service.updateUserRole(1L, "staff"));
        assertEquals("ROLE_ADMIN", admin.getRole());
    }

    @Test
    void inventedOrEmptyRolesAreRejected() {
        for (final String role : new String[] { "ROLE_WIZARD", "", null }) {
            assertThrows(IllegalArgumentException.class, () -> service.updateUserRole(2L, role));
        }
        assertEquals("ROLE_STAFF", staff.getRole());
    }

    @Test
    void promotingToDriverCreatesDriverStats() {
        when(driverStats.findByUsername("staff")).thenReturn(Optional.empty());

        final User updated = service.updateUserRole(2L, "driver");

        assertEquals("ROLE_DRIVER", updated.getRole());
        verify(driverStats).save(any(DriverStats.class));
    }
}
