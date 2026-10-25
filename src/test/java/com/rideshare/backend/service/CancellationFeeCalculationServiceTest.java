package com.rideshare.backend.service;

import com.rideshare.backend.model.Booking;
import com.rideshare.backend.model.CancellationPolicyRule;
import com.rideshare.backend.model.PlatformSettings;
import com.rideshare.backend.model.Ride;
import com.rideshare.backend.model.User;
import com.rideshare.backend.repository.CancellationPolicyRuleRepository;
import com.rideshare.backend.repository.PlatformSettingsRepository;
import com.rideshare.backend.service.CancellationFeeCalculationService.CancellationQuote;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class CancellationFeeCalculationServiceTest {

    @Mock
    private PlatformSettingsRepository platformSettingsRepository;

    @Mock
    private CancellationPolicyRuleRepository cancellationPolicyRuleRepository;

    @Mock
    private RedisCacheService redisCacheService;

    @InjectMocks
    private CancellationFeeCalculationService cancellationFeeCalculationService;

    private PlatformSettings defaultSettings;
    private PlatformSettings activeFeeSettings;
    private Booking testBooking;
    private Ride testRide;
    private User driverUser;
    private User passengerUser;

    @BeforeEach
    public void setup() {
        // Initial Requirement: Default cancellation fee must be ₹0 and disabled
        defaultSettings = PlatformSettings.builder()
                .cancellationFeeEnabled(false)
                .defaultCancellationFee(0.0)
                .cancellationFeeType("FIXED")
                .cancellationGraceMinutes(10)
                .build();

        activeFeeSettings = PlatformSettings.builder()
                .cancellationFeeEnabled(true)
                .defaultCancellationFee(50.0)
                .cancellationFeeType("FIXED")
                .cancellationGraceMinutes(10)
                .build();

        driverUser = new User();
        driverUser.setId(10L);
        driverUser.setName("Driver Ramesh");
        driverUser.setEmail("ramesh.driver@example.com");

        passengerUser = new User();
        passengerUser.setId(20L);
        passengerUser.setName("Passenger Priya");
        passengerUser.setEmail("priya.passenger@example.com");

        testRide = new Ride();
        testRide.setId(100L);
        testRide.setDriver(driverUser);
        testRide.setDepartureTime(LocalDateTime.now().plusHours(30));
        testRide.setFare(300.0);

        testBooking = Booking.builder()
                .id(501L)
                .ride(testRide)
                .passenger(passengerUser)
                .seats(1)
                .totalAmount(300.0)
                .bookedAt(LocalDateTime.now().minusHours(2)) // Booked 2 hours ago (outside grace period)
                .build();
    }

    @Test
    @DisplayName("Initial Requirement: Cancellation fee defaults to ₹0 when feature is disabled")
    public void testDefaultZeroCancellationFeeWhenDisabled() {
        when(platformSettingsRepository.findTopByOrderByIdAsc()).thenReturn(Optional.of(defaultSettings));

        CancellationQuote quote = cancellationFeeCalculationService.calculateQuote(testBooking, false);

        assertNotNull(quote);
        assertEquals(0.0, quote.getCancellationFee(), 0.001, "Fee must be exactly ₹0 when disabled");
        assertEquals(300.0, quote.getRefundAmount(), 0.001, "100% refund must be returned");
        assertTrue(quote.isEligibleForRefund());
        assertTrue(quote.getRuleApplied().contains("ZERO_CANCELLATION") || quote.getRuleApplied().contains("Zero Fee") || quote.getRuleApplied().contains("GLOBAL_DISABLED"));
    }

    @Test
    @DisplayName("Initial Requirement: Fee defaults to ₹0 even when no settings row exists in DB")
    public void testDefaultZeroCancellationFeeWhenNoSettingsRecord() {
        when(platformSettingsRepository.findTopByOrderByIdAsc()).thenReturn(Optional.empty());

        CancellationQuote quote = cancellationFeeCalculationService.calculateQuote(testBooking, false);

        assertNotNull(quote);
        assertEquals(0.0, quote.getCancellationFee(), 0.001, "Fee must default to ₹0");
        assertEquals(300.0, quote.getRefundAmount(), 0.001);
    }

    @Test
    @DisplayName("Driver Cancellation Immunity: Passenger fee is ALWAYS ₹0 and 100% refunded when driver cancels")
    public void testDriverCancellationImmunity() {
        // Even with aggressive fee settings enabled
        when(platformSettingsRepository.findTopByOrderByIdAsc()).thenReturn(Optional.of(activeFeeSettings));

        CancellationQuote quote = cancellationFeeCalculationService.calculateQuote(testBooking, true);

        assertNotNull(quote);
        assertEquals(0.0, quote.getCancellationFee(), 0.001, "Passenger fee must be ₹0 on driver cancellation");
        assertEquals(300.0, quote.getRefundAmount(), 0.001, "100% refund must be guaranteed");
        assertTrue(quote.isDriverCancellation());
        assertTrue(quote.getRuleApplied().contains("DRIVER_CANCELLATION"));
    }

    @Test
    @DisplayName("Grace Period Exemption: Cancellations within grace period (10 min) are 100% free with ₹0 fee")
    public void testGracePeriodExemption() {
        when(platformSettingsRepository.findTopByOrderByIdAsc()).thenReturn(Optional.of(activeFeeSettings));

        // Booked only 3 minutes ago (within 10-minute grace window)
        testBooking.setBookedAt(LocalDateTime.now().minusMinutes(3));

        CancellationQuote quote = cancellationFeeCalculationService.calculateQuote(testBooking, false);

        assertNotNull(quote);
        assertEquals(0.0, quote.getCancellationFee(), 0.001, "Fee must be ₹0 within grace period");
        assertEquals(300.0, quote.getRefundAmount(), 0.001);
        assertTrue(quote.getRuleApplied().contains("GRACE_PERIOD"));
    }

    @Test
    @DisplayName("Time-Window Rules: Matches > 24 hours window with 0% fee and full refund")
    public void testTimeWindowRuleMoreThan24Hours() {
        when(platformSettingsRepository.findTopByOrderByIdAsc()).thenReturn(Optional.of(activeFeeSettings));

        CancellationPolicyRule ruleEarly = CancellationPolicyRule.builder()
                .id(1L)
                .minHoursBeforeDeparture(24.0)
                .maxHoursBeforeDeparture(null)
                .feeType("PERCENTAGE")
                .feeAmount(0.0)
                .isRefundable(true)
                .description("Early cancellation (> 24h) - Free")
                .active(true)
                .build();

        CancellationPolicyRule ruleLate = CancellationPolicyRule.builder()
                .id(2L)
                .minHoursBeforeDeparture(2.0)
                .maxHoursBeforeDeparture(24.0)
                .feeType("PERCENTAGE")
                .feeAmount(20.0)
                .isRefundable(true)
                .description("Standard cancellation (2h - 24h)")
                .active(true)
                .build();

        when(cancellationPolicyRuleRepository.findByActiveTrueOrderByMinHoursBeforeDepartureDesc())
                .thenReturn(Arrays.asList(ruleEarly, ruleLate));

        // Departure is 30 hours away (> 24h)
        testRide.setDepartureTime(LocalDateTime.now().plusHours(30));

        CancellationQuote quote = cancellationFeeCalculationService.calculateQuote(testBooking, false);

        assertNotNull(quote);
        assertEquals(0.0, quote.getCancellationFee(), 0.001, "Early window has 0% fee");
        assertEquals(300.0, quote.getRefundAmount(), 0.001);
    }

    @Test
    @DisplayName("Time-Window Rules: Matches 2-24 hours window with 20% percentage fee")
    public void testTimeWindowRuleBetween2And24Hours() {
        when(platformSettingsRepository.findTopByOrderByIdAsc()).thenReturn(Optional.of(activeFeeSettings));

        CancellationPolicyRule ruleLate = CancellationPolicyRule.builder()
                .id(2L)
                .minHoursBeforeDeparture(2.0)
                .maxHoursBeforeDeparture(24.0)
                .feeType("PERCENTAGE")
                .feeAmount(20.0) // 20% of 300 = ₹60
                .isRefundable(true)
                .description("Standard cancellation (2h - 24h)")
                .active(true)
                .build();

        when(cancellationPolicyRuleRepository.findByActiveTrueOrderByMinHoursBeforeDepartureDesc())
                .thenReturn(Collections.singletonList(ruleLate));

        // Departure is 10 hours away
        testRide.setDepartureTime(LocalDateTime.now().plusHours(10));

        CancellationQuote quote = cancellationFeeCalculationService.calculateQuote(testBooking, false);

        assertNotNull(quote);
        assertEquals(60.0, quote.getCancellationFee(), 0.001, "20% of 300 should be ₹60");
        assertEquals(240.0, quote.getRefundAmount(), 0.001, "300 - 60 = ₹240 refund");
    }

    @Test
    @DisplayName("Boundary Clamping: Fee cannot exceed total booking fare or become negative")
    public void testFeeClampingWithinTotalAmount() {
        PlatformSettings excessiveSettings = PlatformSettings.builder()
                .cancellationFeeEnabled(true)
                .defaultCancellationFee(500.0) // Exceeds 300.0 total fare
                .cancellationFeeType("FIXED")
                .cancellationGraceMinutes(0)
                .build();

        when(platformSettingsRepository.findTopByOrderByIdAsc()).thenReturn(Optional.of(excessiveSettings));
        when(cancellationPolicyRuleRepository.findByActiveTrueOrderByMinHoursBeforeDepartureDesc())
                .thenReturn(Collections.emptyList());

        testRide.setDepartureTime(LocalDateTime.now().plusHours(1));

        CancellationQuote quote = cancellationFeeCalculationService.calculateQuote(testBooking, false);

        assertNotNull(quote);
        assertEquals(300.0, quote.getCancellationFee(), 0.001, "Fee cannot exceed total fare");
        assertEquals(0.0, quote.getRefundAmount(), 0.001, "Refund cannot be negative");
    }
}
