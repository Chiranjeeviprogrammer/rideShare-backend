package com.rideshare.backend.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import java.util.List;

/**
 * Carpooling price recommendation response.
 * Includes a transparent cost breakdown so the driver understands exactly
 * how the recommended price was calculated.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CarpoolPriceRecommendResponse {

    // ── Inputs echoed back ────────────────────────────────────────────────
    /** Route distance used for calculation (meters). */
    private double routeDistanceMeters;
    /** Route distance in km (rounded to 1 decimal). */
    private double routeDistanceKm;
    /** Toll amount used (INR). */
    private double verifiedTollAmountInr;
    /** Parking charges included (INR). */
    private double parkingChargesInr;
    /** Seat count used. */
    private int availableSeats;

    // ── Operating Cost Breakdown ──────────────────────────────────────────
    /** Fuel cost for entire trip (INR). */
    private double fuelCostInr;
    /** Fuel cost per km applied (INR/km). */
    private double fuelCostPerKm;
    /** Vehicle wear & maintenance cost for entire trip (INR). */
    private double maintenanceCostInr;
    /** Maintenance cost per km applied (INR/km). */
    private double maintenanceCostPerKm;
    /** Total trip operating cost (fuel + maintenance + toll + parking), INR. */
    private double totalTripCostInr;

    // ── Per-Seat Calculation ──────────────────────────────────────────────
    /**
     * Cost share per seat = totalTripCost / (availableSeats + 1 for driver).
     * In genuine carpooling the driver covers their own share; passengers split the rest.
     */
    private double driverShareInr;
    private double costPerPassengerSeatInr;

    // ── Recommended Pricing Band ──────────────────────────────────────────
    /** Minimum price per seat (safe cost-sharing floor). */
    private int recommendedMinPerSeat;
    /** Recommended per-seat price (rounded to nearest ₹10). */
    private int recommendedPerSeat;
    /** Maximum permitted price per seat under applicable rules. */
    private int recommendedMaxPerSeat;

    // ── Policy Metadata ───────────────────────────────────────────────────
    /** Jurisdiction key used (e.g. "IN-UP", "DEFAULT"). */
    private String jurisdictionKey;
    /** Vehicle category used. */
    private String vehicleCategory;
    /** True if this jurisdiction's rules are fully configured; false means safe-default was applied. */
    private boolean jurisdictionConfigured;
    /** Human-readable warning if policy is incomplete, null if all good. */
    private String policyWarning;
    /** Max cost-recovery ratio applied (e.g. 1.0 = 100% of cost only, no profit allowed). */
    private double maxCostRecoveryRatio;

    // ── Calculation Explanation ───────────────────────────────────────────
    /** Ordered list of human-readable calculation steps for display to driver. */
    private List<String> calculationSteps;

    // ── Stopover Prices ───────────────────────────────────────────────────
    /** Maximum allowed total passenger contributions per seat (validation ceiling). */
    private int maxTotalContributionPerSeat;
}
