package com.rideshare.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CouponDto {
    private Long id;
    private String code;
    private String title;
    private String description;
    private String discountType; // PERCENTAGE, FIXED
    private Double discountValue;
    private Double maxDiscountCap;
    private Double minRideFare;
    private LocalDateTime validFrom;
    private LocalDateTime validUntil;
    private Integer totalUsageLimit;
    private Integer perUserLimit;
    private Integer currentRedemptions;
    private Boolean firstRideOnly;
    private String fundingType; // PLATFORM, DRIVER
    private String campaignType;
    private String eligibleUserGroup;
    private Boolean isActive;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Computed / Metric helpers for UI
    private Double totalDiscountExpended;
    private Boolean isExpired;
}
