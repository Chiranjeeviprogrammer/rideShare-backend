package com.rideshare.backend;

import com.rideshare.backend.dto.DriverApplicationRequest;
import com.rideshare.backend.dto.ReviewApplicationRequest;
import com.rideshare.backend.dto.RegisterRequest;
import com.rideshare.backend.model.DriverApplication;
import com.rideshare.backend.model.Ride;
import com.rideshare.backend.model.User;
import com.rideshare.backend.repository.DriverApplicationRepository;
import com.rideshare.backend.repository.UserRepository;
import com.rideshare.backend.security.JwtUtil;
import com.rideshare.backend.service.AuthService;
import com.rideshare.backend.service.DriverService;
import com.rideshare.backend.controller.RideController;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class RoleBasedSystemIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private DriverService driverService;

    @Autowired
    private RideController rideController;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DriverApplicationRepository driverApplicationRepository;

    @Autowired
    private JwtUtil jwtUtil;

    private static String testRiderEmail = "rider_" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    private static Long riderUserId;
    private static Long driverApplicationId;

    @Test
    @Order(1)
    public void testNewUserAlwaysRegistersAsRider() {
        RegisterRequest request = new RegisterRequest();
        request.setName("Rider Applicant");
        request.setEmail(testRiderEmail);
        request.setPassword("SecurePassword123!");
        request.setPhoneNumber("+1555" + (int)(Math.random() * 9000000 + 1000000));
        // Even if client attempts to send DRIVER role, backend must override to RIDER
        request.setRole("DRIVER");

        ResponseEntity<?> response = authService.register(request);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());

        User createdUser = userRepository.findByEmail(testRiderEmail);
        assertNotNull(createdUser);
        riderUserId = createdUser.getId();
        // Authoritative validation: New registration MUST have RIDER role
        assertEquals("RIDER", createdUser.getRole());
    }

    @Test
    @Order(2)
    public void testRiderInitialDriverStatusIsPendingOrNotApplied() {
        User user = userRepository.findById(riderUserId).orElseThrow();
        ResponseEntity<?> response = driverService.getMyApplication(user);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map body = (Map) response.getBody();
        assertNotNull(body);
        assertEquals("NOT_APPLIED", body.get("status"));
        assertEquals("RIDER", body.get("role"));
        assertEquals(false, body.get("isApprovedDriver"));
    }

    @Test
    @Order(3)
    public void testRiderCannotPublishRideOffers() {
        User user = userRepository.findById(riderUserId).orElseThrow();
        String token = "Bearer " + jwtUtil.generateToken(user.getEmail());

        Ride ride = new Ride();
        ride.setDriver(user); // Attempting to publish as driver
        ride.setPickupLocation("Delhi");
        ride.setDropoffLocation("Jaipur");
        ride.setFare(500.0);

        ResponseEntity<?> response = rideController.requestRide(token, ride);
        // Must be rejected with 403 FORBIDDEN
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    @Order(4)
    public void testRiderSubmitsDriverApplication() {
        User user = userRepository.findById(riderUserId).orElseThrow();

        DriverApplicationRequest appReq = new DriverApplicationRequest();
        appReq.setLegalName("Rider Applicant Legal");
        appReq.setContactNumber("+919876543210");
        appReq.setCity("Bangalore");
        appReq.setLicenseNumber("KA0120230009999");
        appReq.setLicenseState("Karnataka");
        appReq.setLicenseExpiryDate(LocalDate.now().plusYears(5));
        appReq.setVehicleMake("Hyundai");
        appReq.setVehicleModel("Creta");
        appReq.setRegistrationNumber("KA01XY1234");
        appReq.setVehicleColor("White");
        appReq.setSeatCapacity(4);
        appReq.setDraft(false);

        ResponseEntity<?> response = driverService.submitApplication(user, appReq);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map body = (Map) response.getBody();
        assertNotNull(body);
        assertEquals("PENDING_REVIEW", body.get("status"));
        driverApplicationId = ((Number) body.get("applicationId")).longValue();

        // While pending review, user role must STILL be RIDER
        User freshUser = userRepository.findById(riderUserId).orElseThrow();
        assertEquals("RIDER", freshUser.getRole());
    }

    @Test
    @Order(5)
    public void testAdminReviewsAndApprovesDriver() {
        User reviewer = userRepository.findById(riderUserId).orElseThrow();

        ReviewApplicationRequest reviewReq = new ReviewApplicationRequest();
        reviewReq.setStatus("APPROVED");
        reviewReq.setReviewFeedback("All documents and licence verified successfully.");

        ResponseEntity<?> response = driverService.reviewApplication(driverApplicationId, reviewer, reviewReq);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map body = (Map) response.getBody();
        assertNotNull(body);
        assertEquals("APPROVED", body.get("newStatus"));
        assertEquals("DRIVER", body.get("userRole"));

        // User role in DB must now be DRIVER
        User updatedUser = userRepository.findById(riderUserId).orElseThrow();
        assertEquals("DRIVER", updatedUser.getRole());
    }

    @Test
    @Order(6)
    public void testApprovedDriverCanNowPublishRideOffers() {
        User driverUser = userRepository.findById(riderUserId).orElseThrow();
        assertEquals("DRIVER", driverUser.getRole());
        String token = "Bearer " + jwtUtil.generateToken(driverUser.getEmail());

        Ride ride = new Ride();
        ride.setDriver(driverUser);
        ride.setPickupLocation("Electronic City, Bangalore");
        ride.setDropoffLocation("Whitefield, Bangalore");
        ride.setFare(250.0);

        ResponseEntity<?> response = rideController.requestRide(token, ride);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Ride saved = (Ride) response.getBody();
        assertNotNull(saved);
        assertNotNull(saved.getId());
        assertEquals("PUBLISHED", saved.getStatus());
        assertEquals(driverUser.getId(), saved.getDriver().getId());
    }

    @Test
    @Order(7)
    public void testDriverWithoutVehicleCannotPublishRide() {
        String rand = UUID.randomUUID().toString().substring(0, 6);
        User noVehDriver = new User();
        noVehDriver.setName("No Veh Driver " + rand);
        noVehDriver.setEmail("noveh_" + rand + "@rideshare.com");
        noVehDriver.setPassword("$2a$10$abcdefghijklmnopqrstuvwxyz123456");
        noVehDriver.setRole("DRIVER");
        noVehDriver.setStatus("ACTIVE");
        noVehDriver = userRepository.save(noVehDriver);

        DriverApplication app = new DriverApplication();
        app.setUser(noVehDriver);
        app.setStatus("APPROVED");
        app.setLegalName(noVehDriver.getName());
        app.setLicenseNumber("DL" + rand);
        driverApplicationRepository.save(app);

        String token = "Bearer " + jwtUtil.generateToken(noVehDriver.getEmail(), "DRIVER", noVehDriver.getId());

        Ride ride = new Ride();
        ride.setDriver(noVehDriver);
        ride.setPickupLocation("Electronic City, Bangalore");
        ride.setDropoffLocation("Whitefield, Bangalore");
        ride.setFare(200.0);

        ResponseEntity<?> response = rideController.requestRide(token, ride);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals("NO_VEHICLE_REGISTERED", body.get("code"));
        assertTrue(body.get("message").toString().contains("No vehicle registered"));
    }
}
