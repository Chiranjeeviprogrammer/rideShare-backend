package com.rideshare.backend.service;

import com.rideshare.backend.model.Booking;
import com.rideshare.backend.model.Ride;
import com.rideshare.backend.model.User;
import com.rideshare.backend.model.Wallet;
import com.rideshare.backend.model.Referral;
import com.rideshare.backend.model.Vehicle;
import com.rideshare.backend.repository.BookingRepository;
import com.rideshare.backend.repository.ReferralRepository;
import com.rideshare.backend.repository.RideRepository;
import com.rideshare.backend.repository.UserRepository;
import com.rideshare.backend.repository.WalletRepository;
import com.rideshare.backend.security.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class RideService {

    public static final List<String> ACTIVE_STATUSES = Arrays.asList(
            "PUBLISHED", "FULL", "IN_PROGRESS", "ACCEPTED", "PENDING"
    );

    public static final List<String> TERMINAL_STATUSES = Arrays.asList(
            "COMPLETED", "CANCELLED", "EXPIRED"
    );

    @Autowired
    private RideRepository rideRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private ReferralRepository referralRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private CommissionService commissionService;

    @Autowired
    private com.rideshare.backend.repository.CommissionLedgerRepository commissionLedgerRepository;

    @Autowired
    private EmailService emailService;

    @Autowired
    private com.rideshare.backend.repository.DriverApplicationRepository driverApplicationRepository;

    @Autowired
    private com.rideshare.backend.repository.VehicleRepository vehicleRepository;

    @Autowired(required = false)
    private CouponService couponService;

    @Autowired(required = false)
    private RedisCacheService redisCacheService;

    public List<Ride> getAllRides() {
        return rideRepository.findAllActiveRidesWithDetails();
    }

    public Optional<Ride> getRideById(Long id) {
        if (id == null) return Optional.empty();
        String cacheKey = RedisCacheService.PREFIX_RIDE_DETAIL + id;
        if (redisCacheService != null) {
            Optional<Ride> cached = redisCacheService.get(cacheKey, Ride.class);
            if (cached.isPresent()) {
                return cached;
            }
        }
        Optional<Ride> fromDb = rideRepository.findById(id);
        if (fromDb.isPresent() && redisCacheService != null) {
            redisCacheService.set(cacheKey, fromDb.get(), RedisCacheService.TTL_RIDE_DETAIL);
        }
        return fromDb;
    }

    public List<Ride> searchRides(String from, String to, Integer seats) {
        return searchRides(from, to, seats, null, null);
    }

    public List<Ride> searchRides(String from, String to, Integer seats, String dateStr, String timeStr) {
        String cleanFrom = extractPrimaryCity(from);
        String cleanTo = extractPrimaryCity(to);

        List<Ride> baseRides = (cleanFrom == null && cleanTo == null && seats == null)
                ? rideRepository.findAllActiveRidesWithDetails()
                : rideRepository.searchPublishedRides(cleanFrom, cleanTo, seats);

        // Filter and rank for office carpooling
        java.time.LocalDate filterDate = null;
        if (dateStr != null && !dateStr.isBlank()) {
            try {
                filterDate = java.time.LocalDate.parse(dateStr.trim().substring(0, 10));
            } catch (Exception ignored) {}
        }

        java.time.LocalTime filterTime = null;
        if (timeStr != null && !timeStr.isBlank()) {
            try {
                filterTime = java.time.LocalTime.parse(timeStr.trim());
            } catch (Exception ignored) {}
        }

        final java.time.LocalDate targetDate = filterDate;
        final java.time.LocalTime targetTime = filterTime;

        return baseRides.stream()
                .filter(r -> {
                    if (targetDate == null) return true;
                    if (r.getDepartureTime() == null) return true;
                    return r.getDepartureTime().toLocalDate().equals(targetDate);
                })
                .sorted((a, b) -> {
                    // 1. Departure time proximity if time specified
                    if (targetTime != null && a.getDepartureTime() != null && b.getDepartureTime() != null) {
                        long diffA = Math.abs(java.time.Duration.between(a.getDepartureTime().toLocalTime(), targetTime).toMinutes());
                        long diffB = Math.abs(java.time.Duration.between(b.getDepartureTime().toLocalTime(), targetTime).toMinutes());
                        if (diffA != diffB) {
                            return Long.compare(diffA, diffB);
                        }
                    }
                    // 2. Departure time chronological
                    if (a.getDepartureTime() != null && b.getDepartureTime() != null) {
                        int comp = a.getDepartureTime().compareTo(b.getDepartureTime());
                        if (comp != 0) return comp;
                    }
                    // 3. Lowest fare first
                    double fareA = a.getFare() != null ? a.getFare() : 0.0;
                    double fareB = b.getFare() != null ? b.getFare() : 0.0;
                    int fareComp = Double.compare(fareA, fareB);
                    if (fareComp != 0) return fareComp;
                    // 4. Most available seats first
                    int seatsA = a.getAvailableSeats() != null ? a.getAvailableSeats() : 0;
                    int seatsB = b.getAvailableSeats() != null ? b.getAvailableSeats() : 0;
                    return Integer.compare(seatsB, seatsA);
                })
                .collect(Collectors.toList());
    }

    private String extractPrimaryCity(String loc) {
        if (loc == null || loc.trim().isEmpty() || loc.equalsIgnoreCase("null") || loc.equalsIgnoreCase("Your current location")) {
            return null;
        }
        String first = loc.split(",")[0].trim();
        return first.isEmpty() ? null : first.toLowerCase();
    }

    public List<Ride> getMyRides(String token) {
        String email = jwtUtil.extractEmail(token.substring(7));
        User user = userRepository.findByEmail(email);
        if (user == null) return List.of();

        if ("DRIVER".equals(user.getRole())) {
            return rideRepository.findByDriverId(user.getId());
        } else {
            return rideRepository.findByRiderId(user.getId());
        }
    }

    public ResponseEntity<?> getActiveRide(String token) {
        if (token == null || !token.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Authentication required."));
        }
        String email = jwtUtil.extractEmail(token.substring(7));
        User user = userRepository.findByEmail(email);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "User not found."));
        }

        Optional<Ride> activeRideOpt = rideRepository.findFirstByDriverIdAndStatusInOrderByRequestedAtDesc(user.getId(), ACTIVE_STATUSES);
        if (activeRideOpt.isPresent()) {
            return ResponseEntity.ok(activeRideOpt.get());
        }

        // Also check if user is a passenger on an active ride
        List<Booking> bookings = bookingRepository.findByPassengerIdAndStatus(user.getId(), "CONFIRMED");
        for (Booking b : bookings) {
            if (b.getRide() != null && ACTIVE_STATUSES.contains(b.getRide().getStatus())) {
                return ResponseEntity.ok(b.getRide());
            }
        }

        return ResponseEntity.ok(Map.of("active", false, "message", "No active ride found."));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public synchronized ResponseEntity<?> publishRide(String token, Ride ride) {
        if (token == null || !token.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Authentication required to publish or request rides."));
        }

        String email = jwtUtil.extractEmail(token.substring(7));
        User authenticatedUser = userRepository.findByEmail(email);

        if (authenticatedUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Authentication required to publish or request rides."));
        }

        // Check if this is a driver publishing a ride offer
        boolean isDriverAction = "DRIVER".equalsIgnoreCase(authenticatedUser.getRole()) ||
                (ride.getDriver() != null && ride.getDriver().getId() != null);

        if (isDriverAction) {
            // Requirement 5: Validate that the authenticated user is an approved driver
            if (!"DRIVER".equalsIgnoreCase(authenticatedUser.getRole())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("message", "Only approved drivers can publish rides."));
            }

            // Enforce verified driver application
            Optional<com.rideshare.backend.model.DriverApplication> driverAppOpt = driverApplicationRepository.findByUserId(authenticatedUser.getId());
            if (driverAppOpt.isEmpty() || !"APPROVED".equalsIgnoreCase(driverAppOpt.get().getStatus())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("message", "Driver verification pending: Your documents must be approved before you can publish rides."));
            }

            // Enforce at least one registered vehicle
            long vehicleCount = vehicleRepository.countByDriverId(authenticatedUser.getId());
            if (vehicleCount == 0) {
                // If driver application has registration number, auto-link vehicle
                if (driverAppOpt.isPresent() && driverAppOpt.get().getRegistrationNumber() != null && !driverAppOpt.get().getRegistrationNumber().trim().isEmpty()) {
                    var app = driverAppOpt.get();
                    Vehicle autoVeh = new Vehicle();
                    autoVeh.setDriver(authenticatedUser);
                    autoVeh.setMake(app.getVehicleMake() != null ? app.getVehicleMake() : "Maruti Suzuki");
                    autoVeh.setModel(app.getVehicleModel() != null ? app.getVehicleModel() : "Dzire");
                    autoVeh.setYear(app.getVehicleYear() != null ? app.getVehicleYear() : "2023");
                    autoVeh.setLicensePlate(app.getRegistrationNumber());
                    autoVeh.setColor(app.getVehicleColor() != null ? app.getVehicleColor() : "White");
                    vehicleRepository.save(autoVeh);
                    vehicleCount = 1;
                }
            }

            if (vehicleCount == 0) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                        "message", "No vehicle registered: You must add a vehicle before you can offer seats or publish rides.",
                        "code", "NO_VEHICLE_REGISTERED"
                ));
            }

            // If configured, restrict the Driver from publishing new rides when outstanding commission exceeds threshold.
            if (commissionService.isDriverRestrictedFromPublishing(authenticatedUser)) {
                Double outstanding = commissionService.getDriverCommissionSummary(authenticatedUser) != null 
                        ? (Double) commissionService.getDriverCommissionSummary(authenticatedUser).get("outstandingCommission") 
                        : 0.0;
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "message", "Publishing locked: You have ₹" + outstanding + " in outstanding platform commission. Please settle your commission balance before publishing new rides.",
                        "outstandingCommission", outstanding,
                        "restriction", "COMMISSION_THRESHOLD_EXCEEDED"
                ));
            }

            // 1. Ongoing active trip check (cannot drive another trip while one is in progress)
            List<Ride> ongoingRides = rideRepository.findByDriverIdAndStatusIn(authenticatedUser.getId(), List.of("IN_PROGRESS", "ACCEPTED"));
            if (!ongoingRides.isEmpty()) {
                Ride currentActive = ongoingRides.get(0);
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                        "message", "You already have an active ride (" + currentActive.getStatus() + "). Please complete or cancel your current ride first.",
                        "activeRideId", currentActive.getId(),
                        "currentStatus", currentActive.getStatus()
                ));
            }

            // 2. Immediate on-demand ride check (if departureTime is null, only one immediate active ride allowed)
            if (ride.getDepartureTime() == null) {
                List<Ride> activeImmediateRides = rideRepository.findByDriverIdAndStatusIn(
                        authenticatedUser.getId(),
                        List.of("PUBLISHED", "FULL", "PENDING", "IN_PROGRESS", "ACCEPTED")
                ).stream().filter(r -> r.getDepartureTime() == null).toList();

                if (!activeImmediateRides.isEmpty()) {
                    Ride currentActive = activeImmediateRides.get(0);
                    return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                            "message", "You already have an active ride (" + currentActive.getStatus() + "). Please complete or cancel your current ride first.",
                            "activeRideId", currentActive.getId(),
                            "currentStatus", currentActive.getStatus()
                    ));
                }
            } else {
                // 3. Scheduled departure check: prevent duplicate departure time slots
                boolean slotTaken = rideRepository.existsByDriverIdAndDepartureTimeAndStatusIn(
                        authenticatedUser.getId(),
                        ride.getDepartureTime(),
                        List.of("PUBLISHED", "FULL", "PENDING", "IN_PROGRESS")
                );
                if (slotTaken) {
                    return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                            "message", "You already have a ride scheduled for this exact departure time slot."
                    ));
                }
            }

            ride.setDriver(authenticatedUser);
            // Supabase schema requires non-null rider_id foreign key, assign hosting driver as rider
            if (ride.getRider() == null) {
                ride.setRider(authenticatedUser);
            }
            ride.setStatus("PUBLISHED");

            int seats = (ride.getAvailableSeats() != null && ride.getAvailableSeats() > 0) ? ride.getAvailableSeats() : 3;
            ride.setAvailableSeats(seats);
            if (ride.getTotalSeats() == null || ride.getTotalSeats() <= 0) {
                ride.setTotalSeats(seats);
            }
        } else {
            // Passenger ride request
            if (ride.getRider() == null) {
                ride.setRider(authenticatedUser);
            }
            List<Ride> existingRides = rideRepository.findByRiderId(ride.getRider().getId());
            boolean hasActiveRide = existingRides.stream()
                    .anyMatch(r -> "PENDING".equals(r.getStatus()) || "ACCEPTED".equals(r.getStatus()));
            if (hasActiveRide) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("message", "You already have an active ride. Please complete or cancel it before booking a new one."));
            }
            ride.setStatus("PENDING");
        }

        ride.setRequestedAt(LocalDateTime.now());

        try {
            Ride saved = rideRepository.saveAndFlush(ride);
            if (redisCacheService != null) {
                redisCacheService.evictRideCaches(saved.getId());
            }
            return ResponseEntity.ok(saved);
        } catch (DataIntegrityViolationException e) {
            // Concurrent request hit partial unique index idx_driver_active_ride
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "message", "You already have an active ride. Please complete or cancel your current ride first."
            ));
        }
    }

    @Transactional
    public ResponseEntity<?> startRide(String token, Long id) {
        String email = jwtUtil.extractEmail(token.substring(7));
        User user = userRepository.findByEmail(email);

        Ride ride = rideRepository.findByIdForUpdate(id).orElse(null);
        if (ride == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Ride not found."));
        }

        boolean isDriver = ride.getDriver() != null && ride.getDriver().getId().equals(user.getId());
        if (!isDriver) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Only the assigned driver can start this trip."));
        }

        if (!"PUBLISHED".equals(ride.getStatus()) && !"FULL".equals(ride.getStatus()) && !"ACCEPTED".equals(ride.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("message", "Ride cannot be started from status: " + ride.getStatus()));
        }

        ride.setStatus("IN_PROGRESS");
        Ride saved = rideRepository.save(ride);
        if (redisCacheService != null) {
            redisCacheService.evictRideCaches(ride.getId());
        }

        // Notify confirmed passengers
        List<Booking> bookings = bookingRepository.findByRideId(ride.getId());
        for (Booking b : bookings) {
            if ("CONFIRMED".equals(b.getStatus())) {
                notificationService.sendNotification(b.getPassenger(), "Trip Started", "Your trip with driver " + user.getName() + " has started!");
                if (b.getPassenger() != null && b.getPassenger().getEmail() != null) {
                    emailService.sendTripStartedEmail(b.getPassenger().getEmail(), b.getPassenger().getName(), ride.getId(), user.getName());
                }
            }
        }

        return ResponseEntity.ok(saved);
    }

    @Transactional
    public ResponseEntity<?> completeRide(String token, Long id) {
        String email = jwtUtil.extractEmail(token.substring(7));
        User user = userRepository.findByEmail(email);

        Ride ride = rideRepository.findByIdForUpdate(id).orElse(null);
        if (ride == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Ride not found."));
        }

        boolean isDriver = ride.getDriver() != null && ride.getDriver().getId().equals(user.getId());
        if (!isDriver) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Only the assigned driver can complete this trip."));
        }

        if ("COMPLETED".equals(ride.getStatus())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "Ride is already completed. Duplicate completion request rejected."));
        }

        if (!"IN_PROGRESS".equals(ride.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("message", "Cannot complete a ride that is not in progress. Current status is " + ride.getStatus() + "."));
        }

        ride.setStatus("COMPLETED");
        ride.setCompletedAt(LocalDateTime.now());
        ride.setCompletedBy(user.getEmail());
        Ride savedRide = rideRepository.save(ride);

        // Transition confirmed bookings to COMPLETED while strictly preserving paymentStatus
        List<Booking> bookings = bookingRepository.findByRideId(ride.getId());
        for (Booking b : bookings) {
            if ("CONFIRMED".equals(b.getStatus())) {
                b.setStatus("COMPLETED");
                b.setUpdatedAt(LocalDateTime.now());
                bookingRepository.save(b);
                if (couponService != null) {
                    couponService.markRedemptionCompleted(b);
                }

                // Immutable Driver Earning ledger entry (Sections 11, 12)
                if ("PAID".equals(b.getPaymentStatus()) && !"CASH".equalsIgnoreCase(b.getPaymentMethod())) {
                    String earningKey = "DRIVER_EARNING_BOOKING_" + b.getId();
                    if (commissionLedgerRepository.findByIdempotencyKey(earningKey).isEmpty()) {
                        double baseFare = b.getBaseFare() > 0 ? b.getBaseFare() : (ride.getFare() != null ? ride.getFare() * b.getSeats() : 0.0);
                        double driverAmt = b.getDriverNetEarnings() > 0 ? b.getDriverNetEarnings() : (b.getDriverAmount() > 0 ? b.getDriverAmount() : baseFare);
                        double commAmt = b.getDriverCommissionAmount();

                        com.rideshare.backend.model.CommissionLedger earningLedger = com.rideshare.backend.model.CommissionLedger.builder()
                                .driver(user)
                                .booking(b)
                                .ride(ride)
                                .entryType("DRIVER_EARNING")
                                .grossFare(baseFare)
                                .commissionRatePercent(b.getDriverCommissionRateSnapshot() != null ? b.getDriverCommissionRateSnapshot() : 0.0)
                                .fixedFee(b.getDriverCommissionFixedSnapshot() != null ? b.getDriverCommissionFixedSnapshot() : 0.0)
                                .commissionAmount(commAmt)
                                .driverNetAmount(driverAmt)
                                .status("PAYOUT_ELIGIBLE") // Eligible for settlement and payout
                                .idempotencyKey(earningKey)
                                .createdAt(LocalDateTime.now())
                                .settledAt(null)
                                .notes("Driver net earnings for completed online booking " + b.getBookingCode() + " (Base: ₹" + baseFare + " - Comm: ₹" + commAmt + ")")
                                .build();
                        commissionLedgerRepository.save(earningLedger);

                        // Credit driver wallet balance
                        Wallet driverWallet = walletRepository.findByUserId(user.getId())
                                .orElseGet(() -> {
                                    Wallet w = new Wallet();
                                    w.setUser(user);
                                    w.setBalance(0.0);
                                    return walletRepository.save(w);
                                });
                        driverWallet.setBalance(driverWallet.getBalance() + driverAmt);
                        walletRepository.save(driverWallet);

                        notificationService.sendNotification(user, "Earnings Available for Payout",
                                "₹" + driverAmt + " is now eligible for payout from booking " + b.getBookingCode() + ".");
                    }
                }

                notificationService.sendNotification(b.getPassenger(), "Trip Completed", "Your trip has completed safely. Thank you for riding!");
                if (b.getPassenger() != null && b.getPassenger().getEmail() != null) {
                    emailService.sendTripCompletedEmail(b.getPassenger().getEmail(), b.getPassenger().getName(), ride.getId(), b.getTotalAmount());
                }
            }
        }

        // Process referral rewards for first completed ride
        User rider = ride.getRider();
        if (rider != null && !rider.getId().equals(user.getId())) {
            List<Ride> riderRides = rideRepository.findByRiderId(rider.getId());
            long completedRidesCount = riderRides.stream().filter(r -> "COMPLETED".equals(r.getStatus())).count();

            if (completedRidesCount == 1) {
                Optional<Referral> referralOpt = referralRepository.findByReferredUserId(rider.getId());
                if (referralOpt.isPresent()) {
                    Referral referral = referralOpt.get();
                    if ("PENDING".equals(referral.getStatus())) {
                        referral.setStatus("REWARDED");
                        referral.setRewardedAt(LocalDateTime.now());
                        referralRepository.save(referral);

                        Wallet referrerWallet = walletRepository.findByUserId(referral.getReferrer().getId())
                                .orElseGet(() -> {
                                    Wallet w = new Wallet();
                                    w.setUser(referral.getReferrer());
                                    w.setBalance(0.0);
                                    return walletRepository.save(w);
                                });
                        referrerWallet.setBalance(referrerWallet.getBalance() + referral.getRewardAmount());
                        walletRepository.save(referrerWallet);

                        Wallet referredWallet = walletRepository.findByUserId(rider.getId())
                                .orElseGet(() -> {
                                    Wallet w = new Wallet();
                                    w.setUser(rider);
                                    w.setBalance(0.0);
                                    return walletRepository.save(w);
                                });
                        referredWallet.setBalance(referredWallet.getBalance() + 50.0);
                        walletRepository.save(referredWallet);
                    }
                }
            }
        }

        savedRide = rideRepository.save(ride);
        if (redisCacheService != null) {
            redisCacheService.evictRideCaches(ride.getId());
        }

        return ResponseEntity.ok(savedRide);
    }

    @Transactional
    public ResponseEntity<?> cancelRide(String token, Long id) {
        String email = jwtUtil.extractEmail(token.substring(7));
        User user = userRepository.findByEmail(email);

        Ride ride = rideRepository.findByIdForUpdate(id).orElse(null);
        if (ride == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Ride not found."));
        }

        boolean isRider = ride.getRider() != null && ride.getRider().getId().equals(user.getId());
        boolean isDriver = ride.getDriver() != null && ride.getDriver().getId().equals(user.getId());

        if (!isDriver && !isRider) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Not authorized to cancel this ride."));
        }

        if ("CANCELLED".equals(ride.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("message", "Ride is already cancelled."));
        }

        if ("COMPLETED".equals(ride.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("message", "Cannot cancel a completed ride."));
        }

        ride.setStatus("CANCELLED");
        Ride saved = rideRepository.save(ride);
        if (redisCacheService != null) {
            redisCacheService.evictRideCaches(ride.getId());
        }

        // Cancel and refund any confirmed passenger bookings
        List<Booking> bookings = bookingRepository.findByRideId(ride.getId());
        for (Booking b : bookings) {
            if ("CONFIRMED".equals(b.getStatus()) || "PENDING".equals(b.getStatus())) {
                b.setStatus("CANCELLED");
                b.setUpdatedAt(LocalDateTime.now());
                bookingRepository.save(b);
                notificationService.sendNotification(b.getPassenger(), "Ride Cancelled", "Ride #" + ride.getId() + " was cancelled by the driver. Any fares charged will be refunded.");
                if (b.getPassenger() != null && b.getPassenger().getEmail() != null) {
                    emailService.sendBookingCancelledEmail(b.getPassenger().getEmail(), b.getPassenger().getName(), b.getId(), "Ride #" + ride.getId() + " was cancelled by the driver");
                }
            }
        }

        return ResponseEntity.ok(saved);
    }
}
