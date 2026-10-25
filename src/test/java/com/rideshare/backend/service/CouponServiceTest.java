package com.rideshare.backend.service;

import com.rideshare.backend.dto.CouponValidationResponse;
import com.rideshare.backend.model.*;
import com.rideshare.backend.repository.*;
import com.rideshare.backend.service.FeeCalculationService.FeeCalculationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class CouponServiceTest {

    @Mock
    private CouponRepository couponRepository;

    @Mock
    private CouponRedemptionRepository couponRedemptionRepository;

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private RideRepository rideRepository;

    @Mock
    private RedisCacheService redisCacheService;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private PlatformSettingsRepository platformSettingsRepository;

    @InjectMocks
    private CouponService couponService;

    private FeeCalculationService feeCalculationService;

    private User rider;
    private User driver;
    private Ride ride;
    private Coupon validPercentCoupon;
    private Coupon validFixedCoupon;
    private Coupon firstRideCoupon;

    @BeforeEach
    public void setup() {
        feeCalculationService = new FeeCalculationService();
        ReflectionTestUtils.setField(feeCalculationService, "platformSettingsRepository", platformSettingsRepository);
        ReflectionTestUtils.setField(couponService, "feeCalculationService", feeCalculationService);

        rider = new User();
        rider.setId(100L);
        rider.setName("Alex Rider");
        rider.setEmail("alex@example.com");
        rider.setRole("RIDER");

        driver = new User();
        driver.setId(200L);
        driver.setName("Sam Driver");
        driver.setEmail("sam@example.com");
        driver.setRole("DRIVER");

        ride = Ride.builder()
                .id(1L)
                .driver(driver)
                .fare(200.0)
                .availableSeats(3)
                .status("PUBLISHED")
                .build();

        validPercentCoupon = Coupon.builder()
                .id(10L)
                .code("SAVE20")
                .title("20% Off Carpool")
                .discountType("PERCENTAGE")
                .discountValue(20.0)
                .maxDiscountCap(50.0)
                .minRideFare(100.0)
                .validFrom(LocalDateTime.now().minusDays(1))
                .validUntil(LocalDateTime.now().plusDays(7))
                .totalUsageLimit(100)
                .perUserLimit(2)
                .currentRedemptions(10)
                .firstRideOnly(false)
                .fundingType("PLATFORM")
                .isActive(true)
                .build();

        validFixedCoupon = Coupon.builder()
                .id(11L)
                .code("FLAT50")
                .title("Flat ₹50 Off")
                .discountType("FIXED")
                .discountValue(50.0)
                .minRideFare(100.0)
                .validFrom(LocalDateTime.now().minusDays(1))
                .validUntil(LocalDateTime.now().plusDays(7))
                .totalUsageLimit(50)
                .perUserLimit(1)
                .currentRedemptions(0)
                .firstRideOnly(false)
                .fundingType("PLATFORM")
                .isActive(true)
                .build();

        firstRideCoupon = Coupon.builder()
                .id(12L)
                .code("FIRST100")
                .title("First Ride Special")
                .discountType("FIXED")
                .discountValue(100.0)
                .minRideFare(150.0)
                .validFrom(LocalDateTime.now().minusDays(1))
                .validUntil(LocalDateTime.now().plusDays(7))
                .totalUsageLimit(1000)
                .perUserLimit(1)
                .currentRedemptions(5)
                .firstRideOnly(true)
                .fundingType("PLATFORM")
                .isActive(true)
                .build();
    }

    @Test
    @DisplayName("Should validate percentage discount coupon and apply max discount cap")
    public void testValidateCoupon_PercentageDiscount_WithCap() {
        when(couponRepository.findByCodeIgnoreCase("SAVE20")).thenReturn(Optional.of(validPercentCoupon));
        when(rideRepository.findById(1L)).thenReturn(Optional.of(ride));
        when(couponRedemptionRepository.countByCouponIdAndUserIdAndStatusNot(eq(10L), eq(100L), anyString())).thenReturn(0L);

        // Ride fare: 2 seats * 200 = 400. 20% of 400 = 80. Cap is 50. Discount should be 50.
        CouponValidationResponse response = couponService.validateCoupon("SAVE20", 1L, 2, rider);

        assertTrue(response.isValid());
        assertEquals("SAVE20", response.getCode());
        assertEquals(50.0, response.getDiscountAmount());
        assertEquals(400.0, response.getOriginalBaseFare());
        assertEquals(350.0, response.getFinalPayable());
        assertEquals("PLATFORM", response.getFundingType());
    }

    @Test
    @DisplayName("Should validate fixed discount coupon successfully")
    public void testValidateCoupon_FixedDiscount() {
        when(couponRepository.findByCodeIgnoreCase("FLAT50")).thenReturn(Optional.of(validFixedCoupon));
        when(rideRepository.findById(1L)).thenReturn(Optional.of(ride));
        when(couponRedemptionRepository.countByCouponIdAndUserIdAndStatusNot(eq(11L), eq(100L), anyString())).thenReturn(0L);

        // Ride fare: 1 seat * 200 = 200. Flat 50 discount.
        CouponValidationResponse response = couponService.validateCoupon("FLAT50", 1L, 1, rider);

        assertTrue(response.isValid());
        assertEquals("FLAT50", response.getCode());
        assertEquals(50.0, response.getDiscountAmount());
        assertEquals(200.0, response.getOriginalBaseFare());
        assertEquals(150.0, response.getFinalPayable());
    }

    @Test
    @DisplayName("Should reject expired coupon")
    public void testValidateCoupon_ExpiredCoupon() {
        validPercentCoupon.setValidUntil(LocalDateTime.now().minusDays(1));
        when(couponRepository.findByCodeIgnoreCase("SAVE20")).thenReturn(Optional.of(validPercentCoupon));

        CouponValidationResponse response = couponService.validateCoupon("SAVE20", 1L, 1, rider);

        assertFalse(response.isValid());
        assertTrue(response.getMessage().contains("expired"));
    }

    @Test
    @DisplayName("Should reject coupon that is inactive / disabled")
    public void testValidateCoupon_Inactive() {
        validPercentCoupon.setIsActive(false);
        when(couponRepository.findByCodeIgnoreCase("SAVE20")).thenReturn(Optional.of(validPercentCoupon));

        CouponValidationResponse response = couponService.validateCoupon("SAVE20", 1L, 1, rider);

        assertFalse(response.isValid());
        assertTrue(response.getMessage().contains("deactivated"));
    }

    @Test
    @DisplayName("Should reject coupon when ride fare is below minimum eligible fare")
    public void testValidateCoupon_BelowMinFare() {
        validPercentCoupon.setMinRideFare(500.0);
        when(couponRepository.findByCodeIgnoreCase("SAVE20")).thenReturn(Optional.of(validPercentCoupon));
        when(rideRepository.findById(1L)).thenReturn(Optional.of(ride));

        // 1 seat * 200 = 200 < 500
        CouponValidationResponse response = couponService.validateCoupon("SAVE20", 1L, 1, rider);

        assertFalse(response.isValid());
        assertTrue(response.getMessage().contains("Minimum ride fare"));
    }

    @Test
    @DisplayName("Should reject coupon when total usage limit is exhausted")
    public void testValidateCoupon_TotalLimitExhausted() {
        validPercentCoupon.setTotalUsageLimit(10);
        validPercentCoupon.setCurrentRedemptions(10);
        when(couponRepository.findByCodeIgnoreCase("SAVE20")).thenReturn(Optional.of(validPercentCoupon));

        CouponValidationResponse response = couponService.validateCoupon("SAVE20", 1L, 1, rider);

        assertFalse(response.isValid());
        assertTrue(response.getMessage().contains("reached its maximum total redemptions"));
    }

    @Test
    @DisplayName("Should reject coupon when user exceeds per-user redemption limit")
    public void testValidateCoupon_PerUserLimitExceeded() {
        when(couponRepository.findByCodeIgnoreCase("SAVE20")).thenReturn(Optional.of(validPercentCoupon));
        when(rideRepository.findById(1L)).thenReturn(Optional.of(ride));
        // Per-user limit is 2. Mock 2 redemptions already completed.
        when(couponRedemptionRepository.countByCouponIdAndUserIdAndStatusNot(eq(10L), eq(100L), anyString())).thenReturn(2L);

        CouponValidationResponse response = couponService.validateCoupon("SAVE20", 1L, 1, rider);

        assertFalse(response.isValid());
        assertTrue(response.getMessage().contains("maximum allowed times"));
    }

    @Test
    @DisplayName("Should reject first-ride-only coupon for returning passenger")
    public void testValidateCoupon_FirstRideOnly_ReturningUser() {
        when(couponRepository.findByCodeIgnoreCase("FIRST100")).thenReturn(Optional.of(firstRideCoupon));
        when(rideRepository.findById(1L)).thenReturn(Optional.of(ride));
        // Returning rider has 2 prior confirmed bookings
        when(bookingRepository.countByPassengerIdAndStatusIn(eq(100L), anyList())).thenReturn(2L);

        CouponValidationResponse response = couponService.validateCoupon("FIRST100", 1L, 1, rider);

        assertFalse(response.isValid());
        assertTrue(response.getMessage().contains("first ride"));
    }

    @Test
    @DisplayName("Should accept first-ride-only coupon for new passenger with 0 prior rides")
    public void testValidateCoupon_FirstRideOnly_NewUser() {
        when(couponRepository.findByCodeIgnoreCase("FIRST100")).thenReturn(Optional.of(firstRideCoupon));
        when(rideRepository.findById(1L)).thenReturn(Optional.of(ride));
        when(bookingRepository.countByPassengerIdAndStatusIn(eq(100L), anyList())).thenReturn(0L);
        when(couponRedemptionRepository.countByCouponIdAndUserIdAndStatusNot(eq(12L), eq(100L), anyString())).thenReturn(0L);

        CouponValidationResponse response = couponService.validateCoupon("FIRST100", 1L, 1, rider);

        assertTrue(response.isValid());
        assertEquals(100.0, response.getDiscountAmount());
    }

    @Test
    @DisplayName("Should redeem coupon atomically with pessimistic write lock and record snapshot on booking")
    public void testRedeemCouponAtomically_Success() {
        Booking booking = Booking.builder()
                .id(55L)
                .ride(ride)
                .passenger(rider)
                .seats(2)
                .baseFare(400.0)
                .riderPlatformFee(0.0)
                .totalAmount(350.0)
                .couponCode("SAVE20")
                .couponDiscountAmount(50.0)
                .couponFundingType("PLATFORM")
                .status("PENDING")
                .build();

        when(couponRepository.findByCodeIgnoreCaseForUpdate("SAVE20")).thenReturn(Optional.of(validPercentCoupon));
        when(couponRedemptionRepository.countByCouponIdAndUserIdAndStatusNot(eq(10L), eq(100L), anyString())).thenReturn(0L);
        when(couponRedemptionRepository.save(any(CouponRedemption.class))).thenAnswer(i -> i.getArgument(0));

        CouponRedemption redemption = couponService.redeemCouponAtomically("SAVE20", booking, rider);

        assertNotNull(redemption);
        assertEquals(50.0, redemption.getDiscountAmount());
        assertEquals(400.0, redemption.getOriginalFare());
        assertEquals(350.0, redemption.getFinalPayable());
        assertEquals("APPLIED", redemption.getStatus());
        assertEquals(11, validPercentCoupon.getCurrentRedemptions());

        verify(couponRepository).save(validPercentCoupon);
        verify(couponRedemptionRepository).save(any(CouponRedemption.class));
    }

    @Test
    @DisplayName("Should restore coupon eligibility on booking cancellation")
    public void testRestoreCouponOnCancellation_Success() {
        Booking booking = Booking.builder()
                .id(55L)
                .couponCode("SAVE20")
                .status("CANCELLED")
                .passenger(rider)
                .build();

        CouponRedemption redemption = CouponRedemption.builder()
                .id(1L)
                .booking(booking)
                .coupon(validPercentCoupon)
                .user(rider)
                .couponCode("SAVE20")
                .discountAmount(50.0)
                .status("APPLIED")
                .build();

        validPercentCoupon.setCurrentRedemptions(5);

        when(couponRedemptionRepository.findByBookingId(55L)).thenReturn(Optional.of(redemption));
        when(couponRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(validPercentCoupon));

        couponService.restoreCouponOnCancellation(booking);

        assertEquals("CANCELLED_RESTORED", redemption.getStatus());
        assertNotNull(redemption.getRestoredAt());
        assertEquals(4, validPercentCoupon.getCurrentRedemptions());

        verify(couponRepository).save(validPercentCoupon);
        verify(couponRedemptionRepository).save(redemption);
    }

    @Test
    @DisplayName("Platform-funded discount must NEVER reduce driver net earnings")
    public void testFeeCalculation_PlatformFunded_ProtectsDriverNetEarnings() {
        PlatformSettings settings = PlatformSettings.builder()
                .driverCommissionEnabled(true)
                .driverCommissionType("PERCENTAGE")
                .driverCommissionRatePercent(10.0)
                .build();

        // Base ride fare: ₹500 (1 seat @ 500). Driver commission rate: 10% (₹50).
        // Passenger discount: ₹100 (Platform-funded).
        // Expected:
        // - totalAmount = 500 + riderPlatformFee (0) - 100 = 400
        // - driverNetEarnings = 500 - 50 = 450 (NOT reduced by 100!)
        // - platform absorbs the discount: platformRetention = 50 - 100 = -50
        FeeCalculationResult result = feeCalculationService.calculateQuote(
                500.0,
                1,
                settings,
                BigDecimal.valueOf(100.0),
                "PROMO100",
                "FIXED",
                "PLATFORM"
        );

        assertNotNull(result);
        assertEquals(new BigDecimal("500.00"), result.getBaseFare());
        assertEquals(new BigDecimal("100.00"), result.getDiscount());
        assertEquals("PLATFORM", result.getCouponFundingType());
        assertEquals(new BigDecimal("400.00"), result.getTotalAmount());
        // Driver earnings strictly intact
        assertEquals(new BigDecimal("450.00"), result.getDriverNetEarnings());
        // Platform retention reflects absorbing the promotional discount
        assertEquals(new BigDecimal("-50.00"), result.getPlatformRetention());
    }

    @Test
    @DisplayName("Driver-funded discount reduces driver net earnings when explicitly configured")
    public void testFeeCalculation_DriverFunded_DeductsFromDriver() {
        PlatformSettings settings = PlatformSettings.builder()
                .driverCommissionEnabled(true)
                .driverCommissionType("PERCENTAGE")
                .driverCommissionRatePercent(10.0)
                .build();

        // Base ride fare: ₹500. Driver commission rate: 10% (₹50).
        // Passenger discount: ₹100 (Driver-funded partner deal).
        // Expected:
        // - totalAmount = 400
        // - driverNetEarnings = (500 - 100) - 50 = 350
        // - platform retention remains unaffected: platformRetention = 50
        FeeCalculationResult result = feeCalculationService.calculateQuote(
                500.0,
                1,
                settings,
                BigDecimal.valueOf(100.0),
                "PROMO100",
                "FIXED",
                "DRIVER"
        );

        assertNotNull(result);
        assertEquals(new BigDecimal("500.00"), result.getBaseFare());
        assertEquals(new BigDecimal("100.00"), result.getDiscount());
        assertEquals("DRIVER", result.getCouponFundingType());
        assertEquals(new BigDecimal("400.00"), result.getTotalAmount());
        assertEquals(new BigDecimal("350.00"), result.getDriverNetEarnings());
        assertEquals(new BigDecimal("50.00"), result.getPlatformRetention());
    }
}
