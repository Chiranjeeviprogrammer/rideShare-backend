package com.rideshare.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "coupon_redemptions", indexes = {
    @Index(name = "idx_coupon_redemptions_coupon_id", columnList = "coupon_id"),
    @Index(name = "idx_coupon_redemptions_user_id", columnList = "user_id"),
    @Index(name = "idx_coupon_redemptions_booking_id", columnList = "booking_id"),
    @Index(name = "idx_coupon_redemptions_status", columnList = "status")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_coupon_redemption_booking", columnNames = {"coupon_id", "booking_id"})
})
public class CouponRedemption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "coupon_id", nullable = false)
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private Coupon coupon;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "user_id", nullable = false)
    @JsonIgnoreProperties({"password", "resetToken", "resetTokenExpiresAt", "hibernateLazyInitializer", "handler"})
    private User user;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "booking_id", nullable = false)
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private Booking booking;

    @Column(name = "coupon_code", nullable = false, length = 64)
    private String couponCode;

    @Column(name = "discount_amount", nullable = false)
    private Double discountAmount;

    @Column(name = "original_fare", nullable = false)
    private Double originalFare;

    @Column(name = "final_payable", nullable = false)
    private Double finalPayable;

    @Column(name = "funding_type", nullable = false, length = 32)
    @Builder.Default
    private String fundingType = "PLATFORM"; // "PLATFORM" or "DRIVER"

    @Column(name = "status", nullable = false, length = 32)
    @Builder.Default
    private String status = "APPLIED"; // "APPLIED", "COMPLETED", "CANCELLED_RESTORED"

    @Column(name = "redeemed_at")
    private LocalDateTime redeemedAt;

    @Column(name = "restored_at")
    private LocalDateTime restoredAt;

    @PrePersist
    public void prePersist() {
        if (redeemedAt == null) {
            redeemedAt = LocalDateTime.now();
        }
        if (status == null) {
            status = "APPLIED";
        }
        if (fundingType == null) {
            fundingType = "PLATFORM";
        }
    }
}
