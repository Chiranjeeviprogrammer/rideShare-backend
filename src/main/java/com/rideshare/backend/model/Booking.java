package com.rideshare.backend.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "bookings", indexes = {
    @Index(name = "idx_bookings_passenger_id", columnList = "passenger_id"),
    @Index(name = "idx_bookings_ride_id", columnList = "ride_id"),
    @Index(name = "idx_bookings_status", columnList = "status"),
    @Index(name = "idx_bookings_code", columnList = "booking_code")
})
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "ride_id", nullable = false)
    private Ride ride;

    @ManyToOne
    @JoinColumn(name = "passenger_id", nullable = false)
    private User passenger;

    @Column(nullable = false)
    private int seats;

    @Column(nullable = false)
    private double totalAmount;

    @Column(name = "base_fare")
    @Builder.Default
    private Double baseFare = 0.0;

    @Column(name = "platform_fee")
    @Builder.Default
    private Double platformFee = 0.0;

    // --- Independent Rider Platform Fee Snapshot ---
    @Column(name = "rider_platform_fee")
    @Builder.Default
    private Double riderPlatformFee = 0.0;

    @Column(name = "rider_fee_enabled_snapshot")
    @Builder.Default
    private Boolean riderFeeEnabledSnapshot = false;

    @Column(name = "rider_fee_type_snapshot")
    @Builder.Default
    private String riderFeeTypeSnapshot = "FIXED";

    @Column(name = "rider_fee_rate_snapshot")
    @Builder.Default
    private Double riderFeeRateSnapshot = 0.0;

    // --- Independent Driver Commission Snapshot ---
    @Column(name = "driver_commission_amount")
    @Builder.Default
    private Double driverCommissionAmount = 0.0;

    @Column(name = "driver_commission_enabled_snapshot")
    @Builder.Default
    private Boolean driverCommissionEnabledSnapshot = false;

    @Column(name = "driver_commission_type_snapshot")
    @Builder.Default
    private String driverCommissionTypeSnapshot = "PERCENTAGE";

    @Column(name = "driver_commission_rate_snapshot")
    @Builder.Default
    private Double driverCommissionRateSnapshot = 0.0;

    @Column(name = "driver_commission_fixed_snapshot")
    @Builder.Default
    private Double driverCommissionFixedSnapshot = 0.0;

    // --- Authoritative Driver Net Earnings ---
    @Column(name = "driver_net_earnings")
    @Builder.Default
    private Double driverNetEarnings = 0.0;

    @Column(name = "pricing_snapshot_timestamp")
    private LocalDateTime pricingSnapshotTimestamp;

    @Column(name = "discount")
    @Builder.Default
    private Double discount = 0.0;

    // --- Coupon & Promotional Discount Snapshot ---
    @Column(name = "coupon_code", length = 64)
    private String couponCode;

    @Column(name = "coupon_discount_amount")
    @Builder.Default
    private Double couponDiscountAmount = 0.0;

    @Column(name = "coupon_discount_type", length = 32)
    private String couponDiscountType;

    @Column(name = "coupon_funding_type", length = 32)
    @Builder.Default
    private String couponFundingType = "PLATFORM";


    @Column(name = "tax")
    @Builder.Default
    private Double tax = 0.0;

    @Column(name = "taxable_amount")
    @Builder.Default
    private Double taxableAmount = 0.0;

    @Column(name = "gst_rate_snapshot")
    @Builder.Default
    private Double gstRateSnapshot = 0.0;

    @Column(name = "cgst_amount")
    @Builder.Default
    private Double cgstAmount = 0.0;

    @Column(name = "sgst_amount")
    @Builder.Default
    private Double sgstAmount = 0.0;

    @Column(name = "igst_amount")
    @Builder.Default
    private Double igstAmount = 0.0;

    @Column(name = "is_interstate")
    @Builder.Default
    private Boolean isInterstate = false;

    @Column(name = "place_of_supply")
    private String placeOfSupply;

    @Column(name = "prices_include_gst_snapshot")
    @Builder.Default
    private Boolean pricesIncludeGstSnapshot = true;

    @Column(name = "invoice_number")
    private String invoiceNumber;

    @Column(name = "driver_amount")
    @Builder.Default
    private Double driverAmount = 0.0;

    public double getBaseFare() {
        return baseFare != null ? baseFare : 0.0;
    }

    public double getPlatformFee() {
        return platformFee != null ? platformFee : 0.0;
    }

    public double getRiderPlatformFee() {
        return riderPlatformFee != null ? riderPlatformFee : 0.0;
    }

    public double getDriverCommissionAmount() {
        return driverCommissionAmount != null ? driverCommissionAmount : 0.0;
    }

    public double getDriverNetEarnings() {
        return driverNetEarnings != null ? driverNetEarnings : 0.0;
    }

    public double getDiscount() {
        return discount != null ? discount : 0.0;
    }

    public double getTax() {
        return tax != null ? tax : 0.0;
    }

    public double getDriverAmount() {
        return driverAmount != null ? driverAmount : 0.0;
    }

    @Column(name = "commission_rate_snapshot")
    @Builder.Default
    private Double commissionRateSnapshot = 0.0;

    @Column(name = "commission_fixed_fee_snapshot")
    @Builder.Default
    private Double commissionFixedFeeSnapshot = 0.0;

    @Column(name = "commission_enabled_snapshot")
    @Builder.Default
    private Boolean commissionEnabledSnapshot = false;

    @Column(name = "fee_type_snapshot")
    @Builder.Default
    private String feeTypeSnapshot = "ZERO_COMMISSION";

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(nullable = false)
    private String status; // PENDING, CONFIRMED, CANCELLED, COMPLETED, EXPIRED

    // --- Cancellation Audit & Fee Tracking ---
    @Column(name = "cancellation_fee")
    @Builder.Default
    private Double cancellationFee = 0.0;

    @Column(name = "cancellation_refund_amount")
    @Builder.Default
    private Double cancellationRefundAmount = 0.0;

    @Column(name = "cancellation_rule_applied")
    private String cancellationRuleApplied;

    @Column(name = "cancelled_by")
    private String cancelledBy; // PASSENGER, DRIVER, ADMIN, SYSTEM

    @Column(name = "cancellation_reason")
    private String cancellationReason;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    private String pickupAddress;
    private String dropoffAddress;

    @Builder.Default
    private String paymentMethod = "ONLINE"; // ONLINE, CASH, PAY_AFTER_RIDE

    @Builder.Default
    private String paymentStatus = "PENDING"; // PAID, UNPAID, PENDING_CASH, PENDING, FAILED, REFUNDED

    private String transactionReference;

    @Column(name = "booking_code", length = 32, unique = true)
    private String bookingCode;

    private LocalDateTime bookedAt;
    private LocalDateTime updatedAt;

    private static final String ALPHANUMERIC_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final java.security.SecureRandom SECURE_RANDOM = new java.security.SecureRandom();

    public static String generateBookingCode() {
        // Exactly 12 alphanumeric characters: "BK" prefix + 10 random characters
        StringBuilder sb = new StringBuilder("BK");
        for (int i = 0; i < 10; i++) {
            sb.append(ALPHANUMERIC_CHARS.charAt(SECURE_RANDOM.nextInt(ALPHANUMERIC_CHARS.length())));
        }
        return sb.toString();
    }

    @PrePersist
    public void prePersist() {
        if (this.bookingCode == null || this.bookingCode.trim().isEmpty()) {
            this.bookingCode = generateBookingCode();
        }
        if (this.bookedAt == null) {
            this.bookedAt = LocalDateTime.now();
        }
        if (this.updatedAt == null) {
            this.updatedAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
