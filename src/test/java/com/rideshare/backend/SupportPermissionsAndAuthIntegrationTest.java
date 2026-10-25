package com.rideshare.backend;

import com.rideshare.backend.controller.AdminController;
import com.rideshare.backend.controller.UserController;
import com.rideshare.backend.dto.AuthResponse;
import com.rideshare.backend.dto.LoginRequest;
import com.rideshare.backend.dto.RegisterRequest;
import com.rideshare.backend.dto.ReviewApplicationRequest;
import com.rideshare.backend.model.DriverApplication;
import com.rideshare.backend.model.User;
import com.rideshare.backend.repository.CarMakeRepository;
import com.rideshare.backend.repository.CarModelRepository;
import com.rideshare.backend.repository.DriverApplicationRepository;
import com.rideshare.backend.repository.UserRepository;
import com.rideshare.backend.repository.VehicleRepository;
import com.rideshare.backend.repository.WalletRepository;
import com.rideshare.backend.service.AuthService;
import com.rideshare.backend.service.DatabaseCleanupAndSeedService;
import com.rideshare.backend.service.SupportPermissionService;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class SupportPermissionsAndAuthIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private AdminController adminController;

    @Autowired
    private UserController userController;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private CarMakeRepository carMakeRepository;

    @Autowired
    private CarModelRepository carModelRepository;

    @Autowired
    private VehicleRepository vehicleRepository;

    @Autowired
    private DriverApplicationRepository driverApplicationRepository;

    @Autowired
    private SupportPermissionService supportPermissionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanupAndSeedService databaseCleanupAndSeedService;

    private static String adminToken;
    private static String supportToken;
    private static String driverToken;
    private static String riderToken;
    private static Long supportUserId;

    @org.junit.jupiter.api.BeforeAll
    public void setupDatabase() {
        databaseCleanupAndSeedService.executeSafeCleanupAndSeed();
    }

    private LoginRequest createLogin(String email, String password) {
        LoginRequest req = new LoginRequest();
        req.setEmail(email);
        req.setPassword(password);
        return req;
    }

    // =========================================================================
    // 1. Fixed Accounts Authentication
    // =========================================================================

    @Test
    @Order(1)
    public void testFixedTestAccountsCanAuthenticateWithAsus123() {
        // 1. Admin
        ResponseEntity<?> adminRes = authService.login(createLogin("admin@rideshare.com", "Asus@123"));
        assertEquals(HttpStatus.OK, adminRes.getStatusCode());
        Map<?, ?> adminBody = (Map<?, ?>) adminRes.getBody();
        assertNotNull(adminBody);
        adminToken = (String) adminBody.get("token");
        assertNotNull(adminToken);
        AuthResponse adminUser = (AuthResponse) adminBody.get("user");
        assertNotNull(adminUser);
        assertEquals("ADMIN", adminUser.getRole());

        // 2. Support
        ResponseEntity<?> supportRes = authService.login(createLogin("support@rideshare.com", "Asus@123"));
        assertEquals(HttpStatus.OK, supportRes.getStatusCode());
        Map<?, ?> supportBody = (Map<?, ?>) supportRes.getBody();
        assertNotNull(supportBody);
        supportToken = (String) supportBody.get("token");
        assertNotNull(supportToken);
        AuthResponse supportUser = (AuthResponse) supportBody.get("user");
        assertNotNull(supportUser);
        assertEquals("SUPPORT", supportUser.getRole());
        supportUserId = supportUser.getId();
        assertNotNull(supportUserId);

        // 3. Driver
        ResponseEntity<?> driverRes = authService.login(createLogin("driver@rideshare.com", "Asus@123"));
        assertEquals(HttpStatus.OK, driverRes.getStatusCode());
        Map<?, ?> driverBody = (Map<?, ?>) driverRes.getBody();
        assertNotNull(driverBody);
        driverToken = (String) driverBody.get("token");
        assertNotNull(driverToken);
        AuthResponse driverUser = (AuthResponse) driverBody.get("user");
        assertNotNull(driverUser);
        assertEquals("DRIVER", driverUser.getRole());

        // 4. Rider
        ResponseEntity<?> riderRes = authService.login(createLogin("rider@rideshare.com", "Asus@123"));
        assertEquals(HttpStatus.OK, riderRes.getStatusCode());
        Map<?, ?> riderBody = (Map<?, ?>) riderRes.getBody();
        assertNotNull(riderBody);
        riderToken = (String) riderBody.get("token");
        assertNotNull(riderToken);
        AuthResponse riderUser = (AuthResponse) riderBody.get("user");
        assertNotNull(riderUser);
        assertEquals("RIDER", riderUser.getRole());

        System.out.println("All 4 fixed accounts verified successfully with role-based tokens.");
    }

    @Test
    @Order(2)
    public void testAuthenticationWithInvalidCredentialsFails() {
        ResponseEntity<?> res = authService.login(createLogin("admin@rideshare.com", "WrongPassword999!"));
        assertEquals(HttpStatus.UNAUTHORIZED, res.getStatusCode());
    }

    // =========================================================================
    // 2. Only One Admin Account Enforcement (API & Database)
    // =========================================================================

    @Test
    @Order(3)
    public void testSelfRegistrationCannotCreateAdminRole() {
        RegisterRequest req = new RegisterRequest();
        req.setName("Malicious User");
        req.setEmail("malicious_" + UUID.randomUUID().toString().substring(0, 6) + "@test.com");
        req.setPassword("Password123!");
        req.setPhoneNumber("+1555" + (int)(Math.random() * 9000000 + 1000000));
        req.setRole("ADMIN"); // Client maliciously passes ADMIN

        ResponseEntity<?> response = authService.register(req);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());

        // Verify that in the database, role is forced to RIDER, not ADMIN
        User savedUser = userRepository.findByEmail(req.getEmail());
        assertNotNull(savedUser);
        assertEquals("RIDER", savedUser.getRole(), "New registration MUST be RIDER, never ADMIN");

        // Clean up safely
        walletRepository.findByUserId(savedUser.getId()).ifPresent(w -> walletRepository.delete(w));
        userRepository.delete(savedUser);
    }

    @Test
    @Order(4)
    public void testUserCreationEndpointBlocksAdminRoleCreation() {
        User rogueUser = new User();
        rogueUser.setName("Rogue Admin Attempt");
        rogueUser.setEmail("rogue_" + UUID.randomUUID().toString().substring(0, 6) + "@test.com");
        rogueUser.setPassword("Password123!");
        rogueUser.setRole("ADMIN");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> {
            userController.createUser(rogueUser);
        });

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertTrue(ex.getReason().contains("Admin") || ex.getReason().contains("prohibited"));
    }

    @Test
    @Order(5)
    public void testRoleUpdateEndpointBlocksElevationToAdmin() {
        // Attempt elevation to ADMIN using admin token
        ResponseEntity<?> adminAttempt = userController.updateRole("Bearer " + adminToken, "ADMIN");
        assertEquals(HttpStatus.FORBIDDEN, adminAttempt.getStatusCode());
        Map body1 = (Map) adminAttempt.getBody();
        assertTrue(body1.get("message").toString().contains("Role escalation to ADMIN is strictly prohibited"));

        // Attempt elevation using non-admin (rider) token
        ResponseEntity<?> riderAttempt = userController.updateRole("Bearer " + riderToken, "ADMIN");
        assertEquals(HttpStatus.FORBIDDEN, riderAttempt.getStatusCode());
        Map body2 = (Map) riderAttempt.getBody();
        assertTrue(body2.get("message").toString().contains("Administrative access required"));
    }

    @Test
    @Order(6)
    public void testDatabaseLevelSingleAdminConstraintRejectsDuplicateAdmin() {
        // Attempt a raw database insert with role='ADMIN'
        // The PostgreSQL partial unique index idx_single_primary_admin MUST reject this
        assertThrows(DataIntegrityViolationException.class, () -> {
            jdbcTemplate.execute(
                "INSERT INTO users (name, email, password, role, status, verified, created_at) " +
                "VALUES ('Duplicate Admin', 'duplicate_admin_test@rideshare.com', 'hash', 'ADMIN', 'ACTIVE', true, NOW())"
            );
        });

        // Verify only 1 ADMIN exists in entire database
        Integer adminCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM users WHERE role = 'ADMIN'",
            Integer.class
        );
        assertEquals(1, adminCount, "There must be exactly one primary Admin account in the database");
    }

    // =========================================================================
    // 3. Support Permissions Enforcement (Granular)
    // =========================================================================

    @Test
    @Order(7)
    public void testDefaultSupportAccountHasZeroPermissionsAndGets403() {
        assertNotNull(supportUserId, "supportUserId must be initialized from test 1");
        User supportUser = userRepository.findById(supportUserId).orElseThrow();
        // Reset permissions for support@rideshare.com to ensure 0 permissions
        supportPermissionService.updateSupportPermissions(supportUser, List.of(), "admin@rideshare.com");

        String authHeader = "Bearer " + supportToken;

        // 1. Dashboard requires VIEW_DASHBOARD
        ResponseEntity<?> dashRes = adminController.getDashboardSummary(authHeader);
        assertEquals(HttpStatus.FORBIDDEN, dashRes.getStatusCode());
        Map dashBody = (Map) dashRes.getBody();
        assertTrue(dashBody.get("message").toString().contains("VIEW_DASHBOARD"));

        // 2. Users requires VIEW_USERS
        ResponseEntity<?> usersRes = adminController.getUsers(authHeader, null, null, null, null, 0, 10);
        assertEquals(HttpStatus.FORBIDDEN, usersRes.getStatusCode());

        // 3. Finance requires VIEW_FINANCE
        ResponseEntity<?> finRes = adminController.getFinanceSummary(authHeader);
        assertEquals(HttpStatus.FORBIDDEN, finRes.getStatusCode());

        // 4. Support accounts view requires ADMIN role
        ResponseEntity<?> suppRes = adminController.getSupportAccounts(authHeader);
        assertEquals(HttpStatus.FORBIDDEN, suppRes.getStatusCode());
    }

    @Test
    @Order(8)
    public void testAdminGrantsPermissionsAndSupportAccessIsPermitted() {
        String adminAuthHeader = "Bearer " + adminToken;
        String supportAuthHeader = "Bearer " + supportToken;

        // Admin grants VIEW_DASHBOARD and VIEW_USERS to support
        Map<String, Object> updatePayload = Map.of(
            "permissions", List.of(SupportPermissionService.VIEW_DASHBOARD, SupportPermissionService.VIEW_USERS)
        );

        ResponseEntity<?> updateRes = adminController.updateSupportPermissions(adminAuthHeader, supportUserId, updatePayload, "127.0.0.1");
        assertEquals(HttpStatus.OK, updateRes.getStatusCode());

        // Support calls Dashboard -> Should now SUCCEED (200 OK)
        ResponseEntity<?> dashRes = adminController.getDashboardSummary(supportAuthHeader);
        assertEquals(HttpStatus.OK, dashRes.getStatusCode());
        assertNotNull(dashRes.getBody());

        // Support calls Users -> Should now SUCCEED (200 OK)
        ResponseEntity<?> usersRes = adminController.getUsers(supportAuthHeader, null, null, null, null, 0, 10);
        assertEquals(HttpStatus.OK, usersRes.getStatusCode());
        assertNotNull(usersRes.getBody());

        // Support calls Finance -> Still FORBIDDEN (403)
        ResponseEntity<?> finRes = adminController.getFinanceSummary(supportAuthHeader);
        assertEquals(HttpStatus.FORBIDDEN, finRes.getStatusCode());
    }

    @Test
    @Order(9)
    public void testDriverDocumentMaskingAndReviewPermissions() {
        String supportAuthHeader = "Bearer " + supportToken;

        // Ensure a driver application exists for testing
        DriverApplication app = driverApplicationRepository.findAll().stream().findFirst().orElse(null);
        assertNotNull(app, "Driver application should exist from seeding");

        User supportUser = userRepository.findById(supportUserId).orElseThrow();

        // 1. Grant VIEW_USERS to support, but NOT VIEW_DRIVER_DOCS or MANAGE_DRIVERS
        supportPermissionService.updateSupportPermissions(
                supportUser,
                List.of(SupportPermissionService.VIEW_USERS),
                "admin@rideshare.com"
        );

        // Fetch driver applications as Support
        ResponseEntity<?> appListRes = adminController.getDriverApplications(supportAuthHeader, null, null, 0, 10);
        assertEquals(HttpStatus.OK, appListRes.getStatusCode());
        Page<?> page = (Page<?>) appListRes.getBody();
        assertNotNull(page);
        assertFalse(page.isEmpty());
        DriverApplication redactedApp = (DriverApplication) page.getContent().get(0);

        // Document URLs MUST BE REDACTED (null)
        assertNull(redactedApp.getLicenseFrontUrl(), "licenseFrontUrl must be redacted without VIEW_DRIVER_DOCS");
        assertNull(redactedApp.getLicenseBackUrl(), "licenseBackUrl must be redacted without VIEW_DRIVER_DOCS");
        assertNull(redactedApp.getRcDocumentUrl(), "rcDocumentUrl must be redacted without VIEW_DRIVER_DOCS");
        assertNull(redactedApp.getInsuranceDocumentUrl(), "insuranceDocumentUrl must be redacted without VIEW_DRIVER_DOCS");

        // Attempt to review application without MANAGE_DRIVERS -> 403 FORBIDDEN
        ReviewApplicationRequest reviewReq = new ReviewApplicationRequest();
        reviewReq.setStatus("APPROVED");
        reviewReq.setReviewFeedback("Test approval attempt");

        ResponseEntity<?> reviewRes = adminController.reviewDriverApplication(supportAuthHeader, app.getId(), reviewReq, "127.0.0.1");
        assertEquals(HttpStatus.FORBIDDEN, reviewRes.getStatusCode());
        Map reviewBody = (Map) reviewRes.getBody();
        assertTrue(reviewBody.get("message").toString().contains("MANAGE_DRIVERS"));

        // 2. Grant VIEW_DRIVER_DOCS to Support
        supportPermissionService.updateSupportPermissions(
                supportUser,
                List.of(SupportPermissionService.VIEW_USERS, SupportPermissionService.VIEW_DRIVER_DOCS),
                "admin@rideshare.com"
        );
        ResponseEntity<?> docListRes = adminController.getDriverApplications(supportAuthHeader, null, null, 0, 10);
        Page<?> unredactedPage = (Page<?>) docListRes.getBody();
        DriverApplication unredactedApp = (DriverApplication) unredactedPage.getContent().get(0);
        assertNotNull(unredactedApp.getLicenseFrontUrl(), "licenseFrontUrl must be visible with VIEW_DRIVER_DOCS");
    }

    @Test
    @Order(10)
    public void testSupportCannotPerformAdminLevelActions() {
        String supportAuthHeader = "Bearer " + supportToken;

        // Support cannot create another support account
        Map<String, Object> newSupport = Map.of(
            "name", "Illegal Support",
            "email", "illegal_support@rideshare.com",
            "password", "Asus@123"
        );
        ResponseEntity<?> createRes = adminController.createSupportAccount(supportAuthHeader, newSupport, "127.0.0.1");
        assertEquals(HttpStatus.FORBIDDEN, createRes.getStatusCode());

        // Support cannot modify permissions
        Map<String, Object> permUpdate = Map.of("permissions", List.of(SupportPermissionService.MANAGE_DRIVERS));
        ResponseEntity<?> updateRes = adminController.updateSupportPermissions(supportAuthHeader, supportUserId, permUpdate, "127.0.0.1");
        assertEquals(HttpStatus.FORBIDDEN, updateRes.getStatusCode());

        // Support cannot trigger database cleanup or reset
        ResponseEntity<?> cleanupRes = adminController.safeCleanupAndSeed(supportAuthHeader, "127.0.0.1");
        assertEquals(HttpStatus.FORBIDDEN, cleanupRes.getStatusCode());
    }

    // =========================================================================
    // 4. Car-Related Data Preservation & Safety
    // =========================================================================

    @Test
    @Order(11)
    public void testCarRelatedDataRemains100PercentIntact() {
        long makes = carMakeRepository.count();
        long models = carModelRepository.count();
        long vehicles = vehicleRepository.count();

        System.out.println("Verified Car Makes: " + makes);
        System.out.println("Verified Car Models: " + models);
        System.out.println("Verified Vehicles: " + vehicles);

        assertEquals(30, makes, "All 30 car makes must remain intact");
        assertEquals(115, models, "All 115 car models must remain intact");
        assertTrue(vehicles >= 88, "All vehicles must remain intact (current: " + vehicles + ")");
    }

    @Test
    @Order(12)
    public void testAdminSafeResetWorkflowMaintainsFixedAccountsAndCarData() {
        String adminAuthHeader = "Bearer " + adminToken;

        // Admin calls reset-test-rides
        ResponseEntity<?> resetRes = adminController.resetTestRides(adminAuthHeader, "127.0.0.1");
        assertEquals(HttpStatus.OK, resetRes.getStatusCode());

        // Verify all 4 accounts still exist
        assertNotNull(userRepository.findByEmail("admin@rideshare.com"));
        assertNotNull(userRepository.findByEmail("support@rideshare.com"));
        assertNotNull(userRepository.findByEmail("driver@rideshare.com"));
        assertNotNull(userRepository.findByEmail("rider@rideshare.com"));

        // Verify car data remains intact
        assertEquals(30, carMakeRepository.count());
        assertEquals(115, carModelRepository.count());
        assertTrue(vehicleRepository.count() >= 88);
    }
}
