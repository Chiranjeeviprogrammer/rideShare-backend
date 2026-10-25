package com.rideshare.backend;

import com.rideshare.backend.model.User;
import com.rideshare.backend.repository.*;
import com.rideshare.backend.service.DatabaseCleanupAndSeedService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class DatabaseCleanupAndSeedTest extends PostgresIntegrationTest {

    @Autowired
    private DatabaseCleanupAndSeedService databaseCleanupAndSeedService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private VehicleRepository vehicleRepository;

    @Autowired
    private CarMakeRepository carMakeRepository;

    @Autowired
    private CarModelRepository carModelRepository;

    @Autowired
    private SupportPermissionRepository supportPermissionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    public void testSafeDatabaseCleanupAndFixedTestAccountsSeeding() {
        // Execute the cleanup and seed
        Map<String, Object> summary = databaseCleanupAndSeedService.executeSafeCleanupAndSeed();
        assertEquals("SUCCESS", summary.get("status"));

        // 1. Verify Car data preservation
        long carMakes = carMakeRepository.count();
        long carModels = carModelRepository.count();
        long vehicles = vehicleRepository.count();

        System.out.println("Preserved Car Makes: " + carMakes);
        System.out.println("Preserved Car Models: " + carModels);
        System.out.println("Preserved Vehicles: " + vehicles);

        assertEquals(30, carMakes, "All 30 car makes must be preserved");
        assertEquals(115, carModels, "All 115 car models must be preserved");
        assertTrue(vehicles >= 88, "All car vehicles must be preserved (found: " + vehicles + ")");

        // 2. Verify Exactly Four Fixed Accounts Exist
        List<User> allUsers = userRepository.findAll();
        System.out.println("Remaining Users Count: " + allUsers.size());
        assertEquals(4, allUsers.size(), "Only the 4 fixed test accounts should remain in the database");

        // 3. Verify Only One Admin Account
        long adminCount = userRepository.countByRole("ADMIN");
        assertEquals(1, adminCount, "System must have exactly one Admin account");
        User admin = userRepository.findByEmail(DatabaseCleanupAndSeedService.FIXED_ADMIN_EMAIL);
        assertNotNull(admin);
        assertEquals("ADMIN", admin.getRole());
        assertTrue(passwordEncoder.matches("Asus@123", admin.getPassword()));

        // 4. Verify Support Account
        User support = userRepository.findByEmail(DatabaseCleanupAndSeedService.FIXED_SUPPORT_EMAIL);
        assertNotNull(support);
        assertEquals("SUPPORT", support.getRole());
        assertTrue(passwordEncoder.matches("Asus@123", support.getPassword()));
        // Must have zero privileged permissions by default
        List<String> supportPerms = supportPermissionRepository.findPermissionNamesByUserId(support.getId());
        assertTrue(supportPerms.isEmpty(), "Newly seeded support account must have no permissions by default");

        // 5. Verify Driver Account
        User driver = userRepository.findByEmail(DatabaseCleanupAndSeedService.FIXED_DRIVER_EMAIL);
        assertNotNull(driver);
        assertEquals("DRIVER", driver.getRole());
        assertTrue(passwordEncoder.matches("Asus@123", driver.getPassword()));
        // Vehicles are associated with driver
        long driverVehicles = vehicleRepository.countByDriverId(driver.getId());
        assertEquals(vehicles, driverVehicles, "All preserved vehicles must be associated with the driver account");

        // 6. Verify Rider Account
        User rider = userRepository.findByEmail(DatabaseCleanupAndSeedService.FIXED_RIDER_EMAIL);
        assertNotNull(rider);
        assertEquals("RIDER", rider.getRole());
        assertTrue(passwordEncoder.matches("Asus@123", rider.getPassword()));

        // 7. Verify Idempotence - running seed again does not duplicate or alter accounts
        Map<String, Object> secondRun = databaseCleanupAndSeedService.executeSafeCleanupAndSeed();
        assertEquals("SUCCESS", secondRun.get("status"));
        assertEquals(4, userRepository.count());
        assertEquals(1, userRepository.countByRole("ADMIN"));
        assertEquals(30, carMakeRepository.count());
        assertEquals(115, carModelRepository.count());
        assertEquals(vehicles, vehicleRepository.count());
    }
}
