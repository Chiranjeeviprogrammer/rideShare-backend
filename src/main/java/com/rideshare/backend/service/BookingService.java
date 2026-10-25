package com.rideshare.backend.service;

import com.rideshare.backend.dto.CouponValidationResponse;
import com.rideshare.backend.dto.CreateBookingRequest;
import com.rideshare.backend.model.Booking;
import java.math.BigDecimal;

import com.rideshare.backend.model.Ride;
import com.rideshare.backend.model.User;
import com.rideshare.backend.repository.BookingRepository;
import com.rideshare.backend.repository.RideRepository;
import com.rideshare.backend.repository.UserRepository;
import com.rideshare.backend.security.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class BookingService {

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private RideRepository rideRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private EmailService emailService;

    @Autowired
    @org.springframework.context.annotation.Lazy
    private PaymentService paymentService;

    @Autowired
    private FeeCalculationService feeCalculationService;

    @Autowired
    private CancellationFeeCalculationService cancellationFeeCalculationService;

    @Autowired(required = false)
    private RedisCacheService redisCacheService;

    @Autowired
    private CouponService couponService;

    @Autowired
    private InvoiceService invoiceService;

    @Transactional
    public ResponseEntity<?> createBooking(String token, CreateBookingRequest request) {
        String email = jwtUtil.extractEmail(token.substring(7));
        User passenger = userRepository.findByEmail(email);
        if (passenger == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "User not found"));
        }

        if (request.getRideId() == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "Ride ID is required"));
        }

        // Use pessimistic write lock to prevent race conditions & overbooking
        Ride ride = rideRepository.findByIdForUpdate(request.getRideId()).orElse(null);
        if (ride == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Ride not found"));
        }

        // Check if ride is cancelled, completed, in progress, expired
        if ("COMPLETED".equals(ride.getStatus()) || "CANCELLED".equals(ride.getStatus()) || 
            "IN_PROGRESS".equals(ride.getStatus()) || "EXPIRED".equals(ride.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Cannot book a ride that is " + ride.getStatus().toLowerCase() + "."
            ));
        }

        if ("FULL".equals(ride.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("message", "This ride is already full. No seats available."));
        }

        // Driver cannot book their own ride
        if (ride.getDriver() != null && ride.getDriver().getId().equals(passenger.getId())) {
            return ResponseEntity.badRequest().body(Map.of("message", "Drivers cannot book seats on their own published ride."));
        }

        // Check existing active booking for this passenger on this ride
        List<Booking> existing = bookingRepository.findByRideIdAndPassengerId(ride.getId(), passenger.getId());
        boolean hasActiveBooking = existing.stream()
                .anyMatch(b -> "CONFIRMED".equals(b.getStatus()) || "PENDING".equals(b.getStatus()));
        if (hasActiveBooking) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("message", "You already have an active booking for this ride."));
        }

        int requestedSeats = request.getSeats() > 0 ? request.getSeats() : 1;
        int currentAvailable = ride.getAvailableSeats() != null ? ride.getAvailableSeats() : 3;

        // Requirement 12, 13: Prevent overbooking & check seat count
        if (currentAvailable < requestedSeats) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Insufficient seats available. Only " + currentAvailable + " seat(s) remaining.",
                    "availableSeats", currentAvailable
            ));
        }

        // Requirement 14: Atomically update available seats
        int remainingSeats = currentAvailable - requestedSeats;
        ride.setAvailableSeats(remainingSeats);
        if (remainingSeats == 0) {
            ride.setStatus("FULL");
        }
        rideRepository.save(ride);

        CouponValidationResponse couponVal = null;
        FeeCalculationService.FeeCalculationResult quoteResult;

        if (request.getCouponCode() != null && !request.getCouponCode().trim().isEmpty()) {
            couponVal = couponService.validateCoupon(request.getCouponCode(), ride.getId(), requestedSeats, passenger);
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

        String method = (request.getPaymentMethod() != null && !request.getPaymentMethod().trim().isEmpty())
                ? request.getPaymentMethod().toUpperCase()
                : "ONLINE";

        String initialPaymentStatus;
        if ("CASH".equals(method)) {
            initialPaymentStatus = "PENDING_CASH";
        } else if ("PAY_AFTER_RIDE".equals(method)) {
            initialPaymentStatus = "UNPAID";
        } else {
            initialPaymentStatus = "PENDING";
        }

        // Automatic direct confirmation (NO driver accept/reject step!)
        Booking booking = Booking.builder()
                .bookingCode(Booking.generateBookingCode())
                .ride(ride)
                .passenger(passenger)
                .seats(requestedSeats)
                .status("CONFIRMED")
                .paymentMethod(method)
                .paymentStatus(initialPaymentStatus)
                .pickupAddress(request.getPickupAddress() != null ? request.getPickupAddress() : ride.getPickupLocation())
                .dropoffAddress(request.getDropoffAddress() != null ? request.getDropoffAddress() : ride.getDropoffLocation())
                .bookedAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        // Freeze immutable fee & commission pricing snapshot
        feeCalculationService.applyPricingSnapshot(booking, quoteResult);

        Booking savedBooking = bookingRepository.save(booking);

        // Generate Tax Invoice with immutable GST snapshot
        try {
            invoiceService.createInvoiceForBooking(savedBooking);
        } catch (Exception e) {
            System.err.println("Warning: Failed to generate invoice for booking #" + savedBooking.getId() + ": " + e.getMessage());
        }

        if (couponVal != null && couponVal.isValid()) {
            couponService.redeemCouponAtomically(couponVal.getCode(), savedBooking, passenger);
        }

        // Notify Driver and Passenger
        notificationService.sendNotification(passenger, "Booking Confirmed",
                "Your booking for " + requestedSeats + " seat(s) on ride #" + ride.getId() + " is confirmed!");
        if (passenger.getEmail() != null) {
            String driverName = ride.getDriver() != null ? ride.getDriver().getName() : "Verified Driver";
            emailService.sendBookingConfirmedEmail(
                    passenger.getEmail(),
                    passenger.getName(),
                    savedBooking.getId(),
                    savedBooking.getPickupAddress(),
                    savedBooking.getDropoffAddress(),
                    savedBooking.getTotalAmount(),
                    driverName,
                    ride.getDepartureTime()
            );
        }

        if (ride.getDriver() != null) {
            notificationService.sendNotification(ride.getDriver(), "New Passenger Booked",
                    passenger.getName() + " booked " + requestedSeats + " seat(s) for your ride.");
            if (ride.getDriver().getEmail() != null) {
                emailService.sendNewBookingAlertDriverEmail(
                        ride.getDriver().getEmail(),
                        ride.getDriver().getName(),
                        savedBooking.getId(),
                        passenger.getName(),
                        savedBooking.getPickupAddress(),
                        savedBooking.getDropoffAddress(),
                        savedBooking.getTotalAmount()
                );
            }
        }

        if (redisCacheService != null && ride != null) {
            redisCacheService.evictRideCaches(ride.getId());
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(savedBooking);
    }

    public List<Booking> getMyBookings(String token) {
        String email = jwtUtil.extractEmail(token.substring(7));
        User passenger = userRepository.findByEmail(email);
        if (passenger == null) return List.of();
        return bookingRepository.findByPassengerIdOrderByBookedAtDesc(passenger.getId());
    }

    public ResponseEntity<?> getBookingsForRide(String token, Long rideId) {
        String email = jwtUtil.extractEmail(token.substring(7));
        User user = userRepository.findByEmail(email);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "User not found"));
        }

        Ride ride = rideRepository.findById(rideId).orElse(null);
        if (ride == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Ride not found"));
        }

        // Only driver or admin can view all passenger bookings
        boolean isDriver = ride.getDriver() != null && ride.getDriver().getId().equals(user.getId());
        if (!isDriver) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Only the ride driver can view the passenger roster."));
        }

        List<Booking> bookings = bookingRepository.findByRideId(rideId);
        return ResponseEntity.ok(bookings);
    }

    /**
     * Get authoritative server-side pre-cancellation quote for passenger before confirming cancellation.
     */
    public ResponseEntity<?> getCancellationQuote(String token, Long bookingId) {
        String email = jwtUtil.extractEmail(token.substring(7));
        User user = userRepository.findByEmail(email);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "User not found"));
        }

        Booking booking = bookingRepository.findById(bookingId).orElse(null);
        if (booking == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Booking not found"));
        }

        boolean isPassenger = booking.getPassenger().getId().equals(user.getId());
        boolean isDriver = booking.getRide().getDriver() != null && booking.getRide().getDriver().getId().equals(user.getId());
        boolean isAdmin = "ADMIN".equalsIgnoreCase(user.getRole());

        if (!isPassenger && !isDriver && !isAdmin) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Not authorized to view cancellation terms for this booking."));
        }

        CancellationFeeCalculationService.CancellationQuote quote = cancellationFeeCalculationService.calculateQuote(booking, user);
        return ResponseEntity.ok(quote.toMap());
    }

    @Transactional
    public ResponseEntity<?> cancelBooking(String token, Long bookingId) {
        return cancelBooking(token, bookingId, "Cancelled by user");
    }

    @Transactional
    public ResponseEntity<?> cancelBooking(String token, Long bookingId, String reason) {
        String email = jwtUtil.extractEmail(token.substring(7));
        User user = userRepository.findByEmail(email);

        Booking booking = bookingRepository.findById(bookingId).orElse(null);
        if (booking == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Booking not found"));
        }

        // Only the passenger, driver, or admin can cancel
        boolean isPassenger = booking.getPassenger().getId().equals(user.getId());
        boolean isDriver = booking.getRide().getDriver() != null && booking.getRide().getDriver().getId().equals(user.getId());
        boolean isAdmin = user != null && "ADMIN".equalsIgnoreCase(user.getRole());

        if (!isPassenger && !isDriver && !isAdmin) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Not authorized to cancel this booking."));
        }

        if ("CANCELLED".equals(booking.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("message", "Booking is already cancelled."));
        }

        if ("COMPLETED".equals(booking.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("message", "Cannot cancel a completed booking."));
        }

        CancellationFeeCalculationService.CancellationQuote quote = cancellationFeeCalculationService.calculateQuote(booking, user);

        boolean wasConfirmed = "CONFIRMED".equals(booking.getStatus());
        boolean wasPaidOnline = "PAID".equals(booking.getPaymentStatus()) && "ONLINE".equalsIgnoreCase(booking.getPaymentMethod());

        if (wasPaidOnline) {
            paymentService.processRefund(user, booking.getId(), reason, quote.getRefundAmount(), quote.getCancellationFee(), quote.getRuleApplied());
            Booking updatedBooking = bookingRepository.findById(bookingId).orElse(booking);
            if (redisCacheService != null && booking.getRide() != null) {
                redisCacheService.evictRideCaches(booking.getRide().getId());
            }
            return ResponseEntity.ok(updatedBooking);
        }

        booking.setStatus("CANCELLED");
        booking.setCancellationFee(0.0);
        booking.setCancellationRefundAmount(0.0);
        booking.setCancellationRuleApplied(quote.getRuleApplied());
        booking.setCancelledBy(isDriver ? "DRIVER" : (isAdmin ? "ADMIN" : "PASSENGER"));
        booking.setCancellationReason(reason);
        booking.setCancelledAt(LocalDateTime.now());
        booking.setUpdatedAt(LocalDateTime.now());
        Booking savedBooking = bookingRepository.save(booking);
        couponService.restoreCouponOnCancellation(booking);

        // Atomically restore seat count upon cancellation
        if (wasConfirmed && booking.getRide() != null) {
            Ride ride = rideRepository.findByIdForUpdate(booking.getRide().getId()).orElse(null);
            if (ride != null && !"COMPLETED".equals(ride.getStatus()) && !"CANCELLED".equals(ride.getStatus())) {
                int currentAvailable = ride.getAvailableSeats() != null ? ride.getAvailableSeats() : 0;
                ride.setAvailableSeats(currentAvailable + booking.getSeats());
                if ("FULL".equals(ride.getStatus())) {
                    ride.setStatus("PUBLISHED");
                }
                rideRepository.save(ride);
            }
        }

        if (redisCacheService != null && booking.getRide() != null) {
            redisCacheService.evictRideCaches(booking.getRide().getId());
        }

        notificationService.sendNotification(booking.getPassenger(), "Booking Cancelled",
                "Booking #" + booking.getId() + " has been cancelled. " + quote.getPolicyNotice());
        if (booking.getPassenger() != null && booking.getPassenger().getEmail() != null) {
            emailService.sendBookingCancelledEmail(
                    booking.getPassenger().getEmail(),
                    booking.getPassenger().getName(),
                    booking.getId(),
                    reason != null ? reason : "Cancelled by user"
            );
        }

        return ResponseEntity.ok(savedBooking);
    }

    @Autowired
    private CommissionService commissionService;

    @Transactional
    public ResponseEntity<?> confirmCashPayment(String token, Long bookingId) {
        String email = jwtUtil.extractEmail(token.substring(7));
        User driver = userRepository.findByEmail(email);
        if (driver == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "User not found"));
        }

        Booking booking = bookingRepository.findById(bookingId).orElse(null);
        if (booking == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Booking not found"));
        }

        boolean isDriver = booking.getRide() != null && booking.getRide().getDriver() != null &&
                booking.getRide().getDriver().getId().equals(driver.getId());
        if (!isDriver) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Only the ride driver can confirm cash collection."));
        }

        // Driver must NOT mark online payments as cash or successfully paid
        if ("ONLINE".equals(booking.getPaymentMethod()) && !"PENDING_CASH".equals(booking.getPaymentStatus())) {
            return ResponseEntity.badRequest().body(Map.of("message", "Cannot confirm cash for an online payment booking. Online payments are verified directly via gateway."));
        }

        // Prevent duplicate cash collection recordings
        if ("PAID".equals(booking.getPaymentStatus())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "Cash payment is already collected and marked as PAID. Duplicate recording rejected."));
        }

        booking.setPaymentStatus("PAID");
        booking.setTransactionReference("CASH_COLLECTED_AUDIT_" + driver.getId() + "_" + System.currentTimeMillis());
        booking.setUpdatedAt(LocalDateTime.now());
        Booking saved = bookingRepository.save(booking);

        // Requirement 4, 5: Create an auditable cash collection record & commission payable ledger entry
        commissionService.recordCashCollection(saved, driver);

        notificationService.sendNotification(booking.getPassenger(), "Cash Payment Confirmed",
                "Driver " + driver.getName() + " confirmed receipt of cash payment ₹" + booking.getTotalAmount() + ".");

        return ResponseEntity.ok(saved);
    }

    @Transactional
    public ResponseEntity<?> payBooking(String token, Long bookingId, Map<String, Object> payload) {
        String email = jwtUtil.extractEmail(token.substring(7));
        User passenger = userRepository.findByEmail(email);
        if (passenger == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "User not found"));
        }

        Booking booking = bookingRepository.findById(bookingId).orElse(null);
        if (booking == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Booking not found"));
        }

        if (!booking.getPassenger().getId().equals(passenger.getId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Not authorized to pay for this booking."));
        }

        if ("PAID".equals(booking.getPaymentStatus())) {
            return ResponseEntity.ok(Map.of("message", "Booking is already paid.", "booking", booking));
        }

        String method = payload != null && payload.containsKey("paymentMethod") ? payload.get("paymentMethod").toString() : "ONLINE";
        String txRef = payload != null && payload.containsKey("transactionReference") ? payload.get("transactionReference").toString() : "TXN_" + java.util.UUID.randomUUID().toString().substring(0, 10).toUpperCase();

        booking.setPaymentMethod(method);
        booking.setPaymentStatus("PAID");
        booking.setTransactionReference(txRef);
        booking.setUpdatedAt(LocalDateTime.now());
        Booking saved = bookingRepository.save(booking);

        if (booking.getRide() != null && booking.getRide().getDriver() != null) {
            notificationService.sendNotification(booking.getRide().getDriver(), "Payment Received",
                    "Passenger " + passenger.getName() + " has paid ₹" + booking.getTotalAmount() + " for booking #" + booking.getId());
        }

        return ResponseEntity.ok(saved);
    }

    public ResponseEntity<?> getActiveBooking(String token) {
        String email = jwtUtil.extractEmail(token.substring(7));
        User passenger = userRepository.findByEmail(email);
        if (passenger == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "User not found"));
        }

        List<Booking> bookings = bookingRepository.findByPassengerIdAndStatus(passenger.getId(), "CONFIRMED");
        for (Booking b : bookings) {
            if (b.getRide() != null && "IN_PROGRESS".equals(b.getRide().getStatus())) {
                return ResponseEntity.ok(b);
            }
        }

        return ResponseEntity.ok(Map.of("active", false, "message", "No active booking in progress."));
    }
}
