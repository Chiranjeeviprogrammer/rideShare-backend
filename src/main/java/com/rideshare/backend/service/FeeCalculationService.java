package com.rideshare.backend.service;

import com.rideshare.backend.model.Booking;
import com.rideshare.backend.model.GstSettings;
import com.rideshare.backend.model.PlatformSettings;
import com.rideshare.backend.model.Ride;
import com.rideshare.backend.repository.PlatformSettingsRepository;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Authoritative Centralized Backend Fee Calculation Engine.
 *
 * Guarantees:
 * 1. Independent Rider Platform Fee and Driver Commission calculations.
 * 2. Decimal-safe arithmetic using BigDecimal with RoundingMode.HALF_UP.
 * 3. Driver commission is computed EXCLUSIVELY on eligible baseFare (never on rider fees, taxes, or tips).
 * 4. Freezes immutable pricing snapshots into Bookings.
 * 5. Default state: 0% Driver Commission (disabled), ₹0 Rider Fee (disabled).
 */
@Service
public class FeeCalculationService {

    @Autowired
    private PlatformSettingsRepository platformSettingsRepository;

    @Autowired(required = false)
    private GstSettingsService gstSettingsService;

    @Autowired(required = false)
    private GstCalculationService gstCalculationService;

    @Autowired(required = false)
    private RedisCacheService redisCacheService;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FeeCalculationResult {
        private BigDecimal baseFare;
        private BigDecimal farePerSeat;
        private int seats;

        // Rider Fee Breakdown
        private boolean riderFeeEnabled;
        private String riderFeeType;
        private double riderFeeRate;
        private BigDecimal riderPlatformFee;

        // Driver Commission Breakdown
        private boolean driverCommissionEnabled;
        private String driverCommissionType;
        private double driverCommissionRatePercent;
        private double driverCommissionFixedAmount;
        private BigDecimal driverCommissionAmount;
        private BigDecimal driverNetEarnings;

        // GST Breakdown
        @Builder.Default
        private boolean gstEnabled = true;
        @Builder.Default
        private boolean pricesIncludeGst = true;
        @Builder.Default
        private double gstRate = 5.0;
        private BigDecimal taxableAmount;
        private BigDecimal cgstRate;
        private BigDecimal cgstAmount;
        private BigDecimal sgstRate;
        private BigDecimal sgstAmount;
        private BigDecimal igstRate;
        private BigDecimal igstAmount;
        private boolean isInterstate;
        private String placeOfSupply;
        private String hsnSacCode;
        private String supplierGstin;

        // Totals
        private BigDecimal tax;
        private BigDecimal discount;
        private BigDecimal totalAmount;
        private BigDecimal platformRetention;
        private String couponCode;
        private String couponDiscountType;
        private String couponFundingType;
        @Builder.Default
        private String currency = "INR";


        public Map<String, Object> toMap(Long rideId) {
            Map<String, Object> quote = new HashMap<>();
            quote.put("rideId", rideId);
            quote.put("seats", seats);
            quote.put("farePerSeat", farePerSeat != null ? farePerSeat.doubleValue() : 0.0);
            quote.put("baseFare", baseFare != null ? baseFare.doubleValue() : 0.0);

            // Rider fee
            quote.put("riderFeeEnabled", riderFeeEnabled);
            quote.put("riderFeeType", riderFeeType);
            quote.put("riderFeeRate", riderFeeRate);
            quote.put("riderPlatformFee", riderPlatformFee != null ? riderPlatformFee.doubleValue() : 0.0);
            quote.put("platformFee", riderPlatformFee != null ? riderPlatformFee.doubleValue() : 0.0); // Backwards-compatible alias

            // Driver commission
            quote.put("driverCommissionEnabled", driverCommissionEnabled);
            quote.put("driverCommissionType", driverCommissionType);
            quote.put("driverCommissionRatePercent", driverCommissionRatePercent);
            quote.put("driverCommissionFixedAmount", driverCommissionFixedAmount);
            quote.put("driverCommissionAmount", driverCommissionAmount != null ? driverCommissionAmount.doubleValue() : 0.0);
            quote.put("driverNetEarnings", driverNetEarnings != null ? driverNetEarnings.doubleValue() : 0.0);
            quote.put("driverAmount", driverNetEarnings != null ? driverNetEarnings.doubleValue() : 0.0); // Backwards-compatible alias

            // GST breakdown
            quote.put("gstEnabled", gstEnabled);
            quote.put("pricesIncludeGst", pricesIncludeGst);
            quote.put("gstRate", gstRate);
            quote.put("taxableAmount", taxableAmount != null ? taxableAmount.doubleValue() : 0.0);
            quote.put("cgstRate", cgstRate != null ? cgstRate.doubleValue() : 0.0);
            quote.put("cgstAmount", cgstAmount != null ? cgstAmount.doubleValue() : 0.0);
            quote.put("sgstRate", sgstRate != null ? sgstRate.doubleValue() : 0.0);
            quote.put("sgstAmount", sgstAmount != null ? sgstAmount.doubleValue() : 0.0);
            quote.put("igstRate", igstRate != null ? igstRate.doubleValue() : 0.0);
            quote.put("igstAmount", igstAmount != null ? igstAmount.doubleValue() : 0.0);
            quote.put("isInterstate", isInterstate);
            quote.put("placeOfSupply", placeOfSupply);
            quote.put("hsnSacCode", hsnSacCode != null ? hsnSacCode : "996412");
            quote.put("supplierGstin", supplierGstin != null ? supplierGstin : "");

            quote.put("tax", tax != null ? tax.doubleValue() : 0.0);
            quote.put("discount", discount != null ? discount.doubleValue() : 0.0);
            quote.put("totalAmount", totalAmount != null ? totalAmount.doubleValue() : 0.0);
            quote.put("platformRetention", platformRetention != null ? platformRetention.doubleValue() : 0.0);
            quote.put("currency", currency);
            quote.put("couponCode", couponCode);
            quote.put("couponDiscountType", couponDiscountType);
            quote.put("couponFundingType", couponFundingType != null ? couponFundingType : "PLATFORM");

            // Legacy backward-compatible keys
            quote.put("commissionEnabled", driverCommissionEnabled);
            quote.put("commissionRatePercent", driverCommissionRatePercent);
            quote.put("commissionFixedFee", driverCommissionFixedAmount);
            quote.put("platformFeeType", riderFeeType);

            return quote;
        }
    }

