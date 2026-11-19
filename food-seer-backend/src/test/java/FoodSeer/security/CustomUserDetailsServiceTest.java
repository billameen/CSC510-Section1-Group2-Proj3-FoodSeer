package FoodSeer.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import FoodSeer.entity.User;
import FoodSeer.repositories.UserRepository;

class CustomUserDetailsServiceTest {

    private final UserRepository users = mock(UserRepository.class);
    private final CustomUserDetailsService service = new CustomUserDetailsService(users);

    private static User user(final String role) {
        return User.builder().username("sid").email("sid@example.com").password("hash").role(role).build();
    }

    @Test
    void emailLoginStillUsesTheUsernameAsPrincipal() {
        when(users.findByUsernameOrEmail("sid@example.com", "sid@example.com"))
                .thenReturn(Optional.of(user("ROLE_CUSTOMER")));

        final UserDetails details = service.loadUserByUsername("sid@example.com");

        assertEquals("sid", details.getUsername());
        assertTrue(details.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_CUSTOMER")));
    }

    @Test
    void accountWithoutAValidRoleCannotLogIn() {
        for (final String role : new String[] { "", "ROLE_WIZARD" }) {
            when(users.findByUsernameOrEmail("sid", "sid")).thenReturn(Optional.of(user(role)));

            final DisabledException e = assertThrows(DisabledException.class, () -> service.loadUserByUsername("sid"));
            assertTrue(e.getMessage().contains("role"));
        }
    }

    @Test
    void unknownLoginIsNotFound() {
        when(users.findByUsernameOrEmail("ghost", "ghost")).thenReturn(Optional.empty());

        assertThrows(UsernameNotFoundException.class, () -> service.loadUserByUsername("ghost"));
    }
}
