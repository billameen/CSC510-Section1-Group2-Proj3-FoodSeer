// java
package FoodSeer.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import FoodSeer.entity.User;

import FoodSeer.repositories.FoodRepository;
import FoodSeer.repositories.RoleRepository;
import FoodSeer.repositories.UserRepository;
import FoodSeer.service.impl.DataInitializer;

@SpringBootTest
@Transactional
@ExtendWith(OutputCaptureExtension.class)
public class DataInitializerTest {

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private DataInitializer dataInitializer;

    @Autowired
    private FoodRepository foodRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @BeforeEach
    public void setup() {
//        foodRepository.deleteAll();
//        userRepository.deleteAll();
//        roleRepository.deleteAll();
    }

//    @Test
//    @Transactional
//    public void testAdminUserAndRolesCreated() {
//        assertEquals(0, userRepository.count());
//        assertEquals(0, roleRepository.count());
//
//        dataInitializer.onApplicationReady();
//
//        // roles should exist
//        assertNotNull(roleRepository.findByName("ROLE_ADMIN"));
//        assertNotNull(roleRepository.findByName("ROLE_CUSTOMER"));
//        assertNotNull(roleRepository.findByName("ROLE_STAFF"));
//
//        // admin user should be created
//        assertTrue(userRepository.findByUsername("admin").isPresent());
//    }
//
//    @Test
//    @Transactional
//    public void testAdminPasswordNotChangedIfAdminExists() {
//        // Run initializer once to create admin
//        dataInitializer.onApplicationReady();
//        String originalHash = userRepository.findByUsername("admin").get().getPassword();
//
//        // Run initializer again — should NOT update existing admin password
//        dataInitializer.onApplicationReady();
//        String afterHash = userRepository.findByUsername("admin").get().getPassword();
//
//        // password hashes should match (no update)
//        assertEquals(originalHash, afterHash);
//    }
//
//    @Test
//    @Transactional
//    public void testSampleFoodsCreatedWhenEmpty() {
//        assertEquals(0, foodRepository.count());
//
//        dataInitializer.onApplicationReady();
//
//        assertTrue(foodRepository.count() > 0,
//                "Expected sample foods to be seeded");
//    }

    @Test
    @Transactional
    public void testSampleFoodsNotDuplicatedOnSecondRun() {
        dataInitializer.onApplicationReady();
        long firstCount = foodRepository.count();

        dataInitializer.onApplicationReady();
        long secondCount = foodRepository.count();

        assertEquals(firstCount, secondCount,
                "Food seeding should NOT repeat");
    }

    @Test
    @Transactional
    public void testAdminCreatedFromEnvironmentPasswordWithoutLoggingIt(final CapturedOutput output) {
        userRepository.findByUsername("admin").ifPresent(userRepository::delete);
        ReflectionTestUtils.setField(dataInitializer, "adminPassword", "s3cret-from-env");

        dataInitializer.onApplicationReady();

        final User admin = userRepository.findByUsername("admin").orElseThrow();
        assertEquals("ROLE_ADMIN", admin.getRole());
        assertTrue(passwordEncoder.matches("s3cret-from-env", admin.getPassword()));
        assertFalse(output.getAll().contains("s3cret-from-env"), "the admin password must never be logged");
    }

    @Test
    @Transactional
    public void testNoAdminCreatedWithoutEnvironmentPassword() {
        userRepository.findByUsername("admin").ifPresent(userRepository::delete);
        ReflectionTestUtils.setField(dataInitializer, "adminPassword", "");

        dataInitializer.onApplicationReady();

        assertTrue(userRepository.findByUsername("admin").isEmpty());
    }

    @Test
    @Transactional
    public void testExistingAdminPasswordIsNotOverwritten() {
        userRepository.findByUsername("admin").ifPresent(userRepository::delete);
        ReflectionTestUtils.setField(dataInitializer, "adminPassword", "first-password");
        dataInitializer.onApplicationReady();
        final String originalHash = userRepository.findByUsername("admin").orElseThrow().getPassword();

        ReflectionTestUtils.setField(dataInitializer, "adminPassword", "second-password");
        dataInitializer.onApplicationReady();

        assertEquals(originalHash, userRepository.findByUsername("admin").orElseThrow().getPassword());
    }
}
