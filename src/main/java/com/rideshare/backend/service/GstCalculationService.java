package com.rideshare.backend.service;

import com.rideshare.backend.model.GstSettings;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Authoritative Backend GST Calculation Engine.
 * 
 * Supports:
 * - Decimal-safe arithmetic (BigDecimal, RoundingMode.HALF_UP)
 * - Place of supply rules (Intra-state CGST + SGST vs Inter-state IGST)
 * - Inclusive and Exclusive tax calculations
 * - Configurable GST rates and HSN/SAC classification
 */
@Service
public class GstCalculationService {

    // Common Indian state code mapping for Place of Supply resolution
    private static final Map<String, String> STATE_CODE_MAP = new HashMap<>();

    static {
        STATE_CODE_MAP.put("JAMMU AND KASHMIR", "01");
        STATE_CODE_MAP.put("HIMACHAL PRADESH", "02");
        STATE_CODE_MAP.put("PUNJAB", "03");
        STATE_CODE_MAP.put("CHANDIGARH", "04");
        STATE_CODE_MAP.put("UTTARAKHAND", "05");
        STATE_CODE_MAP.put("HARYANA", "06");
        STATE_CODE_MAP.put("DELHI", "07");
        STATE_CODE_MAP.put("RAJASTHAN", "08");
        STATE_CODE_MAP.put("UTTAR PRADESH", "09");
        STATE_CODE_MAP.put("BIHAR", "10");
        STATE_CODE_MAP.put("SIKKIM", "11");
        STATE_CODE_MAP.put("WEST BENGAL", "19");
        STATE_CODE_MAP.put("JHARKHAND", "20");
        STATE_CODE_MAP.put("ODISHA", "21");
        STATE_CODE_MAP.put("CHHATTISGARH", "22");
        STATE_CODE_MAP.put("MADHYA PRADESH", "23");
        STATE_CODE_MAP.put("GUJARAT", "24");
        STATE_CODE_MAP.put("MAHARASHTRA", "27");
        STATE_CODE_MAP.put("ANDHRA PRADESH", "28");
        STATE_CODE_MAP.put("KARNATAKA", "29");
        STATE_CODE_MAP.put("GOA", "30");
        STATE_CODE_MAP.put("KERALA", "32");
        STATE_CODE_MAP.put("TAMIL NADU", "33");
        STATE_CODE_MAP.put("PUDUCHERRY", "34");
        STATE_CODE_MAP.put("TELANGANA", "36");
        STATE_CODE_MAP.put("LADAKH", "38");
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GstCalculationResult {
        private boolean gstEnabled;
        private boolean pricesIncludeGst;
        private double gstRate;
        private String hsnSacCode;

        private BigDecimal taxableAmount;
        private BigDecimal cgstRate;
        private BigDecimal cgstAmount;
        private BigDecimal sgstRate;
        private BigDecimal sgstAmount;
        private BigDecimal igstRate;
        private BigDecimal igstAmount;
        private BigDecimal totalTaxAmount;
        private BigDecimal totalAmount;

        private boolean isInterstate;
        private String placeOfSupply;
        private String placeOfSupplyStateCode;
        private String supplierState;
        private String supplierStateCode;
        private String supplierGstin;

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("gstEnabled", gstEnabled);
            map.put("pricesIncludeGst", pricesIncludeGst);
            map.put("gstRate", gstRate);
            map.put("hsnSacCode", hsnSacCode);
            map.put("taxableAmount", taxableAmount.doubleValue());
            map.put("cgstRate", cgstRate.doubleValue());
            map.put("cgstAmount", cgstAmount.doubleValue());
            map.put("sgstRate", sgstRate.doubleValue());
            map.put("sgstAmount", sgstAmount.doubleValue());
            map.put("igstRate", igstRate.doubleValue());
            map.put("igstAmount", igstAmount.doubleValue());
            map.put("totalTaxAmount", totalTaxAmount.doubleValue());
            map.put("totalAmount", totalAmount.doubleValue());
            map.put("isInterstate", isInterstate);
            map.put("placeOfSupply", placeOfSupply);
            map.put("placeOfSupplyStateCode", placeOfSupplyStateCode);
            map.put("supplierState", supplierState);
            map.put("supplierStateCode", supplierStateCode);
            map.put("supplierGstin", supplierGstin);
            return map;
        }
    }

    /**
     * Resolves state name & code from address or string.
     */
    public String resolveStateCode(String addressOrState, String defaultStateCode) {
        if (addressOrState == null || addressOrState.isBlank()) {
            return defaultStateCode != null ? defaultStateCode : "29";
        }
        String upper = addressOrState.toUpperCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : STATE_CODE_MAP.entrySet()) {
            if (upper.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return defaultStateCode != null ? defaultStateCode : "29";
    }

    /**
     * Detects state name from state code or address.
     */
    public String resolveStateName(String addressOrState, String defaultStateName) {
        if (addressOrState == null || addressOrState.isBlank()) {
            return defaultStateName != null ? defaultStateName : "Karnataka";
        }
        String upper = addressOrState.toUpperCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : STATE_CODE_MAP.entrySet()) {
            if (upper.contains(entry.getKey())) {
                return entry.getKey();
            }
        }
        return defaultStateName != null ? defaultStateName : "Karnataka";
    }

