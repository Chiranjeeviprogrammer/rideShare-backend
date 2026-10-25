package com.rideshare.backend;

import com.rideshare.backend.dto.CarpoolPriceRecommendRequest;
import com.rideshare.backend.dto.CarpoolPriceRecommendResponse;
import com.rideshare.backend.repository.PlatformSettingsRepository;
import com.rideshare.backend.service.CarpoolPricingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class CarpoolPricingServiceTest {

    @Mock
    private PlatformSettingsRepository platformSettingsRepository;

    @InjectMocks
    private CarpoolPricingService carpoolPricingService;

    @Test
    @DisplayName("1. Recommended price calculation for 200km Petrol Sedan with 3 seats")
    void testRecommendedPricingStandardRoute() {
        when(platformSettingsRepository.findAll()).thenReturn(Collections.emptyList());

        CarpoolPriceRecommendRequest req = CarpoolPriceRecommendRequest.builder()
                .routeDistanceMeters(200_000.0) // 200 km
                .verifiedTollAmountInr(0.0)
                .parkingChargesInr(0.0)
                .availableSeats(3) // 3 passengers + 1 driver = 4 occupants
                .fuelType("PETROL")
                .vehicleCategory("SEDAN")
                .originStateCode("UP")
                .build();

        CarpoolPriceRecommendResponse res = carpoolPricingService.recommend(req);

        assertNotNull(res);
        assertEquals(200.0, res.getRouteDistanceKm());
        assertEquals(7.50, res.getFuelCostPerKm());
        assertEquals(1.80, res.getMaintenanceCostPerKm());
        // Fuel = 200 * 7.50 = 1500, Maint = 200 * 1.80 = 360, Total = 1860
        assertEquals(1500.0, res.getFuelCostInr());
        assertEquals(360.0, res.getMaintenanceCostInr());
        assertEquals(1860.0, res.getTotalTripCostInr());
        // 4 occupants -> 1860 / 4 = 465.0 per seat
        assertEquals(465.0, res.getCostPerPassengerSeatInr());
        // Nearest 10: 470, Floor: 460
        assertEquals(470, res.getRecommendedPerSeat());
        assertTrue(res.getRecommendedMinPerSeat() <= res.getRecommendedPerSeat());
        assertTrue(res.getRecommendedMaxPerSeat() >= res.getRecommendedPerSeat());
    }

    @Test
    @DisplayName("2. Route with verified tolls and parking charges")
    void testPricingWithVerifiedTollsAndParking() {
        when(platformSettingsRepository.findAll()).thenReturn(Collections.emptyList());

        CarpoolPriceRecommendRequest req = CarpoolPriceRecommendRequest.builder()
                .routeDistanceMeters(200_000.0)
                .verifiedTollAmountInr(200.0)
                .parkingChargesInr(50.0)
                .availableSeats(3)
                .fuelType("PETROL")
                .vehicleCategory("SEDAN")
                .build();

        CarpoolPriceRecommendResponse res = carpoolPricingService.recommend(req);

        // Operating cost = 1860, Toll = 200, Parking = 50 -> Total = 2110
        assertEquals(2110.0, res.getTotalTripCostInr());
        assertEquals(200.0, res.getVerifiedTollAmountInr());
        assertEquals(50.0, res.getParkingChargesInr());
        // 4 occupants -> 2110 / 4 = 527.5
        assertEquals(527.5, res.getCostPerPassengerSeatInr());
        // Rounded to nearest 10: 530
        assertEquals(530, res.getRecommendedPerSeat());
    }

    @Test
    @DisplayName("3. Electric vehicle operating cost")
    void testElectricVehicleOperatingCost() {
        when(platformSettingsRepository.findAll()).thenReturn(Collections.emptyList());

        CarpoolPriceRecommendRequest req = CarpoolPriceRecommendRequest.builder()
                .routeDistanceMeters(100_000.0) // 100 km
                .availableSeats(1) // 1 passenger + 1 driver = 2 occupants
                .fuelType("ELECTRIC")
                .vehicleCategory("SEDAN")
                .build();

        CarpoolPriceRecommendResponse res = carpoolPricingService.recommend(req);

        assertEquals(1.50, res.getFuelCostPerKm());
        assertEquals(150.0, res.getFuelCostInr());
        assertEquals(180.0, res.getMaintenanceCostInr());
        // Total = 330 / 2 = 165
        assertEquals(330.0, res.getTotalTripCostInr());
        assertEquals(165.0, res.getCostPerPassengerSeatInr());
        assertEquals(170, res.getRecommendedPerSeat());
    }

    @Test
    @DisplayName("4. Vehicle category maintenance multipliers (SUV wear vs Hatchback)")
    void testVehicleCategoryMultipliers() {
        when(platformSettingsRepository.findAll()).thenReturn(Collections.emptyList());

        CarpoolPriceRecommendRequest suvReq = CarpoolPriceRecommendRequest.builder()
                .routeDistanceMeters(100_000.0)
                .availableSeats(2)
                .vehicleCategory("SUV")
                .fuelType("DIESEL")
                .build();

        CarpoolPriceRecommendResponse suvRes = carpoolPricingService.recommend(suvReq);
        // SUV multiplier = 1.35 * 1.80 = 2.43
        assertEquals(2.43, suvRes.getMaintenanceCostPerKm(), 0.001);

        CarpoolPriceRecommendRequest hbReq = CarpoolPriceRecommendRequest.builder()
                .routeDistanceMeters(100_000.0)
                .availableSeats(2)
                .vehicleCategory("HATCHBACK")
                .fuelType("DIESEL")
                .build();

        CarpoolPriceRecommendResponse hbRes = carpoolPricingService.recommend(hbReq);
        // Hatchback multiplier = 0.85 * 1.80 = 1.53
        assertEquals(1.53, hbRes.getMaintenanceCostPerKm(), 0.001);
    }

    @Test
    @DisplayName("5. Safe cost-sharing flag and warning when jurisdiction is unconfigured")
    void testUnconfiguredJurisdictionWarning() {
        when(platformSettingsRepository.findAll()).thenReturn(Collections.emptyList());

        CarpoolPriceRecommendRequest req = CarpoolPriceRecommendRequest.builder()
                .routeDistanceMeters(50_000.0)
                .availableSeats(2)
                .originStateCode("UNCONFIGURED_STATE")
                .build();

        CarpoolPriceRecommendResponse res = carpoolPricingService.recommend(req);

        assertFalse(res.isJurisdictionConfigured());
        assertNotNull(res.getPolicyWarning());
        assertTrue(res.getPolicyWarning().contains("Safe cost-sharing defaults applied"));
        // Max recovery ratio must be 1.0 (no profit)
        assertEquals(1.0, res.getMaxCostRecoveryRatio());
    }

    @Test
    @DisplayName("6. Minimum price boundary safety")
    void testMinimumPriceSafety() {
        when(platformSettingsRepository.findAll()).thenReturn(Collections.emptyList());

        CarpoolPriceRecommendRequest req = CarpoolPriceRecommendRequest.builder()
                .routeDistanceMeters(2000.0) // 2 km
                .availableSeats(4) // 5 occupants
                .fuelType("CNG")
                .vehicleCategory("HATCHBACK")
                .build();

        CarpoolPriceRecommendResponse res = carpoolPricingService.recommend(req);

        // Min price should not fall below 10 INR
        assertTrue(res.getRecommendedMinPerSeat() >= 10);
        assertTrue(res.getRecommendedPerSeat() >= 10);
    }

    @Test
    @DisplayName("7. Input clamping: Seats clamped to 1..8 and negative distance clamped to 0")
    void testInputSanitisationAndClamping() {
        when(platformSettingsRepository.findAll()).thenReturn(Collections.emptyList());

        CarpoolPriceRecommendRequest req = CarpoolPriceRecommendRequest.builder()
                .routeDistanceMeters(-5000.0)
                .availableSeats(0) // should be clamped to 1
                .build();

        CarpoolPriceRecommendResponse res = carpoolPricingService.recommend(req);

        assertEquals(0.0, res.getRouteDistanceMeters());
        assertEquals(1, res.getAvailableSeats());
    }

    @Test
    @DisplayName("8. Transparent calculation explanation steps included")
    void testCalculationStepsPresent() {
        when(platformSettingsRepository.findAll()).thenReturn(Collections.emptyList());

        CarpoolPriceRecommendRequest req = CarpoolPriceRecommendRequest.builder()
                .routeDistanceMeters(150_000.0)
                .verifiedTollAmountInr(120.0)
                .availableSeats(3)
                .originStateCode("DL")
                .build();

        CarpoolPriceRecommendResponse res = carpoolPricingService.recommend(req);

        assertNotNull(res.getCalculationSteps());
        assertFalse(res.getCalculationSteps().isEmpty());
        // Verify key information is documented in steps
        boolean mentionsDistance = res.getCalculationSteps().stream().anyMatch(s -> s.contains("Route distance: 150.0 km"));
        boolean mentionsFuel = res.getCalculationSteps().stream().anyMatch(s -> s.contains("Fuel cost:"));
        boolean mentionsMaintenance = res.getCalculationSteps().stream().anyMatch(s -> s.contains("Maintenance & wear:"));
        boolean mentionsToll = res.getCalculationSteps().stream().anyMatch(s -> s.contains("Verified tolls: ₹120"));
        boolean mentionsFairShare = res.getCalculationSteps().stream().anyMatch(s -> s.contains("Fair share per seat:"));

        assertTrue(mentionsDistance, "Should detail route distance");
        assertTrue(mentionsFuel, "Should detail fuel cost");
        assertTrue(mentionsMaintenance, "Should detail maintenance cost");
        assertTrue(mentionsToll, "Should detail verified tolls");
        assertTrue(mentionsFairShare, "Should detail fair share per seat");
    }

    @Test
    @DisplayName("9. Driver cannot profit: Max recovery ratio ensures price ceiling aligns with cost share")
    void testNoDriverProfitEnforcement() {
        when(platformSettingsRepository.findAll()).thenReturn(Collections.emptyList());

        CarpoolPriceRecommendRequest req = CarpoolPriceRecommendRequest.builder()
                .routeDistanceMeters(100_000.0)
                .availableSeats(2)
                .fuelType("PETROL")
                .vehicleCategory("SEDAN")
                .build();

        CarpoolPriceRecommendResponse res = carpoolPricingService.recommend(req);

        // Ceiling cannot allow driver to charge above cost recovery
        assertEquals(1.0, res.getMaxCostRecoveryRatio());
        // Max total contribution equals or is capped strictly
        assertTrue(res.getMaxTotalContributionPerSeat() <= res.getRecommendedMaxPerSeat());
    }
}
