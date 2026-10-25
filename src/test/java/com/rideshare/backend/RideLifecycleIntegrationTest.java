package com.rideshare.backend;

import com.rideshare.backend.controller.RideController;
import com.rideshare.backend.controller.BookingController;
import com.rideshare.backend.controller.PaymentController;
import com.rideshare.backend.dto.CreateBookingRequest;
import com.rideshare.backend.dto.CreatePaymentOrderRequest;
import com.rideshare.backend.dto.VerifyPaymentRequest;
import com.rideshare.backend.dto.DriverApplicationRequest;
import com.rideshare.backend.dto.ReviewApplicationRequest;
import com.rideshare.backend.dto.RegisterRequest;
import com.rideshare.backend.model.Booking;
import com.rideshare.backend.model.Ride;
import com.rideshare.backend.model.User;
import com.rideshare.backend.repository.BookingRepository;
import com.rideshare.backend.repository.RideRepository;
import com.rideshare.backend.repository.UserRepository;
import com.rideshare.backend.security.JwtUtil;
import com.rideshare.backend.service.AuthService;
import com.rideshare.backend.service.DriverService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class RideLifecycleIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private RideController rideController;

    @Autowired
    private BookingController bookingController;

    @Autowired
    private PaymentController paymentController;

    @Autowired
    private AuthService authService;

    @Autowired
    private DriverService driverService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RideRepository rideRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private com.rideshare.backend.controller.CommissionController commissionController;

    @Autowired
    private com.rideshare.backend.service.CommissionService commissionService;

    @Autowired
    private com.rideshare.backend.repository.PlatformSettingsRepository platformSettingsRepository;

    private User driverUser;
    private String driverToken;

    private User riderUser;
    private String riderToken;

    @BeforeEach
    public void setup() {
        // Configure standard platform commission settings for lifecycle testing
        com.rideshare.backend.model.PlatformSettings settings = platformSettingsRepository.findAll().stream().findFirst()
                .orElseGet(com.rideshare.backend.model.PlatformSettings::new);
        settings.setCommissionEnabled(true);
        settings.setDriverCommissionEnabled(true);
        settings.setDriverCommissionType("PERCENT_PLUS_FIXED");
        settings.setDriverCommissionRatePercent(15.0);
        settings.setCommissionRatePercent(15.0);
        settings.setDriverCommissionFixedAmount(20.0);
        settings.setCommissionFixedFee(20.0);
        settings.setRiderFeeEnabled(true);
        settings.setRiderFeeType("FIXED");
        settings.setPlatformFeeType("FIXED");
        settings.setRiderFeeAmount(50.0);
        settings.setPlatformFeeAmount(50.0);
        platformSettingsRepository.save(settings);

        // Create an approved driver
        String driverEmail = "driver_" + UUID.randomUUID().toString().substring(0, 8) + "@test.com";
        RegisterRequest dReq = new RegisterRequest();
        dReq.setName("Test Driver");
        dReq.setEmail(driverEmail);
        dReq.setPassword("Password123!");
        dReq.setPhoneNumber("+1" + (int)(Math.random() * 900000000 + 100000000));
        authService.register(dReq);

        driverUser = userRepository.findByEmail(driverEmail);

        // Submit and approve driver application
        driverToken = "Bearer " + jwtUtil.generateToken(driverEmail);
        DriverApplicationRequest appReq = new DriverApplicationRequest();
        appReq.setLegalName("Test Driver Legal");
        appReq.setLicenseNumber("DL-" + UUID.randomUUID().toString().substring(0, 8));
        appReq.setLicenseExpiryDate(LocalDate.now().plusYears(4));
        appReq.setRegistrationNumber("REG-" + UUID.randomUUID().toString().substring(0, 8));
        appReq.setVehicleMake("Hyundai");
        appReq.setVehicleModel("Verna");
        appReq.setSeatCapacity(4);
        ResponseEntity<?> appResp = driverService.submitApplication(driverUser, appReq);
        Map<?, ?> appMap = (Map<?, ?>) appResp.getBody();
        Long appId = ((Number) appMap.get("applicationId")).longValue();

        ReviewApplicationRequest rev = new ReviewApplicationRequest();
        rev.setStatus("APPROVED");
        driverService.reviewApplication(appId, driverUser, rev);
        driverUser = userRepository.findById(driverUser.getId()).orElseThrow();

        // Create a rider
        String riderEmail = "rider_" + UUID.randomUUID().toString().substring(0, 8) + "@test.com";
        RegisterRequest rReq = new RegisterRequest();
        rReq.setName("Test Rider");
        rReq.setEmail(riderEmail);
        rReq.setPassword("Password123!");
        rReq.setPhoneNumber("+1" + (int)(Math.random() * 900000000 + 100000000));
        authService.register(rReq);
        riderUser = userRepository.findByEmail(riderEmail);
        riderToken = "Bearer " + jwtUtil.generateToken(riderEmail);
    }

    @Test
    public void testUnauthorizedUserCannotPublishRides() {
        // A user with role RIDER cannot publish a ride offer
        Ride ride = new Ride();
        ride.setPickupLocation("Delhi");
        ride.setDropoffLocation("Noida");
        ride.setFare(300.0);
        ride.setAvailableSeats(3);
        ride.setDriver(riderUser);

        ResponseEntity<?> response = rideController.requestRide(riderToken, ride);
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        Map body = (Map) response.getBody();
        assertTrue(body.get("message").toString().contains("Only approved drivers"));
    }

    @Test
    public void testStrictSingleActiveRideAndDuplicatePublishConflict() {
        // Driver publishes first ride
        Ride ride1 = new Ride();
        ride1.setPickupLocation("Connaught Place, Delhi");
        ride1.setDropoffLocation("Cyber Hub, Gurgaon");
        ride1.setFare(400.0);
        ride1.setAvailableSeats(3);
        ride1.setTotalSeats(3);

        ResponseEntity<?> resp1 = rideController.requestRide(driverToken, ride1);
        assertEquals(HttpStatus.OK, resp1.getStatusCode());
        Ride published1 = (Ride) resp1.getBody();
        assertNotNull(published1);
        assertEquals("PUBLISHED", published1.getStatus());

        // Driver attempts to publish a second active ride while first is PUBLISHED
        Ride ride2 = new Ride();
        ride2.setPickupLocation("Sector 18, Noida");
        ride2.setDropoffLocation("Indirapuram, Ghaziabad");
        ride2.setFare(200.0);
        ride2.setAvailableSeats(2);

        ResponseEntity<?> resp2 = rideController.requestRide(driverToken, ride2);
        // Requirement 6: Must return HTTP 409 Conflict with clear message
        assertEquals(HttpStatus.CONFLICT, resp2.getStatusCode());
        Map body = (Map) resp2.getBody();
        assertNotNull(body);
        assertTrue(body.get("message").toString().contains("active ride"));
        assertTrue(body.get("message").toString().contains("complete or cancel"));
    }

    @Test
    public void testTerminalCompletionAllowsNewPublishing() {
        // Publish first ride
        Ride ride = new Ride();
        ride.setPickupLocation("Koramangala, Bangalore");
        ride.setDropoffLocation("Whitefield, Bangalore");
        ride.setFare(350.0);
        ride.setAvailableSeats(3);

        ResponseEntity<?> resp = rideController.requestRide(driverToken, ride);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Ride published = (Ride) resp.getBody();

        // Start trip -> IN_PROGRESS
        ResponseEntity<?> startResp = rideController.startRide(driverToken, published.getId());
        assertEquals(HttpStatus.OK, startResp.getStatusCode());
        assertEquals("IN_PROGRESS", ((Ride) startResp.getBody()).getStatus());

        // Still active in IN_PROGRESS, publishing should still conflict 409
        Ride duplicateAttempt = new Ride();
        duplicateAttempt.setPickupLocation("A");
        duplicateAttempt.setDropoffLocation("B");
        ResponseEntity<?> conflictResp = rideController.requestRide(driverToken, duplicateAttempt);
        assertEquals(HttpStatus.CONFLICT, conflictResp.getStatusCode());

        // Complete trip -> COMPLETED (terminal status)
        ResponseEntity<?> completeResp = rideController.completeRide(driverToken, published.getId());
        assertEquals(HttpStatus.OK, completeResp.getStatusCode());
        assertEquals("COMPLETED", ((Ride) completeResp.getBody()).getStatus());

        // Now driver can publish a new ride successfully
        Ride newRide = new Ride();
        newRide.setPickupLocation("Indiranagar, Bangalore");
        newRide.setDropoffLocation("Airport, Bangalore");
        newRide.setFare(600.0);
        newRide.setAvailableSeats(4);

        ResponseEntity<?> newResp = rideController.requestRide(driverToken, newRide);
        assertEquals(HttpStatus.OK, newResp.getStatusCode());
        assertEquals("PUBLISHED", ((Ride) newResp.getBody()).getStatus());
    }

    @Test
    public void testCancellationAllowsNewPublishing() {
        // Publish ride
        Ride ride = new Ride();
        ride.setPickupLocation("Mumbai CST");
        ride.setDropoffLocation("Navi Mumbai");
        ride.setFare(500.0);
        ride.setAvailableSeats(3);

        ResponseEntity<?> resp = rideController.requestRide(driverToken, ride);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Ride published = (Ride) resp.getBody();

        // Cancel ride -> CANCELLED (terminal status)
        ResponseEntity<?> cancelResp = rideController.cancelRide(driverToken, published.getId());
        assertEquals(HttpStatus.OK, cancelResp.getStatusCode());
        assertEquals("CANCELLED", ((Ride) cancelResp.getBody()).getStatus());

        // Driver can publish a new ride
        Ride secondRide = new Ride();
        secondRide.setPickupLocation("Bandra");
        secondRide.setDropoffLocation("Andheri");
        secondRide.setFare(200.0);
        secondRide.setAvailableSeats(2);

        ResponseEntity<?> secondResp = rideController.requestRide(driverToken, secondRide);
        assertEquals(HttpStatus.OK, secondResp.getStatusCode());
        assertEquals("PUBLISHED", ((Ride) secondResp.getBody()).getStatus());
    }

    @Test
    public void testExpiredRideAllowsNewPublishing() {
        // Create an expired ride directly in repository
        Ride ride = new Ride();
        ride.setDriver(driverUser);
        ride.setRider(driverUser);
        ride.setPickupLocation("Pune Station");
        ride.setDropoffLocation("Hinjewadi");
        ride.setFare(300.0);
        ride.setStatus("EXPIRED");
        ride.setRequestedAt(LocalDateTime.now().minusDays(2));
        rideRepository.save(ride);

        // Driver with only EXPIRED ride can publish new ride
        Ride newRide = new Ride();
        newRide.setPickupLocation("Pune Station");
        newRide.setDropoffLocation("Viman Nagar");
        newRide.setFare(250.0);
        newRide.setAvailableSeats(3);

        ResponseEntity<?> resp = rideController.requestRide(driverToken, newRide);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("PUBLISHED", ((Ride) resp.getBody()).getStatus());
    }

    @Test
    public void testAutomaticBookingConfirmationAndSeatDeduction() {
        // Driver publishes ride with 3 seats
        Ride ride = new Ride();
        ride.setPickupLocation("Jaipur");
        ride.setDropoffLocation("Delhi");
        ride.setFare(500.0);
        ride.setAvailableSeats(3);
        ride.setTotalSeats(3);

        ResponseEntity<?> pubResp = rideController.requestRide(driverToken, ride);
        Ride published = (Ride) pubResp.getBody();

        // Rider books 2 seats directly
        CreateBookingRequest bookReq = new CreateBookingRequest();
        bookReq.setRideId(published.getId());
        bookReq.setSeats(2);

        ResponseEntity<?> bookResp = bookingController.createBooking(riderToken, bookReq);
        assertEquals(HttpStatus.CREATED, bookResp.getStatusCode());
        Booking booking = (Booking) bookResp.getBody();

        // Requirement 6: Must be automatically CONFIRMED without driver approval
        assertEquals("CONFIRMED", booking.getStatus());
        assertEquals(2, booking.getSeats());

        // Requirement 14: Available seats must be atomically updated (3 - 2 = 1)
        Ride updatedRide = rideRepository.findById(published.getId()).orElseThrow();
        assertEquals(1, updatedRide.getAvailableSeats());
        assertEquals("PUBLISHED", updatedRide.getStatus());

        // Requirement 4 & 16: Driver manual accept must be rejected
        ResponseEntity<?> acceptResp = rideController.acceptRide(published.getId(), driverUser.getId());
        assertEquals(HttpStatus.BAD_REQUEST, acceptResp.getStatusCode());
    }

    @Test
    public void testFullRidePreventsOverbookingAndRestorationOnCancellation() {
        // Driver publishes ride with 1 seat
        Ride ride = new Ride();
        ride.setPickupLocation("Chandigarh");
        ride.setDropoffLocation("Shimla");
        ride.setFare(600.0);
        ride.setAvailableSeats(1);
        ride.setTotalSeats(1);

        ResponseEntity<?> pubResp = rideController.requestRide(driverToken, ride);
        Ride published = (Ride) pubResp.getBody();

        // Rider 1 books 1 seat -> fills the ride
        CreateBookingRequest req1 = new CreateBookingRequest();
        req1.setRideId(published.getId());
        req1.setSeats(1);
        ResponseEntity<?> bookResp1 = bookingController.createBooking(riderToken, req1);
        assertEquals(HttpStatus.CREATED, bookResp1.getStatusCode());
        Booking booking1 = (Booking) bookResp1.getBody();

        // Ride status should now be FULL and 0 available seats
        Ride rideAfterBook = rideRepository.findById(published.getId()).orElseThrow();
        assertEquals(0, rideAfterBook.getAvailableSeats());
        assertEquals("FULL", rideAfterBook.getStatus());

        // Another rider tries to book -> rejected due to insufficient seats
        String rider2Email = "rider2_" + UUID.randomUUID().toString().substring(0, 8) + "@test.com";
        RegisterRequest r2Req = new RegisterRequest();
        r2Req.setName("Rider 2");
        r2Req.setEmail(rider2Email);
        r2Req.setPassword("Password123!");
        authService.register(r2Req);
        String rider2Token = "Bearer " + jwtUtil.generateToken(rider2Email);

        CreateBookingRequest req2 = new CreateBookingRequest();
        req2.setRideId(published.getId());
        req2.setSeats(1);
        ResponseEntity<?> bookResp2 = bookingController.createBooking(rider2Token, req2);
        assertEquals(HttpStatus.BAD_REQUEST, bookResp2.getStatusCode());

        // Cancel booking 1 -> seat should be restored to 1 and status back to PUBLISHED
        ResponseEntity<?> cancelResp = bookingController.cancelBooking(riderToken, booking1.getId());
        assertEquals(HttpStatus.OK, cancelResp.getStatusCode());

        Ride rideAfterCancel = rideRepository.findById(published.getId()).orElseThrow();
        assertEquals(1, rideAfterCancel.getAvailableSeats());
        assertEquals("PUBLISHED", rideAfterCancel.getStatus());

        // Requirement 8: Driver STILL has an active ride restriction!
        Ride duplicateRide = new Ride();
        duplicateRide.setPickupLocation("Origin");
        duplicateRide.setDropoffLocation("Destination");
        ResponseEntity<?> dupResp = rideController.requestRide(driverToken, duplicateRide);
        assertEquals(HttpStatus.CONFLICT, dupResp.getStatusCode());
    }

    @Test
    public void testConcurrentRidePublishingPrevention() throws InterruptedException {
        // Simulate concurrent simultaneous publishing requests from the same driver
        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    latch.await();
                    Ride r = new Ride();
                    r.setPickupLocation("City " + index);
                    r.setDropoffLocation("Destination " + index);
                    r.setFare(300.0);
                    r.setAvailableSeats(3);
                    ResponseEntity<?> resp = rideController.requestRide(driverToken, r);
                    if (resp.getStatusCode() == HttpStatus.OK) {
                        successCount.incrementAndGet();
                    } else if (resp.getStatusCode() == HttpStatus.CONFLICT) {
                        conflictCount.incrementAndGet();
                    }
                } catch (Exception ignored) {}
            });
        }

        // Fire all threads simultaneously
        latch.countDown();
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        // Exactly ONE request must succeed; all other concurrent attempts must be rejected with 409 Conflict
        assertEquals(1, successCount.get(), "Exactly one ride publish should succeed under concurrent race condition");
        assertEquals(threads - 1, conflictCount.get(), "All other concurrent attempts must return 409 Conflict");
    }

    @Test
    public void testPendingPaymentDoesNotAutomaticallyCancelRideOrBooking() {
        // Driver publishes ride
        Ride ride = new Ride();
        ride.setPickupLocation("South Ex, Delhi");
        ride.setDropoffLocation("Noida Sec 62");
        ride.setFare(350.0);
        ride.setAvailableSeats(3);
        ride.setTotalSeats(3);
        ResponseEntity<?> pubResp = rideController.requestRide(driverToken, ride);
        Ride published = (Ride) pubResp.getBody();

        // Rider books with ONLINE payment method
        CreateBookingRequest req = new CreateBookingRequest();
        req.setRideId(published.getId());
        req.setSeats(1);
        req.setPaymentMethod("ONLINE");
        ResponseEntity<?> bookResp = bookingController.createBooking(riderToken, req);
        assertEquals(HttpStatus.CREATED, bookResp.getStatusCode());
        Booking booking = (Booking) bookResp.getBody();

        // 1. Pending payment must NOT cancel booking or ride
        assertEquals("CONFIRMED", booking.getStatus());
        assertEquals("PENDING", booking.getPaymentStatus());

        Ride currentRide = rideRepository.findById(published.getId()).orElseThrow();
        assertEquals("PUBLISHED", currentRide.getStatus());
        assertEquals(2, currentRide.getAvailableSeats());

        // Booking remains CONFIRMED and seats remain reserved
        Booking retrievedBooking = bookingRepository.findById(booking.getId()).orElseThrow();
        assertEquals("CONFIRMED", retrievedBooking.getStatus());
        assertEquals("PENDING", retrievedBooking.getPaymentStatus());
    }

    @Test
    public void testPaymentFailureRetainsBookingAndAllowsRetry() {
        // Driver publishes ride
        Ride ride = new Ride();
        ride.setPickupLocation("Hauz Khas");
        ride.setDropoffLocation("Cyber City");
        ride.setFare(400.0);
        ride.setAvailableSeats(2);
        Ride published = (Ride) rideController.requestRide(driverToken, ride).getBody();

        // Rider books
        CreateBookingRequest req = new CreateBookingRequest();
        req.setRideId(published.getId());
        req.setSeats(1);
        req.setPaymentMethod("ONLINE");
        Booking booking = (Booking) bookingController.createBooking(riderToken, req).getBody();

        // Create payment order
        CreatePaymentOrderRequest orderReq = new CreatePaymentOrderRequest();
        orderReq.setBookingId(booking.getId());
        orderReq.setAmount(booking.getTotalAmount());
        orderReq.setCurrency("INR");
        ResponseEntity<?> orderResp = paymentController.createOrder(riderToken, orderReq);
        assertEquals(HttpStatus.CREATED, orderResp.getStatusCode());
        Map<?, ?> orderMap = (Map<?, ?>) orderResp.getBody();
        String gatewayOrderId = (String) orderMap.get("gatewayOrderId");

        // Verify with invalid signature (simulating gateway failure or mismatch)
        VerifyPaymentRequest verifyReq = new VerifyPaymentRequest();
        verifyReq.setGatewayOrderId(gatewayOrderId);
        verifyReq.setGatewayPaymentId("pay_fail_123");
        verifyReq.setGatewaySignature(""); // Blank signature triggers failure

        ResponseEntity<?> verifyResp = paymentController.verifyPayment(riderToken, verifyReq);
        assertEquals(HttpStatus.BAD_REQUEST, verifyResp.getStatusCode());

        // Rule 1: Definitive failure retains the booking and seat reservation!
        Booking afterFailureBooking = bookingRepository.findById(booking.getId()).orElseThrow();
        assertEquals("CONFIRMED", afterFailureBooking.getStatus(), "Booking status must remain CONFIRMED upon payment failure");
        assertEquals("FAILED", afterFailureBooking.getPaymentStatus(), "Payment status must be FAILED to allow retry");

        Ride rideAfterFailure = rideRepository.findById(published.getId()).orElseThrow();
        assertEquals(1, rideAfterFailure.getAvailableSeats(), "Seat reservation must not be released on payment failure");

        // Rider retries payment successfully
        ResponseEntity<?> retryPayResp = bookingController.payBooking(riderToken, booking.getId(), Map.of(
                "paymentMethod", "ONLINE",
                "transactionReference", "TXN_RETRY_SUCCESS"
        ));
        assertEquals(HttpStatus.OK, retryPayResp.getStatusCode());
        Booking afterRetryBooking = bookingRepository.findById(booking.getId()).orElseThrow();
        assertEquals("PAID", afterRetryBooking.getPaymentStatus());
        assertEquals("CONFIRMED", afterRetryBooking.getStatus());
    }

    @Test
    public void testElapsedDepartureTimeDoesNotAutomaticallyCompleteOrCancelRide() {
        // Driver publishes ride with past departure time
        Ride ride = new Ride();
        ride.setPickupLocation("Old Delhi");
        ride.setDropoffLocation("New Delhi");
        ride.setFare(200.0);
        ride.setAvailableSeats(3);
        ride.setDepartureTime(LocalDateTime.now().minusHours(3)); // 3 hours in the past
        Ride published = (Ride) rideController.requestRide(driverToken, ride).getBody();

        // Elapsed time must NOT automatically cancel or complete the ride
        Ride retrieved = rideRepository.findById(published.getId()).orElseThrow();
        assertEquals("PUBLISHED", retrieved.getStatus());
        assertNotEquals("CANCELLED", retrieved.getStatus());
        assertNotEquals("COMPLETED", retrieved.getStatus());
    }

    @Test
    public void testManualCompletionStrictlyAuthorizedForDriverOnly() {
        // Driver publishes ride
        Ride ride = new Ride();
        ride.setPickupLocation("Dwarka");
        ride.setDropoffLocation("Rohini");
        ride.setFare(450.0);
        ride.setAvailableSeats(3);
        Ride published = (Ride) rideController.requestRide(driverToken, ride).getBody();

        // 1. Rider cannot start or complete ride
        ResponseEntity<?> riderStart = rideController.startRide(riderToken, published.getId());
        assertEquals(HttpStatus.FORBIDDEN, riderStart.getStatusCode());

        ResponseEntity<?> riderComplete = rideController.completeRide(riderToken, published.getId());
        assertEquals(HttpStatus.FORBIDDEN, riderComplete.getStatusCode());

        // 2. Driver cannot complete a ride that is not IN_PROGRESS
        ResponseEntity<?> driverPrematureComplete = rideController.completeRide(driverToken, published.getId());
        assertEquals(HttpStatus.BAD_REQUEST, driverPrematureComplete.getStatusCode());

        // 3. Driver starts ride -> IN_PROGRESS
        ResponseEntity<?> startResp = rideController.startRide(driverToken, published.getId());
        assertEquals(HttpStatus.OK, startResp.getStatusCode());
        assertEquals("IN_PROGRESS", ((Ride) startResp.getBody()).getStatus());

        // 4. Authorized driver explicitly completes ride
        ResponseEntity<?> completeResp = rideController.completeRide(driverToken, published.getId());
        assertEquals(HttpStatus.OK, completeResp.getStatusCode());
        Ride completedRide = (Ride) completeResp.getBody();
        assertEquals("COMPLETED", completedRide.getStatus());
        assertNotNull(completedRide.getCompletedAt(), "Completion timestamp must be recorded");
        assertEquals(driverUser.getEmail(), completedRide.getCompletedBy(), "Authorized actor identity must be recorded");

        // 5. Duplicate completion attempt must be rejected
        ResponseEntity<?> duplicateResp = rideController.completeRide(driverToken, published.getId());
        assertEquals(HttpStatus.CONFLICT, duplicateResp.getStatusCode());
    }

    @Test
    public void testCompletingRideDoesNotMarkUnpaidPaymentAsPaid() {
        // Driver publishes ride
        Ride ride = new Ride();
        ride.setPickupLocation("Aerocity");
        ride.setDropoffLocation("Gurgaon Phase 1");
        ride.setFare(500.0);
        ride.setAvailableSeats(3);
        Ride published = (Ride) rideController.requestRide(driverToken, ride).getBody();

        // Passenger books with PAY_AFTER_RIDE option
        CreateBookingRequest bookReq = new CreateBookingRequest();
        bookReq.setRideId(published.getId());
        bookReq.setSeats(1);
        bookReq.setPaymentMethod("PAY_AFTER_RIDE");
        Booking booking = (Booking) bookingController.createBooking(riderToken, bookReq).getBody();
        assertEquals("UNPAID", booking.getPaymentStatus());

        // Driver starts and completes trip
        rideController.startRide(driverToken, published.getId());
        ResponseEntity<?> compResp = rideController.completeRide(driverToken, published.getId());
        assertEquals(HttpStatus.OK, compResp.getStatusCode());

        // Rule 3: Completing trip must NEVER automatically mark payment as PAID!
        Booking postTripBooking = bookingRepository.findById(booking.getId()).orElseThrow();
        assertEquals("COMPLETED", postTripBooking.getStatus(), "Booking transitions to COMPLETED");
        assertEquals("UNPAID", postTripBooking.getPaymentStatus(), "Payment status MUST stay UNPAID until explicitly settled");

        // Rider pays post-trip via Pay Now
        ResponseEntity<?> payPostTrip = bookingController.payBooking(riderToken, booking.getId(), Map.of(
                "paymentMethod", "ONLINE",
                "transactionReference", "TXN_POST_TRIP_SETTLED"
        ));
        assertEquals(HttpStatus.OK, payPostTrip.getStatusCode());

        Booking finalizedBooking = bookingRepository.findById(booking.getId()).orElseThrow();
        assertEquals("PAID", finalizedBooking.getPaymentStatus());
    }

    @Test
    public void testExplicitAuthorizedCancellationPolicyAndRefund() {
        // Driver publishes ride
        Ride ride = new Ride();
        ride.setPickupLocation("Saket");
        ride.setDropoffLocation("Noida");
        ride.setFare(300.0);
        ride.setAvailableSeats(2);
        Ride published = (Ride) rideController.requestRide(driverToken, ride).getBody();

        // Rider books and pays upfront
        CreateBookingRequest bookReq = new CreateBookingRequest();
        bookReq.setRideId(published.getId());
        bookReq.setSeats(1);
        Booking booking = (Booking) bookingController.createBooking(riderToken, bookReq).getBody();

        // Mark paid
        bookingController.payBooking(riderToken, booking.getId(), Map.of("paymentMethod", "ONLINE"));
        Booking paidBooking = bookingRepository.findById(booking.getId()).orElseThrow();
        assertEquals("PAID", paidBooking.getPaymentStatus());

        // Rider cancels booking
        ResponseEntity<?> cancelResp = bookingController.cancelBooking(riderToken, booking.getId());
        assertEquals(HttpStatus.OK, cancelResp.getStatusCode());

        Booking cancelledBooking = bookingRepository.findById(booking.getId()).orElseThrow();
        assertEquals("CANCELLED", cancelledBooking.getStatus());
        assertTrue("REFUND_PENDING".equals(cancelledBooking.getPaymentStatus()) || "REFUNDED".equals(cancelledBooking.getPaymentStatus()), "Paid cancelled booking triggers refund status");

        // Seat is restored
        Ride rideAfterCancel = rideRepository.findById(published.getId()).orElseThrow();
        assertEquals(2, rideAfterCancel.getAvailableSeats());

        // Cannot cancel a completed booking
        rideController.startRide(driverToken, published.getId());
        rideController.completeRide(driverToken, published.getId());

        // Completed ride cannot be cancelled
        ResponseEntity<?> cancelCompletedRide = rideController.cancelRide(driverToken, published.getId());
        assertEquals(HttpStatus.BAD_REQUEST, cancelCompletedRide.getStatusCode());
    }

    @Test
    public void testPaymentMethodsEndpointReturnsConfiguredModes() {
        ResponseEntity<?> resp = paymentController.getPaymentMethodsConfig();
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertNotNull(body);
        assertTrue(body.containsKey("CARD"));
        assertTrue(body.containsKey("UPI"));
        assertTrue(body.containsKey("WALLET"));
        assertTrue(body.containsKey("CASH"));
    }

    @Test
    public void testPaymentReceiptAuthorizedAccessAndItemization() {
        // Driver publishes ride
        Ride ride = new Ride();
        ride.setPickupLocation("Indiranagar");
        ride.setDropoffLocation("Whitefield");
        ride.setFare(250.0);
        ride.setAvailableSeats(3);
        Ride published = (Ride) rideController.requestRide(driverToken, ride).getBody();

        // Rider books 2 seats with ONLINE payment
        CreateBookingRequest bookReq = new CreateBookingRequest();
        bookReq.setRideId(published.getId());
        bookReq.setSeats(2);
        bookReq.setPaymentMethod("ONLINE");
        Booking booking = (Booking) bookingController.createBooking(riderToken, bookReq).getBody();

        // Mark paid with reference
        bookingController.payBooking(riderToken, booking.getId(), Map.of(
                "paymentMethod", "ONLINE",
                "transactionReference", "TXN_ONLINE_RECEIPT_123"
        ));

        // Passenger can fetch receipt
        ResponseEntity<?> passengerReceiptResp = paymentController.getPaymentReceipt(riderToken, booking.getId());
        assertEquals(HttpStatus.OK, passengerReceiptResp.getStatusCode());
        Map<?, ?> receiptBody = (Map<?, ?>) passengerReceiptResp.getBody();
        assertTrue((Boolean) receiptBody.get("success"));
        Map<?, ?> receipt = (Map<?, ?>) receiptBody.get("receipt");
        assertEquals(booking.getId(), ((Number) receipt.get("bookingId")).longValue());
        assertEquals(500.0, ((Number) receipt.get("baseRideContribution")).doubleValue());
        assertEquals(50.0, ((Number) receipt.get("platformFee")).doubleValue());
        assertEquals(550.0, ((Number) receipt.get("totalAmount")).doubleValue());
        assertEquals("PAID", receipt.get("paymentStatus"));
        assertEquals("TXN_ONLINE_RECEIPT_123", receipt.get("transactionReference"));

        // Driver can also fetch receipt
        ResponseEntity<?> driverReceiptResp = paymentController.getPaymentReceipt(driverToken, booking.getId());
        assertEquals(HttpStatus.OK, driverReceiptResp.getStatusCode());

        // Unauthorized user cannot fetch receipt
        String unauthorizedToken = "Bearer " + jwtUtil.generateToken("unauthorized_stranger@test.com");
        ResponseEntity<?> unauthorizedResp = paymentController.getPaymentReceipt(unauthorizedToken, booking.getId());
        assertEquals(HttpStatus.FORBIDDEN, unauthorizedResp.getStatusCode());
    }

    @Test
    public void testDriverEarningsAccurateLedgerAndCashCollectionRules() {
        // Driver publishes ride
        Ride ride = new Ride();
        ride.setPickupLocation("MG Road");
        ride.setDropoffLocation("Electronic City");
        ride.setFare(200.0);
        ride.setAvailableSeats(4);
        Ride published = (Ride) rideController.requestRide(driverToken, ride).getBody();

        // Rider 1 books 1 seat via CASH
        CreateBookingRequest b1Req = new CreateBookingRequest();
        b1Req.setRideId(published.getId());
        b1Req.setSeats(1);
        b1Req.setPaymentMethod("CASH");
        Booking cashBooking = (Booking) bookingController.createBooking(riderToken, b1Req).getBody();
        assertEquals("PENDING_CASH", cashBooking.getPaymentStatus());

        // Driver starts and completes ride
        rideController.startRide(driverToken, published.getId());
        rideController.completeRide(driverToken, published.getId());

        // Rule: Driver CANNOT mark online payments as cash!
        // Let's create an online booking to verify driver cannot confirm cash on it
        Ride ride2 = new Ride();
        ride2.setPickupLocation("Koramangala");
        ride2.setDropoffLocation("Bellandur");
        ride2.setFare(150.0);
        ride2.setAvailableSeats(2);
        Ride published2 = (Ride) rideController.requestRide(driverToken, ride2).getBody();

        CreateBookingRequest b2Req = new CreateBookingRequest();
        b2Req.setRideId(published2.getId());
        b2Req.setSeats(1);
        b2Req.setPaymentMethod("ONLINE");
        Booking onlineBooking = (Booking) bookingController.createBooking(riderToken, b2Req).getBody();

        // Driver attempts to call confirmCashPayment on online booking -> Must return 400 Bad Request
        ResponseEntity<?> invalidCashConfirm = bookingController.confirmCashPayment(driverToken, onlineBooking.getId());
        assertEquals(HttpStatus.BAD_REQUEST, invalidCashConfirm.getStatusCode());

        // Driver marks valid cash booking as received
        ResponseEntity<?> validCashConfirm = bookingController.confirmCashPayment(driverToken, cashBooking.getId());
        assertEquals(HttpStatus.OK, validCashConfirm.getStatusCode());
        Booking updatedCashBooking = bookingRepository.findById(cashBooking.getId()).orElseThrow();
        assertEquals("PAID", updatedCashBooking.getPaymentStatus());
        assertTrue(updatedCashBooking.getTransactionReference().startsWith("CASH_COLLECTED_AUDIT_"));

        // Duplicate cash confirmation must be rejected (409 Conflict)
        ResponseEntity<?> dupCashConfirm = bookingController.confirmCashPayment(driverToken, cashBooking.getId());
        assertEquals(HttpStatus.CONFLICT, dupCashConfirm.getStatusCode());

        // Check Driver Earnings API returns real, verified financials
        ResponseEntity<?> earningsResp = driverService.getDriverEarningsSummary(driverUser);
        assertEquals(HttpStatus.OK, earningsResp.getStatusCode());
        Map<?, ?> earningsData = (Map<?, ?>) earningsResp.getBody();
        assertNotNull(earningsData);
        assertTrue((Boolean) earningsData.get("success"));

        // Net Earnings check: gross contribution 200.0, platform fee 50.0 => net earnings 150.0
        // Driver collected 250.0 total cash (200 fare + 50 fee), so Driver owes 50.0 platform fee!
        double grossContribution = ((Number) earningsData.get("grossRideContribution")).doubleValue();
        double platformFees = ((Number) earningsData.get("platformFees")).doubleValue();
        double netEarnings = ((Number) earningsData.get("netEarnings")).doubleValue();
        double cashCollected = ((Number) earningsData.get("cashCollectedByDriver")).doubleValue();
        double owedToPlatform = ((Number) earningsData.get("moneyOwedToPlatform")).doubleValue();

        assertEquals(200.0, grossContribution);
        assertEquals(50.0, platformFees);
        assertEquals(150.0, netEarnings);
        assertEquals(250.0, cashCollected);
        assertEquals(50.0, owedToPlatform);
    }

    @Test
    public void testPlatformCommissionCalculationAndLedgerRecording() {
        // Test backend commission calculation logic: 15% + 20.0 fixed fee
        // For gross fare 300.0: (300 * 0.15) + 20.0 = 45.0 + 20.0 = 65.0 commission, driver net = 235.0
        Map<String, Double> calc = commissionService.calculateCommission(300.0);
        assertEquals(65.0, calc.get("commissionAmount"));
        assertEquals(235.0, calc.get("driverNetAmount"));

        // Driver publishes ride
        Ride ride = new Ride();
        ride.setPickupLocation("Connaught Place");
        ride.setDropoffLocation("Cyber City");
        ride.setFare(250.0);
        ride.setAvailableSeats(3);
        Ride published = (Ride) rideController.requestRide(driverToken, ride).getBody();

        // Rider books with CASH payment: totalAmount = (250 * 1) + 50 platformFee = 300.0
        CreateBookingRequest bookReq = new CreateBookingRequest();
        bookReq.setRideId(published.getId());
        bookReq.setSeats(1);
        bookReq.setPaymentMethod("CASH");
        Booking booking = (Booking) bookingController.createBooking(riderToken, bookReq).getBody();

        // Driver confirms cash payment -> records commission charge automatically
        ResponseEntity<?> cashResp = bookingController.confirmCashPayment(driverToken, booking.getId());
        assertEquals(HttpStatus.OK, cashResp.getStatusCode());

        // Verify driver commission summary from Commission API
        ResponseEntity<?> commSummaryResp = commissionController.getCommissionSummary(driverToken);
        assertEquals(HttpStatus.OK, commSummaryResp.getStatusCode());
        Map<?, ?> summary = (Map<?, ?>) commSummaryResp.getBody();
        assertNotNull(summary);
        assertTrue(((Number) summary.get("outstandingCommission")).doubleValue() > 0.0);

        // Check idempotency: second cash confirmation rejected (409 Conflict)
        ResponseEntity<?> dupCash = bookingController.confirmCashPayment(driverToken, booking.getId());
        assertEquals(HttpStatus.CONFLICT, dupCash.getStatusCode());
    }

    @Test
    public void testCommissionSettlementLifecycleAndThresholdEnforcement() {
        // Driver publishes ride
        Ride ride = new Ride();
        ride.setPickupLocation("Sector 18");
        ride.setDropoffLocation("Sector 62");
        ride.setFare(200.0);
        ride.setAvailableSeats(2);
        Ride published = (Ride) rideController.requestRide(driverToken, ride).getBody();

        // Rider books via CASH
        CreateBookingRequest bookReq = new CreateBookingRequest();
        bookReq.setRideId(published.getId());
        bookReq.setSeats(1);
        bookReq.setPaymentMethod("CASH");
        Booking booking = (Booking) bookingController.createBooking(riderToken, bookReq).getBody();

        // Confirm cash collection
        bookingController.confirmCashPayment(driverToken, booking.getId());

        // Driver creates settlement order
        com.rideshare.backend.dto.CreateSettlementOrderRequest settleOrderReq = new com.rideshare.backend.dto.CreateSettlementOrderRequest();
        settleOrderReq.setPaymentMethod("UPI");
        ResponseEntity<?> orderResp = commissionController.createSettlementOrder(driverToken, settleOrderReq);
        assertEquals(HttpStatus.CREATED, orderResp.getStatusCode());
        Map<?, ?> orderMap = (Map<?, ?>) orderResp.getBody();
        String orderId = (String) orderMap.get("orderId");
        String gatewayOrderId = (String) orderMap.get("gatewayOrderId");
        double amount = ((Number) orderMap.get("amount")).doubleValue();
        assertTrue(amount > 0.0);

        // 1. Test settlement failure on bad signature: retains outstanding balance
        com.rideshare.backend.dto.VerifySettlementRequest failedVerify = new com.rideshare.backend.dto.VerifySettlementRequest();
        failedVerify.setOrderId(orderId);
        failedVerify.setGatewayOrderId(gatewayOrderId);
        failedVerify.setGatewayPaymentId("pay_dummy_fail");
        failedVerify.setGatewaySignature(""); // missing signature triggers failure
        ResponseEntity<?> failedResp = commissionController.verifySettlement(driverToken, failedVerify);
        assertEquals(HttpStatus.BAD_REQUEST, failedResp.getStatusCode());

        // Balance should still be outstanding
        Map<?, ?> midSummary = (Map<?, ?>) commissionController.getCommissionSummary(driverToken).getBody();
        assertEquals(amount, ((Number) midSummary.get("outstandingCommission")).doubleValue());

        // 2. Test settlement success on valid signature: clears outstanding balance
        com.rideshare.backend.dto.VerifySettlementRequest successVerify = new com.rideshare.backend.dto.VerifySettlementRequest();
        successVerify.setOrderId(orderId);
        successVerify.setGatewayOrderId(gatewayOrderId);
        successVerify.setGatewayPaymentId("pay_verified_12345");
        successVerify.setGatewaySignature("valid_cryptographic_signature");
        ResponseEntity<?> successResp = commissionController.verifySettlement(driverToken, successVerify);
        assertEquals(HttpStatus.OK, successResp.getStatusCode());

        // Outstanding balance must now be 0.0
        Map<?, ?> settledSummary = (Map<?, ?>) commissionController.getCommissionSummary(driverToken).getBody();
        assertEquals(0.0, ((Number) settledSummary.get("outstandingCommission")).doubleValue());
        assertEquals(amount, ((Number) settledSummary.get("settledCommission")).doubleValue());
    }

    @Test
    public void testBookingCodeFormatAndCommissionSync() {
        Ride ride = new Ride();
        ride.setPickupLocation("Connaught Place");
        ride.setDropoffLocation("Cyber City");
        ride.setFare(250.0);
        ride.setAvailableSeats(3);
        Ride published = (Ride) rideController.requestRide(driverToken, ride).getBody();
        assertNotNull(published);

        CreateBookingRequest bookReq = new CreateBookingRequest();
        bookReq.setRideId(published.getId());
        bookReq.setSeats(2);
        bookReq.setPaymentMethod("CASH");
        Booking booking = (Booking) bookingController.createBooking(riderToken, bookReq).getBody();
        assertNotNull(booking);

        // 1. Verify 12-character alphanumeric bookingCode
        assertNotNull(booking.getBookingCode(), "Booking code must not be null");
        assertEquals(12, booking.getBookingCode().length(), "Booking code must be exactly 12 characters");
        assertTrue(booking.getBookingCode().matches("^[A-Z0-9]{12}$"), 
                "Booking code must be uppercase alphanumeric: " + booking.getBookingCode());

        // 2. Verify bookingCode in receipt
        ResponseEntity<?> receiptResp = paymentController.getPaymentReceipt(riderToken, booking.getId());
        assertEquals(HttpStatus.OK, receiptResp.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) receiptResp.getBody();
        Map<?, ?> receipt = (Map<?, ?>) body.get("receipt");
        assertNotNull(receipt, "Receipt object must not be null");
        assertEquals(booking.getBookingCode(), receipt.get("bookingCode"));

        // 3. Confirm cash payment & verify bookingCode in Commission Ledger history
        bookingController.confirmCashPayment(driverToken, booking.getId());
        ResponseEntity<?> summaryResp = commissionController.getCommissionSummary(driverToken);
        assertEquals(HttpStatus.OK, summaryResp.getStatusCode());
        Map<?, ?> summaryMap = (Map<?, ?>) summaryResp.getBody();
        List<?> history = (List<?>) summaryMap.get("history");
        assertFalse(history.isEmpty());

        Map<?, ?> latestEntry = (Map<?, ?>) history.get(0);
        assertEquals(booking.getBookingCode(), latestEntry.get("bookingCode"));
        assertTrue(((String) latestEntry.get("notes")).contains(booking.getBookingCode()));
    }
}