    /**
     * Authoritative calculation from current database settings for a ride with optional coupon discount.
     */
    public FeeCalculationResult calculateQuote(Ride ride, int seats, BigDecimal discount, String couponCode, String couponDiscountType, String couponFundingType) {
        double fare = (ride != null && ride.getFare() != null) ? ride.getFare() : 0.0;
        PlatformSettings settings = getActivePlatformSettings();
        String location = (ride != null && ride.getPickupLocation() != null) ? ride.getPickupLocation() : null;
        return calculateQuote(fare, seats, settings, discount, couponCode, couponDiscountType, couponFundingType, location);
    }

    public FeeCalculationResult calculateQuote(Ride ride, int seats) {
        return calculateQuote(ride, seats, BigDecimal.ZERO, null, null, "PLATFORM");
    }

    public FeeCalculationResult calculateQuote(double farePerSeatVal, int seatsVal, PlatformSettings settings) {
        return calculateQuote(farePerSeatVal, seatsVal, settings, BigDecimal.ZERO, null, null, "PLATFORM", null);
    }

    public FeeCalculationResult calculateQuote(double farePerSeatVal, int seatsVal, PlatformSettings settings,
                                              BigDecimal discountVal, String couponCode, String couponDiscountType, String couponFundingType) {
        return calculateQuote(farePerSeatVal, seatsVal, settings, discountVal, couponCode, couponDiscountType, couponFundingType, null);
    }

