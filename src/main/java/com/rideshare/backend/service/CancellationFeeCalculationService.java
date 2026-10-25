package com.rideshare.backend.service;

import com.rideshare.backend.model.Booking;
import com.rideshare.backend.model.CancellationPolicyRule;
import com.rideshare.backend.model.PlatformSettings;
import com.rideshare.backend.model.User;
import com.rideshare.backend.repository.CancellationPolicyRuleRepository;
import com.rideshare.backend.repository.PlatformSettingsRepository;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-authoritative cancellation fee calculation engine.
 * Guarantees every cancellation fee defaults to ₹0 until an authorized administrator explicitly configures a fee.
 * Automatically exempts passengers from penalties when a driver cancels.
 */
@Slf4j
@Service
public class CancellationFeeCalculationService {

    @Autowired
    private PlatformSettingsRepository platformSettingsRepository;

    @Autowired
    private CancellationPolicyRuleRepository cancellationPolicyRuleRepository;

    @Autowired(required = false)
    private RedisCacheService redisCacheService;

    @Data
    @Builder
    public static class CancellationQuote {
        private Long bookingId;
        private Long rideId;
        private int seats;
        private double totalPaid;
        private double cancellationFee;
        private double refundAmount;
        private double hoursUntilDeparture;
        private String ruleApplied;
        private String policyNotice;
        private boolean isDriverCancellation;
        private boolean isEligibleForRefund;
        private boolean cancellationFeeEnabled;

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("bookingId", bookingId);
            map.put("rideId", rideId);
            map.put("seats", seats);
            map.put("totalPaid", Math.round(totalPaid * 100.0) / 100.0);
            map.put("cancellationFee", Math.round(cancellationFee * 100.0) / 100.0);
            map.put("refundAmount", Math.round(refundAmount * 100.0) / 100.0);
            map.put("hoursUntilDeparture", Math.round(hoursUntilDeparture * 10.0) / 10.0);
            map.put("ruleApplied", ruleApplied);
            map.put("policyNotice", policyNotice);
            map.put("isDriverCancellation", isDriverCancellation);
            map.put("isEligibleForRefund", isEligibleForRefund);
            map.put("cancellationFeeEnabled", cancellationFeeEnabled);
            return map;
        }
    }

    public CancellationQuote calculateQuote(Booking booking, boolean isDriverCancellation) {
        if (booking == null) {
            throw new IllegalArgumentException("Booking cannot be null for cancellation quote calculation.");
        }
        User initiator = null;
        if (isDriverCancellation && booking.getRide() != null) {
            initiator = booking.getRide().getDriver();
        } else {
            initiator = booking.getPassenger();
        }
        return calculateQuote(booking, initiator);
    }

    public CancellationQuote calculateQuote(Booking booking, User initiator) {
        if (booking == null) {
            throw new IllegalArgumentException("Booking cannot be null for cancellation quote calculation.");
        }

        PlatformSettings settings = platformSettingsRepository.findTopByOrderByIdAsc()
                .orElseGet(() -> PlatformSettings.builder()
                        .cancellationFeeEnabled(false)
                        .defaultCancellationFee(0.0)
                        .build());

        double totalPaid = booking.getTotalAmount();
        boolean isDriver = booking.getRide() != null && booking.getRide().getDriver() != null &&
                initiator != null && booking.getRide().getDriver().getId().equals(initiator.getId());

        // Driver cancelling NEVER charges the passenger: Passenger gets 100% full refund
        if (isDriver) {
            return CancellationQuote.builder()
                    .bookingId(booking.getId())
                    .rideId(booking.getRide() != null ? booking.getRide().getId() : null)
                    .seats(booking.getSeats())
                    .totalPaid(totalPaid)
                    .cancellationFee(0.0)
                    .refundAmount(totalPaid)
                    .hoursUntilDeparture(calculateHoursUntilDeparture(booking))
                    .ruleApplied("DRIVER_CANCELLATION_FULL_REFUND")
                    .policyNotice("Driver cancelled the ride. 100% of your payment is refunded with zero deduction.")
                    .isDriverCancellation(true)
                    .isEligibleForRefund(totalPaid > 0)
                    .cancellationFeeEnabled(Boolean.TRUE.equals(settings.getCancellationFeeEnabled()))
                    .build();
        }

        // Rule 1: If cancellation fees are globally disabled by Admin, fee is strictly ₹0.00
        if (!Boolean.TRUE.equals(settings.getCancellationFeeEnabled())) {
            return CancellationQuote.builder()
                    .bookingId(booking.getId())
                    .rideId(booking.getRide() != null ? booking.getRide().getId() : null)
                    .seats(booking.getSeats())
                    .totalPaid(totalPaid)
                    .cancellationFee(0.0)
                    .refundAmount(totalPaid)
                    .hoursUntilDeparture(calculateHoursUntilDeparture(booking))
                    .ruleApplied("ZERO_CANCELLATION_FEE_MODE")
                    .policyNotice("RideShare Zero-Penalty Mode: No cancellation fees apply. Full refund will be processed.")
                    .isDriverCancellation(false)
                    .isEligibleForRefund(totalPaid > 0)
                    .cancellationFeeEnabled(false)
                    .build();
        }

        // Rule 2: Booking Grace Period Exemption (e.g., cancelled within 10 minutes of booking)
        if (booking.getBookedAt() != null) {
            long minutesSinceBooking = Duration.between(booking.getBookedAt(), LocalDateTime.now()).toMinutes();
            int graceMinutes = settings.getCancellationGraceMinutes() != null ? settings.getCancellationGraceMinutes() : 10;
            if (minutesSinceBooking >= 0 && minutesSinceBooking <= graceMinutes) {
                return CancellationQuote.builder()
                        .bookingId(booking.getId())
                        .rideId(booking.getRide() != null ? booking.getRide().getId() : null)
                        .seats(booking.getSeats())
                        .totalPaid(totalPaid)
                        .cancellationFee(0.0)
                        .refundAmount(totalPaid)
                        .hoursUntilDeparture(calculateHoursUntilDeparture(booking))
                        .ruleApplied("GRACE_PERIOD_EXEMPTION")
                        .policyNotice("Cancelled within the " + graceMinutes + "-minute grace period. No fee charged.")
                        .isDriverCancellation(false)
                        .isEligibleForRefund(totalPaid > 0)
                        .cancellationFeeEnabled(true)
                        .build();
            }
        }

        // Rule 3: Time-to-departure policy rules evaluation
        double hoursUntilDeparture = calculateHoursUntilDeparture(booking);
        List<CancellationPolicyRule> activeRules = cancellationPolicyRuleRepository.findByActiveTrueOrderByMinHoursBeforeDepartureDesc();

        CancellationPolicyRule matchedRule = null;
        for (CancellationPolicyRule rule : activeRules) {
            double min = rule.getMinHoursBeforeDeparture() != null ? rule.getMinHoursBeforeDeparture() : 0.0;
            Double max = rule.getMaxHoursBeforeDeparture();
            if (hoursUntilDeparture >= min && (max == null || hoursUntilDeparture < max)) {
                matchedRule = rule;
                break;
            }
        }

        double fee = 0.0;
        String ruleName = "DEFAULT_POLICY";
        String notice = settings.getCancellationPolicyNotice() != null ? settings.getCancellationPolicyNotice() : "Standard carpooling policy";

        if (matchedRule != null) {
            ruleName = matchedRule.getName() != null ? matchedRule.getName() : "TIME_WINDOW_RULE";
            if ("PERCENTAGE".equalsIgnoreCase(matchedRule.getFeeType())) {
                double pct = matchedRule.getFeeAmount() != null ? matchedRule.getFeeAmount() : 0.0;
                fee = (totalPaid * pct) / 100.0;
            } else {
                fee = matchedRule.getFeeAmount() != null ? matchedRule.getFeeAmount() : 0.0;
            }
            if (matchedRule.getDescription() != null && !matchedRule.getDescription().trim().isEmpty()) {
                notice = matchedRule.getDescription();
            }
        } else {
            // Fallback to platform settings default cancellation fee
            if ("PERCENTAGE".equalsIgnoreCase(settings.getCancellationFeeType())) {
                double pct = settings.getDefaultCancellationFee() != null ? settings.getDefaultCancellationFee() : 0.0;
                fee = (totalPaid * pct) / 100.0;
            } else {
                fee = settings.getDefaultCancellationFee() != null ? settings.getDefaultCancellationFee() : 0.0;
            }
        }

        // Boundary protections: Fee cannot be negative or exceed total amount
        fee = Math.max(0.0, Math.min(totalPaid, fee));
        double refund = Math.max(0.0, totalPaid - fee);

        return CancellationQuote.builder()
                .bookingId(booking.getId())
                .rideId(booking.getRide() != null ? booking.getRide().getId() : null)
                .seats(booking.getSeats())
                .totalPaid(totalPaid)
                .cancellationFee(fee)
                .refundAmount(refund)
                .hoursUntilDeparture(hoursUntilDeparture)
                .ruleApplied(ruleName)
                .policyNotice(notice)
                .isDriverCancellation(false)
                .isEligibleForRefund(refund > 0)
                .cancellationFeeEnabled(true)
                .build();
    }

    private double calculateHoursUntilDeparture(Booking booking) {
        if (booking.getRide() == null || booking.getRide().getDepartureTime() == null) {
            return 999.0;
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime departure = booking.getRide().getDepartureTime();
        if (now.isAfter(departure)) {
            return 0.0;
        }
        long minutes = Duration.between(now, departure).toMinutes();
        return Math.max(0.0, minutes / 60.0);
    }
}
