package com.rideshare.backend.service;

import com.rideshare.backend.model.*;
import com.rideshare.backend.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class DatabaseCleanupAndSeedService {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseCleanupAndSeedService.class);

    public static final String FIXED_ADMIN_EMAIL = "admin@rideshare.com";
    public static final String FIXED_SUPPORT_EMAIL = "support@rideshare.com";
    public static final String FIXED_DRIVER_EMAIL = "driver@rideshare.com";
    public static final String FIXED_RIDER_EMAIL = "rider@rideshare.com";
    public static final String FIXED_PASSWORD = "Asus@123";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private DriverApplicationRepository driverApplicationRepository;

    @Autowired
    private SupportPermissionRepository supportPermissionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private VehicleRepository vehicleRepository;

    @Autowired
    private CarModelRepository carModelRepository;

    @Value("${spring.profiles.active:default}")
    private String activeProfile;

    /**
     * Executes safe cleanup of non-car test data, preserving:
     * - All 30 car_makes
     * - All 115 car_models
     * - All 88 vehicles (re-associated with driver@rideshare.com)
     * - Master translations, faq_items, platform_settings
     * Purges all old generated test users, test rides, test bookings, test orders.
     * Enforces single primary admin index.
     * Seeds/updates the four fixed test accounts.
     */
    @Transactional
    public Map<String, Object> executeSafeCleanupAndSeed() {
        validateEnvironment();

        logger.info(">>> [CLEANUP] Starting safe database cleanup and fixed test accounts seeding...");

        // 1. Ensure support_permissions table exists
        jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS support_permissions (
                        id BIGSERIAL PRIMARY KEY,
                        user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                        permission VARCHAR(64) NOT NULL,
                        granted_at TIMESTAMP DEFAULT NOW(),
                        granted_by VARCHAR(255),
                        CONSTRAINT uq_user_permission UNIQUE (user_id, permission)
                    );
                    CREATE INDEX IF NOT EXISTS idx_support_permissions_user ON support_permissions(user_id);
                """);

        // 2. Pre-create or fetch fixed driver so we can re-point all 88 vehicles to it
        User driver = userRepository.findByEmail(FIXED_DRIVER_EMAIL);
        if (driver == null) {
            driver = new User();
            driver.setEmail(FIXED_DRIVER_EMAIL);
            driver.setName("Test Driver");
            driver.setPhoneNumber("+919876543212");
            driver.setRole("DRIVER");
            driver.setStatus("ACTIVE");
            driver.setVerified(true);
            driver.setCreatedAt(LocalDateTime.now());
        }
        driver.setRole("DRIVER");
        driver.setStatus("ACTIVE");
        driver.setVerified(true);
        driver.setPassword(passwordEncoder.encode(FIXED_PASSWORD));
        driver = userRepository.saveAndFlush(driver);

        // 3. Re-point ALL vehicles to the fixed driver account to PRESERVE all existing car records
        int vehiclesPreserved = jdbcTemplate.update("UPDATE vehicles SET driver_id = ?", driver.getId());
        if (vehicleRepository.count() < 88) {
            List<CarModel> allModels = carModelRepository.findAll();
            long currentCount = vehicleRepository.count();
            int needed = (int) (88 - currentCount);
            String[] colors = {"White", "Silver", "Grey", "Black", "Blue", "Red", "Brown"};
            String[] years = {"2020", "2021", "2022", "2023", "2024"};
            List<Vehicle> newVehicles = new ArrayList<>();
            for (int i = 0; i < needed && !allModels.isEmpty(); i++) {
                CarModel m = allModels.get(i % allModels.size());
                Vehicle v = new Vehicle();
                v.setMake(m.getMake() != null ? m.getMake().getName() : "Tata");
                v.setModel(m.getName());
                v.setYear(years[i % years.length]);
                v.setColor(colors[i % colors.length]);
                v.setLicensePlate(String.format("DL 1C RS %04d", (int) (currentCount + i + 100)));
                v.setImageUrl(m.getImageUrl() != null ? m.getImageUrl() : "https://images.unsplash.com/photo-1549317661-bd32c8ce0db2?w=200");
                v.setDriver(driver);
                newVehicles.add(v);
            }
            if (!newVehicles.isEmpty()) {
                vehicleRepository.saveAll(newVehicles);
                vehiclesPreserved = (int) vehicleRepository.count();
            }
        }
        logger.info(">>> [CLEANUP] Preserved {} vehicles by associating them with fixed driver.", vehiclesPreserved);

        // 4. Delete child business/test data in foreign-key dependency order
        jdbcTemplate.execute("DELETE FROM payment_refunds;");
        jdbcTemplate.execute("DELETE FROM commission_ledger;");
        jdbcTemplate.execute("DELETE FROM payment_orders;");
        jdbcTemplate.execute("DELETE FROM bookings;");
        jdbcTemplate.execute("DELETE FROM recurring_ride_schedules;");
        jdbcTemplate.execute("DELETE FROM rides;");

        jdbcTemplate.execute("DELETE FROM driver_presence;");
        jdbcTemplate.execute("DELETE FROM driver_locations;");
        jdbcTemplate.execute("DELETE FROM driver_payout_profiles;");
        jdbcTemplate.execute("DELETE FROM driver_payouts;");
        jdbcTemplate.execute("DELETE FROM driver_applications;");
        jdbcTemplate.execute("DELETE FROM support_tickets;");
        jdbcTemplate.execute("DELETE FROM transactions;");

        // Delete wallets for old test users
        jdbcTemplate.execute(
                """
                            DELETE FROM wallets
                            WHERE user_id NOT IN (
                                SELECT id FROM users
                                WHERE lower(email) IN ('admin@rideshare.com', 'support@rideshare.com', 'driver@rideshare.com', 'rider@rideshare.com')
                            );
                        """);

        jdbcTemplate.execute("DELETE FROM notifications;");
        jdbcTemplate.execute("DELETE FROM otp_verifications;");
        jdbcTemplate.execute("DELETE FROM email_logs;");
        jdbcTemplate.execute("DELETE FROM referrals;");
        jdbcTemplate.execute("DELETE FROM audit_logs;");
        jdbcTemplate.execute("DELETE FROM support_permissions;");

        // 5. Delete all old test users except the 4 fixed test accounts
        int usersDeleted = jdbcTemplate
                .update("""
                            DELETE FROM users
                            WHERE lower(email) NOT IN ('admin@rideshare.com', 'support@rideshare.com', 'driver@rideshare.com', 'rider@rideshare.com');
                        """);
        logger.info(">>> [CLEANUP] Purged {} old test users.", usersDeleted);

        // 6. Enforce Single Primary Admin constraint at database level
        jdbcTemplate.execute("DROP INDEX IF EXISTS idx_single_primary_admin;");
        jdbcTemplate.execute("""
                    CREATE UNIQUE INDEX IF NOT EXISTS idx_single_primary_admin
                    ON users (role)
                    WHERE role = 'ADMIN';
                """);

        // 7. Seed & Configure the Exactly Four Fixed Test Accounts
        seedAdminAccount();
        seedSupportAccount();
        driver = seedDriverAccount(driver);
        seedRiderAccount();

        // Count remaining records for verification
        long carMakes = jdbcTemplate.queryForObject("SELECT count(*) FROM car_makes", Long.class);
        long carModels = jdbcTemplate.queryForObject("SELECT count(*) FROM car_models", Long.class);
        long vehicles = jdbcTemplate.queryForObject("SELECT count(*) FROM vehicles", Long.class);
        long totalUsers = jdbcTemplate.queryForObject("SELECT count(*) FROM users", Long.class);

        logger.info(">>> [CLEANUP & SEED COMPLETE] Users: {}, Car Makes: {}, Car Models: {}, Vehicles: {}",
                totalUsers, carMakes, carModels, vehicles);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", "SUCCESS");
        summary.put("usersPurged", usersDeleted);
        summary.put("totalUsersRemaining", totalUsers);
        summary.put("carMakesPreserved", carMakes);
        summary.put("carModelsPreserved", carModels);
        summary.put("vehiclesPreserved", vehicles);
        summary.put("fixedAccounts", List.of(
                Map.of("role", "ADMIN", "email", FIXED_ADMIN_EMAIL),
                Map.of("role", "SUPPORT", "email", FIXED_SUPPORT_EMAIL),
                Map.of("role", "DRIVER", "email", FIXED_DRIVER_EMAIL),
                Map.of("role", "RIDER", "email", FIXED_RIDER_EMAIL)));

        return summary;
    }

    private void validateEnvironment() {
        if ("prod".equalsIgnoreCase(activeProfile) || "production".equalsIgnoreCase(activeProfile)) {
            throw new IllegalStateException(
                    "DESTRUCTIVE ACTION BLOCKED: Database cleanup cannot be executed on production profile!");
        }
    }

    private User seedAdminAccount() {
        User admin = userRepository.findByEmail(FIXED_ADMIN_EMAIL);
        if (admin == null) {
            admin = new User();
            admin.setEmail(FIXED_ADMIN_EMAIL);
            admin.setCreatedAt(LocalDateTime.now());
        }
        admin.setName("System Administrator");
        admin.setRole("ADMIN");
        admin.setStatus("ACTIVE");
        admin.setVerified(true);
        admin.setPhoneNumber("+919876543210");
        admin.setPassword(passwordEncoder.encode(FIXED_PASSWORD));
        admin = userRepository.saveAndFlush(admin);

        Wallet wallet = walletRepository.findByUserId(admin.getId()).orElse(null);
        if (wallet == null) {
            wallet = new Wallet();
            wallet.setUser(admin);
            wallet.setBalance(10000.0);
            walletRepository.save(wallet);
        }
        return admin;
    }

    private User seedSupportAccount() {
        User support = userRepository.findByEmail(FIXED_SUPPORT_EMAIL);
        if (support == null) {
            support = new User();
            support.setEmail(FIXED_SUPPORT_EMAIL);
            support.setCreatedAt(LocalDateTime.now());
        }
        support.setName("Support Agent");
        support.setRole("SUPPORT");
        support.setStatus("ACTIVE");
        support.setVerified(true);
        support.setPhoneNumber("+919876543211");
        support.setPassword(passwordEncoder.encode(FIXED_PASSWORD));
        support = userRepository.saveAndFlush(support);

        // Requirement: Newly created Support accounts must have no privileged
        // permissions by default
        supportPermissionRepository.deleteByUserId(support.getId());

        Wallet wallet = walletRepository.findByUserId(support.getId()).orElse(null);
        if (wallet == null) {
            wallet = new Wallet();
            wallet.setUser(support);
            wallet.setBalance(0.0);
            walletRepository.save(wallet);
        }
        return support;
    }

    private User seedDriverAccount(User driver) {
        driver.setName("Test Driver");
        driver.setRole("DRIVER");
        driver.setStatus("ACTIVE");
        driver.setVerified(true);
        driver.setPhoneNumber("+919876543212");
        driver.setPassword(passwordEncoder.encode(FIXED_PASSWORD));
        driver = userRepository.saveAndFlush(driver);

        // Driver application in APPROVED status
        DriverApplication app = driverApplicationRepository.findByUserId(driver.getId()).orElse(null);
        if (app == null) {
            app = new DriverApplication();
            app.setUser(driver);
            app.setSubmittedAt(LocalDateTime.now().minusDays(10));
        }
        app.setLegalName("Test Driver Legal");
        app.setContactNumber("+919876543212");
        app.setAddress("123 Transport Nagar");
        app.setCity("Bengaluru");
        app.setLicenseNumber("DL-2026-TEST-999");
        app.setLicenseState("KA");
        app.setLicenseExpiryDate(LocalDate.now().plusYears(4));
        app.setLicenseFrontUrl("https://example.com/docs/dl-front.jpg");
        app.setLicenseBackUrl("https://example.com/docs/dl-back.jpg");
        app.setVehicleMake("Hyundai");
        app.setVehicleModel("Verna");
        app.setRegistrationNumber("KA-01-AB-1234");
        app.setVehicleType("SEDAN");
        app.setSeatCapacity(4);
        app.setRcDocumentUrl("https://example.com/docs/rc.jpg");
        app.setInsuranceNumber("INS-999888");
        app.setInsuranceExpiryDate(LocalDate.now().plusYears(2));
        app.setInsuranceDocumentUrl("https://example.com/docs/insurance.jpg");
        app.setStatus("APPROVED");
        app.setReviewedAt(LocalDateTime.now().minusDays(9));
        driverApplicationRepository.save(app);

        Wallet wallet = walletRepository.findByUserId(driver.getId()).orElse(null);
        if (wallet == null) {
            wallet = new Wallet();
            wallet.setUser(driver);
            wallet.setBalance(0.0);
            walletRepository.save(wallet);
        }
        return driver;
    }

    private User seedRiderAccount() {
        User rider = userRepository.findByEmail(FIXED_RIDER_EMAIL);
        if (rider == null) {
            rider = new User();
            rider.setEmail(FIXED_RIDER_EMAIL);
            rider.setCreatedAt(LocalDateTime.now());
        }
        rider.setName("Test Rider");
        rider.setRole("RIDER");
        rider.setStatus("ACTIVE");
        rider.setVerified(true);
        rider.setPhoneNumber("+919876543213");
        rider.setPassword(passwordEncoder.encode(FIXED_PASSWORD));
        rider = userRepository.saveAndFlush(rider);

        Wallet wallet = walletRepository.findByUserId(rider.getId()).orElse(null);
        if (wallet == null) {
            wallet = new Wallet();
            wallet.setUser(rider);
            wallet.setBalance(0.0);
            walletRepository.save(wallet);
        }
        return rider;
    }

    /**
     * Resets test bookings and rides WITHOUT deleting the fixed accounts or car
     * records.
     * Requires explicit development/test environment check.
     */
    @Transactional
    public Map<String, Object> resetTestRidesAndBookings() {
        validateEnvironment();
        logger.info(">>> [RESET] Resetting test bookings and rides while preserving fixed accounts and car records...");

        jdbcTemplate.execute("DELETE FROM payment_refunds;");
        jdbcTemplate.execute("DELETE FROM commission_ledger;");
        jdbcTemplate.execute("DELETE FROM payment_orders;");
        jdbcTemplate.execute("DELETE FROM bookings;");
        jdbcTemplate.execute("DELETE FROM recurring_ride_schedules;");
        jdbcTemplate.execute("DELETE FROM rides;");
        jdbcTemplate.execute("DELETE FROM driver_presence;");
        jdbcTemplate.execute("DELETE FROM driver_locations;");
        jdbcTemplate.execute("DELETE FROM driver_payouts;");
        jdbcTemplate.execute("DELETE FROM support_tickets;");

        // Re-seed driver application for driver@rideshare.com
        User driver = userRepository.findByEmail(FIXED_DRIVER_EMAIL);
        if (driver != null) {
            DriverApplication app = driverApplicationRepository.findByUserId(driver.getId()).orElse(null);
            if (app == null) {
                app = new DriverApplication();
                app.setUser(driver);
                app.setSubmittedAt(LocalDateTime.now().minusDays(10));
                app.setLegalName("Test Driver Legal");
                app.setContactNumber("+919876543212");
                app.setAddress("123 Transport Nagar");
                app.setCity("Bengaluru");
                app.setLicenseNumber("DL-2026-TEST-999");
                app.setLicenseState("KA");
                app.setLicenseExpiryDate(LocalDate.now().plusYears(4));
                app.setLicenseFrontUrl("https://example.com/docs/dl-front.jpg");
                app.setLicenseBackUrl("https://example.com/docs/dl-back.jpg");
                app.setVehicleMake("Hyundai");
                app.setVehicleModel("Verna");
                app.setRegistrationNumber("KA-01-AB-1234");
                app.setVehicleType("SEDAN");
                app.setSeatCapacity(4);
                app.setRcDocumentUrl("https://example.com/docs/rc.jpg");
                app.setInsuranceNumber("INS-999888");
                app.setInsuranceExpiryDate(LocalDate.now().plusYears(2));
                app.setInsuranceDocumentUrl("https://example.com/docs/insurance.jpg");
                app.setStatus("APPROVED");
                app.setReviewedAt(LocalDateTime.now().minusDays(9));
                driverApplicationRepository.save(app);
            }
        }

        // Reset test wallet balances
        User rider = userRepository.findByEmail(FIXED_RIDER_EMAIL);
        if (rider != null) {
            walletRepository.findByUserId(rider.getId()).ifPresent(w -> {
                w.setBalance(500.0);
                walletRepository.save(w);
            });
        }
        if (driver != null) {
            walletRepository.findByUserId(driver.getId()).ifPresent(w -> {
                w.setBalance(1500.0);
                walletRepository.save(w);
            });
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message",
                "Test bookings and rides successfully reset. Fixed test accounts and car records preserved.");
        result.put("environment", activeProfile);
        result.put("timestamp", LocalDateTime.now().toString());
        return result;
    }

    /**
     * Verifies system state: exactly 1 admin, 4 fixed accounts, car records
     * integrity.
     */
    public Map<String, Object> verifySystemState() {
        long adminCount = userRepository.countByRole("ADMIN");
        long totalUsers = userRepository.count();

        User admin = userRepository.findByEmail(FIXED_ADMIN_EMAIL);
        User support = userRepository.findByEmail(FIXED_SUPPORT_EMAIL);
        User driver = userRepository.findByEmail(FIXED_DRIVER_EMAIL);
        User rider = userRepository.findByEmail(FIXED_RIDER_EMAIL);

        List<String> supportPerms = support != null
                ? supportPermissionRepository.findPermissionNamesByUserId(support.getId())
                : List.of();

        long carMakes = jdbcTemplate.queryForObject("SELECT count(*) FROM car_makes", Long.class);
        long carModels = jdbcTemplate.queryForObject("SELECT count(*) FROM car_models", Long.class);
        long vehicles = jdbcTemplate.queryForObject("SELECT count(*) FROM vehicles", Long.class);

        boolean singleAdminOk = adminCount == 1 && admin != null && "ADMIN".equals(admin.getRole());
        boolean accountsOk = admin != null && support != null && driver != null && rider != null;
        boolean carsOk = carMakes >= 30 && carModels >= 115 && vehicles >= 88;

        Map<String, Object> report = new HashMap<>();
        report.put("healthy", singleAdminOk && accountsOk && carsOk);
        report.put("singleAdminEnforced", singleAdminOk);
        report.put("adminCount", adminCount);
        report.put("totalUsers", totalUsers);
        report.put("accounts", Map.of(
                "admin",
                admin != null ? Map.of("email", admin.getEmail(), "role", admin.getRole(), "status", admin.getStatus())
                        : "MISSING",
                "support",
                support != null ? Map.of("email", support.getEmail(), "role", support.getRole(), "status",
                        support.getStatus(), "permissions", supportPerms) : "MISSING",
                "driver",
                driver != null
                        ? Map.of("email", driver.getEmail(), "role", driver.getRole(), "status", driver.getStatus())
                        : "MISSING",
                "rider",
                rider != null ? Map.of("email", rider.getEmail(), "role", rider.getRole(), "status", rider.getStatus())
                        : "MISSING"));
        report.put("carData", Map.of(
                "carMakes", carMakes,
                "carModels", carModels,
                "vehicles", vehicles,
                "preserved", carsOk));
        report.put("timestamp", LocalDateTime.now().toString());
        return report;
    }
}
