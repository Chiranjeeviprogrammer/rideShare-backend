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
public class CouponOfferDto {
    private Long id;
    private String code;
    private String title;
    private String description;
    private String discountType; // PERCENTAGE, FIXED
    private Double discountValue;
    private Double maxDiscountCap;
    private Double minRideFare;
    private LocalDateTime validUntil;
    private String fundingType;
    private String campaignType;
    private Boolean firstRideOnly;
    private Boolean isEligibleForUser;
    private String ineligibleReason;
}
