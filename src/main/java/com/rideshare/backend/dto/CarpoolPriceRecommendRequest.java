package com.rideshare.backend.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

/**
 * Request to calculate a carpooling price recommendation.
 * The frontend sends real route data obtained from Ola Maps.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CarpoolPriceRecommendRequest {

    /** Route distance in meters (from Ola Maps – must never be invented). */
    private double routeDistanceMeters;

    /** Verified toll amount in INR from Ola Maps toll info (0 if not available). */
    private double verifiedTollAmountInr;

    /** Parking charges driver expects to share (0 if none). */
    private double parkingChargesInr;

    /** Number of available seats for passengers (excludes driver). */
    private int availableSeats;

    /** ISO 3166-2 state code of origin, e.g. "IN-UP", "IN-BR". Null means unknown jurisdiction. */
    private String originStateCode;

    /** ISO 3166-2 state code of destination, e.g. "IN-BR". Null means same as origin. */
    private String destinationStateCode;

    /** Vehicle category: "2W", "HATCHBACK", "SEDAN", "SUV", "VAN". Defaults to "SEDAN". */
    private String vehicleCategory;

    /** Fuel type: "PETROL", "DIESEL", "CNG", "ELECTRIC". Defaults to "PETROL". */
    private String fuelType;
}
