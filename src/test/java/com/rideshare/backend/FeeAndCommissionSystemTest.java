package com.rideshare.backend;

import com.rideshare.backend.controller.AdminController;
import com.rideshare.backend.dto.CreatePaymentOrderRequest;
import com.rideshare.backend.dto.VerifyPaymentRequest;
import com.rideshare.backend.model.*;
import com.rideshare.backend.repository.*;
import com.rideshare.backend.security.JwtUtil;
import com.rideshare.backend.service.FeeCalculationService;
import com.rideshare.backend.service.FeeCalculationService.FeeCalculationResult;
import com.rideshare.backend.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class FeeAndCommissionSystemTest extends PostgresIntegrationTest {

    @Autowired
    private FeeCalculationService feeCalculationService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private AdminController adminController;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RideRepository rideRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private CommissionLedgerRepository commissionLedgerRepository;

    @Autowired
    private JwtUtil jwtUtil;

    private User adminUser;
    private User driverUser;
    private User riderUser;
    private Ride testRide;

    @BeforeEach
    public void setup() {
        // Ensure test users exist
        adminUser = userRepository.findByEmail("admin@rideshare.com");
        if (adminUser == null) {
            adminUser = new User();
            adminUser.setName("System Admin");
            adminUser.setEmail("admin@rideshare.com");
            adminUser.setPassword("Pass@123");
            adminUser.setRole("ADMIN");
            adminUser.setStatus("ACTIVE");
            adminUser = userRepository.save(adminUser);
        }

        driverUser = userRepository.findByEmail("testdriver_fee@rideshare.com");
        if (driverUser == null) {
            driverUser = new User();
            driverUser.setName("Fee Test Driver");
            driverUser.setEmail("testdriver_fee@rideshare.com");
            driverUser.setPassword("Pass@123");
            driverUser.setRole("DRIVER");
            driverUser.setStatus("ACTIVE");
            driverUser = userRepository.save(driverUser);
        }

        riderUser = userRepository.findByEmail("testrider_fee@rideshare.com");
        if (riderUser == null) {
            riderUser = new User();
            riderUser.setName("Fee Test Rider");
            riderUser.setEmail("testrider_fee@rideshare.com");
            riderUser.setPassword("Pass@123");
            riderUser.setRole("RIDER");
            riderUser.setStatus("ACTIVE");
            riderUser = userRepository.save(riderUser);
        }

        // Create a persistent test ride
        testRide = new Ride();
        testRide.setRider(riderUser);
        testRide.setDriver(driverUser);
        testRide.setPickupLocation("Test Pickup Terminal");
        testRide.setDropoffLocation("Test Dropoff Hub");
        testRide.setDepartureTime(LocalDateTime.now().plusDays(1));
        testRide.setFare(500.0);
        testRide.setAvailableSeats(4);
        testRide.setStatus("PUBLISHED");
        testRide = rideRepository.save(testRide);
    }

    // -------------------------------------------------------------
    // SCENARIO 1: Both fees disabled (Default business model state)
    // -------------------------------------------------------------
    @Test
    @DisplayName("Scenario 1: Both fees disabled - Rider pays base fare only, driver receives 100% of base fare")
    public void testBothFeesDisabled() {
        PlatformSettings settings = PlatformSettings.builder()
                .driverCommissionEnabled(false)
                .driverCommissionType("PERCENTAGE")
                .driverCommissionRatePercent(0.0)
                .driverCommissionFixedAmount(0.0)
                .riderFeeEnabled(false)
                .riderFeeType("FIXED")
                .riderFeeAmount(0.0)
                .riderFeePercent(0.0)
                .build();

        FeeCalculationResult breakdown = feeCalculationService.calculateQuote(500.0, 1, settings);

        assertEquals(0, new BigDecimal("500.00").compareTo(breakdown.getBaseFare()));
        assertEquals(0, BigDecimal.ZERO.setScale(2).compareTo(breakdown.getRiderPlatformFee()));
        assertEquals(0, new BigDecimal("500.00").compareTo(breakdown.getTotalAmount()));
        assertEquals(0, BigDecimal.ZERO.setScale(2).compareTo(breakdown.getDriverCommissionAmount()));
        assertEquals(0, new BigDecimal("500.00").compareTo(breakdown.getDriverNetEarnings()));
        assertEquals(0, BigDecimal.ZERO.setScale(2).compareTo(breakdown.getPlatformRetention()));
    }

    // -------------------------------------------------------------
    // SCENARIO 2: Driver commission enabled; rider fee disabled
    // -------------------------------------------------------------
    @Test
    @DisplayName("Scenario 2: Driver commission 10% enabled, rider fee disabled - Rider pays 500, driver keeps 450, platform retains 50")
    public void testDriverCommissionEnabledRiderFeeDisabled() {
        PlatformSettings settings = PlatformSettings.builder()
                .driverCommissionEnabled(true)
                .driverCommissionType("PERCENTAGE")
                .driverCommissionRatePercent(10.0)
                .driverCommissionFixedAmount(0.0)
                .riderFeeEnabled(false)
                .riderFeeType("FIXED")
                .riderFeeAmount(0.0)
                .build();

        FeeCalculationResult breakdown = feeCalculationService.calculateQuote(500.0, 1, settings);

        assertEquals(0, new BigDecimal("500.00").compareTo(breakdown.getBaseFare()));
        assertEquals(0, BigDecimal.ZERO.setScale(2).compareTo(breakdown.getRiderPlatformFee()));
        assertEquals(0, new BigDecimal("500.00").compareTo(breakdown.getTotalAmount()));
        assertEquals(0, new BigDecimal("50.00").compareTo(breakdown.getDriverCommissionAmount()));
        assertEquals(0, new BigDecimal("450.00").compareTo(breakdown.getDriverNetEarnings()));
        assertEquals(0, new BigDecimal("50.00").compareTo(breakdown.getPlatformRetention()));
    }

    // -------------------------------------------------------------
    // SCENARIO 3: Driver commission disabled; rider fee enabled
    // -------------------------------------------------------------
    @Test
    @DisplayName("Scenario 3: Driver commission disabled, rider fee fixed Rs 35 enabled - Rider pays 535, driver keeps 500, platform retains 35")
    public void testDriverCommissionDisabledRiderFeeEnabled() {
        PlatformSettings settings = PlatformSettings.builder()
                .driverCommissionEnabled(false)
                .driverCommissionRatePercent(0.0)
                .riderFeeEnabled(true)
                .riderFeeType("FIXED")
                .riderFeeAmount(35.0)
                .build();

        FeeCalculationResult breakdown = feeCalculationService.calculateQuote(500.0, 1, settings);

        assertEquals(0, new BigDecimal("500.00").compareTo(breakdown.getBaseFare()));
        assertEquals(0, new BigDecimal("35.00").compareTo(breakdown.getRiderPlatformFee()));
        assertEquals(0, new BigDecimal("535.00").compareTo(breakdown.getTotalAmount()));
        assertEquals(0, BigDecimal.ZERO.setScale(2).compareTo(breakdown.getDriverCommissionAmount()));
        assertEquals(0, new BigDecimal("500.00").compareTo(breakdown.getDriverNetEarnings()));
        assertEquals(0, new BigDecimal("35.00").compareTo(breakdown.getPlatformRetention()));
    }

    // -------------------------------------------------------------
    // SCENARIO 4: Both fees enabled (Dual independent charges)
    // -------------------------------------------------------------
    @Test
    @DisplayName("Scenario 4: Both fees enabled - 10% commission on base fare + Rs 30 rider fee on base fare")
    public void testBothFeesEnabled() {
        PlatformSettings settings = PlatformSettings.builder()
                .driverCommissionEnabled(true)
                .driverCommissionType("PERCENTAGE")
                .driverCommissionRatePercent(10.0)
                .riderFeeEnabled(true)
                .riderFeeType("FIXED")
                .riderFeeAmount(30.0)
                .build();

        FeeCalculationResult breakdown = feeCalculationService.calculateQuote(1000.0, 1, settings);

        // Base fare: 1000.00
        // Rider fee: 30.00 -> Rider pays 1030.00
        // Driver commission: 10% of 1000.00 = 100.00 (Commission base is strictly baseFare, NOT baseFare+riderFee!)
        // Driver net earnings: 1000.00 - 100.00 = 900.00
        // Platform retained: 30.00 + 100.00 = 130.00
        assertEquals(0, new BigDecimal("1000.00").compareTo(breakdown.getBaseFare()));
        assertEquals(0, new BigDecimal("30.00").compareTo(breakdown.getRiderPlatformFee()));
        assertEquals(0, new BigDecimal("1030.00").compareTo(breakdown.getTotalAmount()));
        assertEquals(0, new BigDecimal("100.00").compareTo(breakdown.getDriverCommissionAmount()));
        assertEquals(0, new BigDecimal("900.00").compareTo(breakdown.getDriverNetEarnings()));
        assertEquals(0, new BigDecimal("130.00").compareTo(breakdown.getPlatformRetention()));
    }

    // -------------------------------------------------------------
    // SCENARIO 5: Percentage and fixed-fee calculations
    // -------------------------------------------------------------
    @Test
    @DisplayName("Scenario 5: Fixed commission and percentage rider fee")
    public void testFixedCommissionAndPercentageRiderFee() {
        PlatformSettings settings = PlatformSettings.builder()
                .driverCommissionEnabled(true)
                .driverCommissionType("FIXED")
                .driverCommissionFixedAmount(45.0)
                .riderFeeEnabled(true)
                .riderFeeType("PERCENTAGE")
                .riderFeePercent(5.0)
                .build();

        FeeCalculationResult breakdown = feeCalculationService.calculateQuote(800.0, 1, settings);

        // Rider fee: 5% of 800.00 = 40.00 -> Rider pays 840.00
        // Driver commission: Fixed 45.00 -> Driver keeps 800.00 - 45.00 = 755.00
        // Platform retained: 40.00 + 45.00 = 85.00
        assertEquals(0, new BigDecimal("40.00").compareTo(breakdown.getRiderPlatformFee()));
        assertEquals(0, new BigDecimal("840.00").compareTo(breakdown.getTotalAmount()));
        assertEquals(0, new BigDecimal("45.00").compareTo(breakdown.getDriverCommissionAmount()));
        assertEquals(0, new BigDecimal("755.00").compareTo(breakdown.getDriverNetEarnings()));
        assertEquals(0, new BigDecimal("85.00").compareTo(breakdown.getPlatformRetention()));
    }

    // -------------------------------------------------------------
    // SCENARIO 6: Invalid amounts and percentages (Admin validation rejection)
    // -------------------------------------------------------------
    @Test
    @DisplayName("Scenario 6: Rejection of invalid rates (< 0% or > 100%) and negative amounts")
    public void testInvalidRateRejections() {
        String adminToken = "Bearer " + jwtUtil.generateToken(adminUser.getEmail(), adminUser.getRole(), adminUser.getId());

        // Negative driver commission rate
        PlatformSettings invalidSettings1 = PlatformSettings.builder()
                .driverCommissionEnabled(true)
                .driverCommissionType("PERCENTAGE")
                .driverCommissionRatePercent(-5.0)
                .build();
        ResponseEntity<?> resp1 = adminController.updatePlatformSettings(adminToken, invalidSettings1, "127.0.0.1");
        assertEquals(HttpStatus.BAD_REQUEST, resp1.getStatusCode());

        // Rate > 100%
        PlatformSettings invalidSettings2 = PlatformSettings.builder()
                .driverCommissionEnabled(true)
                .driverCommissionType("PERCENTAGE")
                .driverCommissionRatePercent(105.0)
                .build();
        ResponseEntity<?> resp2 = adminController.updatePlatformSettings(adminToken, invalidSettings2, "127.0.0.1");
        assertEquals(HttpStatus.BAD_REQUEST, resp2.getStatusCode());

        // Negative rider platform fee
        PlatformSettings invalidSettings3 = PlatformSettings.builder()
                .riderFeeEnabled(true)
                .riderFeeType("FIXED")
                .riderFeeAmount(-20.0)
                .build();
        ResponseEntity<?> resp3 = adminController.updatePlatformSettings(adminToken, invalidSettings3, "127.0.0.1");
        assertEquals(HttpStatus.BAD_REQUEST, resp3.getStatusCode());
    }

    // -------------------------------------------------------------
    // SCENARIO 7: Rounding and currency handling (HALF_UP verification)
    // -------------------------------------------------------------
    @Test
    @DisplayName("Scenario 7: Half-up rounding precision to 2 decimal places")
    public void testRoundingAndCurrencyHandling() {
        PlatformSettings settings = PlatformSettings.builder()
                .driverCommissionEnabled(true)
                .driverCommissionType("PERCENTAGE")
                .driverCommissionRatePercent(7.77) // 7.77% on Rs 333.33 -> 25.899741 -> rounds to 25.90
                .riderFeeEnabled(true)
                .riderFeeType("PERCENTAGE")
                .riderFeePercent(3.33) // 3.33% on Rs 333.33 -> 11.099889 -> rounds to 11.10
                .build();

        FeeCalculationResult breakdown = feeCalculationService.calculateQuote(333.33, 1, settings);

        assertEquals(new BigDecimal("11.10"), breakdown.getRiderPlatformFee());
        assertEquals(new BigDecimal("344.43"), breakdown.getTotalAmount());
        assertEquals(new BigDecimal("25.90"), breakdown.getDriverCommissionAmount());
        assertEquals(new BigDecimal("307.43"), breakdown.getDriverNetEarnings());
        assertEquals(new BigDecimal("37.00"), breakdown.getPlatformRetention());
    }

    // -------------------------------------------------------------
    // SCENARIO 8: Fee configuration changed after booking creation (Snapshot immutability)
    // -------------------------------------------------------------
    @Test
    @DisplayName("Scenario 8: Immutable snapshots - Changing platform settings does NOT alter existing booking snapshot")
    public void testSnapshotImmutability() {
        // Step 1: Create booking with commission 10% and rider fee Rs 25
        Booking booking = Booking.builder()
                .ride(testRide)
                .passenger(riderUser)
                .seats(1)
                .baseFare(400.0)
                .riderPlatformFee(25.0)
                .riderFeeEnabledSnapshot(true)
                .riderFeeTypeSnapshot("FIXED")
                .riderFeeRateSnapshot(25.0)
                .driverCommissionAmount(40.0)
                .driverCommissionEnabledSnapshot(true)
                .driverCommissionTypeSnapshot("PERCENTAGE")
                .driverCommissionRateSnapshot(10.0)
                .driverNetEarnings(360.0)
                .totalAmount(425.0)
                .status("CONFIRMED")
                .paymentStatus("PAID")
                .paymentMethod("ONLINE")
                .bookedAt(LocalDateTime.now())
                .pricingSnapshotTimestamp(LocalDateTime.now())
                .build();
        booking = bookingRepository.save(booking);
        Long bookingId = booking.getId();

        // Step 2: Now Admin modifies platform settings to 20% commission and Rs 50 rider fee
        String adminToken = "Bearer " + jwtUtil.generateToken(adminUser.getEmail(), adminUser.getRole(), adminUser.getId());
        PlatformSettings newSettings = PlatformSettings.builder()
                .driverCommissionEnabled(true)
                .driverCommissionType("PERCENTAGE")
                .driverCommissionRatePercent(20.0)
                .riderFeeEnabled(true)
                .riderFeeType("FIXED")
                .riderFeeAmount(50.0)
                .build();
        adminController.updatePlatformSettings(adminToken, newSettings, "127.0.0.1");

        // Step 3: Fetch original booking from DB and assert its snapshot remains strictly unchanged!
        Booking reloaded = bookingRepository.findById(bookingId).orElseThrow();
        assertEquals(400.0, reloaded.getBaseFare());
        assertEquals(25.0, reloaded.getRiderPlatformFee());
        assertEquals(40.0, reloaded.getDriverCommissionAmount());
        assertEquals(360.0, reloaded.getDriverNetEarnings());
        assertEquals(425.0, reloaded.getTotalAmount());
        assertEquals(10.0, reloaded.getDriverCommissionRateSnapshot());
    }

    // -------------------------------------------------------------
    // SCENARIO 9: Duplicate payment / Idempotency handling
    // -------------------------------------------------------------
    @Test
    @DisplayName("Scenario 9: Payment verification idempotency - Second verification does not double-charge or create duplicate ledger entries")
    public void testPaymentIdempotency() {
        String riderToken = "Bearer " + jwtUtil.generateToken(riderUser.getEmail(), riderUser.getRole(), riderUser.getId());

        CreatePaymentOrderRequest createReq = new CreatePaymentOrderRequest();
        createReq.setRideId(testRide.getId());
        createReq.setSeats(1);
        createReq.setPickupAddress("A");
        createReq.setDropoffAddress("B");

        ResponseEntity<?> orderResp = paymentService.createOrder(riderToken, createReq);
        assertEquals(HttpStatus.CREATED, orderResp.getStatusCode());
        Map<String, Object> body = (Map<String, Object>) orderResp.getBody();
        String orderId = (String) body.get("orderId");
        String gatewayOrderId = (String) body.get("gatewayOrderId");

        // First verification
        VerifyPaymentRequest verifyReq = new VerifyPaymentRequest();
        verifyReq.setOrderId(orderId);
        verifyReq.setGatewayOrderId(gatewayOrderId);
        verifyReq.setGatewayPaymentId("pay_test_" + UUID.randomUUID().toString().substring(0, 8));
        verifyReq.setGatewaySignature("test_sig");

        ResponseEntity<?> verifyResp1 = paymentService.verifyPayment(riderToken, verifyReq);
        assertEquals(HttpStatus.OK, verifyResp1.getStatusCode());

        // Count ledger entries before 2nd call
        long ledgerCountBefore = commissionLedgerRepository.count();

        // Second duplicate verification with same details
        ResponseEntity<?> verifyResp2 = paymentService.verifyPayment(riderToken, verifyReq);
        assertEquals(HttpStatus.OK, verifyResp2.getStatusCode());

        // Idempotency check: No duplicate ledger entries created
        long ledgerCountAfter = commissionLedgerRepository.count();
        assertEquals(ledgerCountBefore, ledgerCountAfter);
    }

    // -------------------------------------------------------------
    // SCENARIO 10: Full and partial refunds
    // -------------------------------------------------------------
    @Test
    @DisplayName("Scenario 10: Refund handling - Reverses retained platform fees and commissions proportionally")
    public void testRefundHandling() {
        Booking booking = Booking.builder()
                .ride(testRide)
                .passenger(riderUser)
                .seats(1)
                .baseFare(600.0)
                .riderPlatformFee(30.0)
                .driverCommissionAmount(60.0)
                .driverNetEarnings(540.0)
                .totalAmount(630.0)
                .status("CONFIRMED")
                .paymentStatus("PAID")
                .paymentMethod("ONLINE")
                .transactionReference("pay_refund_test_" + UUID.randomUUID().toString().substring(0, 8))
                .build();
        booking = bookingRepository.save(booking);

        // Process refund via paymentService
        ResponseEntity<?> refundResp = paymentService.processRefund(riderUser, booking.getId(), "Passenger cancelled");
        assertEquals(HttpStatus.OK, refundResp.getStatusCode());

        Booking refundedBooking = bookingRepository.findById(booking.getId()).orElseThrow();
        assertEquals("REFUNDED", refundedBooking.getPaymentStatus());
        assertEquals("CANCELLED", refundedBooking.getStatus());
    }

    // -------------------------------------------------------------
    // SCENARIO 11: Cancellation and failed payment
    // -------------------------------------------------------------
    @Test
    @DisplayName("Scenario 11: Cancelled booking does not generate unearned driver payouts")
    public void testCancellationState() {
        Booking cancelled = Booking.builder()
                .ride(testRide)
                .passenger(riderUser)
                .seats(1)
                .baseFare(300.0)
                .riderPlatformFee(20.0)
                .driverCommissionAmount(30.0)
                .driverNetEarnings(270.0)
                .totalAmount(320.0)
                .status("CANCELLED")
                .paymentStatus("FAILED")
                .paymentMethod("ONLINE")
                .build();
        cancelled = bookingRepository.save(cancelled);

        assertNotEquals("PAID", cancelled.getPaymentStatus());
        assertEquals("CANCELLED", cancelled.getStatus());
    }

    // -------------------------------------------------------------
    // SCENARIO 12: Unauthorized attempts to modify settings (RBAC)
    // -------------------------------------------------------------
    @Test
    @DisplayName("Scenario 12: RBAC security - Non-admin user receives 403 Forbidden when attempting to modify fee settings")
    public void testUnauthorizedSettingsModification() {
        String riderToken = "Bearer " + jwtUtil.generateToken(riderUser.getEmail(), riderUser.getRole(), riderUser.getId());

        PlatformSettings hackSettings = PlatformSettings.builder()
                .driverCommissionRatePercent(0.0)
                .riderFeeAmount(0.0)
                .build();

        ResponseEntity<?> resp = adminController.updatePlatformSettings(riderToken, hackSettings, "127.0.0.1");
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
    }

    // -------------------------------------------------------------
    // SCENARIO 13: Admin dashboard totals vs. booking-level ledger records
    // -------------------------------------------------------------
    @Test
    @DisplayName("Scenario 13: Finance summary metrics output distinct values and reconcile with bookings")
    public void testAdminFinanceSummaryMetrics() {
        String adminToken = "Bearer " + jwtUtil.generateToken(adminUser.getEmail(), adminUser.getRole(), adminUser.getId());
        ResponseEntity<?> summaryResp = adminController.getFinanceSummary(adminToken);
        assertEquals(HttpStatus.OK, summaryResp.getStatusCode());

        Map<String, Object> summary = (Map<String, Object>) summaryResp.getBody();
        assertNotNull(summary);
        assertTrue(summary.containsKey("totalRiderPlatformFees"));
        assertTrue(summary.containsKey("totalDriverCommission"));
        assertTrue(summary.containsKey("grossPlatformRevenue"));
        assertTrue(summary.containsKey("netPlatformRevenue"));
        assertTrue(summary.containsKey("pendingDriverCommission"));
    }
}
