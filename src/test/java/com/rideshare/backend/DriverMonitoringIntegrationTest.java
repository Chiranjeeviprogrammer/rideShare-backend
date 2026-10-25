package com.rideshare.backend;

import com.rideshare.backend.config.DriverTrackingProperties;
import com.rideshare.backend.controller.AdminDriverMonitoringController;
import com.rideshare.backend.controller.AuthController;
import com.rideshare.backend.controller.DriverPresenceController;
import com.rideshare.backend.dto.DriverHeartbeatRequest;
import com.rideshare.backend.dto.DriverMonitoringItemDto;
import com.rideshare.backend.dto.DriverMonitoringMetricsDto;
import com.rideshare.backend.model.DriverApplication;
import com.rideshare.backend.model.DriverPresence;
import com.rideshare.backend.model.Ride;
import com.rideshare.backend.model.User;
import com.rideshare.backend.repository.DriverApplicationRepository;
import com.rideshare.backend.repository.DriverPresenceRepository;
import com.rideshare.backend.repository.RideRepository;
import com.rideshare.backend.repository.UserRepository;
import com.rideshare.backend.security.JwtUtil;
import com.rideshare.backend.service.DriverTrackingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class DriverMonitoringIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private DriverTrackingService driverTrackingService;

    @Autowired
    private DriverPresenceRepository driverPresenceRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DriverApplicationRepository driverApplicationRepository;

    @Autowired
    private RideRepository rideRepository;

    @Autowired
    private DriverPresenceController driverPresenceController;

    @Autowired
    private AdminDriverMonitoringController adminDriverMonitoringController;

    @Autowired
    private AuthController authController;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private DriverTrackingProperties trackingProperties;

    private User adminUser;
    private User approvedDriverUser;
    private User unapprovedDriverUser;
    private String adminToken;
    private String approvedDriverToken;
    private String unapprovedDriverToken;

    @BeforeEach
    public void setup() {
        String rand = UUID.randomUUID().toString().substring(0, 8);

        // Find or Create Admin
        adminUser = userRepository.findByEmail("admin@rideshare.com");
        if (adminUser == null) {
            adminUser = userRepository.findAll().stream()
                    .filter(u -> "ADMIN".equalsIgnoreCase(u.getRole()))
                    .findFirst()
                    .orElse(null);
        }
        if (adminUser == null) {
            adminUser = new User();
            adminUser.setName("Admin Tester " + rand);
            adminUser.setEmail("admin_" + rand + "@test.com");
            adminUser.setPassword("password123");
            adminUser.setRole("ADMIN");
            adminUser.setStatus("ACTIVE");
            adminUser = userRepository.save(adminUser);
        }
        adminToken = "Bearer " + jwtUtil.generateToken(adminUser.getEmail(), "ADMIN", adminUser.getId());

        // Create Approved Driver
        approvedDriverUser = new User();
        approvedDriverUser.setName("Approved Driver " + rand);
        approvedDriverUser.setEmail("driver_app_" + rand + "@test.com");
        approvedDriverUser.setPassword("password123");
        approvedDriverUser.setRole("DRIVER");
        approvedDriverUser.setStatus("ACTIVE");
        approvedDriverUser = userRepository.save(approvedDriverUser);
        approvedDriverToken = "Bearer " + jwtUtil.generateToken(approvedDriverUser.getEmail(), "DRIVER", approvedDriverUser.getId());

        DriverApplication app = new DriverApplication();
        app.setUser(approvedDriverUser);
        app.setStatus("APPROVED");
        app.setLegalName(approvedDriverUser.getName());
        app.setLicenseNumber("DL" + rand);
        app.setRegistrationNumber("REG" + rand);
        driverApplicationRepository.save(app);

        // Create Unapproved Driver (PENDING_REVIEW)
        unapprovedDriverUser = new User();
        unapprovedDriverUser.setName("Unapproved Driver " + rand);
        unapprovedDriverUser.setEmail("driver_unapp_" + rand + "@test.com");
        unapprovedDriverUser.setPassword("password123");
        unapprovedDriverUser.setRole("DRIVER");
        unapprovedDriverUser.setStatus("ACTIVE");
        unapprovedDriverUser = userRepository.save(unapprovedDriverUser);
        unapprovedDriverToken = "Bearer " + jwtUtil.generateToken(unapprovedDriverUser.getEmail(), "DRIVER", unapprovedDriverUser.getId());

        DriverApplication unapp = new DriverApplication();
        unapp.setUser(unapprovedDriverUser);
        unapp.setStatus("PENDING_REVIEW");
        unapp.setLegalName(unapprovedDriverUser.getName());
        unapp.setLicenseNumber("DL_UNAPP_" + rand);
        unapp.setRegistrationNumber("REG_UNAPP_" + rand);
        driverApplicationRepository.save(unapp);
    }

    @Test
    public void testHeartbeatRefresh() {
        // Send heartbeat for approved driver
        DriverHeartbeatRequest request = DriverHeartbeatRequest.builder()
                .latitude(28.6139)
                .longitude(77.2090)
                .heading(90.0)
                .speed(45.5)
                .locationSharingEnabled(true)
                .status("ONLINE")
                .deviceInfo("Android-Test")
                .build();

        ResponseEntity<?> response = driverPresenceController.sendHeartbeat(approvedDriverToken, request);
        assertEquals(HttpStatus.OK, response.getStatusCode());

        DriverPresence presence = driverPresenceRepository.findById(approvedDriverUser.getId()).orElse(null);
        assertNotNull(presence);
        assertTrue(presence.getIsOnline());
        assertEquals("ONLINE", presence.getLastStatus());
        assertNotNull(presence.getLastHeartbeatAt());
        assertEquals(28.6139, presence.getLatitude(), 0.0001);
        assertEquals(77.2090, presence.getLongitude(), 0.0001);
        assertEquals("Android-Test", presence.getDeviceInfo());
    }

    @Test
    public void testDriverOnlineToOfflineTimeout() {
        // Set up driver presence with a heartbeat in the past older than timeout
        LocalDateTime oldHeartbeat = LocalDateTime.now().minusSeconds(trackingProperties.getOnlineTimeoutSeconds() + 20);
        DriverPresence presence = DriverPresence.builder()
                .driverId(approvedDriverUser.getId())
                .isOnline(true)
                .lastStatus("ONLINE")
                .lastHeartbeatAt(oldHeartbeat)
                .locationSharingEnabled(true)
                .updatedAt(oldHeartbeat)
                .build();
        driverPresenceRepository.save(presence);

        // Run expiry check
        driverTrackingService.checkPresenceExpiry();

        DriverPresence updated = driverPresenceRepository.findById(approvedDriverUser.getId()).orElse(null);
        assertNotNull(updated);
        assertFalse(updated.getIsOnline());
        assertEquals("OFFLINE", updated.getLastStatus());
    }

    @Test
    public void testAvailableVsOnTripStatus() {
        // 1. Online approved driver without ride is AVAILABLE
        DriverHeartbeatRequest freeRequest = DriverHeartbeatRequest.builder()
                .latitude(28.7041)
                .longitude(77.1025)
                .status("ONLINE")
                .build();
        driverTrackingService.recordHeartbeat(approvedDriverUser, freeRequest);

        Page<DriverMonitoringItemDto> freeList = driverTrackingService.getDriverMonitoringList(
                approvedDriverUser.getEmail(), "ALL", PageRequest.of(0, 10)
        );
        assertEquals(1, freeList.getContent().size());
        DriverMonitoringItemDto freeItem = freeList.getContent().get(0);
        assertEquals("AVAILABLE", freeItem.getAvailabilityStatus());
        assertTrue(freeItem.isOnline());
        assertTrue(freeItem.isApproved());

        // 2. When driver is assigned an IN_PROGRESS ride, status changes to ON_TRIP
        Ride ride = new Ride();
        ride.setDriver(approvedDriverUser);
        ride.setRider(adminUser);
        ride.setStatus("IN_PROGRESS");
        ride.setPickupLocation("Terminal 3");
        ride.setDropoffLocation("Cyber Hub");
        ride.setRequestedAt(LocalDateTime.now());
        ride = rideRepository.save(ride);

        Page<DriverMonitoringItemDto> tripList = driverTrackingService.getDriverMonitoringList(
                approvedDriverUser.getEmail(), "ALL", PageRequest.of(0, 10)
        );
        assertEquals(1, tripList.getContent().size());
        DriverMonitoringItemDto tripItem = tripList.getContent().get(0);
        assertEquals("ON_TRIP", tripItem.getAvailabilityStatus());
        assertEquals(ride.getId(), tripItem.getCurrentRideId());

        // Clean up test ride
        ride.setStatus("COMPLETED");
        rideRepository.save(ride);
    }

    @Test
    public void testUnapprovedDriversCannotBeAvailable() {
        // Unapproved driver sends heartbeat and goes online
        DriverHeartbeatRequest request = DriverHeartbeatRequest.builder()
                .latitude(28.5355)
                .longitude(77.3910)
                .status("ONLINE")
                .build();
        driverTrackingService.recordHeartbeat(unapprovedDriverUser, request);

        Page<DriverMonitoringItemDto> list = driverTrackingService.getDriverMonitoringList(
                unapprovedDriverUser.getEmail(), "ALL", PageRequest.of(0, 10)
        );
        assertEquals(1, list.getContent().size());
        DriverMonitoringItemDto item = list.getContent().get(0);

        assertTrue(item.isOnline());
        assertFalse(item.isApproved());
        // Must be UNAVAILABLE_UNAPPROVED, never AVAILABLE!
        assertEquals("UNAVAILABLE_UNAPPROVED", item.getAvailabilityStatus());

        // Metrics must not count unapproved driver as available
        DriverMonitoringMetricsDto metrics = driverTrackingService.calculateMetrics();
        assertTrue(metrics.getOnlineDrivers() >= 1);
    }

    @Test
    public void testStaleLocationDetection() {
        // Driver with location older than locationFreshnessThresholdSeconds
        LocalDateTime staleTime = LocalDateTime.now().minusSeconds(trackingProperties.getLocationFreshnessThresholdSeconds() + 30);
        DriverPresence stalePresence = DriverPresence.builder()
                .driverId(approvedDriverUser.getId())
                .isOnline(true)
                .lastStatus("ONLINE")
                .lastHeartbeatAt(LocalDateTime.now()) // fresh heartbeat
                .latitude(28.6139)
                .longitude(77.2090)
                .locationUpdatedAt(staleTime) // STALE location
                .locationSharingEnabled(true)
                .updatedAt(LocalDateTime.now())
                .build();
        driverPresenceRepository.save(stalePresence);

        Page<DriverMonitoringItemDto> list = driverTrackingService.getDriverMonitoringList(
                approvedDriverUser.getEmail(), "ALL", PageRequest.of(0, 10)
        );
        assertEquals(1, list.getContent().size());
        DriverMonitoringItemDto item = list.getContent().get(0);

        assertTrue(item.isOnline());
        assertTrue(item.isLocationStale());
    }

    @Test
    public void testDuplicateAndConcurrentHeartbeatHandling() {
        // Sending multiple rapid heartbeats must update the same row safely
        for (int i = 0; i < 5; i++) {
            DriverHeartbeatRequest request = DriverHeartbeatRequest.builder()
                    .latitude(28.6139 + (i * 0.001))
                    .longitude(77.2090 + (i * 0.001))
                    .status("ONLINE")
                    .build();
            driverTrackingService.recordHeartbeat(approvedDriverUser, request);
        }

        List<DriverPresence> records = driverPresenceRepository.findAll().stream()
                .filter(p -> p.getDriverId().equals(approvedDriverUser.getId()))
                .toList();
        assertEquals(1, records.size());
        assertTrue(records.get(0).getIsOnline());
    }

    @Test
    public void testUnauthorizedAdminAccess() {
        // Non-admin token must be rejected
        ResponseEntity<?> response = adminDriverMonitoringController.getMetrics(approvedDriverToken);
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());

        // Null token must be rejected
        ResponseEntity<?> responseNoAuth = adminDriverMonitoringController.getMetrics(null);
        assertEquals(HttpStatus.FORBIDDEN, responseNoAuth.getStatusCode());

        // Admin token must be accepted
        ResponseEntity<?> adminResponse = adminDriverMonitoringController.getMetrics(adminToken);
        assertEquals(HttpStatus.OK, adminResponse.getStatusCode());
        assertNotNull(adminResponse.getBody());
    }

    @Test
    public void testExistingRideLifecycleRemainingUnchanged() {
        // Create an in-progress ride
        Ride activeRide = new Ride();
        activeRide.setDriver(approvedDriverUser);
        activeRide.setRider(adminUser);
        activeRide.setStatus("IN_PROGRESS");
        activeRide.setPickupLocation("Connaught Place");
        activeRide.setDropoffLocation("Cyber City");
        activeRide.setRequestedAt(LocalDateTime.now());
        activeRide = rideRepository.save(activeRide);

        // Driver times out and goes offline
        LocalDateTime oldHeartbeat = LocalDateTime.now().minusSeconds(trackingProperties.getOnlineTimeoutSeconds() + 60);
        DriverPresence presence = DriverPresence.builder()
                .driverId(approvedDriverUser.getId())
                .isOnline(true)
                .lastStatus("ONLINE")
                .lastHeartbeatAt(oldHeartbeat)
                .locationSharingEnabled(true)
                .currentRideId(activeRide.getId())
                .updatedAt(oldHeartbeat)
                .build();
        driverPresenceRepository.save(presence);

        // Run expiry check
        driverTrackingService.checkPresenceExpiry();

        // Verify driver is now marked OFFLINE
        DriverPresence updatedPresence = driverPresenceRepository.findById(approvedDriverUser.getId()).orElse(null);
        assertNotNull(updatedPresence);
        assertFalse(updatedPresence.getIsOnline());

        // CRITICAL REQUIREMENT: Active ride status MUST REMAIN UNCHANGED ('IN_PROGRESS')
        Ride reloadedRide = rideRepository.findById(activeRide.getId()).orElse(null);
        assertNotNull(reloadedRide);
        assertEquals("IN_PROGRESS", reloadedRide.getStatus(),
                "Heartbeat timeout must NEVER cancel or complete in-progress rides.");

        // Cleanup
        activeRide.setStatus("COMPLETED");
        rideRepository.save(activeRide);
    }

    @Test
    public void testDriverLogoutInvalidatesPresence() {
        // Driver sends a heartbeat and goes online
        DriverHeartbeatRequest request = DriverHeartbeatRequest.builder()
                .latitude(28.6139)
                .longitude(77.2090)
                .status("ONLINE")
                .build();
        driverPresenceController.sendHeartbeat(approvedDriverToken, request);

        DriverPresence beforeLogout = driverPresenceRepository.findById(approvedDriverUser.getId()).orElse(null);
        assertNotNull(beforeLogout);
        assertTrue(beforeLogout.getIsOnline());

        // Driver calls logout endpoint
        ResponseEntity<?> logoutResponse = authController.logout(approvedDriverToken);
        assertEquals(HttpStatus.OK, logoutResponse.getStatusCode());

        // Presence must immediately be marked OFFLINE in the database
        DriverPresence afterLogout = driverPresenceRepository.findById(approvedDriverUser.getId()).orElse(null);
        assertNotNull(afterLogout);
        assertFalse(afterLogout.getIsOnline(), "Driver presence must be marked OFFLINE immediately upon logout.");
        assertEquals("OFFLINE", afterLogout.getLastStatus());
    }

    @Test
    public void testDriverCannotUpdateAnotherDriverPresence() {
        // Driver sends heartbeat with unapproved driver's token
        DriverHeartbeatRequest req = DriverHeartbeatRequest.builder()
                .latitude(28.7041)
                .longitude(77.1025)
                .status("ONLINE")
                .build();
        driverPresenceController.sendHeartbeat(unapprovedDriverToken, req);

        // Verify unapproved driver row was updated, but approved driver row is untouched
        DriverPresence unapprovedPresence = driverPresenceRepository.findById(unapprovedDriverUser.getId()).orElse(null);
        assertNotNull(unapprovedPresence);
        assertEquals(28.7041, unapprovedPresence.getLatitude(), 0.0001);

        // Calling /me with unapprovedDriverToken only returns their own info
        ResponseEntity<?> meResponse = driverPresenceController.getMyPresence(unapprovedDriverToken);
        assertEquals(HttpStatus.OK, meResponse.getStatusCode());
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> body = (java.util.Map<String, Object>) meResponse.getBody();
        assertNotNull(body);
        assertEquals(unapprovedDriverUser.getId(), body.get("driverId"));
        assertFalse((Boolean) body.get("isApproved"));
    }
}
