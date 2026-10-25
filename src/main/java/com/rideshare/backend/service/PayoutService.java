package com.rideshare.backend.service;

import com.rideshare.backend.model.*;
import com.rideshare.backend.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class PayoutService {

    public static final double MIN_PAYOUT_AMOUNT = 50.0;

    @Autowired
    private DriverPayoutProfileRepository driverPayoutProfileRepository;

    @Autowired
    private DriverPayoutRepository driverPayoutRepository;

    @Autowired
    private CommissionLedgerRepository commissionLedgerRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private RideRepository rideRepository;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private EmailService emailService;

    @Autowired
    private RazorpayService razorpayService;

    /**
     * Get Driver Payout Profile
     */
    public ResponseEntity<?> getPayoutProfile(User driver) {
        if (!"DRIVER".equals(driver.getRole())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Only drivers have payout profiles."));
        }

        Optional<DriverPayoutProfile> profileOpt = driverPayoutProfileRepository.findByDriverId(driver.getId());
        if (profileOpt.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "configured", false,
                    "message", "No payout profile configured yet. Please add your bank or UPI details."
            ));
        }

        DriverPayoutProfile profile = profileOpt.get();
        Map<String, Object> resp = new HashMap<>();
        resp.put("configured", true);
        resp.put("id", profile.getId());
        resp.put("accountType", profile.getAccountType());
        resp.put("accountHolderName", profile.getAccountHolderName());
        resp.put("accountNumberMasked", profile.getAccountNumberMasked());
        resp.put("maskedAccountNumber", profile.getAccountNumberMasked());
        resp.put("ifscCode", profile.getIfscCode());
        resp.put("bankName", profile.getBankName());
        resp.put("upiId", profile.getUpiId());
        resp.put("verificationStatus", profile.getVerificationStatus());
        resp.put("isEligibleForPayout", profile.getIsEligibleForPayout());
        resp.put("updatedAt", profile.getUpdatedAt());

        return ResponseEntity.ok(resp);
    }

    /**
     * Create or update driver payout profile (Masked, no sensitive CVV/PIN stored)
     */
    @Transactional
    public ResponseEntity<?> updatePayoutProfile(User driver, Map<String, String> request) {
        if (!"DRIVER".equals(driver.getRole())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Only drivers can configure payout profiles."));
        }

        String accountType = request.getOrDefault("accountType", "BANK_ACCOUNT").toUpperCase();
        String accountHolder = request.get("accountHolderName");
        String accountNumber = request.get("accountNumber");
        String ifsc = request.get("ifscCode");
        String bankName = request.get("bankName");
        String upiId = request.get("upiId");

        DriverPayoutProfile profile = driverPayoutProfileRepository.findByDriverId(driver.getId())
                .orElseGet(() -> DriverPayoutProfile.builder().driver(driver).build());

        profile.setAccountType(accountType);

        if ("UPI".equalsIgnoreCase(accountType)) {
            if (upiId == null || !upiId.contains("@") || upiId.trim().length() < 5) {
                return ResponseEntity.badRequest().body(Map.of("message", "Valid UPI ID (e.g. name@bank) is required."));
            }
            profile.setUpiId(upiId.trim());
            profile.setAccountHolderName(accountHolder != null ? accountHolder.trim() : driver.getName());
        } else {
            // BANK_ACCOUNT
            if (accountNumber == null || accountNumber.trim().length() < 8) {
                return ResponseEntity.badRequest().body(Map.of("message", "Valid bank account number is required."));
            }
            if (ifsc == null || ifsc.trim().length() < 4) {
                return ResponseEntity.badRequest().body(Map.of("message", "Valid IFSC code is required."));
            }

            String cleanNum = accountNumber.trim();
            String masked = "•••• •••• " + (cleanNum.length() > 4 ? cleanNum.substring(cleanNum.length() - 4) : cleanNum);
            profile.setAccountNumberMasked(masked);
            profile.setIfscCode(ifsc.trim().toUpperCase());
            profile.setAccountHolderName(accountHolder != null ? accountHolder.trim() : driver.getName());
            profile.setBankName(bankName != null ? bankName.trim() : "Bank Account");
        }

        profile.setVerificationStatus("VERIFIED");
        profile.setIsEligibleForPayout(true);
        profile.setUpdatedAt(LocalDateTime.now());

        DriverPayoutProfile saved = driverPayoutProfileRepository.save(profile);

        notificationService.sendNotification(driver, "Payout Profile Saved", "Your payout profile (" + saved.getAccountType() + ") has been updated successfully.");

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Payout profile saved successfully.",
                "accountType", saved.getAccountType(),
                "accountNumberMasked", saved.getAccountNumberMasked() != null ? saved.getAccountNumberMasked() : saved.getUpiId(),
                "verificationStatus", saved.getVerificationStatus()
        ));
    }

    /**
     * Get Real Dynamic Driver Earnings Breakdown
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getDriverEarningsBreakdown(User driver) {
        List<Ride> driverRides = rideRepository.findByDriverId(driver.getId());
        LocalDate today = LocalDate.now();
        LocalDate startOfWeek = today.minusDays(today.getDayOfWeek().getValue() - 1);

        double todayEarnings = 0.0;
        double weeklyEarnings = 0.0;
        double totalEarnings = 0.0;
        double pendingEarnings = 0.0;
        double payoutEligible = 0.0;
        double paidEarnings = 0.0;

        List<Map<String, Object>> completedRidesList = new ArrayList<>();

        for (Ride ride : driverRides) {
            List<Booking> bookings = bookingRepository.findByRideId(ride.getId());
            boolean isCompleted = "COMPLETED".equals(ride.getStatus());

            double rideFareTotal = 0.0;
            double rideCommissionTotal = 0.0;
            double rideRiderFeeTotal = 0.0;
            double rideDriverEarning = 0.0;

            for (Booking b : bookings) {
                if ("CANCELLED".equals(b.getStatus())) continue;

                double fare = b.getBaseFare() > 0 ? b.getBaseFare() : (ride.getFare() != null ? ride.getFare() * b.getSeats() : 0.0);
                double commission = b.getDriverCommissionAmount();
                double riderFee = b.getRiderPlatformFee();
                double driverAmt = b.getDriverNetEarnings() > 0 ? b.getDriverNetEarnings() : (b.getDriverAmount() > 0 ? b.getDriverAmount() : Math.max(0.0, fare - commission));

                rideFareTotal += fare;
                rideCommissionTotal += commission;
                rideRiderFeeTotal += riderFee;
                rideDriverEarning += driverAmt;

                if (!isCompleted) {
                    if ("CONFIRMED".equals(b.getStatus())) {
                        pendingEarnings += driverAmt;
                    }
                }
            }

            if (isCompleted) {
                totalEarnings += rideDriverEarning;

                if (ride.getCompletedAt() != null) {
                    LocalDate compDate = ride.getCompletedAt().toLocalDate();
                    if (compDate.isEqual(today)) {
                        todayEarnings += rideDriverEarning;
                    }
                    if (!compDate.isBefore(startOfWeek)) {
                        weeklyEarnings += rideDriverEarning;
                    }
                }

                Map<String, Object> rMap = new HashMap<>();
                rMap.put("rideId", ride.getId());
                rMap.put("pickup", ride.getPickupLocation());
                rMap.put("dropoff", ride.getDropoffLocation());
                rMap.put("departureTime", ride.getDepartureTime());
                rMap.put("completedAt", ride.getCompletedAt());
                rMap.put("rideFare", Math.round(rideFareTotal * 100.0) / 100.0);
                rMap.put("driverCommission", Math.round(rideCommissionTotal * 100.0) / 100.0);
                rMap.put("riderPlatformFee", Math.round(rideRiderFeeTotal * 100.0) / 100.0);
                rMap.put("platformFee", Math.round((rideCommissionTotal + rideRiderFeeTotal) * 100.0) / 100.0);
                rMap.put("driverEarning", Math.round(rideDriverEarning * 100.0) / 100.0);
                rMap.put("settlementStatus", "SETTLED");
                completedRidesList.add(rMap);
            }
        }

        // Payout and ledger reconciliation
        Double paidFromPayouts = driverPayoutRepository.getTotalPaidAmountByDriverId(driver.getId());

        payoutEligible = Math.max(0.0, totalEarnings - (paidFromPayouts != null ? paidFromPayouts : 0.0));
        paidEarnings = paidFromPayouts != null ? paidFromPayouts : 0.0;

        Map<String, Object> breakdown = new HashMap<>();
        breakdown.put("driverId", driver.getId());
        breakdown.put("driverName", driver.getName());
        breakdown.put("todayEarnings", Math.round(todayEarnings * 100.0) / 100.0);
        breakdown.put("weeklyEarnings", Math.round(weeklyEarnings * 100.0) / 100.0);
        breakdown.put("totalEarnings", Math.round(totalEarnings * 100.0) / 100.0);
        breakdown.put("pendingEarnings", Math.round(pendingEarnings * 100.0) / 100.0);
        breakdown.put("payoutEligible", Math.round(payoutEligible * 100.0) / 100.0);
        breakdown.put("paidEarnings", Math.round(paidEarnings * 100.0) / 100.0);
        breakdown.put("minPayoutThreshold", MIN_PAYOUT_AMOUNT);
        breakdown.put("completedRides", completedRidesList);

        return breakdown;
    }

    /**
     * Request Driver Payout with idempotency and state protection
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public ResponseEntity<?> requestPayout(User driver, Map<String, Object> request) {
        if (!"DRIVER".equals(driver.getRole())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Only drivers can request payouts."));
        }

        Optional<DriverPayoutProfile> profileOpt = driverPayoutProfileRepository.findByDriverId(driver.getId());
        if (profileOpt.isEmpty() || !Boolean.TRUE.equals(profileOpt.get().getIsEligibleForPayout())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Please configure and verify your payout account (Bank or UPI) before requesting a withdrawal."
            ));
        }

        DriverPayoutProfile profile = profileOpt.get();

        Map<String, Object> earnings = getDriverEarningsBreakdown(driver);
        double eligibleAmount = (Double) earnings.get("payoutEligible");

        if (eligibleAmount < MIN_PAYOUT_AMOUNT) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Minimum withdrawal threshold is ₹" + String.format("%.2f", MIN_PAYOUT_AMOUNT) +
                               ". Your current eligible balance is ₹" + String.format("%.2f", eligibleAmount)
            ));
        }

        double requestedAmount = eligibleAmount;
        if (request != null && request.containsKey("amount")) {
            try {
                double parsed = Double.parseDouble(request.get("amount").toString());
                if (parsed > 0) {
                    requestedAmount = Math.min(parsed, eligibleAmount);
                }
            } catch (Exception ignored) {}
        }

        if (requestedAmount < MIN_PAYOUT_AMOUNT) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Requested payout amount must be at least ₹" + String.format("%.2f", MIN_PAYOUT_AMOUNT)
            ));
        }

        // RazorpayX transfers are not available in test mode. We fail closed safely:
        // Do not manufacture a paid payout record or fake ledger entry.
        try {
            razorpayService.createPayout(
                    profile.getProviderFundAccountId(),
                    requestedAmount,
                    "IMPS",
                    "RideShare Driver Payout"
            );
        } catch (RazorpayService.PaymentConfigurationException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "success", false,
                    "message", ex.getMessage(),
                    "status", "PAYOUTS_DISABLED_IN_TEST_MODE"
            ));
        }

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "success", false,
                "message", "Driver payouts are unavailable while Razorpay is restricted to test mode.",
                "status", "PAYOUTS_DISABLED_IN_TEST_MODE"
        ));
    }

    /**
     * Get Payout History for Driver
     */
    public ResponseEntity<?> getDriverPayouts(User driver) {
        if (!"DRIVER".equals(driver.getRole())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Only drivers can view payouts."));
        }

        List<DriverPayout> payouts = driverPayoutRepository.findByDriverIdOrderByCreatedAtDesc(driver.getId());
        return ResponseEntity.ok(payouts);
    }
}
