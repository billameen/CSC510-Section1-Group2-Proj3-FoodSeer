package FoodSeer.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Map;

import FoodSeer.constant.Roles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

import FoodSeer.dto.AuthResponseDto;
import FoodSeer.dto.LoginRequestDto;
import FoodSeer.dto.RegisterRequestDto;
import FoodSeer.entity.DriverStats;
import FoodSeer.entity.User;
import FoodSeer.repositories.DriverStatsRepository;
import FoodSeer.repositories.UserRepository;
import FoodSeer.security.JwtTokenProvider;
import FoodSeer.service.FitnessDataService;
import FoodSeer.service.impl.AuthServiceImpl;

@ExtendWith(MockitoExtension.class)
public class AuthServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private DriverStatsRepository driverStatsRepository;

    @Mock
    private FitnessDataService fitnessDataService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AuthenticationManager authManager;

    @Mock
    private JwtTokenProvider jwtService;

    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        // Constructor parameter order matches fields in AuthServiceImpl:
        // userRepository, driverStatsRepository, fitnessDataService, passwordEncoder, authManager, jwtService
        authService = new AuthServiceImpl(userRepository, driverStatsRepository, fitnessDataService, passwordEncoder, authManager, jwtService);
    }

    /** A valid customer sign-up; tests change one field at a time. */
    private static RegisterRequestDto req(String username, String email, String password, String role) {
        return new RegisterRequestDto(username, email, password, role);
    }

    private static RegisterRequestDto customer(String username) {
        return req(username, username + "@example.com", "strongPassword", "customer");
    }

    @Test
    void register_whenUsernameExists_returnsBadRequest() {
        when(userRepository.existsByUsername("takenUser")).thenReturn(true);

        ResponseEntity<Map<String, String>> resp = authService.register(customer("takenUser"));

        assertEquals(400, resp.getStatusCodeValue());
        assertEquals("Username already taken", resp.getBody().get("error"));
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void register_whenInvalidUsernameChars_returnsBadRequest() {
        ResponseEntity<Map<String, String>> resp = authService.register(customer("bad$user"));

        assertEquals(400, resp.getStatusCodeValue());
        assertTrue(resp.getBody().get("error").startsWith("Username"));
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void register_allowsDigitsInUsername() {
        when(userRepository.existsByUsername("sid2026")).thenReturn(false);
        when(userRepository.existsByEmail("sid2026@example.com")).thenReturn(false);
        when(passwordEncoder.encode("strongPassword")).thenReturn("hashedPwd");

        ResponseEntity<Map<String, String>> resp = authService.register(customer("sid2026"));

        assertEquals(200, resp.getStatusCodeValue());
        verify(userRepository).save(any(User.class));
    }

    @Test
    void register_whenPasswordTooShort_returnsBadRequest() {
        when(userRepository.existsByUsername("validUser")).thenReturn(false);

        ResponseEntity<Map<String, String>> resp = authService
                .register(req("validUser", "valid@example.com", "a", "customer"));

        assertEquals(400, resp.getStatusCodeValue());
        assertTrue(resp.getBody().get("error").startsWith("Password"));
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void register_whenPasswordSevenChars_returnsBadRequest() {
        // Boundary check: the stated policy is a minimum of 8 characters, so
        // a 7-character password must still be rejected.
        when(userRepository.existsByUsername("validUser")).thenReturn(false);

        ResponseEntity<Map<String, String>> resp = authService
                .register(req("validUser", "valid@example.com", "abc1234", "customer"));

        assertEquals(400, resp.getStatusCodeValue());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void register_whenPasswordEightChars_isAccepted() {
        // Boundary check: an 8-character password meets the stated minimum
        // and should be accepted.
        when(userRepository.existsByUsername("validUser")).thenReturn(false);
        when(userRepository.existsByEmail("validUser@example.com")).thenReturn(false);
        when(passwordEncoder.encode("abc12345")).thenReturn("hashedPwd");

        ResponseEntity<Map<String, String>> resp = authService
                .register(req("validUser", "validUser@example.com", "abc12345", "customer"));

        assertEquals(200, resp.getStatusCodeValue());
        verify(userRepository, times(1)).save(any(User.class));
    }

    @Test
    void register_whenEmailInvalid_saysEmail() {
        when(userRepository.existsByUsername("validUser")).thenReturn(false);

        ResponseEntity<Map<String, String>> resp = authService
                .register(req("validUser", "not-an-email", "strongPassword", "customer"));

        assertEquals(400, resp.getStatusCodeValue());
        assertEquals("Please enter a valid email address", resp.getBody().get("error"));
    }

    @Test
    void register_whenEmailTaken_returnsBadRequest() {
        when(userRepository.existsByUsername("second")).thenReturn(false);
        when(userRepository.existsByEmail("second@example.com")).thenReturn(true);

        ResponseEntity<Map<String, String>> resp = authService.register(customer("second"));

        assertEquals(400, resp.getStatusCodeValue());
        assertEquals("Email already in use", resp.getBody().get("error"));
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void register_rejectsMissingEmptyAndUnknownRoles() {
        for (String role : new String[] { null, "", "  ", "wizard", "ROLE_WIZARD" }) {
            ResponseEntity<Map<String, String>> resp = authService
                    .register(req("validUser", "valid@example.com", "strongPassword", role));

            assertEquals(400, resp.getStatusCodeValue(), "role " + role);
            assertTrue(resp.getBody().get("error").toLowerCase().contains("role"), "role " + role);
        }
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void register_rejectsStaffAndAdmin() {
        for (String role : new String[] { "staff", "STAFF", "ROLE_STAFF", "admin" }) {
            ResponseEntity<Map<String, String>> resp = authService
                    .register(req("validUser", "valid@example.com", "strongPassword", role));

            assertEquals(400, resp.getStatusCodeValue(), "role " + role);
        }
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void createAccount_allowsStaffForAdmins() {
        when(userRepository.existsByUsername("newStaff")).thenReturn(false);
        when(userRepository.existsByEmail("newStaff@example.com")).thenReturn(false);
        when(passwordEncoder.encode("strongPassword")).thenReturn("hashedPwd");
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);

        ResponseEntity<Map<String, String>> resp = authService
                .createAccount(req("newStaff", "newStaff@example.com", "strongPassword", "staff"));

        assertEquals(200, resp.getStatusCodeValue());
        verify(userRepository).save(saved.capture());
        assertEquals("ROLE_STAFF", saved.getValue().getRole());
    }

    @Test
    void register_whenDriverRole_createsUserAndDriverStats() {
        when(userRepository.existsByUsername("driverOne")).thenReturn(false);
        when(userRepository.existsByEmail("driver1@example.com")).thenReturn(false);
        when(passwordEncoder.encode("strongPassword")).thenReturn("hashedPwd");

        // capture saved user and driverStats
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        ArgumentCaptor<DriverStats> statsCaptor = ArgumentCaptor.forClass(DriverStats.class);

        ResponseEntity<Map<String, String>> resp = authService
                .register(req("driverOne", "driver1@example.com", "strongPassword", "driver"));

        assertEquals(200, resp.getStatusCodeValue());
        assertEquals("Registered", resp.getBody().get("message"));

        verify(userRepository, times(1)).save(userCaptor.capture());
        User saved = userCaptor.getValue();
        assertEquals("driverOne", saved.getUsername());
        assertEquals("hashedPwd", saved.getPassword());
        assertEquals(Roles.ROLE_DRIVER, saved.getRole());

        verify(driverStatsRepository, times(1)).save(statsCaptor.capture());
        DriverStats ds = statsCaptor.getValue();
        assertEquals("driverOne", ds.getUsername());

        // a driver isn't a customer, so no fitness data should be generated for them
        verify(fitnessDataService, never()).generateFitnessDataIfMissing(anyString());
    }

    /**
     * Registering as a customer must generate a starter fitness-data file,
     * so the new account has calorie-budget recommendations from day one
     * instead of needing generate_fitness_data.py run by hand.
     */
    @Test
    void register_whenCustomerRole_generatesFitnessData() {
        when(userRepository.existsByUsername("newCustomer")).thenReturn(false);
        when(userRepository.existsByEmail("newCustomer@example.com")).thenReturn(false);
        when(passwordEncoder.encode("strongPassword")).thenReturn("hashedPwd");

        ResponseEntity<Map<String, String>> resp = authService.register(customer("newCustomer"));

        assertEquals(200, resp.getStatusCodeValue());
        verify(fitnessDataService, times(1)).generateFitnessDataIfMissing("newCustomer");
        // a customer isn't a driver, so no DriverStats row should be created for them
        verify(driverStatsRepository, never()).save(any(DriverStats.class));
    }

    @Test
    void login_whenAuthenticationFails_throwsBadCredentialsException() {
        LoginRequestDto req = mock(LoginRequestDto.class);
        when(req.username()).thenReturn("user");
        when(req.password()).thenReturn("pass");

        when(authManager.authenticate(any())).thenThrow(new BadCredentialsException("Bad credentials"));

        assertThrows(BadCredentialsException.class, () -> authService.login(req));
        verify(authManager, times(1)).authenticate(any());
    }

}
