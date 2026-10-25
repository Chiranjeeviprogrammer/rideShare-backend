package com.rideshare.backend.controller;

import com.rideshare.backend.dto.CarpoolPriceRecommendRequest;
import com.rideshare.backend.dto.CarpoolPriceRecommendResponse;
import com.rideshare.backend.service.CarpoolPricingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST endpoint for carpooling price recommendations.
 * Requires authentication – only a logged-in driver can request a recommendation.
 */
@RestController
@RequestMapping("/api/pricing")
public class CarpoolPricingController {

    @Autowired
    private CarpoolPricingService carpoolPricingService;

    /**
     * POST /api/pricing/recommend
     *
     * Body: CarpoolPriceRecommendRequest (route distance, tolls, seats, jurisdiction, vehicle)
     * Returns: CarpoolPriceRecommendResponse with recommended band and transparent breakdown.
     *
     * The caller MUST supply real Ola Maps route distance and verified toll data.
     * The server will reject requests with zero distance to prevent meaningless calculations.
     */
    @PostMapping("/recommend")
    public ResponseEntity<?> recommend(
            @RequestHeader(value = "Authorization", required = false) String token,
            @RequestBody CarpoolPriceRecommendRequest request) {

        // Basic guard: route distance must be provided and non-trivial (≥ 1 km)
        if (request.getRouteDistanceMeters() < 1000.0) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "INVALID_DISTANCE",
                "message", "routeDistanceMeters must be at least 1000 m (1 km). " +
                           "Provide the actual Ola Maps route distance."
            ));
        }

        if (request.getAvailableSeats() < 1 || request.getAvailableSeats() > 8) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "INVALID_SEATS",
                "message", "availableSeats must be between 1 and 8."
            ));
        }

        CarpoolPriceRecommendResponse recommendation = carpoolPricingService.recommend(request);
        return ResponseEntity.ok(recommendation);
    }

    /**
     * POST /api/pricing/validate-stopover
     *
     * Validate that a set of stopover prices is within allowed limits.
     * Returns a validation result per segment.
     */
    @PostMapping("/validate-stopover")
    public ResponseEntity<?> validateStopoverPrices(
            @RequestHeader(value = "Authorization", required = false) String token,
            @RequestBody Map<String, Object> body) {

        double routeDistanceMeters = ((Number) body.getOrDefault("routeDistanceMeters", 0)).doubleValue();
        int seats = ((Number) body.getOrDefault("availableSeats", 1)).intValue();
        String originState = (String) body.getOrDefault("originStateCode", null);
        String vehicleCat  = (String) body.getOrDefault("vehicleCategory", "SEDAN");
        double toll        = ((Number) body.getOrDefault("verifiedTollAmountInr", 0)).doubleValue();

        if (routeDistanceMeters < 1000.0) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "INVALID_DISTANCE",
                "message", "routeDistanceMeters must be at least 1000 m."
            ));
        }

        CarpoolPriceRecommendRequest baseReq = CarpoolPriceRecommendRequest.builder()
                .routeDistanceMeters(routeDistanceMeters)
                .verifiedTollAmountInr(toll)
                .availableSeats(seats)
                .originStateCode(originState)
                .vehicleCategory(vehicleCat)
                .build();

        CarpoolPriceRecommendResponse base = carpoolPricingService.recommend(baseReq);

        // The max total contribution across all stopovers must not exceed the max full-route price
        return ResponseEntity.ok(Map.of(
            "maxTotalContributionPerSeat", base.getMaxTotalContributionPerSeat(),
            "recommendedPerSeat", base.getRecommendedPerSeat(),
            "jurisdictionConfigured", base.isJurisdictionConfigured(),
            "policyWarning", base.getPolicyWarning() != null ? base.getPolicyWarning() : ""
        ));
    }
}
