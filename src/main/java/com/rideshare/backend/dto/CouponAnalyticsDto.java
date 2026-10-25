package com.rideshare.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CouponAnalyticsDto {
    private long totalCoupons;
    private long activeCoupons;
    private long expiredCoupons;
    private long totalRedemptions;
    private double totalDiscountExpenditure;
    private double platformFundedExpenditure;
    private double driverFundedExpenditure;
}
