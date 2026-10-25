package com.rideshare.backend.model;

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
@Table(name = "coupons", indexes = {
    @Index(name = "idx_coupons_code", columnList = "code", unique = true),
    @Index(name = "idx_coupons_is_active", columnList = "is_active"),
    @Index(name = "idx_coupons_valid_until", columnList = "valid_until")
})
public class Coupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String code; // Uppercase, trimmed, e.g. "WELCOME50"

    @Column(length = 255)
    private String title;

    @Column(length = 1000)
    private String description;

    @Column(name = "discount_type", nullable = false, length = 32)
    @Builder.Default
    private String discountType = "PERCENTAGE"; // "PERCENTAGE" or "FIXED"

    @Column(name = "discount_value", nullable = false)
    private Double discountValue; // e.g. 20.0 for 20%, or 50.0 for ₹50

    @Column(name = "max_discount_cap")
    private Double maxDiscountCap; // Maximum cap for percentage discounts, e.g. ₹100

    @Column(name = "min_ride_fare")
    @Builder.Default
    private Double minRideFare = 0.0; // Minimum gross base fare required

    @Column(name = "valid_from")
    private LocalDateTime validFrom;

    @Column(name = "valid_until")
    private LocalDateTime validUntil;

    @Column(name = "total_usage_limit")
    @Builder.Default
    private Integer totalUsageLimit = 0; // 0 or null = unlimited

    @Column(name = "per_user_limit")
    @Builder.Default
    private Integer perUserLimit = 1; // Default 1 per user

    @Column(name = "current_redemptions")
    @Builder.Default
    private Integer currentRedemptions = 0; // Authoritative usage count

    @Column(name = "first_ride_only")
    @Builder.Default
    private Boolean firstRideOnly = false; // Only users with 0 prior rides

    @Column(name = "funding_type", nullable = false, length = 32)
    @Builder.Default
    private String fundingType = "PLATFORM"; // "PLATFORM" or "DRIVER"

    @Column(name = "campaign_type", length = 64)
    @Builder.Default
    private String campaignType = "PROMOTIONAL"; // "PROMOTIONAL", "SEASONAL", "FIRST_RIDE", "REFERRAL"

    @Column(name = "eligible_user_group", length = 64)
    @Builder.Default
    private String eligibleUserGroup = "ALL"; // "ALL", "NEW_USERS", "STUDENTS"

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_by", length = 128)
    private String createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (code != null) {
            code = code.trim().toUpperCase();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
        if (currentRedemptions == null) {
            currentRedemptions = 0;
        }
        if (isActive == null) {
            isActive = true;
        }
        if (fundingType == null) {
            fundingType = "PLATFORM";
        }
        if (discountType == null) {
            discountType = "PERCENTAGE";
        }
        if (minRideFare == null) {
            minRideFare = 0.0;
        }
        if (perUserLimit == null) {
            perUserLimit = 1;
        }
        if (totalUsageLimit == null) {
            totalUsageLimit = 0;
        }
        if (firstRideOnly == null) {
            firstRideOnly = false;
        }
    }

    @PreUpdate
    public void preUpdate() {
        if (code != null) {
            code = code.trim().toUpperCase();
        }
        updatedAt = LocalDateTime.now();
    }
}
