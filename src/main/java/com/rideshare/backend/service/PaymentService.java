package com.rideshare.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rideshare.backend.dto.CouponValidationResponse;
import com.rideshare.backend.dto.CreatePaymentOrderRequest;
import com.rideshare.backend.dto.VerifyPaymentRequest;
import com.rideshare.backend.model.*;
import java.math.BigDecimal;

import com.rideshare.backend.repository.*;
import com.rideshare.backend.security.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    @Autowired
    private PaymentOrderRepository paymentOrderRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private RideRepository rideRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CommissionLedgerRepository commissionLedgerRepository;

    @Autowired
    private PaymentRefundRepository paymentRefundRepository;

    @Autowired
    private DriverPayoutRepository driverPayoutRepository;

    @Autowired
    private RazorpayService razorpayService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private EmailService emailService;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private FeeCalculationService feeCalculationService;

    @Autowired
    private CancellationFeeCalculationService cancellationFeeCalculationService;

    @Autowired
    private CouponService couponService;

    @Autowired(required = false)
    private RedisCacheService redisCacheService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Calculate quote from backend database settings (Never trust frontend financial inputs)
     */
    public Map<String, Object> calculateQuote(Ride ride, int seats) {
        FeeCalculationService.FeeCalculationResult result = feeCalculationService.calculateQuote(ride, seats);
        return result.toMap(ride != null ? ride.getId() : null);
    }

    /**
     * Get live pricing quote for passenger before booking with optional coupon validation.
     */
    public ResponseEntity<?> getQuoteForRide(Long rideId, int seats) {
        return getQuoteForRide(rideId, seats, null, null);
    }

    public ResponseEntity<?> getQuoteForRide(Long rideId, int seats, String couponCode, String token) {
        if (rideId == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "Ride ID is required."));
        }
        Optional<Ride> rideOpt = rideRepository.findById(rideId);
        if (rideOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Ride not found."));
        }
        Ride ride = rideOpt.get();

        User user = null;
        if (token != null && token.startsWith("Bearer ")) {
            try {
                String email = jwtUtil.extractEmail(token.substring(7));
                user = userRepository.findByEmail(email);
            } catch (Exception e) {}
        }

        if (couponCode != null && !couponCode.trim().isEmpty()) {
            CouponValidationResponse validation = couponService.validateCoupon(couponCode, rideId, seats, user);
            if (validation.isValid()) {
                FeeCalculationService.FeeCalculationResult result = feeCalculationService.calculateQuote(
                        ride,
                        seats,
                        BigDecimal.valueOf(validation.getDiscountAmount()),
                        validation.getCode(),
                        validation.getDiscountType(),
                        validation.getFundingType()
                );
                Map<String, Object> map = result.toMap(rideId);
                map.put("couponValid", true);
                map.put("couponMessage", validation.getMessage());
                return ResponseEntity.ok(map);
            } else {
                FeeCalculationService.FeeCalculationResult result = feeCalculationService.calculateQuote(ride, seats);
                Map<String, Object> map = result.toMap(rideId);
                map.put("couponValid", false);
                map.put("couponMessage", validation.getMessage());
                map.put("couponErrorCode", validation.getErrorCode());
                return ResponseEntity.ok(map);
            }
        }

        return ResponseEntity.ok(calculateQuote(ride, seats));
    }


    /**
     * Step 7: Create Payment Order with temporary seat lock & timeout
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public ResponseEntity<?> createOrder(String token, CreatePaymentOrderRequest request) {
        String email = jwtUtil.extractEmail(token.substring(7));
        User user = userRepository.findByEmail(email);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "User not found"));
        }

        // Clean up any stale expired seat locks first
        releaseExpiredSeatLocks();

        Booking booking;
        Ride ride;
        int requestedSeats = (request.getSeats() != null && request.getSeats() > 0) ? request.getSeats() : 1;

        if (request.getBookingId() != null) {
            booking = bookingRepository.findById(request.getBookingId()).orElse(null);
            if (booking == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Booking not found"));
            }
            if (!booking.getPassenger().getId().equals(user.getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Not authorized for this booking."));
            }
            if ("CANCELLED".equals(booking.getStatus()) || "EXPIRED".equals(booking.getStatus())) {
                return ResponseEntity.badRequest().body(Map.of("message", "Cannot pay for a " + booking.getStatus().toLowerCase() + " booking."));
            }
            if ("PAID".equals(booking.getPaymentStatus())) {
                return ResponseEntity.badRequest().body(Map.of("message", "Booking is already paid."));
            }
            ride = booking.getRide();
        } else if (request.getRideId() != null) {
            // Pessimistic lock on Ride to prevent race conditions & double-booking
            ride = rideRepository.findByIdForUpdate(request.getRideId()).orElse(null);
            if (ride == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Ride not found"));
            }

            if ("COMPLETED".equals(ride.getStatus()) || "CANCELLED".equals(ride.getStatus()) ||
                "IN_PROGRESS".equals(ride.getStatus()) || "EXPIRED".equals(ride.getStatus())) {
                return ResponseEntity.badRequest().body(Map.of("message", "Cannot book a ride that is " + ride.getStatus().toLowerCase() + "."));
            }

            if (ride.getDriver() != null && ride.getDriver().getId().equals(user.getId())) {
                return ResponseEntity.badRequest().body(Map.of("message", "Drivers cannot book seats on their own published ride."));
            }

            int available = ride.getAvailableSeats() != null ? ride.getAvailableSeats() : 0;
            if (available < requestedSeats) {
                return ResponseEntity.badRequest().body(Map.of(
                        "message", "Insufficient seats. Only " + available + " seat(s) remaining.",
                        "availableSeats", available
                ));
            }

            // Calculate backend financial quote using decimal-safe FeeCalculationService with optional coupon
            CouponValidationResponse couponVal = null;
            FeeCalculationService.FeeCalculationResult quoteResult;

            if (request.getCouponCode() != null && !request.getCouponCode().trim().isEmpty()) {
                couponVal = couponService.validateCoupon(request.getCouponCode(), ride.getId(), requestedSeats, user);
                if (!couponVal.isValid()) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "message", couponVal.getMessage(),
                            "errorCode", couponVal.getErrorCode()
                    ));
                }
                quoteResult = feeCalculationService.calculateQuote(
                        ride,
                        requestedSeats,
                        BigDecimal.valueOf(couponVal.getDiscountAmount()),
                        couponVal.getCode(),
                        couponVal.getDiscountType(),
                        couponVal.getFundingType()
                );
            } else {
                quoteResult = feeCalculationService.calculateQuote(ride, requestedSeats);
            }

            // Atomically lock seats
            int remaining = available - requestedSeats;
            ride.setAvailableSeats(remaining);
            if (remaining == 0) {
                ride.setStatus("FULL");
            }
            rideRepository.save(ride);

            PlatformSettings settings = feeCalculationService.getActivePlatformSettings();
            int timeoutMinutes = settings.getSeatLockTimeoutMinutes() != null ? settings.getSeatLockTimeoutMinutes() : 10;

            boolean isCash = "CASH".equalsIgnoreCase(request.getPaymentMethod());

            booking = Booking.builder()
                    .bookingCode(Booking.generateBookingCode())
                    .ride(ride)
                    .passenger(user)
                    .seats(requestedSeats)
                    .status(isCash ? "CONFIRMED" : "PENDING")
                    .paymentMethod(isCash ? "CASH" : "ONLINE")
                    .paymentStatus(isCash ? "UNPAID" : "PENDING")
                    .pickupAddress(request.getPickupAddress() != null ? request.getPickupAddress() : ride.getPickupLocation())
                    .dropoffAddress(request.getDropoffAddress() != null ? request.getDropoffAddress() : ride.getDropoffLocation())
                    .expiresAt(isCash ? null : LocalDateTime.now().plusMinutes(timeoutMinutes))
                    .bookedAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();

            // Freeze immutable fee & commission pricing snapshot
            feeCalculationService.applyPricingSnapshot(booking, quoteResult);

            booking = bookingRepository.save(booking);

            if (isCash && couponVal != null && couponVal.isValid()) {
                couponService.redeemCouponAtomically(couponVal.getCode(), booking, user);
            }

            if (isCash) {

                String cashOrderId = "CASH_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
                PaymentOrder cashOrder = PaymentOrder.builder()
                        .orderId(cashOrderId)
                        .gatewayOrderId("cash_order_" + booking.getId())
                        .booking(booking)
                        .user(user)
                        .ride(ride)
                        .amount(booking.getTotalAmount())
                        .platformFee(booking.getPlatformFee())
                        .driverAmount(booking.getDriverAmount())
                        .currency("INR")
                        .paymentMethod("CASH")
                        .provider("CASH")
                        .status("PENDING")
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build();

                PaymentOrder savedCash = paymentOrderRepository.save(cashOrder);

                Map<String, Object> cashResp = new HashMap<>();
                cashResp.put("orderId", savedCash.getOrderId());
                cashResp.put("gatewayOrderId", savedCash.getGatewayOrderId());
                cashResp.put("bookingId", booking.getId());
                cashResp.put("bookingCode", booking.getBookingCode());
                cashResp.put("amount", savedCash.getAmount());
                cashResp.put("baseFare", booking.getBaseFare());
                cashResp.put("platformFee", booking.getPlatformFee());
                cashResp.put("driverAmount", booking.getDriverAmount());
                cashResp.put("currency", savedCash.getCurrency());
                cashResp.put("status", "CONFIRMED");
                cashResp.put("paymentMethod", "CASH");
                return ResponseEntity.ok(cashResp);
            }
        } else {
            return ResponseEntity.badRequest().body(Map.of("message", "Either bookingId or rideId is required."));
        }

        // A provider failure must roll back any newly-created pending booking and
        // its seat lock; it must never turn into a synthetic order.
        Map<String, Object> gatewayOrder;
        try {
            gatewayOrder = razorpayService.createOrder(
                    booking.getTotalAmount(), booking.getBookingCode(), "INR");
        } catch (RazorpayService.PaymentConfigurationException | RazorpayService.PaymentProviderException ex) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "success", false,
                    "message", ex.getMessage(),
                    "status", "PAYMENT_UNAVAILABLE"
            ));
        }

        String gatewayOrderId = gatewayOrder.get("gatewayOrderId").toString();
        String internalOrderId = "ORD_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();

        PaymentOrder paymentOrder = PaymentOrder.builder()
                .orderId(internalOrderId)
                .gatewayOrderId(gatewayOrderId)
                .booking(booking)
                .user(user)
                .ride(ride)
                .amount(booking.getTotalAmount())
                .platformFee(booking.getPlatformFee())
                .driverAmount(booking.getDriverAmount())
                .currency("INR")
                .paymentMethod("ONLINE")
                .provider("RAZORPAY")
                .status("CREATED")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        PaymentOrder savedOrder = paymentOrderRepository.save(paymentOrder);

        Map<String, Object> responseMap = new HashMap<>();
        responseMap.put("orderId", savedOrder.getOrderId());
        responseMap.put("gatewayOrderId", savedOrder.getGatewayOrderId());
        responseMap.put("bookingId", booking.getId());
        responseMap.put("bookingCode", booking.getBookingCode());
        responseMap.put("amount", savedOrder.getAmount());
        responseMap.put("baseFare", booking.getBaseFare());
        responseMap.put("platformFee", booking.getPlatformFee());
        responseMap.put("driverAmount", booking.getDriverAmount());
        responseMap.put("currency", savedOrder.getCurrency());
        responseMap.put("status", savedOrder.getStatus());
        responseMap.put("expiresAt", booking.getExpiresAt() != null ? booking.getExpiresAt().toString() : "");
        responseMap.put("razorpayKeyId", razorpayService.getKeyId());

        return ResponseEntity.status(HttpStatus.CREATED).body(responseMap);
    }

    /**
     * Step 8: Verify Payment & Confirm Booking
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public ResponseEntity<?> verifyPayment(String token, VerifyPaymentRequest request) {
        String gatewayOrderId = request.getGatewayOrderId();
        String internalOrderId = request.getOrderId();
        String paymentId = request.getGatewayPaymentId();
        String signature = request.getGatewaySignature();

        if (paymentId == null || paymentId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Gateway payment ID is required."));
        }

        PaymentOrder order = null;
        if (gatewayOrderId != null && !gatewayOrderId.isBlank()) {
            order = paymentOrderRepository.findByGatewayOrderId(gatewayOrderId).orElse(null);
        }
        if (order == null && internalOrderId != null && !internalOrderId.isBlank()) {
            order = paymentOrderRepository.findByOrderId(internalOrderId).orElse(null);
        }

        if (order == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Payment order not found."));
        }

        // Idempotency: if already paid, return confirmed state without duplicate processing
        if ("PAID".equals(order.getStatus())) {
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Payment already verified successfully.",
                    "orderId", order.getOrderId(),
                    "bookingId", order.getBooking().getId(),
                    "bookingCode", order.getBooking().getBookingCode(),
                    "status", "PAID"
            ));
        }

        // Verify cryptographic signature
        boolean signatureValid = razorpayService.verifyPaymentSignature(
                order.getGatewayOrderId() != null ? order.getGatewayOrderId() : order.getOrderId(),
                paymentId,
                signature
        );

        if (!signatureValid) {
            order.setStatus("FAILED");
            order.setFailureReason("Invalid cryptographic gateway signature.");
            order.setUpdatedAt(LocalDateTime.now());
            paymentOrderRepository.save(order);

            Booking booking = order.getBooking();
            if (booking != null && !"PAID".equals(booking.getPaymentStatus())) {
                booking.setPaymentStatus("FAILED");
                booking.setUpdatedAt(LocalDateTime.now());
                bookingRepository.save(booking);
            }

            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Payment verification failed: Invalid cryptographic signature. You can retry payment.",
                    "status", "FAILED"
            ));
        }

        // Mark payment order as PAID
        order.setStatus("PAID");
        order.setGatewayPaymentId(paymentId);
        order.setPaidAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        paymentOrderRepository.save(order);

        // Transition Booking to CONFIRMED and PAID
        Booking booking = order.getBooking();
        booking.setStatus("CONFIRMED");
        booking.setPaymentStatus("PAID");
        booking.setTransactionReference(paymentId);
        booking.setUpdatedAt(LocalDateTime.now());
        Booking savedBooking = bookingRepository.save(booking);

        if (savedBooking.getCouponCode() != null && !savedBooking.getCouponCode().trim().isEmpty()) {
            couponService.redeemCouponAtomically(savedBooking.getCouponCode(), savedBooking, savedBooking.getPassenger());
        }

        // Record platform fee entry in immutable driver ledger
        if (booking.getRide() != null && booking.getRide().getDriver() != null) {
            String ledgerIdempotencyKey = "PAYMENT_CONFIRM_BOOKING_" + booking.getId();
            Optional<CommissionLedger> existingLedger = commissionLedgerRepository.findByIdempotencyKey(ledgerIdempotencyKey);
            if (existingLedger.isEmpty()) {
                double riderFee = booking.getRiderPlatformFee();
                double driverComm = booking.getDriverCommissionAmount();
                double totalPlatformRetention = Math.round((riderFee + driverComm) * 100.0) / 100.0;
                double driverNet = booking.getDriverNetEarnings() > 0 ? booking.getDriverNetEarnings() : booking.getDriverAmount();

                CommissionLedger ledger = CommissionLedger.builder()
                        .driver(booking.getRide().getDriver())
                        .booking(booking)
                        .ride(booking.getRide())
                        .entryType("PLATFORM_FEE")
                        .grossFare(booking.getTotalAmount())
                        .commissionRatePercent(booking.getDriverCommissionRateSnapshot() != null ? booking.getDriverCommissionRateSnapshot() : 0.0)
                        .fixedFee(riderFee)
                        .commissionAmount(totalPlatformRetention)
                        .driverNetAmount(driverNet)
                        .status("SETTLED")
                        .idempotencyKey(ledgerIdempotencyKey)
                        .settlementReference(paymentId)
                        .createdAt(LocalDateTime.now())
                        .settledAt(LocalDateTime.now())
                        .notes("Platform fee (₹" + riderFee + ") & commission (₹" + driverComm + ") retained on booking " + booking.getBookingCode())
                        .build();
                commissionLedgerRepository.save(ledger);
            }
        }

        // Dispatches notifications and emails
        notificationService.sendNotification(order.getUser(), "Payment Confirmed",
                "Payment of ₹" + order.getAmount() + " was confirmed for booking " + booking.getBookingCode());

        if (order.getUser() != null && order.getUser().getEmail() != null) {
            String driverName = (booking.getRide() != null && booking.getRide().getDriver() != null)
                    ? booking.getRide().getDriver().getName() : "Verified Driver";
            emailService.sendBookingConfirmedEmail(
                    order.getUser().getEmail(),
                    order.getUser().getName(),
                    booking.getId(),
                    booking.getPickupAddress(),
                    booking.getDropoffAddress(),
                    booking.getTotalAmount(),
                    driverName,
                    booking.getRide() != null ? booking.getRide().getDepartureTime() : null
            );
        }

        if (booking.getRide() != null && booking.getRide().getDriver() != null) {
            notificationService.sendNotification(booking.getRide().getDriver(), "New Passenger Booked",
                    order.getUser().getName() + " booked " + booking.getSeats() + " seat(s) for your ride.");

            if (booking.getRide().getDriver().getEmail() != null) {
                emailService.sendNewBookingAlertDriverEmail(
                        booking.getRide().getDriver().getEmail(),
                        booking.getRide().getDriver().getName(),
                        booking.getId(),
                        order.getUser().getName(),
                        booking.getPickupAddress(),
                        booking.getDropoffAddress(),
                        booking.getTotalAmount()
                );
            }
        }

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Payment verified and booking confirmed!",
                "orderId", order.getOrderId(),
                "bookingId", savedBooking.getId(),
                "bookingCode", savedBooking.getBookingCode(),
                "amount", order.getAmount(),
                "status", "PAID"
        ));
    }

    /**
     * Step 9: Secure Idempotent Webhook Handler
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public ResponseEntity<?> handleWebhook(String payload, String signatureHeader) {
        if (!razorpayService.verifyWebhookSignature(payload, signatureHeader)) {
            log.warn("Invalid webhook signature received.");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Invalid webhook signature"));
        }

        try {
            JsonNode root = objectMapper.readTree(payload);
            String event = root.path("event").asText("");
            log.info("Processing verified payment webhook event: {}", event);

            JsonNode payloadObj = root.path("payload");

            if ("payment.captured".equals(event) || "order.paid".equals(event)) {
                JsonNode paymentEntity = payloadObj.path("payment").path("entity");
                String gatewayOrderId = paymentEntity.path("order_id").asText(null);
                String gatewayPaymentId = paymentEntity.path("id").asText(null);

                if (gatewayOrderId != null && gatewayPaymentId != null) {
                    Optional<PaymentOrder> optOrder = paymentOrderRepository.findByGatewayOrderId(gatewayOrderId);
                    if (optOrder.isPresent()) {
                        PaymentOrder order = optOrder.get();
                        if (!"PAID".equals(order.getStatus())) {
                            order.setStatus("PAID");
                            order.setGatewayPaymentId(gatewayPaymentId);
                            order.setPaidAt(LocalDateTime.now());
                            paymentOrderRepository.save(order);

                            Booking booking = order.getBooking();
                            if (booking != null) {
                                booking.setStatus("CONFIRMED");
                                booking.setPaymentStatus("PAID");
                                booking.setTransactionReference(gatewayPaymentId);
                                booking.setUpdatedAt(LocalDateTime.now());
                                bookingRepository.save(booking);
                            }
                        }
                    }
                }
            } else if ("payment.failed".equals(event)) {
                JsonNode paymentEntity = payloadObj.path("payment").path("entity");
                String gatewayOrderId = paymentEntity.path("order_id").asText(null);
                String reason = paymentEntity.path("error_description").asText("Payment failed at gateway.");

                if (gatewayOrderId != null) {
                    Optional<PaymentOrder> optOrder = paymentOrderRepository.findByGatewayOrderId(gatewayOrderId);
                    if (optOrder.isPresent()) {
                        PaymentOrder order = optOrder.get();
                        if (!"PAID".equals(order.getStatus())) {
                            order.setStatus("FAILED");
                            order.setFailureReason(reason);
                            paymentOrderRepository.save(order);

                            Booking booking = order.getBooking();
                            if (booking != null && !"PAID".equals(booking.getPaymentStatus())) {
                                booking.setPaymentStatus("FAILED");
                                bookingRepository.save(booking);
                            }
                        }
                    }
                }
            } else if ("refund.processed".equals(event)) {
                JsonNode refundEntity = payloadObj.path("refund").path("entity");
                String providerRefundId = refundEntity.path("id").asText(null);
                if (providerRefundId != null) {
                    Optional<PaymentRefund> optRefund = paymentRefundRepository.findByProviderRefundId(providerRefundId);
                    if (optRefund.isPresent()) {
                        PaymentRefund refund = optRefund.get();
                        String newStatus = refund.getCancellationFee() != null && refund.getCancellationFee() > 0
                                ? "PARTIALLY_REFUNDED" : "REFUNDED";
                        if (!newStatus.equals(refund.getStatus())) {
                            refund.setStatus(newStatus);
                            refund.setProcessedAt(LocalDateTime.now());
                            paymentRefundRepository.save(refund);
                            Booking booking = refund.getBooking();
                            if (booking != null) {
                                booking.setPaymentStatus(refund.getStatus());
                                booking.setUpdatedAt(LocalDateTime.now());
                                bookingRepository.save(booking);

                                // Record ledger reversal idempotently on webhook confirmation
                                if (booking.getRide() != null && booking.getRide().getDriver() != null) {
                                    String ledgerKey = "LEDGER_REFUND_" + refund.getRefundId();
                                    if (commissionLedgerRepository.findByIdempotencyKey(ledgerKey).isEmpty()) {
                                        double reversedCommission = Math.round((booking.getRiderPlatformFee() + booking.getDriverCommissionAmount()) * 100.0) / 100.0;
                                        double driverNet = booking.getDriverNetEarnings() > 0 ? booking.getDriverNetEarnings() : booking.getDriverAmount();
                                        CommissionLedger refundLedger = CommissionLedger.builder()
                                                .driver(booking.getRide().getDriver())
                                                .booking(booking)
                                                .ride(booking.getRide())
                                                .entryType("REFUND")
                                                .grossFare(refund.getAmount())
                                                .commissionRatePercent(0.0)
                                                .fixedFee(0.0)
                                                .commissionAmount(reversedCommission)
                                                .driverNetAmount(driverNet)
                                                .status("REFUNDED")
                                                .idempotencyKey(ledgerKey)
                                                .settlementReference(providerRefundId)
                                                .createdAt(LocalDateTime.now())
                                                .settledAt(LocalDateTime.now())
                                                .notes("Reconciled refund #" + refund.getRefundId() + " via verified test webhook")
                                                .build();
                                        commissionLedgerRepository.save(refundLedger);
                                    }
                                }
                            }
                        }
                    }
                }
            } else if ("payout.processed".equals(event)) {
                JsonNode payoutEntity = payloadObj.path("payout").path("entity");
                String providerPayoutId = payoutEntity.path("id").asText(null);
                if (providerPayoutId != null) {
                    Optional<DriverPayout> optPayout = driverPayoutRepository.findByProviderPayoutId(providerPayoutId);
                    if (optPayout.isPresent()) {
                        DriverPayout payout = optPayout.get();
                        payout.setStatus("PAID");
                        payout.setProcessedAt(LocalDateTime.now());
                        driverPayoutRepository.save(payout);
                    }
                }
            } else if ("payout.failed".equals(event)) {
                JsonNode payoutEntity = payloadObj.path("payout").path("entity");
                String providerPayoutId = payoutEntity.path("id").asText(null);
                String reason = payoutEntity.path("failure_reason").asText("Payout failed at gateway.");
                if (providerPayoutId != null) {
                    Optional<DriverPayout> optPayout = driverPayoutRepository.findByProviderPayoutId(providerPayoutId);
                    if (optPayout.isPresent()) {
                        DriverPayout payout = optPayout.get();
                        payout.setStatus("FAILED");
                        payout.setFailureReason(reason);
                        driverPayoutRepository.save(payout);
                    }
                }
            }

            return ResponseEntity.ok(Map.of("status", "WEBHOOK_PROCESSED", "event", event));
        } catch (Exception e) {
            log.error("Webhook processing error: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", "Error processing webhook"));
        }
    }

    /**
     * Step 15: Process Booking Cancellation & Refund
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public ResponseEntity<?> processRefund(User initiator, Long bookingId, String reason) {
        return processRefund(initiator, bookingId, reason, null, null, null);
    }

    /**
     * Step 15: Process Booking Cancellation & Refund with Configurable Cancellation Fee
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public ResponseEntity<?> processRefund(User initiator, Long bookingId, String reason,
                                           Double explicitRefundAmount, Double explicitCancellationFee, String ruleApplied) {
        Booking booking = bookingRepository.findById(bookingId).orElse(null);
        if (booking == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Booking not found."));
        }

        boolean isAdmin = initiator != null && "ADMIN".equals(initiator.getRole());
        boolean isPassenger = initiator != null && booking.getPassenger().getId().equals(initiator.getId());
        boolean isDriver = booking.getRide() != null && booking.getRide().getDriver() != null &&
                initiator != null && booking.getRide().getDriver().getId().equals(initiator.getId());

        if (!isAdmin && !isPassenger && !isDriver) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Not authorized to refund this booking."));
        }

        if ("REFUNDED".equals(booking.getPaymentStatus())) {
            return ResponseEntity.badRequest().body(Map.of("message", "Booking is already refunded."));
        }

        // Calculate authoritative cancellation fee & net refund amount
        double cancellationFee = 0.0;
        double refundAmount = booking.getTotalAmount();
        String appliedRule = ruleApplied;

        if (explicitRefundAmount != null && explicitCancellationFee != null) {
            refundAmount = Math.max(0.0, explicitRefundAmount);
            cancellationFee = Math.max(0.0, explicitCancellationFee);
        } else if (cancellationFeeCalculationService != null) {
            CancellationFeeCalculationService.CancellationQuote quote =
                    cancellationFeeCalculationService.calculateQuote(booking, initiator);
            refundAmount = quote.getRefundAmount();
            cancellationFee = quote.getCancellationFee();
            if (appliedRule == null) {
                appliedRule = quote.getRuleApplied();
            }
        }

        // If not paid online, cancel booking directly without financial gateway refund
        if (!"PAID".equals(booking.getPaymentStatus()) || !"ONLINE".equalsIgnoreCase(booking.getPaymentMethod())) {
            booking.setStatus("CANCELLED");
            booking.setCancellationFee(0.0);
            booking.setCancellationRefundAmount(0.0);
            booking.setCancellationRuleApplied(appliedRule != null ? appliedRule : "CASH_OR_UNPAID_CANCEL");
            booking.setCancelledBy(isDriver ? "DRIVER" : (isAdmin ? "ADMIN" : "PASSENGER"));
            booking.setCancelledAt(LocalDateTime.now());
            booking.setCancellationReason(reason);
            booking.setUpdatedAt(LocalDateTime.now());
            bookingRepository.save(booking);

            // Restore seats
            if (booking.getRide() != null && !"COMPLETED".equals(booking.getRide().getStatus())) {
                Ride ride = rideRepository.findByIdForUpdate(booking.getRide().getId()).orElse(null);
                if (ride != null) {
                    ride.setAvailableSeats(ride.getAvailableSeats() + booking.getSeats());
                    if ("FULL".equals(ride.getStatus())) ride.setStatus("PUBLISHED");
                    rideRepository.save(ride);
                }
            }

            if (redisCacheService != null && booking.getRide() != null) {
                redisCacheService.evictRideCaches(booking.getRide().getId());
            }

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Booking cancelled. No online payment was charged.",
                    "status", "CANCELLED",
                    "cancellationFee", 0.0,
                    "refundAmount", 0.0
            ));
        }

        // Online refund calculation
        String refundId = "RFD_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        String idempotencyKey = "REFUND_BOOKING_" + booking.getId();

        // Check if refund was already recorded
        Optional<PaymentRefund> existingRefund = paymentRefundRepository.findByIdempotencyKey(idempotencyKey);
        if (existingRefund.isPresent()) {
            return ResponseEntity.ok(existingRefund.get());
        }

        PaymentOrder paymentOrder = paymentOrderRepository.findByBookingId(booking.getId()).stream()
                .filter(p -> "PAID".equals(p.getStatus()))
                .findFirst()
                .orElse(null);

        String paymentRef = booking.getTransactionReference();
        if (paymentRef == null && paymentOrder != null) {
            paymentRef = paymentOrder.getGatewayPaymentId();
        }

        String providerRefundId = null;
        if (refundAmount > 0) {
            if (paymentRef == null || paymentRef.isBlank()) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                        "message", "A captured gateway payment reference is required before a refund can be requested."
                ));
            }
            // Call payment provider refund API
            Map<String, Object> gatewayRefund = razorpayService.createRefund(paymentRef, refundAmount, reason);
            providerRefundId = gatewayRefund.get("refundId") != null ? gatewayRefund.get("refundId").toString() : null;
            if (providerRefundId == null || providerRefundId.isBlank()) {
                throw new RazorpayService.PaymentProviderException("Razorpay test refund response did not include a refund ID.");
            }
        }

        PaymentRefund refund = PaymentRefund.builder()
                .refundId(refundId)
                .booking(booking)
                .paymentOrder(paymentOrder)
                .passenger(booking.getPassenger())
                .amount(refundAmount)
                .cancellationFee(cancellationFee)
                .currency("INR")
                .reason(reason != null ? reason : "Passenger cancellation before trip")
                .status(refundAmount > 0 ? "REFUND_PROCESSING" : "NO_REFUND_DUE")
                .provider("RAZORPAY")
                .providerRefundId(providerRefundId)
                .idempotencyKey(idempotencyKey)
                .createdAt(LocalDateTime.now())
                .processedAt(refundAmount > 0 ? null : LocalDateTime.now())
                .build();

        PaymentRefund savedRefund = paymentRefundRepository.save(refund);

        // Update booking state with cancellation fee & refund tracking
        booking.setStatus("CANCELLED");
        booking.setPaymentStatus(refundAmount > 0 ? "REFUND_IN_PROGRESS" : "NO_REFUND_DUE");
        booking.setCancellationFee(cancellationFee);
        booking.setCancellationRefundAmount(refundAmount);
        booking.setCancellationRuleApplied(appliedRule != null ? appliedRule : "TIME_WINDOW_POLICY");
        booking.setCancelledBy(isDriver ? "DRIVER" : (isAdmin ? "ADMIN" : "PASSENGER"));
        booking.setCancelledAt(LocalDateTime.now());
        booking.setCancellationReason(reason);
        booking.setUpdatedAt(LocalDateTime.now());
        bookingRepository.save(booking);
        couponService.restoreCouponOnCancellation(booking);

        if (redisCacheService != null && booking.getRide() != null) {
            redisCacheService.evictRideCaches(booking.getRide().getId());
        }

        // Restore seats
        if (booking.getRide() != null && !"COMPLETED".equals(booking.getRide().getStatus())) {
            Ride ride = rideRepository.findByIdForUpdate(booking.getRide().getId()).orElse(null);
            if (ride != null) {
                ride.setAvailableSeats(ride.getAvailableSeats() + booking.getSeats());
                if ("FULL".equals(ride.getStatus())) ride.setStatus("PUBLISHED");
                rideRepository.save(ride);
            }
        }

        // The financial reversal is recorded only after a verified
        // refund.processed webhook, not when the provider request is accepted.
        if (refundAmount <= 0) {
            if (booking.getRide() != null && booking.getRide().getDriver() != null) {
                double reversedCommission = Math.round((booking.getRiderPlatformFee() + booking.getDriverCommissionAmount()) * 100.0) / 100.0;
                double driverNet = booking.getDriverNetEarnings() > 0 ? booking.getDriverNetEarnings() : booking.getDriverAmount();

                CommissionLedger refundLedger = CommissionLedger.builder()
                        .driver(booking.getRide().getDriver())
                        .booking(booking)
                        .ride(booking.getRide())
                        .entryType("REFUND")
                        .grossFare(refundAmount)
                        .commissionRatePercent(0.0)
                        .fixedFee(0.0)
                        .commissionAmount(reversedCommission)
                        .driverNetAmount(driverNet)
                        .status("REFUNDED")
                        .idempotencyKey("LEDGER_" + idempotencyKey)
                        .settlementReference(providerRefundId)
                        .createdAt(LocalDateTime.now())
                        .settledAt(LocalDateTime.now())
                        .notes("Refund #" + refundId + " for booking " + booking.getBookingCode())
                        .build();
                commissionLedgerRepository.save(refundLedger);
            }
        }

        // Notifications
        notificationService.sendNotification(booking.getPassenger(), "Refund Processed",
                "Your refund of ₹" + refundAmount + " has been processed. Ref: " + providerRefundId);

        if (booking.getPassenger().getEmail() != null) {
            emailService.sendPlainTextEmail(
                    booking.getPassenger().getEmail(),
                    "RideShare Refund Processed: ₹" + refundAmount,
                    "Hello " + booking.getPassenger().getName() + ",\n\nYour refund has been processed successfully.\n\nRefund Amount: ₹" +
                    refundAmount + "\nRefund Reference: " + refundId + "\nGateway Reference: " + providerRefundId +
                    "\nBooking Code: " + booking.getBookingCode() + "\nDate: " + LocalDateTime.now() + "\n\nFunds will reflect in your original payment method in 3-5 business days.",
                    "REFUND"
            );
        }

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Refund processed successfully.",
                "refundId", savedRefund.getRefundId(),
                "amount", savedRefund.getAmount(),
                "status", savedRefund.getStatus(),
                "providerRefundId", providerRefundId
        ));
    }

    /**
     * Automatic release of expired temporary seat locks
     */
    @Transactional
    public void releaseExpiredSeatLocks() {
        List<Booking> expired = bookingRepository.findExpiredTemporaryBookings(LocalDateTime.now());
        for (Booking b : expired) {
            b.setStatus("EXPIRED");
            b.setPaymentStatus("EXPIRED");
            b.setUpdatedAt(LocalDateTime.now());
            bookingRepository.save(b);
            couponService.restoreCouponOnCancellation(b);

            if (b.getRide() != null && !"COMPLETED".equals(b.getRide().getStatus()) && !"CANCELLED".equals(b.getRide().getStatus())) {
                Ride ride = rideRepository.findByIdForUpdate(b.getRide().getId()).orElse(null);
                if (ride != null) {
                    ride.setAvailableSeats(ride.getAvailableSeats() + b.getSeats());
                    if ("FULL".equals(ride.getStatus())) {
                        ride.setStatus("PUBLISHED");
                    }
                    rideRepository.save(ride);
                }
            }

            List<PaymentOrder> orders = paymentOrderRepository.findByBookingId(b.getId());
            for (PaymentOrder o : orders) {
                if ("CREATED".equals(o.getStatus())) {
                    o.setStatus("EXPIRED");
                    o.setUpdatedAt(LocalDateTime.now());
                    paymentOrderRepository.save(o);
                }
            }
        }
    }

    /**
     * Payment Methods Configuration for checkout
     */
    public ResponseEntity<?> getPaymentMethodsConfig() {
        return ResponseEntity.ok(Map.of(
                "CARD", Map.of("enabled", true, "name", "Credit / Debit Card", "description", "Visa, Mastercard, RuPay with secure 3D-Secure authentication"),
                "UPI", Map.of("enabled", true, "name", "UPI / Google Pay / PhonePe", "description", "Instant VPA bank transfer"),
                "WALLET", Map.of("enabled", true, "name", "RideShare Wallet", "description", "1-click instant debit from in-app wallet balance"),
                "CASH", Map.of("enabled", true, "name", "Cash to Driver", "description", "Pay physical cash directly to driver after ride completion")
        ));
    }

    /**
     * Get verified receipt for a booking
     */
    public ResponseEntity<?> getPaymentReceipt(String token, Long bookingId) {
        String email = jwtUtil.extractEmail(token.substring(7));
        User user = userRepository.findByEmail(email);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "User not found or unauthorized."));
        }

        Booking booking = bookingRepository.findById(bookingId).orElse(null);
        if (booking == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Booking not found."));
        }

        boolean isPassenger = booking.getPassenger().getId().equals(user.getId());
        boolean isDriver = booking.getRide() != null && booking.getRide().getDriver() != null && booking.getRide().getDriver().getId().equals(user.getId());
        boolean isAdmin = "ADMIN".equals(user.getRole());

        if (!isPassenger && !isDriver && !isAdmin) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Not authorized to view this receipt."));
        }

        Map<String, Object> receipt = new HashMap<>();
        receipt.put("bookingId", booking.getId());
        receipt.put("bookingCode", booking.getBookingCode());
        receipt.put("rideId", booking.getRide() != null ? booking.getRide().getId() : null);
        receipt.put("passengerName", booking.getPassenger().getName());
        receipt.put("driverName", booking.getRide() != null && booking.getRide().getDriver() != null ? booking.getRide().getDriver().getName() : "Verified Driver");
        receipt.put("seats", booking.getSeats());
        receipt.put("pickup", booking.getPickupAddress());
        receipt.put("dropoff", booking.getDropoffAddress());
        receipt.put("baseRideContribution", booking.getBaseFare() > 0 ? booking.getBaseFare() : (booking.getTotalAmount() - booking.getPlatformFee()));
        receipt.put("platformFee", booking.getPlatformFee());
        receipt.put("totalAmount", booking.getTotalAmount());
        receipt.put("driverAmount", booking.getDriverAmount());
        receipt.put("paymentMethod", booking.getPaymentMethod());
        receipt.put("paymentStatus", booking.getPaymentStatus());
        receipt.put("transactionReference", booking.getTransactionReference());

        // GST & Invoice Details
        receipt.put("invoiceNumber", booking.getInvoiceNumber());
        receipt.put("taxableAmount", booking.getTaxableAmount() != null && booking.getTaxableAmount() > 0 ? booking.getTaxableAmount() : (booking.getTotalAmount() - booking.getTax()));
        receipt.put("tax", booking.getTax());
        receipt.put("gstRate", booking.getGstRateSnapshot() != null ? booking.getGstRateSnapshot() : 5.0);
        receipt.put("cgstAmount", booking.getCgstAmount() != null ? booking.getCgstAmount() : 0.0);
        receipt.put("sgstAmount", booking.getSgstAmount() != null ? booking.getSgstAmount() : 0.0);
        receipt.put("igstAmount", booking.getIgstAmount() != null ? booking.getIgstAmount() : 0.0);
        receipt.put("isInterstate", Boolean.TRUE.equals(booking.getIsInterstate()));
        receipt.put("placeOfSupply", booking.getPlaceOfSupply());
        receipt.put("pricesIncludeGst", booking.getPricesIncludeGstSnapshot() != null ? booking.getPricesIncludeGstSnapshot() : true);

        receipt.put("bookedAt", booking.getBookedAt());
        receipt.put("updatedAt", booking.getUpdatedAt());

        return ResponseEntity.ok(Map.of("success", true, "receipt", receipt));
    }

    /**
     * Get passenger payment history
     */
    public ResponseEntity<?> getPaymentHistory(String token) {
        String email = jwtUtil.extractEmail(token.substring(7));
        User user = userRepository.findByEmail(email);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "User not found"));
        }

        List<Booking> bookings = bookingRepository.findByPassengerIdOrderByBookedAtDesc(user.getId());
        List<PaymentRefund> refunds = paymentRefundRepository.findByPassengerIdOrderByCreatedAtDesc(user.getId());
        List<Map<String, Object>> history = new ArrayList<>();

        for (Booking b : bookings) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", b.getId());
            item.put("bookingId", b.getId());
            item.put("bookingCode", b.getBookingCode());
            item.put("orderId", b.getBookingCode() != null ? b.getBookingCode() : "BK-" + b.getId());
            item.put("rideId", b.getRide() != null ? b.getRide().getId() : null);
            item.put("pickup", b.getPickupAddress());
            item.put("dropoff", b.getDropoffAddress());
            item.put("amount", b.getTotalAmount());
            item.put("totalAmount", b.getTotalAmount());
            item.put("baseFare", b.getBaseFare());
            item.put("platformFee", b.getPlatformFee());
            item.put("invoiceNumber", b.getInvoiceNumber());
            item.put("tax", b.getTax());
            item.put("taxableAmount", b.getTaxableAmount() != null && b.getTaxableAmount() > 0 ? b.getTaxableAmount() : (b.getTotalAmount() - b.getTax()));
            item.put("paymentMethod", b.getPaymentMethod() != null ? b.getPaymentMethod() : "ONLINE");

            // Look up associated refund if any
            Optional<PaymentRefund> optRefund = refunds.stream()
                    .filter(r -> r.getBooking() != null && r.getBooking().getId().equals(b.getId()))
                    .findFirst();

            if (optRefund.isPresent()) {
                PaymentRefund r = optRefund.get();
                item.put("refundStatus", r.getStatus()); // REFUNDED, REFUND_IN_PROGRESS, etc.
                item.put("refundAmount", r.getAmount());
                item.put("refundReason", r.getReason());
                item.put("refundId", r.getRefundId());
                item.put("status", r.getStatus());
                item.put("paymentStatus", r.getStatus());
            } else if ("REFUNDED".equalsIgnoreCase(b.getPaymentStatus()) || "REFUND_IN_PROGRESS".equalsIgnoreCase(b.getPaymentStatus())) {
                item.put("refundStatus", b.getPaymentStatus());
                item.put("refundAmount", b.getTotalAmount());
                item.put("status", b.getPaymentStatus());
                item.put("paymentStatus", b.getPaymentStatus());
            } else if ("CANCELLED".equalsIgnoreCase(b.getStatus())) {
                item.put("status", "CANCELLED");
                item.put("paymentStatus", "CANCELLED");
            } else if ("PAID".equalsIgnoreCase(b.getPaymentStatus())) {
                item.put("status", "PAID");
                item.put("paymentStatus", "PAID");
            } else if ("FAILED".equalsIgnoreCase(b.getPaymentStatus())) {
                item.put("status", "FAILED");
                item.put("paymentStatus", "FAILED");
            } else {
                item.put("status", b.getPaymentStatus() != null ? b.getPaymentStatus() : "PENDING");
                item.put("paymentStatus", b.getPaymentStatus() != null ? b.getPaymentStatus() : "PENDING");
            }

            item.put("bookingStatus", b.getStatus());
            item.put("transactionReference", b.getTransactionReference());
            item.put("gatewayPaymentId", b.getTransactionReference());
            item.put("bookedAt", b.getBookedAt());
            item.put("createdAt", b.getBookedAt() != null ? b.getBookedAt() : LocalDateTime.now());
            history.add(item);
        }

        return ResponseEntity.ok(history);
    }
}
