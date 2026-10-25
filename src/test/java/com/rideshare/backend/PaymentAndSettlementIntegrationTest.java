package com.rideshare.backend;

import com.rideshare.backend.controller.WalletController;
import com.rideshare.backend.dto.CreatePaymentOrderRequest;
import com.rideshare.backend.dto.VerifyPaymentRequest;
import com.rideshare.backend.model.*;
import com.rideshare.backend.repository.*;
import com.rideshare.backend.security.JwtUtil;
import com.rideshare.backend.service.PaymentService;
import com.rideshare.backend.service.PayoutService;
import com.rideshare.backend.service.RazorpayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class PaymentAndSettlementIntegrationTest extends PostgresIntegrationTest {

    private static final String TEST_KEY_ID = "rzp_test_integrationKey123";
    private static final String TEST_KEY_SECRET = "testSecretKey456789";
    private static final String TEST_WEBHOOK_SECRET = "testWebhookSecret456789";

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PayoutService payoutService;

    @Autowired
    private RazorpayService razorpayService;

    @Autowired
    private WalletController walletController;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RideRepository rideRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private PaymentOrderRepository paymentOrderRepository;

    @Autowired
    private PaymentRefundRepository paymentRefundRepository;

    @Autowired
    private PlatformSettingsRepository platformSettingsRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private JwtUtil jwtUtil;

    private User rider;
    private User driver;
    private Ride ride;
    private String riderToken;
    private RestTemplate mockRestTemplate;

    @BeforeEach
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void setup() {
        String randomSuffix = UUID.randomUUID().toString().substring(0, 6);

        rider = new User();
        rider.setEmail("rider_pay_" + randomSuffix + "@rideshare.com");
        rider.setName("Rider Payment Tester");
        rider.setPassword("$2a$10$abcdefghijklmnopqrstuvwxyz123456");
        rider.setRole("RIDER");
        rider.setStatus("ACTIVE");
        rider = userRepository.save(rider);
        riderToken = "Bearer " + jwtUtil.generateToken(rider.getEmail());

        driver = new User();
        driver.setEmail("driver_pay_" + randomSuffix + "@rideshare.com");
        driver.setName("Driver Payment Tester");
        driver.setPassword("$2a$10$abcdefghijklmnopqrstuvwxyz123456");
        driver.setRole("DRIVER");
        driver.setStatus("ACTIVE");
        driver = userRepository.save(driver);

        ride = new Ride();
        ride.setDriver(driver);
        ride.setRider(driver);
        ride.setPickupLocation("Indiranagar, Bangalore");
        ride.setDropoffLocation("Electronic City, Bangalore");
        ride.setFare(500.0);
        ride.setTotalSeats(3);
        ride.setAvailableSeats(3);
        ride.setStatus("PUBLISHED");
        ride.setDepartureTime(LocalDateTime.now().plusHours(2));
        ride = rideRepository.save(ride);

        PlatformSettings settings = platformSettingsRepository.findAll().stream().findFirst()
                .orElseGet(() -> PlatformSettings.builder().build());
        settings.setRiderFeeEnabled(true);
        settings.setRiderFeeType("FIXED");
        settings.setPlatformFeeType("FIXED");
        settings.setRiderFeeAmount(50.0);
        settings.setPlatformFeeAmount(50.0);
        settings.setDriverCommissionEnabled(false);
        settings.setCommissionEnabled(false);
        settings.setDriverCommissionRatePercent(0.0);
        settings.setCommissionRatePercent(0.0);
        settings.setDriverCommissionFixedAmount(0.0);
        settings.setCommissionFixedFee(0.0);
        platformSettingsRepository.save(settings);

        // Configure strict test-mode credentials
        razorpayService.setMode("TEST");
        razorpayService.setKeyId(TEST_KEY_ID);
        razorpayService.setKeySecret(TEST_KEY_SECRET);
        razorpayService.setWebhookSecret(TEST_WEBHOOK_SECRET);

        // Configure mock RestTemplate for isolated test provider interaction
        mockRestTemplate = Mockito.mock(RestTemplate.class);
        Mockito.when(mockRestTemplate.postForEntity(
                ArgumentMatchers.contains("/orders"),
                ArgumentMatchers.any(),
                ArgumentMatchers.eq(Map.class)
        )).thenReturn(ResponseEntity.ok(Map.of(
                "id", "order_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10),
                "amount", 55000,
                "currency", "INR",
                "status", "created"
        )));

        Mockito.when(mockRestTemplate.postForEntity(
                ArgumentMatchers.contains("/refund"),
                ArgumentMatchers.any(),
                ArgumentMatchers.eq(Map.class)
        )).thenReturn(ResponseEntity.ok(Map.of(
                "id", "rfd_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10),
                "amount", 55000,
                "status", "processed"
        )));

        razorpayService.setRestTemplate(mockRestTemplate);
    }

    @Test
    @DisplayName("1. Valid Test-Order Creation via Test Provider")
    public void testValidTestOrderCreation() {
        Map<String, Object> order = razorpayService.createOrder(550.0, "rcpt_test_01", "INR");

        assertNotNull(order);
        assertNotNull(order.get("gatewayOrderId"));
        assertTrue(order.get("gatewayOrderId").toString().startsWith("order_test_"));
        assertEquals(550.0, ((Number) order.get("amount")).doubleValue());
        assertEquals("INR", order.get("currency"));
        assertEquals("CREATED", order.get("status"));
    }

    @Test
    @DisplayName("2. Missing Test Credentials Fails Closed")
    public void testMissingTestCredentialsFailsClosed() {
        razorpayService.setKeyId("");
        assertThrows(RazorpayService.PaymentConfigurationException.class, () -> {
            razorpayService.createOrder(500.0, "rcpt_fail_01", "INR");
        });

        razorpayService.setKeyId(TEST_KEY_ID);
        razorpayService.setKeySecret("");
        assertThrows(RazorpayService.PaymentConfigurationException.class, () -> {
            razorpayService.createOrder(500.0, "rcpt_fail_02", "INR");
        });
    }

    @Test
    @DisplayName("3. Attempted Live-Mode Activation is Strictly Rejected")
    public void testAttemptedLiveModeActivationRejected() {
        // Reject LIVE mode flag
        razorpayService.setMode("LIVE");
        assertThrows(RazorpayService.PaymentConfigurationException.class, () -> {
            razorpayService.requireTestCredentials();
        });

        // Reject rzp_live_ keys
        razorpayService.setMode("TEST");
        razorpayService.setKeyId("rzp_live_secretProductionKey123");
        assertThrows(RazorpayService.PaymentConfigurationException.class, () -> {
            razorpayService.requireTestCredentials();
        });
    }

    @Test
    @DisplayName("4. Cryptographic Signature Verification: Genuine vs Invalid and Forged")
    public void testCryptographicSignatureVerification() throws Exception {
        // Null or blank signature fails
        assertFalse(razorpayService.verifyPaymentSignature("order_123", "pay_123", null));
        assertFalse(razorpayService.verifyPaymentSignature("order_123", "pay_123", ""));

        // Forged or arbitrary signature fails
        assertFalse(razorpayService.verifyPaymentSignature("order_123", "pay_123", "forged_random_signature"));

        // Genuine HMAC-SHA256 signature calculated with test secret succeeds
        String genuineSignature = razorpayService.calculateHmacSha256("order_123|pay_123", TEST_KEY_SECRET);
        assertTrue(razorpayService.verifyPaymentSignature("order_123", "pay_123", genuineSignature));

        // Tampered order or payment ID fails even with real signature
        assertFalse(razorpayService.verifyPaymentSignature("order_tampered", "pay_123", genuineSignature));
    }

    @Test
    @DisplayName("5. Invalid Webhook Signature Rejected")
    public void testInvalidWebhookSignatureRejected() {
        String payload = "{\"event\":\"payment.captured\",\"payload\":{}}";

        // Missing signature rejected
        ResponseEntity<?> respNull = paymentService.handleWebhook(payload, null);
        assertEquals(HttpStatus.UNAUTHORIZED, respNull.getStatusCode());

        // Forged signature rejected
        ResponseEntity<?> respForged = paymentService.handleWebhook(payload, "forged_webhook_signature");
        assertEquals(HttpStatus.UNAUTHORIZED, respForged.getStatusCode());
    }

    @Test
    @DisplayName("6. Valid Test Webhook Processing (payment.captured and payment.failed)")
    public void testValidTestWebhookProcessing() throws Exception {
        // Create an order first
        CreatePaymentOrderRequest request = new CreatePaymentOrderRequest();
        request.setRideId(ride.getId());
        request.setSeats(1);
        request.setPaymentMethod("RAZORPAY");

        ResponseEntity<?> orderResp = paymentService.createOrder(riderToken, request);
        assertEquals(HttpStatus.CREATED, orderResp.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> orderBody = (Map<String, Object>) orderResp.getBody();
        String gatewayOrderId = (String) orderBody.get("gatewayOrderId");
        Long bookingId = Long.parseLong(orderBody.get("bookingId").toString());

        // Construct valid payment.captured webhook
        String paymentId = "pay_webhook_test_01";
        String payload = "{" +
                "\"event\":\"payment.captured\"," +
                "\"payload\":{" +
                "  \"payment\":{" +
                "    \"entity\":{" +
                "      \"id\":\"" + paymentId + "\"," +
                "      \"order_id\":\"" + gatewayOrderId + "\"" +
                "    }" +
                "  }" +
                "}" +
                "}";

        String validWebhookSig = razorpayService.calculateHmacSha256(payload, TEST_WEBHOOK_SECRET);
        ResponseEntity<?> webhookResp = paymentService.handleWebhook(payload, validWebhookSig);
        assertEquals(HttpStatus.OK, webhookResp.getStatusCode());

        // Verify that order and booking transitioned to PAID and CONFIRMED
        Booking updatedBooking = bookingRepository.findById(bookingId).orElseThrow();
        assertEquals("CONFIRMED", updatedBooking.getStatus());
        assertEquals("PAID", updatedBooking.getPaymentStatus());
    }

    @Test
    @DisplayName("7. Duplicate Webhook Delivery is Idempotent")
    public void testDuplicateWebhookDeliveryIdempotent() throws Exception {
        CreatePaymentOrderRequest request = new CreatePaymentOrderRequest();
        request.setRideId(ride.getId());
        request.setSeats(1);
        request.setPaymentMethod("RAZORPAY");

        ResponseEntity<?> orderResp = paymentService.createOrder(riderToken, request);
        @SuppressWarnings("unchecked")
        Map<String, Object> orderBody = (Map<String, Object>) orderResp.getBody();
        String gatewayOrderId = (String) orderBody.get("gatewayOrderId");

        String payload = "{" +
                "\"event\":\"payment.captured\"," +
                "\"payload\":{" +
                "  \"payment\":{" +
                "    \"entity\":{" +
                "      \"id\":\"pay_duplicate_test_01\"," +
                "      \"order_id\":\"" + gatewayOrderId + "\"" +
                "    }" +
                "  }" +
                "}" +
                "}";

        String signature = razorpayService.calculateHmacSha256(payload, TEST_WEBHOOK_SECRET);

        // First delivery
        ResponseEntity<?> resp1 = paymentService.handleWebhook(payload, signature);
        assertEquals(HttpStatus.OK, resp1.getStatusCode());

        // Second duplicate delivery
        ResponseEntity<?> resp2 = paymentService.handleWebhook(payload, signature);
        assertEquals(HttpStatus.OK, resp2.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body2 = (Map<String, Object>) resp2.getBody();
        assertEquals("WEBHOOK_PROCESSED", body2.get("status"));
    }

    @Test
    @DisplayName("8. Provider Timeout or Outage Fails Closed and Rolls Back Seat Lock")
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void testProviderTimeoutFailsClosed() {
        RestTemplate failingRestTemplate = Mockito.mock(RestTemplate.class);
        Mockito.when(failingRestTemplate.postForEntity(
                ArgumentMatchers.contains("/orders"),
                ArgumentMatchers.any(),
                ArgumentMatchers.eq(Map.class)
        )).thenThrow(new ResourceAccessException("Connection timed out to Razorpay test API"));

        razorpayService.setRestTemplate(failingRestTemplate);

        CreatePaymentOrderRequest request = new CreatePaymentOrderRequest();
        request.setRideId(ride.getId());
        request.setSeats(1);
        request.setPaymentMethod("RAZORPAY");

        // Order creation must fail closed with 503 SERVICE_UNAVAILABLE
        ResponseEntity<?> response = paymentService.createOrder(riderToken, request);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());

        // Available seats on ride must NOT be decremented (rolled back)
        Ride rideAfterFailure = rideRepository.findById(ride.getId()).orElseThrow();
        assertEquals(3, rideAfterFailure.getAvailableSeats());
    }

    @Test
    @DisplayName("9. Payment Verification Failure Does Not Confirm Booking")
    public void testPaymentFailureWithoutFalseSuccess() {
        CreatePaymentOrderRequest request = new CreatePaymentOrderRequest();
        request.setRideId(ride.getId());
        request.setSeats(1);
        request.setPaymentMethod("RAZORPAY");

        ResponseEntity<?> orderResp = paymentService.createOrder(riderToken, request);
        @SuppressWarnings("unchecked")
        Map<String, Object> orderBody = (Map<String, Object>) orderResp.getBody();
        String internalOrderId = (String) orderBody.get("orderId");
        String gatewayOrderId = (String) orderBody.get("gatewayOrderId");

        // Submit forged signature
        VerifyPaymentRequest verifyReq = new VerifyPaymentRequest();
        verifyReq.setOrderId(internalOrderId);
        verifyReq.setGatewayOrderId(gatewayOrderId);
        verifyReq.setGatewayPaymentId("pay_tampered_001");
        verifyReq.setGatewaySignature("forged_signature_not_matching_hmac");

        ResponseEntity<?> verifyResp = paymentService.verifyPayment(riderToken, verifyReq);
        assertEquals(HttpStatus.BAD_REQUEST, verifyResp.getStatusCode());

        // Booking must be FAILED, NOT CONFIRMED
        PaymentOrder order = paymentOrderRepository.findByOrderId(internalOrderId).orElseThrow();
        assertEquals("FAILED", order.getStatus());
        assertEquals("FAILED", order.getBooking().getPaymentStatus());
        assertNotEquals("CONFIRMED", order.getBooking().getStatus());
    }

    @Test
    @DisplayName("10. Driver Payout Rejects Sub-Threshold and Fails Closed Against Live Transfers")
    public void testPayoutFailureHandling() {
        // Below minimum threshold of ₹50 must be rejected
        ResponseEntity<?> rejectResp = payoutService.requestPayout(driver, Map.of("amount", 25.0));
        assertEquals(HttpStatus.BAD_REQUEST, rejectResp.getStatusCode());

        // Setup payout profile
        payoutService.updatePayoutProfile(driver, Map.of(
                "accountType", "BANK_ACCOUNT",
                "accountHolderName", "Driver Tester",
                "accountNumber", "123456789012",
                "ifscCode", "HDFC0001234",
                "bankName", "HDFC Bank"
        ));

        // Payout execution must fail closed without creating fake success records
        ResponseEntity<?> resp = payoutService.requestPayout(driver, Map.of("amount", 100.0));
        assertTrue(resp.getStatusCode().is4xxClientError() || resp.getStatusCode().is5xxServerError());
    }

    @Test
    @DisplayName("11. Wallet Top-Up Verification and Idempotency Protection")
    public void testWalletTopupAndIdempotency() throws Exception {
        String gatewayOrderId = "order_wallet_test_01";
        String gatewayPaymentId = "pay_wallet_test_01";
        String validSignature = razorpayService.calculateHmacSha256(gatewayOrderId + "|" + gatewayPaymentId, TEST_KEY_SECRET);

        // 1. Forged signature topup is rejected
        ResponseEntity<?> forgedResp = walletController.verifyWalletTopup(riderToken, Map.of(
                "amount", 200.0,
                "gatewayOrderId", gatewayOrderId,
                "gatewayPaymentId", gatewayPaymentId,
                "gatewaySignature", "forged_signature"
        ));
        assertEquals(HttpStatus.BAD_REQUEST, forgedResp.getStatusCode());

        // 2. Valid signature topup succeeds
        ResponseEntity<?> validResp = walletController.verifyWalletTopup(riderToken, Map.of(
                "amount", 200.0,
                "gatewayOrderId", gatewayOrderId,
                "gatewayPaymentId", gatewayPaymentId,
                "gatewaySignature", validSignature
        ));
        assertEquals(HttpStatus.OK, validResp.getStatusCode());

        Wallet walletAfter1 = walletController.getMyWallet(riderToken);
        assertEquals(200.0, walletAfter1.getBalance(), 0.01);

        // 3. Duplicate topup with identical payment ID is idempotent (balance unchanged)
        ResponseEntity<?> dupResp = walletController.verifyWalletTopup(riderToken, Map.of(
                "amount", 200.0,
                "gatewayOrderId", gatewayOrderId,
                "gatewayPaymentId", gatewayPaymentId,
                "gatewaySignature", validSignature
        ));
        assertEquals(HttpStatus.OK, dupResp.getStatusCode());

        Wallet walletAfter2 = walletController.getMyWallet(riderToken);
        assertEquals(200.0, walletAfter2.getBalance(), 0.01);

        // 4. Arbitrary unverified funding is forbidden
        ResponseEntity<?> addFundsResp = walletController.addFunds(riderToken, 5000.0);
        assertEquals(HttpStatus.FORBIDDEN, addFundsResp.getStatusCode());
    }

    @Test
    @DisplayName("12. Booking Confirmation Only After Verified Payment")
    public void testBookingConfirmationOnlyAfterVerifiedPayment() throws Exception {
        CreatePaymentOrderRequest request = new CreatePaymentOrderRequest();
        request.setRideId(ride.getId());
        request.setSeats(1);
        request.setPaymentMethod("RAZORPAY");

        ResponseEntity<?> orderResp = paymentService.createOrder(riderToken, request);
        @SuppressWarnings("unchecked")
        Map<String, Object> orderBody = (Map<String, Object>) orderResp.getBody();
        String internalOrderId = (String) orderBody.get("orderId");
        String gatewayOrderId = (String) orderBody.get("gatewayOrderId");

        String paymentId = "pay_test_verified_" + UUID.randomUUID().toString().substring(0, 6);
        String genuineSignature = razorpayService.calculateHmacSha256(gatewayOrderId + "|" + paymentId, TEST_KEY_SECRET);

        VerifyPaymentRequest verifyReq = new VerifyPaymentRequest();
        verifyReq.setOrderId(internalOrderId);
        verifyReq.setGatewayOrderId(gatewayOrderId);
        verifyReq.setGatewayPaymentId(paymentId);
        verifyReq.setGatewaySignature(genuineSignature);

        ResponseEntity<?> verifyResp = paymentService.verifyPayment(riderToken, verifyReq);
        assertEquals(HttpStatus.OK, verifyResp.getStatusCode());

        Booking confirmedBooking = bookingRepository.findById(Long.parseLong(orderBody.get("bookingId").toString())).orElseThrow();
        assertEquals("CONFIRMED", confirmedBooking.getStatus());
        assertEquals("PAID", confirmedBooking.getPaymentStatus());
    }

    @Test
    @DisplayName("13. Cash Booking Independence from Online Payments")
    public void testCashPaymentIndependence() {
        CreatePaymentOrderRequest request = new CreatePaymentOrderRequest();
        request.setRideId(ride.getId());
        request.setSeats(1);
        request.setPaymentMethod("CASH");

        ResponseEntity<?> response = paymentService.createOrder(riderToken, request);
        assertEquals(HttpStatus.OK, response.getStatusCode());

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        Long bookingId = Long.parseLong(body.get("bookingId").toString());

        Booking booking = bookingRepository.findById(bookingId).orElseThrow();
        assertEquals("CONFIRMED", booking.getStatus());
        assertEquals("UNPAID", booking.getPaymentStatus());
        assertEquals("CASH", booking.getPaymentMethod());

        // Zero calls to Razorpay gateway for cash bookings
        Mockito.verify(mockRestTemplate, Mockito.never()).postForEntity(
                ArgumentMatchers.contains("/orders"),
                ArgumentMatchers.any(),
                ArgumentMatchers.any()
        );
    }

    @Test
    @DisplayName("14. Dynamic Quote Calculation Consistency")
    public void testDynamicQuoteCalculationConsistency() {
        Map<String, Object> quote = paymentService.calculateQuote(ride, 2);
        assertNotNull(quote);
        assertEquals(2, quote.get("seats"));
        assertEquals(1000.0, ((Number) quote.get("baseFare")).doubleValue());
        assertTrue(((Number) quote.get("platformFee")).doubleValue() > 0);
        assertEquals("INR", quote.get("currency"));
    }
}
