package com.rideshare.backend;

import com.rideshare.backend.dto.ReviewApplicationRequest;
import com.rideshare.backend.model.DriverApplication;
import com.rideshare.backend.model.SupportTicket;
import com.rideshare.backend.model.User;
import com.rideshare.backend.repository.DriverApplicationRepository;
import com.rideshare.backend.repository.SupportTicketRepository;
import com.rideshare.backend.repository.UserRepository;
import com.rideshare.backend.security.JwtUtil;
import com.rideshare.backend.controller.AdminController;
import com.rideshare.backend.controller.PublicController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class AdminAndPublicSystemIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private AdminController adminController;

    @Autowired
    private PublicController publicController;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DriverApplicationRepository driverApplicationRepository;

    @Autowired
    private SupportTicketRepository supportTicketRepository;

    @Autowired
    private JwtUtil jwtUtil;

    private User adminUser;
    private User riderUser;
    private String adminToken;
    private String riderToken;

    @BeforeEach
    public void setup() {
        String randomSuffix = UUID.randomUUID().toString().substring(0, 6);

        adminUser = userRepository.findByEmail("admin@rideshare.com");
        if (adminUser == null) {
            adminUser = userRepository.findAll().stream()
                    .filter(u -> "ADMIN".equalsIgnoreCase(u.getRole()))
                    .findFirst()
                    .orElse(null);
        }
        if (adminUser == null) {
            adminUser = new User();
            adminUser.setEmail("admin_test_" + randomSuffix + "@rideshare.com");
            adminUser.setName("Admin Tester");
            adminUser.setPassword("$2a$10$abcdefghijklmnopqrstuvwxyz123456");
            adminUser.setRole("ADMIN");
            adminUser.setStatus("ACTIVE");
            adminUser.setVerified(true);
            adminUser = userRepository.save(adminUser);
        }
        adminToken = "Bearer " + jwtUtil.generateToken(adminUser.getEmail(), adminUser.getRole(), adminUser.getId());

        riderUser = new User();
        riderUser.setEmail("rider_test_" + randomSuffix + "@rideshare.com");
        riderUser.setName("Rider Tester");
        riderUser.setPassword("$2a$10$abcdefghijklmnopqrstuvwxyz123456");
        riderUser.setRole("RIDER");
        riderUser.setStatus("ACTIVE");
        riderUser.setVerified(true);
        userRepository.save(riderUser);
        riderToken = "Bearer " + jwtUtil.generateToken(riderUser.getEmail(), riderUser.getRole(), riderUser.getId());
    }

    @Test
    public void testAdminAccessAllowedForAdminUser() {
        ResponseEntity<?> response = adminController.getDashboardSummary(adminToken);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map body = (Map) response.getBody();
        assertNotNull(body);
        assertTrue(body.containsKey("users"));
        assertTrue(body.containsKey("rides"));
        assertTrue(body.containsKey("finances"));
    }

    @Test
    public void testRiderDeniedAccessToAdminDashboard() {
        ResponseEntity<?> response = adminController.getDashboardSummary(riderToken);
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    public void testAdminUserSuspensionAndReactivation() {
        // Admin suspends rider
        ResponseEntity<?> suspendResp = adminController.updateUserStatus(
                adminToken,
                riderUser.getId(),
                Map.of("status", "SUSPENDED", "reason", "Terms violation test"),
                "127.0.0.1"
        );
        assertEquals(HttpStatus.OK, suspendResp.getStatusCode());

        User updatedUser = userRepository.findById(riderUser.getId()).orElseThrow();
        assertEquals("SUSPENDED", updatedUser.getStatus());
        assertEquals("Terms violation test", updatedUser.getSuspensionReason());

        // Admin reactivates rider
        ResponseEntity<?> reactivateResp = adminController.updateUserStatus(
                adminToken,
                riderUser.getId(),
                Map.of("status", "ACTIVE"),
                "127.0.0.1"
        );
        assertEquals(HttpStatus.OK, reactivateResp.getStatusCode());
        assertEquals("ACTIVE", userRepository.findById(riderUser.getId()).orElseThrow().getStatus());
    }

    @Test
    public void testAdminCannotSuspendSelf() {
        ResponseEntity<?> selfSuspendResp = adminController.updateUserStatus(
                adminToken,
                adminUser.getId(),
                Map.of("status", "SUSPENDED", "reason", "Self suspension attempt"),
                "127.0.0.1"
        );
        assertEquals(HttpStatus.BAD_REQUEST, selfSuspendResp.getStatusCode());
    }

    @Test
    public void testDriverApprovalWorkflow() {
        DriverApplication app = new DriverApplication();
        app.setUser(riderUser);
        app.setLegalName("Rider Applicant");
        app.setContactNumber("+919999888877");
        app.setLicenseNumber("DL12345678");
        app.setVehicleMake("Honda");
        app.setVehicleModel("City");
        app.setStatus("PENDING_REVIEW");
        driverApplicationRepository.save(app);

        ReviewApplicationRequest reviewReq = new ReviewApplicationRequest();
        reviewReq.setStatus("APPROVED");
        reviewReq.setReviewFeedback("Verified driving license and documents.");

        ResponseEntity<?> reviewResp = adminController.reviewDriverApplication(
                adminToken,
                app.getId(),
                reviewReq,
                "127.0.0.1"
        );
        assertEquals(HttpStatus.OK, reviewResp.getStatusCode());

        User approvedUser = userRepository.findById(riderUser.getId()).orElseThrow();
        assertEquals("DRIVER", approvedUser.getRole());
    }

    @Test
    public void testEmailStatusNeverExposesCredentials() {
        ResponseEntity<?> response = adminController.getEmailStatus(adminToken);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map body = (Map) response.getBody();
        assertNotNull(body);
        assertFalse(body.containsKey("password"));
        assertFalse(body.containsKey("appPassword"));
        assertFalse(body.containsKey("mailPassword"));
    }

    @Test
    public void testPublicContactFormSubmission() {
        Map<String, String> payload = Map.of(
                "name", "Website Visitor",
                "email", "visitor@example.com",
                "phone", "+919876543210",
                "subject", "Inquiry about corporate carpooling",
                "message", "Hello, we would like to explore corporate RideShare for our tech campus.",
                "category", "GENERAL"
        );

        ResponseEntity<?> response = publicController.submitContactForm(payload, "127.0.0.1");
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        Map body = (Map) response.getBody();
        assertNotNull(body);
        assertTrue((Boolean) body.get("success"));
        assertNotNull(body.get("ticketNumber"));

        String ticketNumber = (String) body.get("ticketNumber");
        SupportTicket ticket = supportTicketRepository.findByTicketNumber(ticketNumber).orElse(null);
        assertNotNull(ticket);
        assertEquals("visitor@example.com", ticket.getEmail());
    }
}
