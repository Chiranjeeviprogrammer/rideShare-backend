package com.rideshare.backend;

import com.rideshare.backend.controller.*;
import com.rideshare.backend.dto.*;
import com.rideshare.backend.model.*;
import com.rideshare.backend.repository.*;
import com.rideshare.backend.security.JwtUtil;
import com.rideshare.backend.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class OfficeCarpoolingIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private AuthController authController;

    @Autowired
    private UserController userController;

    @Autowired
    private RideController rideController;

    @Autowired
    private RecurringRideController recurringRideController;

    @Autowired
    private BookingController bookingController;

    @Autowired
    private AdminController adminController;

    @Autowired
    private DriverPresenceController driverPresenceController;

    @Autowired
    private RecurringRideService recurringRideService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private CommissionService commissionService;

    @Autowired
    private DriverTrackingService driverTrackingService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RideRepository rideRepository;

    @Autowired
    private RecurringRideScheduleRepository recurringRideScheduleRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private DriverApplicationRepository driverApplicationRepository;

    @Autowired
    private PlatformSettingsRepository platformSettingsRepository;

    @Autowired
    private DriverPresenceRepository driverPresenceRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private JwtUtil jwtUtil;

    private User admin;
    private User approvedDriver;
    private User unapprovedDriver;
    private User commuterRider;

    private String adminToken;
    private String approvedDriverToken;
    private String unapprovedDriverToken;
    private String commuterRiderToken;

    @BeforeEach
    public void setup() {
        String rand = UUID.randomUUID().toString().substring(0, 8);

        // 1. Admin Setup
        admin = userRepository.findByEmail("admin@rideshare.com");
        if (admin == null) {
            admin = userRepository.findAll().stream()
                    .filter(u -> "ADMIN".equalsIgnoreCase(u.getRole()))
                    .findFirst()
                    .orElse(null);
        }
        if (admin == null) {
            admin = new User();
            admin.setName("Admin " + rand);
            admin.setEmail("admin_ocp_" + rand + "@rideshare.com");
            admin.setPassword("$2a$10$abcdefghijklmnopqrstuvwxyz123456");
            admin.setRole("ADMIN");
            admin.setStatus("ACTIVE");
            admin = userRepository.save(admin);
        }
        adminToken = "Bearer " + jwtUtil.generateToken(admin.getEmail(), "ADMIN", admin.getId());

        // 2. Approved Driver Setup
        approvedDriver = new User();
        approvedDriver.setName("Approved Driver " + rand);
        approvedDriver.setEmail("driver_app_" + rand + "@rideshare.com");
        approvedDriver.setPassword("$2a$10$abcdefghijklmnopqrstuvwxyz123456");
        approvedDriver.setRole("DRIVER");
        approvedDriver.setStatus("ACTIVE");
        approvedDriver.setHomeAddress("Whitefield, Bangalore");
        approvedDriver.setOfficeAddress("Electronic City, Bangalore");
        approvedDriver = userRepository.save(approvedDriver);
        approvedDriverToken = "Bearer " + jwtUtil.generateToken(approvedDriver.getEmail(), "DRIVER", approvedDriver.getId());

        DriverApplication app = new DriverApplication();
        app.setUser(approvedDriver);
        app.setStatus("APPROVED");
        app.setLegalName(approvedDriver.getName());
        app.setLicenseNumber("DL" + rand);
        app.setRegistrationNumber("REG" + rand);
        driverApplicationRepository.save(app);

        // 3. Unapproved Driver Setup
        unapprovedDriver = new User();
        unapprovedDriver.setName("Unapproved Driver " + rand);
        unapprovedDriver.setEmail("driver_unapp_" + rand + "@rideshare.com");
        unapprovedDriver.setPassword("$2a$10$abcdefghijklmnopqrstuvwxyz123456");
        unapprovedDriver.setRole("DRIVER");
        unapprovedDriver.setStatus("ACTIVE");
        unapprovedDriver = userRepository.save(unapprovedDriver);
        unapprovedDriverToken = "Bearer " + jwtUtil.generateToken(unapprovedDriver.getEmail(), "DRIVER", unapprovedDriver.getId());

        DriverApplication pendingApp = new DriverApplication();
        pendingApp.setUser(unapprovedDriver);
        pendingApp.setStatus("PENDING_REVIEW");
        pendingApp.setLegalName(unapprovedDriver.getName());
        pendingApp.setLicenseNumber("DL_PENDING_" + rand);
        pendingApp.setRegistrationNumber("REG_PENDING_" + rand);
        driverApplicationRepository.save(pendingApp);

        // 4. Commuter Rider Setup
        commuterRider = new User();
        commuterRider.setName("Commuter Rider " + rand);
        commuterRider.setEmail("rider_commute_" + rand + "@rideshare.com");
        commuterRider.setPassword("$2a$10$abcdefghijklmnopqrstuvwxyz123456");
        commuterRider.setRole("RIDER");
        commuterRider.setStatus("ACTIVE");
        commuterRider = userRepository.save(commuterRider);
        commuterRiderToken = "Bearer " + jwtUtil.generateToken(commuterRider.getEmail(), "RIDER", commuterRider.getId());
    }

    @Test
    @DisplayName("Acceptance Criterion 1: User can register, update commute profile, and search rides")
    public void test01_newUserCanRegisterAndUpdateCommuteProfile() {
        String rand = UUID.randomUUID().toString().substring(0, 6);
        RegisterRequest reg = new RegisterRequest();
        reg.setName("Office Goer " + rand);
        reg.setEmail("office_" + rand + "@techpark.com");
        reg.setPassword("Pass1234!");
        reg.setRole("RIDER");

        ResponseEntity<?> regResp = authController.register(reg);
        assertTrue(regResp.getStatusCode().is2xxSuccessful());

        String token = "Bearer " + jwtUtil.generateToken(reg.getEmail(), "RIDER", null);

        // Update commuter profile with home and office locations
        Map<String, Object> profileUpdate = new HashMap<>();
        profileUpdate.put("homeAddress", "Indiranagar, Bangalore");
        profileUpdate.put("homeLatitude", 12.9784);
        profileUpdate.put("homeLongitude", 77.6408);
        profileUpdate.put("officeAddress", "Manyata Tech Park, Bangalore");
        profileUpdate.put("officeLatitude", 13.0500);
        profileUpdate.put("officeLongitude", 77.6200);
        profileUpdate.put("workCompany", "Acme Tech Solutions");

        ResponseEntity<?> updateResp = userController.updateMyProfile(token, profileUpdate);
        assertEquals(HttpStatus.OK, updateResp.getStatusCode());

        User refreshed = userRepository.findByEmail(reg.getEmail());
        assertNotNull(refreshed);
        assertEquals("Indiranagar, Bangalore", refreshed.getHomeAddress());
        assertEquals("Manyata Tech Park, Bangalore", refreshed.getOfficeAddress());
        assertEquals("Acme Tech Solutions", refreshed.getWorkCompany());

        // Search rides using date and time commute filters
        List<Ride> rides = rideController.getRides(
                "Indiranagar", "Manyata", 1, LocalDate.now().toString(), "09:00"
        );
        assertNotNull(rides);
    }

    @Test
    @DisplayName("Acceptance Criterion 2: Unapproved driver cannot publish rides")
    public void test02_unapprovedDriverCannotPublishRides() {
        Ride ride = new Ride();
        ride.setPickupLocation("Indiranagar, Bangalore");
        ride.setDropoffLocation("Electronic City, Bangalore");
        ride.setFare(150.0);
        ride.setTotalSeats(3);
        ride.setAvailableSeats(3);
        ride.setDepartureTime(LocalDateTime.now().plusDays(1).withHour(8).withMinute(30));

        ResponseEntity<?> response = rideController.requestRide(unapprovedDriverToken, ride);
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertTrue(response.getBody().toString().contains("Driver verification pending"));
    }

    @Test
    @DisplayName("Acceptance Criterion 3: Approved driver can publish a one-time ride")
    public void test03_approvedDriverCanPublishOneTimeRide() {
        Ride ride = new Ride();
        ride.setPickupLocation("Whitefield, Bangalore");
        ride.setDropoffLocation("Electronic City, Bangalore");
        ride.setFare(200.0);
        ride.setTotalSeats(3);
        ride.setAvailableSeats(3);
        ride.setDepartureTime(LocalDateTime.now().plusDays(2).withHour(8).withMinute(0));

        ResponseEntity<?> response = rideController.requestRide(approvedDriverToken, ride);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Ride published = (Ride) response.getBody();
        assertNotNull(published.getId());
        assertEquals("PUBLISHED", published.getStatus());
        assertEquals(approvedDriver.getId(), published.getDriver().getId());
    }

    @Test
    @DisplayName("Acceptance Criteria 4 & 5: Driver configures recurring schedule & departures generated without duplicates")
    public void test04_and_05_recurringOfficeScheduleAndDuplicateFreeGeneration() {
        RecurringRideScheduleRequest req = new RecurringRideScheduleRequest();
        req.setOrigin("Whitefield Commuter Hub");
        req.setDestination("Electronic City Phase 1");
        req.setDaysOfWeek(List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"));
        req.setDepartureTime("08:30");
        req.setStartDate(LocalDate.now());
        req.setEndDate(LocalDate.now().plusDays(14));
        req.setAvailableSeats(3);
        req.setFarePerSeat(140.0);

        ResponseEntity<?> createResp = recurringRideController.createSchedule(approvedDriverToken, req);
        assertEquals(HttpStatus.OK, createResp.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> respBody = (Map<String, Object>) createResp.getBody();
        Long scheduleId = ((Number) respBody.get("scheduleId")).longValue();

        RecurringRideSchedule schedule = recurringRideScheduleRepository.findById(scheduleId).orElseThrow();
        assertEquals("ACTIVE", schedule.getStatus());
        assertTrue(schedule.getDaysOfWeek().contains("MONDAY"));

        // Inspect generated departures
        ResponseEntity<?> departuresResp = recurringRideController.getScheduleDepartures(approvedDriverToken, schedule.getId());
        assertEquals(HttpStatus.OK, departuresResp.getStatusCode());
        @SuppressWarnings("unchecked")
        List<Ride> departures = (List<Ride>) departuresResp.getBody();
        assertFalse(departures.isEmpty(), "Departures should be generated for the rolling horizon");
        int initialCount = departures.size();

        for (Ride d : departures) {
            assertEquals("PUBLISHED", d.getStatus());
            assertEquals(140.0, d.getFare());
            assertEquals(3, d.getAvailableSeats());
            assertEquals(schedule.getId(), d.getRecurringScheduleId());
            assertEquals(LocalTime.of(8, 30), d.getDepartureTime().toLocalTime());
        }

        // Idempotency: Trigger departure generation again for the same horizon
        int added = recurringRideService.generateDeparturesForSchedule(schedule, LocalDate.now(), LocalDate.now().plusDays(7));
        assertEquals(0, added, "Should not generate duplicate departures for existing schedule slots");

        List<Ride> refreshedDepartures = rideRepository.findByRecurringScheduleId(schedule.getId());
        assertEquals(initialCount, refreshedDepartures.size(), "Departure count must remain identical");
    }

    @Test
    @DisplayName("Acceptance Criteria 6 & 8: Riders book available seats & cancellation restores seat inventory")
    public void test06_and_08_seatInventoryBookingAndCancellationRestore() {
        Ride ride = new Ride();
        ride.setDriver(approvedDriver);
        ride.setRider(approvedDriver);
        ride.setPickupLocation("HSR Layout");
        ride.setDropoffLocation("Bellandur EcoSpace");
        ride.setFare(100.0);
        ride.setTotalSeats(3);
        ride.setAvailableSeats(3);
        ride.setStatus("PUBLISHED");
        ride.setDepartureTime(LocalDateTime.now().plusDays(1).withHour(9).withMinute(0));
        ride = rideRepository.save(ride);

        // 1. Rider books 2 seats
        CreateBookingRequest req = new CreateBookingRequest();
        req.setRideId(ride.getId());
        req.setSeats(2);
        req.setPaymentMethod("CASH");

        ResponseEntity<?> bookResp = bookingController.createBooking(commuterRiderToken, req);
        assertTrue(bookResp.getStatusCode().is2xxSuccessful());
        Booking booking = (Booking) bookResp.getBody();
        assertNotNull(booking);
        assertEquals("CONFIRMED", booking.getStatus());
        assertEquals(2, booking.getSeats());

        // Verify remaining seats is 1
        Ride updatedRide = rideRepository.findById(ride.getId()).orElseThrow();
        assertEquals(1, updatedRide.getAvailableSeats());

        // 2. Rider attempts to book 2 more seats (only 1 available) -> must fail
        CreateBookingRequest overBookReq = new CreateBookingRequest();
        overBookReq.setRideId(ride.getId());
        overBookReq.setSeats(2);
        overBookReq.setPaymentMethod("CASH");

        ResponseEntity<?> overBookResp = bookingController.createBooking(commuterRiderToken, overBookReq);
        assertEquals(HttpStatus.CONFLICT, overBookResp.getStatusCode());

        // 3. Rider cancels the confirmed booking -> seat count restored to 3
        ResponseEntity<?> cancelResp = bookingController.cancelBooking(commuterRiderToken, booking.getId());
        assertEquals(HttpStatus.OK, cancelResp.getStatusCode());

        Ride restoredRide = rideRepository.findById(ride.getId()).orElseThrow();
        assertEquals(3, restoredRide.getAvailableSeats());
        assertEquals("PUBLISHED", restoredRide.getStatus());

        Booking cancelledBooking = bookingRepository.findById(booking.getId()).orElseThrow();
        assertEquals("CANCELLED", cancelledBooking.getStatus());
    }

    @Test
    @DisplayName("Acceptance Criterion 7: Concurrent booking requests cannot oversell seats")
    public void test07_concurrentBookingRequestsCannotOversell() throws Exception {
        Ride singleSeatRide = new Ride();
        singleSeatRide.setDriver(approvedDriver);
        singleSeatRide.setRider(approvedDriver);
        singleSeatRide.setPickupLocation("Koramangala");
        singleSeatRide.setDropoffLocation("Bagmane Tech Park");
        singleSeatRide.setFare(120.0);
        singleSeatRide.setTotalSeats(1);
        singleSeatRide.setAvailableSeats(1);
        singleSeatRide.setStatus("PUBLISHED");
        singleSeatRide.setDepartureTime(LocalDateTime.now().plusDays(1).withHour(8).withMinute(45));
        final Ride savedSingleSeatRide = rideRepository.save(singleSeatRide);

        int threadCount = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    CreateBookingRequest req = new CreateBookingRequest();
                    req.setRideId(savedSingleSeatRide.getId());
                    req.setSeats(1);
                    req.setPaymentMethod("CASH");

                    ResponseEntity<?> resp = bookingController.createBooking(commuterRiderToken, req);
                    if (resp.getStatusCode().is2xxSuccessful()) {
                        successCount.incrementAndGet();
                    } else {
                        failureCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                }
            });
        }

        latch.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));

        assertEquals(1, successCount.get(), "Exactly one concurrent booking must succeed for a 1-seat ride");
        assertEquals(threadCount - 1, failureCount.get(), "Remaining concurrent requests must fail");

        Ride finalRide = rideRepository.findById(savedSingleSeatRide.getId()).orElseThrow();
        assertEquals(0, finalRide.getAvailableSeats(), "Seat inventory must never become negative");
        assertEquals("FULL", finalRide.getStatus());
    }

    @Test
    @DisplayName("Acceptance Criterion 9: Commission remains exactly 0% in Zero-Commission Launch Mode")
    public void test09_zeroCommissionLaunchMode() {
        // Ensure platform settings have commission disabled
        PlatformSettings settings = platformSettingsRepository.findAll().stream().findFirst()
                .orElseGet(() -> PlatformSettings.builder().build());
        settings.setCommissionEnabled(false);
        settings.setCommissionRatePercent(0.0);
        settings.setCommissionFixedFee(0.0);
        settings.setPlatformFeeAmount(0.0);
        platformSettingsRepository.save(settings);

        Ride ride = new Ride();
        ride.setDriver(approvedDriver);
        ride.setRider(approvedDriver);
        ride.setPickupLocation("BTM Layout");
        ride.setDropoffLocation("Electronic City");
        ride.setFare(100.0);
        ride.setTotalSeats(2);
        ride.setAvailableSeats(2);
        ride.setStatus("PUBLISHED");
        ride.setDepartureTime(LocalDateTime.now().plusDays(1).withHour(8).withMinute(0));
        ride = rideRepository.save(ride);

        // 1. Calculate Quote
        Map<String, Object> quote = paymentService.calculateQuote(ride, 2);
        assertEquals(200.0, ((Number) quote.get("baseFare")).doubleValue());
        assertEquals(0.0, ((Number) quote.get("platformFee")).doubleValue());
        assertEquals(200.0, ((Number) quote.get("totalAmount")).doubleValue());
        assertEquals(200.0, ((Number) quote.get("driverAmount")).doubleValue());
        assertFalse((Boolean) quote.get("commissionEnabled"));

        // 2. Create Booking and check snapshot
        CreateBookingRequest req = new CreateBookingRequest();
        req.setRideId(ride.getId());
        req.setSeats(1);
        req.setPaymentMethod("CASH");

        ResponseEntity<?> bookResp = bookingController.createBooking(commuterRiderToken, req);
        assertTrue(bookResp.getStatusCode().is2xxSuccessful());
        Booking booking = (Booking) bookResp.getBody();

        assertEquals(0.0, booking.getPlatformFee());
        assertEquals(100.0, booking.getTotalAmount());
        assertEquals(100.0, booking.getDriverAmount());
        assertFalse(booking.getCommissionEnabledSnapshot());

        // 3. Record cash collection
        CommissionLedger ledger = commissionService.recordCashCollection(booking, approvedDriver);
        assertNotNull(ledger);
        assertEquals(0.0, ledger.getCommissionAmount(), "Commission deducted in zero-commission mode must be 0.0");
        assertEquals(100.0, ledger.getDriverNetAmount(), "Driver retains 100% of the cost-sharing amount");
        assertEquals("SETTLED", ledger.getStatus());
    }

    @Test
    @DisplayName("Acceptance Criteria 10 & 11: Admin can update commission settings with audit log; Unauthorized user blocked")
    public void test10_and_11_adminSettingsAuthorizationAndAuditLogging() {
        // Unauthorized non-admin attempt
        PlatformSettings maliciousSettings = new PlatformSettings();
        maliciousSettings.setCommissionEnabled(true);
        maliciousSettings.setCommissionRatePercent(25.0);

        ResponseEntity<?> forbiddenResp = adminController.updatePlatformSettings(commuterRiderToken, maliciousSettings, "127.0.0.1");
        assertEquals(HttpStatus.FORBIDDEN, forbiddenResp.getStatusCode());

        // Authorized admin update
        PlatformSettings newSettings = new PlatformSettings();
        newSettings.setCommissionEnabled(true);
        newSettings.setCommissionRatePercent(10.0);
        newSettings.setCommissionFixedFee(15.0);
        newSettings.setPlatformFeeType("FIXED");
        newSettings.setPlatformFeeAmount(30.0);
        newSettings.setEffectiveDate(LocalDateTime.now().plusDays(15));

        ResponseEntity<?> adminResp = adminController.updatePlatformSettings(adminToken, newSettings, "192.168.1.100");
        assertEquals(HttpStatus.OK, adminResp.getStatusCode());
        PlatformSettings updated = (PlatformSettings) adminResp.getBody();
        assertTrue(updated.getCommissionEnabled());
        assertEquals(10.0, updated.getCommissionRatePercent());

        // Verify audit log entry
        List<AuditLog> logs = auditLogRepository.findTop20ByOrderByTimestampDesc();
        assertFalse(logs.isEmpty());
        AuditLog latestLog = logs.stream()
                .filter(l -> "CONFIG_UPDATE".equals(l.getAction()) && admin.getEmail().equals(l.getAdminEmail()))
                .findFirst()
                .orElse(null);

        assertNotNull(latestLog, "Audit log entry must be created when admin updates platform settings");
        assertTrue(latestLog.getDetails().contains("CommissionRate=10.0%"));

        // Reset to launch mode (0% commission)
        PlatformSettings resetSettings = new PlatformSettings();
        resetSettings.setCommissionEnabled(false);
        resetSettings.setCommissionRatePercent(0.0);
        resetSettings.setCommissionFixedFee(0.0);
        adminController.updatePlatformSettings(adminToken, resetSettings, "127.0.0.1");
    }

    @Test
    @DisplayName("Acceptance Criterion 12: Driver heartbeat expiry marks driver offline")
    public void test12_driverPresenceExpiryMarksDriverOffline() {
        DriverHeartbeatRequest hb = DriverHeartbeatRequest.builder()
                .latitude(12.9716)
                .longitude(77.5946)
                .status("ONLINE")
                .locationSharingEnabled(true)
                .build();

        ResponseEntity<?> hbResp = driverPresenceController.sendHeartbeat(approvedDriverToken, hb);
        assertEquals(HttpStatus.OK, hbResp.getStatusCode());

        DriverPresence presence = driverPresenceRepository.findById(approvedDriver.getId()).orElseThrow();
        assertTrue(presence.getIsOnline());
        assertEquals("ONLINE", presence.getLastStatus());

        // Simulate heartbeat expiry (older than 120 seconds timeout)
        presence.setLastHeartbeatAt(LocalDateTime.now().minusSeconds(150));
        driverPresenceRepository.save(presence);

        // Run automated expiry checker
        driverTrackingService.checkPresenceExpiry();

        DriverPresence expiredPresence = driverPresenceRepository.findById(approvedDriver.getId()).orElseThrow();
        assertFalse(expiredPresence.getIsOnline(), "Driver must be marked offline after timeout");
        assertEquals("OFFLINE", expiredPresence.getLastStatus());
    }

    @Test
    @DisplayName("Acceptance Criterion 13: Admin dashboard counts reflect real database records")
    public void test13_adminCountsReflectDatabaseRecords() {
        ResponseEntity<?> dashboardResp = adminController.getDashboardSummary(adminToken);
        assertEquals(HttpStatus.OK, dashboardResp.getStatusCode());

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) dashboardResp.getBody();
        assertNotNull(body);

        @SuppressWarnings("unchecked")
        Map<String, Object> usersMap = (Map<String, Object>) body.get("users");
        @SuppressWarnings("unchecked")
        Map<String, Object> ridesMap = (Map<String, Object>) body.get("rides");
        @SuppressWarnings("unchecked")
        Map<String, Object> bookingsMap = (Map<String, Object>) body.get("bookings");
        @SuppressWarnings("unchecked")
        Map<String, Object> recurringMap = (Map<String, Object>) body.get("recurringSchedules");

        assertEquals(userRepository.count(), ((Number) usersMap.get("total")).longValue());
        assertEquals(rideRepository.count(), ((Number) ridesMap.get("total")).longValue());
        assertEquals(bookingRepository.count(), ((Number) bookingsMap.get("total")).longValue());
        assertEquals(recurringRideScheduleRepository.count(), ((Number) recurringMap.get("total")).longValue());
    }

    @Test
    @DisplayName("Acceptance Criterion 14: Payment status transitions validated server-side")
    public void test14_paymentStatusTransitionsValidatedServerSide() {
        Ride ride = new Ride();
        ride.setDriver(approvedDriver);
        ride.setRider(approvedDriver);
        ride.setPickupLocation("Sarjapur Road");
        ride.setDropoffLocation("Whitefield");
        ride.setFare(150.0);
        ride.setTotalSeats(2);
        ride.setAvailableSeats(2);
        ride.setStatus("PUBLISHED");
        ride.setDepartureTime(LocalDateTime.now().plusDays(1).withHour(7).withMinute(30));
        ride = rideRepository.save(ride);

        CreateBookingRequest req = new CreateBookingRequest();
        req.setRideId(ride.getId());
        req.setSeats(1);
        req.setPaymentMethod("CASH");

        Booking booking = (Booking) bookingController.createBooking(commuterRiderToken, req).getBody();
        assertNotNull(booking);
        assertEquals("PENDING_CASH", booking.getPaymentStatus());

        // Refunding an UNPAID cash booking should not issue an online gateway refund
        ResponseEntity<?> refundResp = paymentService.processRefund(commuterRider, booking.getId(), "Cancel unpaid");
        assertEquals(HttpStatus.OK, refundResp.getStatusCode());

        Booking refundedBooking = bookingRepository.findById(booking.getId()).orElseThrow();
        assertEquals("CANCELLED", refundedBooking.getStatus());
        // Cash unpaid cancellation does not get marked REFUNDED online
        assertNotEquals("REFUNDED", refundedBooking.getPaymentStatus());
    }

    @Test
    @DisplayName("Acceptance Criterion 15: Cancelling recurring schedule preserves completed past rides")
    public void test15_cancellingRecurringSchedulePreservesCompletedRides() {
        RecurringRideSchedule schedule = RecurringRideSchedule.builder()
                .driver(approvedDriver)
                .origin("Indiranagar")
                .destination("Electronic City")
                .daysOfWeek("MONDAY,WEDNESDAY,FRIDAY")
                .departureTime("08:00:00")
                .startDate(LocalDate.now().minusDays(7))
                .endDate(LocalDate.now().plusDays(7))
                .availableSeats(3)
                .farePerSeat(150.0)
                .status("ACTIVE")
                .createdAt(LocalDateTime.now().minusDays(7))
                .build();
        schedule = recurringRideScheduleRepository.save(schedule);

        // Past completed departure
        Ride completedPastRide = new Ride();
        completedPastRide.setDriver(approvedDriver);
        completedPastRide.setRider(approvedDriver);
        completedPastRide.setPickupLocation("Indiranagar");
        completedPastRide.setDropoffLocation("Electronic City");
        completedPastRide.setFare(150.0);
        completedPastRide.setTotalSeats(3);
        completedPastRide.setAvailableSeats(1);
        completedPastRide.setStatus("COMPLETED");
        completedPastRide.setDepartureTime(LocalDateTime.now().minusDays(2));
        completedPastRide.setRecurringScheduleId(schedule.getId());
        completedPastRide.setRequestedAt(LocalDateTime.now().minusDays(3));
        completedPastRide.setCompletedAt(LocalDateTime.now().minusDays(2));
        completedPastRide = rideRepository.save(completedPastRide);

        // Future uncompleted departure
        Ride futureRide = new Ride();
        futureRide.setDriver(approvedDriver);
        futureRide.setRider(approvedDriver);
        futureRide.setPickupLocation("Indiranagar");
        futureRide.setDropoffLocation("Electronic City");
        futureRide.setFare(150.0);
        futureRide.setTotalSeats(3);
        futureRide.setAvailableSeats(3);
        futureRide.setStatus("PUBLISHED");
        futureRide.setDepartureTime(LocalDateTime.now().plusDays(2));
        futureRide.setRecurringScheduleId(schedule.getId());
        futureRide.setRequestedAt(LocalDateTime.now());
        futureRide = rideRepository.save(futureRide);

        // Cancel schedule
        ResponseEntity<?> cancelResp = recurringRideController.cancelSchedule(approvedDriverToken, schedule.getId(), Map.of("reason", "Cancelled by driver"));
        assertEquals(HttpStatus.OK, cancelResp.getStatusCode());

        // Future ride is cancelled
        Ride cancelledFuture = rideRepository.findById(futureRide.getId()).orElseThrow();
        assertEquals("CANCELLED", cancelledFuture.getStatus());

        // Past completed ride is untouched
        Ride untouchedPast = rideRepository.findById(completedPastRide.getId()).orElseThrow();
        assertEquals("COMPLETED", untouchedPast.getStatus());
    }
}