    /**
     * Performs decimal-safe GST calculation for a given amount, location, and configuration.
     *
     * @param amount Quoted gross fare (if inclusive) or base fare (if exclusive)
     * @param tripLocation Pickup address or state to determine Place of Supply
     * @param settings Authoritative GST configuration
     */
    public GstCalculationResult calculate(BigDecimal amount, String tripLocation, GstSettings settings) {
        if (amount == null) amount = BigDecimal.ZERO;
        amount = amount.setScale(2, RoundingMode.HALF_UP);

        if (settings == null || !Boolean.TRUE.equals(settings.getIsEnabled())) {
            // GST is completely disabled
            return GstCalculationResult.builder()
                    .gstEnabled(false)
                    .pricesIncludeGst(true)
                    .gstRate(0.0)
                    .hsnSacCode(settings != null ? settings.getHsnSacCode() : "996412")
                    .taxableAmount(amount)
                    .cgstRate(BigDecimal.ZERO)
                    .cgstAmount(BigDecimal.ZERO)
                    .sgstRate(BigDecimal.ZERO)
                    .sgstAmount(BigDecimal.ZERO)
                    .igstRate(BigDecimal.ZERO)
                    .igstAmount(BigDecimal.ZERO)
                    .totalTaxAmount(BigDecimal.ZERO)
                    .totalAmount(amount)
                    .isInterstate(false)
                    .placeOfSupply(settings != null ? settings.getBusinessState() : "Karnataka")
                    .placeOfSupplyStateCode(settings != null ? settings.getBusinessStateCode() : "29")
                    .supplierState(settings != null ? settings.getBusinessState() : "Karnataka")
                    .supplierStateCode(settings != null ? settings.getBusinessStateCode() : "29")
                    .supplierGstin(settings != null ? settings.getGstin() : "")
                    .build();
        }

        String supplierState = settings.getBusinessState() != null ? settings.getBusinessState() : "Karnataka";
        String supplierStateCode = settings.getBusinessStateCode() != null ? settings.getBusinessStateCode() : "29";

        String placeOfSupply = resolveStateName(tripLocation, supplierState);
        String placeOfSupplyCode = resolveStateCode(tripLocation, supplierStateCode);

        // Determine if Inter-state
        boolean isInterstate;
        String ruleType = settings.getTaxRuleType() != null ? settings.getTaxRuleType().toUpperCase() : "AUTOMATIC_POS";
        if ("ALWAYS_IGST".equals(ruleType)) {
            isInterstate = true;
        } else if ("ALWAYS_CGST_SGST".equals(ruleType)) {
            isInterstate = false;
        } else {
            // AUTOMATIC_POS
            isInterstate = !supplierStateCode.equals(placeOfSupplyCode);
        }

        double rate = settings.getGstRate() != null && settings.getGstRate() >= 0 ? settings.getGstRate() : 5.0;
        BigDecimal gstRateBd = BigDecimal.valueOf(rate).setScale(2, RoundingMode.HALF_UP);

        BigDecimal cgstRateBd;
        BigDecimal sgstRateBd;
        BigDecimal igstRateBd;

        if (isInterstate) {
            cgstRateBd = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            sgstRateBd = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            igstRateBd = gstRateBd;
        } else {
            BigDecimal halfRate = gstRateBd.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
            cgstRateBd = halfRate;
            sgstRateBd = halfRate;
            igstRateBd = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }

        boolean pricesIncludeGst = Boolean.TRUE.equals(settings.getPricesIncludeGst());

        BigDecimal taxableAmount;
        BigDecimal totalTaxAmount;
        BigDecimal totalAmount;

        if (rate <= 0.0) {
            taxableAmount = amount;
            totalTaxAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            totalAmount = amount;
        } else if (pricesIncludeGst) {
            // INCLUSIVE: Taxable = Amount / (1 + Rate / 100)
            BigDecimal factor = BigDecimal.ONE.add(
                    gstRateBd.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)
            );
            taxableAmount = amount.divide(factor, 2, RoundingMode.HALF_UP);
            totalTaxAmount = amount.subtract(taxableAmount).setScale(2, RoundingMode.HALF_UP);
            totalAmount = amount;
        } else {
            // EXCLUSIVE: Taxable = Amount, Tax = Amount * (Rate / 100)
            taxableAmount = amount;
            totalTaxAmount = amount.multiply(gstRateBd)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            totalAmount = taxableAmount.add(totalTaxAmount).setScale(2, RoundingMode.HALF_UP);
        }

        BigDecimal cgstAmount;
        BigDecimal sgstAmount;
        BigDecimal igstAmount;

        if (isInterstate) {
            cgstAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            sgstAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            igstAmount = totalTaxAmount;
        } else {
            cgstAmount = totalTaxAmount.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
            sgstAmount = totalTaxAmount.subtract(cgstAmount).setScale(2, RoundingMode.HALF_UP);
            igstAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }

        return GstCalculationResult.builder()
                .gstEnabled(true)
                .pricesIncludeGst(pricesIncludeGst)
                .gstRate(rate)
                .hsnSacCode(settings.getHsnSacCode() != null ? settings.getHsnSacCode() : "996412")
                .taxableAmount(taxableAmount)
                .cgstRate(cgstRateBd)
                .cgstAmount(cgstAmount)
                .sgstRate(sgstRateBd)
                .sgstAmount(sgstAmount)
                .igstRate(igstRateBd)
                .igstAmount(igstAmount)
                .totalTaxAmount(totalTaxAmount)
                .totalAmount(totalAmount)
                .isInterstate(isInterstate)
                .placeOfSupply(placeOfSupply)
                .placeOfSupplyStateCode(placeOfSupplyCode)
                .supplierState(supplierState)
                .supplierStateCode(supplierStateCode)
                .supplierGstin(settings.getGstin())
                .build();
    }
}