    /**
     * Pure calculation logic with given farePerSeat, seats, settings, promotional discount, and trip location for GST.
     */
    public FeeCalculationResult calculateQuote(double farePerSeatVal, int seatsVal, PlatformSettings settings,
                                              BigDecimal discountVal, String couponCode, String couponDiscountType, String couponFundingType,
                                              String tripLocation) {
        int requestedSeats = Math.max(1, seatsVal);
        BigDecimal farePerSeat = BigDecimal.valueOf(Math.max(0.0, farePerSeatVal)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal baseFare = farePerSeat.multiply(BigDecimal.valueOf(requestedSeats)).setScale(2, RoundingMode.HALF_UP);

        if (settings == null) {
            settings = PlatformSettings.builder().build();
        }

        // --- 1. RIDER PLATFORM FEE CALCULATION ---
        boolean riderFeeEnabled = Boolean.TRUE.equals(settings.getRiderFeeEnabled())
                || (Boolean.TRUE.equals(settings.getCommissionEnabled()) &&
                    ((settings.getPlatformFeeAmount() != null && settings.getPlatformFeeAmount() > 0) ||
                     (settings.getRiderFeeAmount() != null && settings.getRiderFeeAmount() > 0) ||
                     (settings.getRiderFeePercent() != null && settings.getRiderFeePercent() > 0)));

        String riderFeeType = settings.getRiderFeeType();
        if (riderFeeType == null && settings.getPlatformFeeType() != null) {
            riderFeeType = settings.getPlatformFeeType();
        }
        if (riderFeeType == null) {
            riderFeeType = "FIXED";
        }
        riderFeeType = riderFeeType.toUpperCase();

        double riderRate = 0.0;
        BigDecimal riderPlatformFee = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        if (riderFeeEnabled) {
            if ("PERCENTAGE".equalsIgnoreCase(riderFeeType)) {
                riderRate = settings.getRiderFeePercent() != null && settings.getRiderFeePercent() > 0 ?
                        settings.getRiderFeePercent() :
                        (settings.getPlatformFeeAmount() != null ? Math.max(0.0, settings.getPlatformFeeAmount()) : 0.0);
                BigDecimal pct = BigDecimal.valueOf(riderRate).setScale(4, RoundingMode.HALF_UP);
                riderPlatformFee = baseFare.multiply(pct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            } else {
                // FIXED amount
                riderRate = settings.getRiderFeeAmount() != null && settings.getRiderFeeAmount() > 0 ?
                        settings.getRiderFeeAmount() :
                        (settings.getPlatformFeeAmount() != null ? Math.max(0.0, settings.getPlatformFeeAmount()) : 0.0);
                riderPlatformFee = BigDecimal.valueOf(riderRate).setScale(2, RoundingMode.HALF_UP);
            }
        }
        riderPlatformFee = riderPlatformFee.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);

        // --- 2. DRIVER COMMISSION CALCULATION ---
        // Commission is calculated EXCLUSIVELY on eligible baseFare, never on rider fees or taxes!
        boolean driverCommissionEnabled = Boolean.TRUE.equals(settings.getDriverCommissionEnabled())
                || (Boolean.TRUE.equals(settings.getCommissionEnabled()) &&
                    ((settings.getCommissionRatePercent() != null && settings.getCommissionRatePercent() > 0) ||
                     (settings.getCommissionFixedFee() != null && settings.getCommissionFixedFee() > 0) ||
                     (settings.getDriverCommissionRatePercent() != null && settings.getDriverCommissionRatePercent() > 0) ||
                     (settings.getDriverCommissionFixedAmount() != null && settings.getDriverCommissionFixedAmount() > 0)));

        double commRatePercent = settings.getDriverCommissionRatePercent() != null && settings.getDriverCommissionRatePercent() > 0 ?
                settings.getDriverCommissionRatePercent() :
                (settings.getCommissionRatePercent() != null ? Math.max(0.0, settings.getCommissionRatePercent()) : 0.0);
        double commFixedFee = settings.getDriverCommissionFixedAmount() != null && settings.getDriverCommissionFixedAmount() > 0 ?
                settings.getDriverCommissionFixedAmount() :
                (settings.getCommissionFixedFee() != null ? Math.max(0.0, settings.getCommissionFixedFee()) : 0.0);

        String driverCommType = settings.getDriverCommissionType();
        if (driverCommType == null || "PERCENTAGE".equalsIgnoreCase(driverCommType)) {
            if (commFixedFee > 0.0 && commRatePercent > 0.0) {
                driverCommType = "PERCENT_PLUS_FIXED";
            } else if (commFixedFee > 0.0 && commRatePercent == 0.0) {
                driverCommType = "FIXED";
            } else {
                driverCommType = "PERCENTAGE";
            }
        }
        driverCommType = driverCommType.toUpperCase();

        BigDecimal driverCommissionAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        if (driverCommissionEnabled) {
            if ("FIXED".equalsIgnoreCase(driverCommType)) {
                driverCommissionAmount = BigDecimal.valueOf(commFixedFee).setScale(2, RoundingMode.HALF_UP);
            } else if ("PERCENT_PLUS_FIXED".equalsIgnoreCase(driverCommType)) {
                BigDecimal pct = BigDecimal.valueOf(commRatePercent).setScale(4, RoundingMode.HALF_UP);
                BigDecimal pctPortion = baseFare.multiply(pct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                driverCommissionAmount = pctPortion.add(BigDecimal.valueOf(commFixedFee)).setScale(2, RoundingMode.HALF_UP);
            } else {
                // Default: PERCENTAGE
                BigDecimal pct = BigDecimal.valueOf(commRatePercent).setScale(4, RoundingMode.HALF_UP);
                driverCommissionAmount = baseFare.multiply(pct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            }
            // Commission can never be negative and cannot exceed eligible base fare
            driverCommissionAmount = driverCommissionAmount.max(BigDecimal.ZERO).min(baseFare).setScale(2, RoundingMode.HALF_UP);
        }

        // --- 3. GST CALCULATION ---
        GstSettings gstSettings = (gstSettingsService != null) ? gstSettingsService.getActiveSettings() : null;
        GstCalculationService.GstCalculationResult gst = null;
        BigDecimal grossSubject = baseFare.add(riderPlatformFee);

        if (gstCalculationService != null && gstSettings != null && Boolean.TRUE.equals(gstSettings.getIsEnabled())) {
            gst = gstCalculationService.calculate(grossSubject, tripLocation, gstSettings);
        }

        boolean gstEnabled = false;
        boolean pricesIncludeGst = true;
        double gstRate = 0.0;
        BigDecimal tax = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal taxableAmount = grossSubject;
        BigDecimal cgstRate = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal cgstAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal sgstRate = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal sgstAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal igstRate = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal igstAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        boolean isInterstate = false;
        String placeOfSupply = (gstSettings != null && gstSettings.getBusinessState() != null) ? gstSettings.getBusinessState() : "Karnataka";
        String hsnSacCode = (gstSettings != null && gstSettings.getHsnSacCode() != null) ? gstSettings.getHsnSacCode() : "996412";
        String supplierGstin = (gstSettings != null && gstSettings.getGstin() != null) ? gstSettings.getGstin() : "";

        if (gst != null && gst.isGstEnabled()) {
            gstEnabled = true;
            pricesIncludeGst = gst.isPricesIncludeGst();
            gstRate = gst.getGstRate();
            tax = gst.getTotalTaxAmount();
            taxableAmount = gst.getTaxableAmount();
            cgstRate = gst.getCgstRate();
            cgstAmount = gst.getCgstAmount();
            sgstRate = gst.getSgstRate();
            sgstAmount = gst.getSgstAmount();
            igstRate = gst.getIgstRate();
            igstAmount = gst.getIgstAmount();
            isInterstate = gst.isInterstate();
            placeOfSupply = gst.getPlaceOfSupply();
            hsnSacCode = gst.getHsnSacCode();
            supplierGstin = gst.getSupplierGstin();
        }

        // --- 4. TOTALS & NET EARNINGS ---
        BigDecimal discount = (discountVal != null ? discountVal : BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        String fundingType = (couponFundingType != null && !couponFundingType.trim().isEmpty()) ? couponFundingType.toUpperCase() : "PLATFORM";

        BigDecimal grossRiderTotal;
        if (gstEnabled && !pricesIncludeGst) {
            // Exclusive: base + rider fee + tax
            grossRiderTotal = taxableAmount.add(tax).setScale(2, RoundingMode.HALF_UP);
        } else {
            // Inclusive: total already includes tax
            grossRiderTotal = grossSubject.setScale(2, RoundingMode.HALF_UP);
        }
        discount = discount.min(grossRiderTotal).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);

        BigDecimal totalRiderPayable = grossRiderTotal.subtract(discount).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);

        // Explicit rule: Driver earnings are NEVER reduced by platform-funded discounts!
        BigDecimal driverNetEarnings;
        BigDecimal platformRetention;
        if ("DRIVER".equalsIgnoreCase(fundingType)) {
            // Driver explicitly funded: discount reduces driver base
            BigDecimal discountedDriverBase = baseFare.subtract(discount).max(BigDecimal.ZERO);
            driverNetEarnings = discountedDriverBase.subtract(driverCommissionAmount).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
            platformRetention = riderPlatformFee.add(driverCommissionAmount).setScale(2, RoundingMode.HALF_UP);
        } else {
            // Default: PLATFORM FUNDED
            // Driver earnings are completely untouched!
            driverNetEarnings = baseFare.subtract(driverCommissionAmount).setScale(2, RoundingMode.HALF_UP);
            // Platform absorbs the discount
            platformRetention = riderPlatformFee.add(driverCommissionAmount).subtract(discount).setScale(2, RoundingMode.HALF_UP);
        }

        return FeeCalculationResult.builder()
                .baseFare(baseFare)
                .farePerSeat(farePerSeat)
                .seats(requestedSeats)
                .riderFeeEnabled(riderFeeEnabled)
                .riderFeeType(riderFeeType)
                .riderFeeRate(riderRate)
                .riderPlatformFee(riderPlatformFee)
                .driverCommissionEnabled(driverCommissionEnabled)
                .driverCommissionType(driverCommType)
                .driverCommissionRatePercent(commRatePercent)
                .driverCommissionFixedAmount(commFixedFee)
                .driverCommissionAmount(driverCommissionAmount)
                .driverNetEarnings(driverNetEarnings)
                .gstEnabled(gstEnabled)
                .pricesIncludeGst(pricesIncludeGst)
                .gstRate(gstRate)
                .taxableAmount(taxableAmount)
                .cgstRate(cgstRate)
                .cgstAmount(cgstAmount)
                .sgstRate(sgstRate)
                .sgstAmount(sgstAmount)
                .igstRate(igstRate)
                .igstAmount(igstAmount)
                .isInterstate(isInterstate)
                .placeOfSupply(placeOfSupply)
                .hsnSacCode(hsnSacCode)
                .supplierGstin(supplierGstin)
                .tax(tax)
                .discount(discount)
                .totalAmount(totalRiderPayable)
                .platformRetention(platformRetention)
                .currency("INR")
                .couponCode(couponCode)
                .couponDiscountType(couponDiscountType)
                .couponFundingType(fundingType)
                .build();
    }

    /**
     * Apply immutable pricing and GST snapshots to a booking upon creation.
     */
    public void applyPricingSnapshot(Booking booking, FeeCalculationResult calc) {
        booking.setBaseFare(calc.getBaseFare().doubleValue());
        booking.setRiderPlatformFee(calc.getRiderPlatformFee().doubleValue());
        booking.setRiderFeeEnabledSnapshot(calc.isRiderFeeEnabled());
        booking.setRiderFeeTypeSnapshot(calc.getRiderFeeType());
        booking.setRiderFeeRateSnapshot(calc.getRiderFeeRate());

        booking.setDriverCommissionAmount(calc.getDriverCommissionAmount().doubleValue());
        booking.setDriverCommissionEnabledSnapshot(calc.isDriverCommissionEnabled());
        booking.setDriverCommissionTypeSnapshot(calc.getDriverCommissionType());
        booking.setDriverCommissionRateSnapshot(calc.getDriverCommissionRatePercent());
        booking.setDriverCommissionFixedSnapshot(calc.getDriverCommissionFixedAmount());

        booking.setDriverNetEarnings(calc.getDriverNetEarnings().doubleValue());
        booking.setTotalAmount(calc.getTotalAmount().doubleValue());
        booking.setTax(calc.getTax() != null ? calc.getTax().doubleValue() : 0.0);
        booking.setDiscount(calc.getDiscount().doubleValue());
        booking.setPricingSnapshotTimestamp(LocalDateTime.now());

        // GST Immutable Snapshots
        booking.setTaxableAmount(calc.getTaxableAmount() != null ? calc.getTaxableAmount().doubleValue() : calc.getTotalAmount().doubleValue());
        booking.setGstRateSnapshot(calc.getGstRate());
        booking.setCgstAmount(calc.getCgstAmount() != null ? calc.getCgstAmount().doubleValue() : 0.0);
        booking.setSgstAmount(calc.getSgstAmount() != null ? calc.getSgstAmount().doubleValue() : 0.0);
        booking.setIgstAmount(calc.getIgstAmount() != null ? calc.getIgstAmount().doubleValue() : 0.0);
        booking.setIsInterstate(calc.isInterstate());
        booking.setPlaceOfSupply(calc.getPlaceOfSupply());
        booking.setPricesIncludeGstSnapshot(calc.isPricesIncludeGst());

        if (calc.getCouponCode() != null && !calc.getCouponCode().trim().isEmpty()) {
            booking.setCouponCode(calc.getCouponCode().trim().toUpperCase());
            booking.setCouponDiscountAmount(calc.getDiscount().doubleValue());
            booking.setCouponDiscountType(calc.getCouponDiscountType());
            booking.setCouponFundingType(calc.getCouponFundingType() != null ? calc.getCouponFundingType() : "PLATFORM");
        }

        // Sync legacy backward-compatible fields
        booking.setPlatformFee(calc.getRiderPlatformFee().doubleValue());
        booking.setDriverAmount(calc.getDriverNetEarnings().doubleValue());
        booking.setCommissionEnabledSnapshot(calc.isDriverCommissionEnabled());
        booking.setCommissionRateSnapshot(calc.getDriverCommissionRatePercent());
        booking.setCommissionFixedFeeSnapshot(calc.getDriverCommissionFixedAmount());
        booking.setFeeTypeSnapshot(calc.isDriverCommissionEnabled() ? calc.getDriverCommissionType() : "ZERO_COMMISSION");
    }

    public PlatformSettings getActivePlatformSettings() {
        if (redisCacheService != null) {
            java.util.Optional<PlatformSettings> cached = redisCacheService.get(
                    RedisCacheService.PREFIX_PLATFORM_SETTINGS, PlatformSettings.class);
            if (cached.isPresent()) {
                return cached.get();
            }
        }

        PlatformSettings settings = platformSettingsRepository.findAll().stream().findFirst()
                .orElseGet(() -> PlatformSettings.builder().build());

        if (redisCacheService != null && settings != null) {
            redisCacheService.set(RedisCacheService.PREFIX_PLATFORM_SETTINGS, settings, RedisCacheService.TTL_SETTINGS);
        }

        return settings;
    }
}
