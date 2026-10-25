package com.rideshare.backend.service;

import com.rideshare.backend.config.CommissionProperties;
import com.rideshare.backend.dto.CreateSettlementOrderRequest;
import com.rideshare.backend.dto.VerifySettlementRequest;
import com.rideshare.backend.model.Booking;
import com.rideshare.backend.model.CommissionLedger;
import com.rideshare.backend.model.User;
import com.rideshare.backend.repository.CommissionLedgerRepository;
import com.rideshare.backend.repository.UserRepository;
import com.rideshare.backend.security.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class CommissionService {

    @Autowired
    private CommissionProperties commissionProperties;

    @Autowired
    private CommissionLedgerRepository commissionLedgerRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private RazorpayService razorpayService;

    /**
     * Calculate commission based on configured rules.
     * Uses Math.round to 2 decimal places.
     */
    public Map<String, Double> calculateCommission(double grossFare) {
        double ratePercent = commissionProperties.getRatePercent();
        double fixedFee = commissionProperties.getFixedFee();
        String type = commissionProperties.getCalculationType();

        double commission = 0.0;
        if ("FIXED".equalsIgnoreCase(type)) {
            commission = fixedFee;
        } else if ("PERCENTAGE".equalsIgnoreCase(type)) {
            commission = (grossFare * ratePercent) / 100.0;
        } else {
            // PERCENT_PLUS_FIXED
            commission = ((grossFare * ratePercent) / 100.0) + fixedFee;
        }

        // Commission can never exceed gross fare
        commission = Math.min(grossFare, Math.max(0.0, commission));
        commission = Math.round(commission * 100.0) / 100.0;
        double driverNet = Math.round((grossFare - commission) * 100.0) / 100.0;

        return Map.of(
                "grossFare", grossFare,
                "ratePercent", ratePercent,
                "fixedFee", fixedFee,
                "commissionAmount", commission,
                "driverNetAmount", driverNet
        );
    }

    /**
     * Record cash collection and commission charge atomically.
     * Enforces idempotency via unique key COMM_CHARGE_BOOKING_{bookingId}.
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public CommissionLedger recordCashCollection(Booking booking, User driver) {
        String idempotencyKey = "COMM_CHARGE_BOOKING_" + booking.getId();

        Optional<CommissionLedger> existing = commissionLedgerRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        double grossFare = booking.getTotalAmount();
        double baseFare = booking.getBaseFare() > 0 ? booking.getBaseFare() : grossFare;
        double riderFee = booking.getRiderPlatformFee();
        double commAmount = booking.getDriverCommissionAmount();
        double driverNet = booking.getDriverNetEarnings() > 0 ? booking.getDriverNetEarnings() : (baseFare - commAmount);

        // Platform retention from cash collection = Rider Platform Fee + Driver Commission
        double platformOwed = Math.round((commAmount + riderFee) * 100.0) / 100.0;
        String ledgerStatus = (platformOwed <= 0.0) ? "SETTLED" : "OUTSTANDING";

        CommissionLedger ledger = CommissionLedger.builder()
                .driver(driver)
                .booking(booking)
                .ride(booking.getRide())
                .entryType("CHARGE")
                .grossFare(grossFare)
                .commissionRatePercent(booking.getDriverCommissionRateSnapshot() != null ? booking.getDriverCommissionRateSnapshot() : 0.0)
                .fixedFee(booking.getDriverCommissionFixedSnapshot() != null ? booking.getDriverCommissionFixedSnapshot() : 0.0)
                .commissionAmount(platformOwed)
                .driverNetAmount(driverNet)
                .status(ledgerStatus)
                .idempotencyKey(idempotencyKey)
                .createdAt(LocalDateTime.now())
                .notes("Platform fee (₹" + riderFee + ") & commission (₹" + commAmount + ") charge for cash booking " + (booking.getBookingCode() != null ? booking.getBookingCode() : ("#" + booking.getId())))
                .build();

        return commissionLedgerRepository.save(ledger);
    }

    /**
     * Return comprehensive commission ledger summary for a driver.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getDriverCommissionSummary(User driver) {
        Double totalCharged = commissionLedgerRepository.getTotalCommissionChargedByDriverId(driver.getId());
        Double settledAmount = commissionLedgerRepository.getSettledCommissionByDriverId(driver.getId());
        Double outstandingAmount = commissionLedgerRepository.getOutstandingCommissionByDriverId(driver.getId());
        Double totalCashGross = commissionLedgerRepository.getTotalCashGrossFareByDriverId(driver.getId());

        List<CommissionLedger> history = commissionLedgerRepository.findByDriverIdOrderByCreatedAtDesc(driver.getId());

        double threshold = commissionProperties.getOutstandingThreshold();
        boolean isPublishRestricted = commissionProperties.isEnforcePublishThreshold() &&
                outstandingAmount != null && outstandingAmount >= threshold;

        List<Map<String, Object>> historyList = new ArrayList<>();
        for (CommissionLedger c : history) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", c.getId());
            item.put("entryType", c.getEntryType());
            item.put("grossFare", c.getGrossFare());
            item.put("commissionAmount", c.getCommissionAmount());
            item.put("driverNetAmount", c.getDriverNetAmount());
            item.put("status", c.getStatus());
            item.put("bookingId", c.getBooking() != null ? c.getBooking().getId() : null);
            item.put("bookingCode", c.getBooking() != null ? c.getBooking().getBookingCode() : null);
            item.put("rideId", c.getRide() != null ? c.getRide().getId() : null);
            item.put("settlementReference", c.getSettlementReference());
            item.put("settledAt", c.getSettledAt());
            item.put("createdAt", c.getCreatedAt());
            item.put("notes", c.getNotes());
            historyList.add(item);
        }

        Map<String, Object> summary = new HashMap<>();
        summary.put("driverId", driver.getId());
        summary.put("driverName", driver.getName());
        summary.put("totalCashGrossCollected", totalCashGross != null ? totalCashGross : 0.0);
        summary.put("totalCommissionCharged", totalCharged != null ? totalCharged : 0.0);
        summary.put("settledCommission", settledAmount != null ? settledAmount : 0.0);
        summary.put("outstandingCommission", outstandingAmount != null ? outstandingAmount : 0.0);
        summary.put("driverNetEarnings", Math.max(0.0, (totalCashGross != null ? totalCashGross : 0.0) - (totalCharged != null ? totalCharged : 0.0)));
        summary.put("outstandingThreshold", threshold);
        summary.put("isPublishRestricted", isPublishRestricted);
        summary.put("commissionRatePercent", commissionProperties.getRatePercent());
        summary.put("fixedFee", commissionProperties.getFixedFee());
        summary.put("calculationType", commissionProperties.getCalculationType());
        summary.put("history", historyList);

        return summary;
    }

    /**
     * Check whether driver is prohibited from publishing rides due to outstanding commission.
     */
    public boolean isDriverRestrictedFromPublishing(User driver) {
        if (!commissionProperties.isEnforcePublishThreshold()) {
            return false;
        }
        Double outstanding = commissionLedgerRepository.getOutstandingCommissionByDriverId(driver.getId());
        return outstanding != null && outstanding >= commissionProperties.getOutstandingThreshold();
    }

    /**
     * Create an online payment order for driver commission settlement.
     */
    @Transactional
    public ResponseEntity<?> createSettlementOrder(String token, CreateSettlementOrderRequest request) {
        String email = jwtUtil.extractEmail(token.substring(7));
        User driver = userRepository.findByEmail(email);
        if (driver == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "User not found"));
        }

        Double outstanding = commissionLedgerRepository.getOutstandingCommissionByDriverId(driver.getId());
        if (outstanding == null || outstanding <= 0.0) {
            return ResponseEntity.badRequest().body(Map.of("message", "No outstanding commission to settle. Outstanding balance is ₹0.00."));
        }

        double settleAmount = (request.getAmount() != null && request.getAmount() > 0)
                ? Math.min(request.getAmount(), outstanding)
                : outstanding;

        settleAmount = Math.round(settleAmount * 100.0) / 100.0;

        String orderId = "COMM_ORD_" + UUID.randomUUID().toString().substring(0, 10).toUpperCase();
        Map<String, Object> gatewayOrder;
        try {
            gatewayOrder = razorpayService.createOrder(settleAmount, orderId, "INR");
        } catch (RazorpayService.PaymentConfigurationException | RazorpayService.PaymentProviderException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "success", false,
                    "message", ex.getMessage(),
                    "status", "PAYMENT_UNAVAILABLE"
            ));
        }
        String gatewayOrderId = gatewayOrder.get("gatewayOrderId").toString();

        // Record settlement pending entry in ledger
        String idempotencyKey = "COMM_SETTLE_" + orderId;
        CommissionLedger settlementEntry = CommissionLedger.builder()
                .driver(driver)
                .entryType("SETTLEMENT")
                .grossFare(settleAmount)
                .commissionRatePercent(0.0)
                .fixedFee(0.0)
                .commissionAmount(settleAmount)
                .driverNetAmount(0.0)
                .status("INITIATED")
                .idempotencyKey(idempotencyKey)
                .settlementReference(gatewayOrderId)
                .createdAt(LocalDateTime.now())
                .notes("Settlement payment initiated via " + (request.getPaymentMethod() != null ? request.getPaymentMethod() : "ONLINE"))
                .build();

        commissionLedgerRepository.save(settlementEntry);

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "success", true,
                "orderId", orderId,
                "gatewayOrderId", gatewayOrderId,
                "amount", settleAmount,
                "currency", "INR",
                "status", "INITIATED",
                "outstandingBefore", outstanding,
                "remainingOutstandingAfterSettlement", Math.max(0.0, outstanding - settleAmount)
        ));
    }

    /**
     * Verify settlement payment through payment provider and update ledger.
     * Retains outstanding balance on failure/invalid signature.
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public ResponseEntity<?> verifySettlement(String token, VerifySettlementRequest request) {
        String email = jwtUtil.extractEmail(token.substring(7));
        User driver = userRepository.findByEmail(email);
        if (driver == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "User not found"));
        }

        if (request.getGatewayOrderId() == null || request.getGatewayPaymentId() == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "Gateway order ID and payment ID are required."));
        }

        String idempotencyKey = "COMM_SETTLE_" + request.getOrderId();
        Optional<CommissionLedger> optSettlement = commissionLedgerRepository.findByIdempotencyKey(idempotencyKey);
        if (optSettlement.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Settlement order not found."));
        }

        CommissionLedger settlement = optSettlement.get();

        if (settlement.getDriver() == null || !driver.getId().equals(settlement.getDriver().getId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Not authorized for this settlement order."));
        }

        if (!request.getGatewayOrderId().equals(settlement.getSettlementReference())) {
            return ResponseEntity.badRequest().body(Map.of("message", "Settlement gateway order does not match the server record."));
        }

        // Idempotency: if already verified, return ok
        if ("VERIFIED".equals(settlement.getStatus())) {
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Commission settlement already verified.",
                    "settlementReference", settlement.getSettlementReference()
            ));
        }

        // Validate the actual Razorpay checkout signature against the server-created order.
        boolean signatureValid = razorpayService.verifyPaymentSignature(
                settlement.getSettlementReference(), request.getGatewayPaymentId(), request.getGatewaySignature());
        if (!signatureValid) {
            settlement.setStatus("FAILED");
            settlement.setNotes("Payment signature verification failed. Outstanding balance retained.");
            commissionLedgerRepository.save(settlement);
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Invalid settlement payment signature. Outstanding commission balance retained.",
                    "status", "FAILED"
            ));
        }

        // Mark settlement entry as VERIFIED
        settlement.setStatus("VERIFIED");
        settlement.setSettlementReference(request.getGatewayPaymentId());
        settlement.setSettledAt(LocalDateTime.now());
        commissionLedgerRepository.save(settlement);

        // Reconcile and mark oldest outstanding CHARGE entries as SETTLED up to settleAmount
        double remainingToClear = settlement.getCommissionAmount();
        List<CommissionLedger> outstandingCharges = commissionLedgerRepository
                .findByDriverIdAndEntryTypeAndStatus(driver.getId(), "CHARGE", "OUTSTANDING");

        // Sort ascending by creation time to clear oldest first
        outstandingCharges.sort(Comparator.comparing(CommissionLedger::getCreatedAt));

        for (CommissionLedger charge : outstandingCharges) {
            if (remainingToClear <= 0.0) break;
            if (charge.getCommissionAmount() <= remainingToClear) {
                remainingToClear -= charge.getCommissionAmount();
                charge.setStatus("SETTLED");
                charge.setSettlementReference(settlement.getSettlementReference());
                charge.setSettledAt(LocalDateTime.now());
                commissionLedgerRepository.save(charge);
            }
        }

        notificationService.sendNotification(driver, "Commission Settled",
                "Successfully settled ₹" + settlement.getCommissionAmount() + " in platform commissions. Ref: " + request.getGatewayPaymentId());

        Double currentOutstanding = commissionLedgerRepository.getOutstandingCommissionByDriverId(driver.getId());

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Commission settlement successfully verified and reconciled!",
                "settledAmount", settlement.getCommissionAmount(),
                "paymentReference", request.getGatewayPaymentId(),
                "outstandingBalance", currentOutstanding != null ? currentOutstanding : 0.0
        ));
    }

    /**
     * Administrative reporting: View commission balances and settlement history across all drivers.
     */
    public ResponseEntity<?> getAdminCommissionReport(User admin) {
        if (!"ADMIN".equals(admin.getRole())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Administrative access required."));
        }

        List<CommissionLedger> allEntries = commissionLedgerRepository.findAllByOrderByCreatedAtDesc();
        double totalPlatformEarned = 0.0;
        double totalSettled = 0.0;
        double totalOutstanding = 0.0;

        for (CommissionLedger c : allEntries) {
            if ("CHARGE".equals(c.getEntryType())) {
                totalPlatformEarned += c.getCommissionAmount();
                if ("OUTSTANDING".equals(c.getStatus())) {
                    totalOutstanding += c.getCommissionAmount();
                } else if ("SETTLED".equals(c.getStatus())) {
                    totalSettled += c.getCommissionAmount();
                }
            }
        }

        Map<String, Object> report = new HashMap<>();
        report.put("success", true);
        report.put("totalPlatformCommissionEarned", Math.round(totalPlatformEarned * 100.0) / 100.0);
        report.put("totalCommissionSettled", Math.round(totalSettled * 100.0) / 100.0);
        report.put("totalCommissionOutstanding", Math.round(totalOutstanding * 100.0) / 100.0);
        report.put("totalLedgerEntries", allEntries.size());
        report.put("configuredRatePercent", commissionProperties.getRatePercent());
        report.put("configuredFixedFee", commissionProperties.getFixedFee());
        report.put("publishBlockingThreshold", commissionProperties.getOutstandingThreshold());
        report.put("ledger", allEntries);

        return ResponseEntity.ok(report);
    }
}
