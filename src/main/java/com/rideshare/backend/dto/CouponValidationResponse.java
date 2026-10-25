package com.rideshare.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CouponValidationResponse {
    private boolean valid;
    private String code;
    private String title;
    private String description;
    private String discountType; // PERCENTAGE, FIXED
    private double discountValue;
    private double discountAmount;
    private double originalBaseFare;
    private double platformFee;
    private double finalPayable;
    private String fundingType; // PLATFORM, DRIVER
    private String message;
    private String errorCode; // INVALID_CODE, EXPIRED, DISABLED, USAGE_LIMIT_EXHAUSTED, USER_LIMIT_EXCEEDED, MIN_FARE_NOT_MET, FIRST_RIDE_ONLY, RIDE_NOT_FOUND
}
