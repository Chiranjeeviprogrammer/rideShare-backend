package com.rideshare.backend.service;

import com.rideshare.backend.dto.*;
import com.rideshare.backend.model.*;
import com.rideshare.backend.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class CouponService {

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private CouponRedemptionRepository couponRedemptionRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private RideRepository rideRepository;

    @Autowired
    private FeeCalculationService feeCalculationService;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired(required = false)
    private RedisCacheService redisCacheService;

    /**
     * Validate a coupon against a ride and passenger, returning calculated discount and breakdown.
     */
    @Transactional(readOnly = true)
    public CouponValidationResponse validateCoupon(String rawCode, Long rideId, int seats, User passenger) {
        if (rawCode == null || rawCode.trim().isEmpty()) {
            return CouponValidationResponse.builder()
                    .valid(false)
                    .errorCode("INVALID_CODE")
                    .message("Please enter a promo code.")
                    .build();
        }

        String code = rawCode.trim().toUpperCase();
        Coupon coupon = couponRepository.findByCodeIgnoreCase(code).orElse(null);
        if (coupon == null) {
            return CouponValidationResponse.builder()
                    .valid(false)
                    .code(code)
                    .errorCode("INVALID_CODE")
                    .message("Coupon code '" + code + "' is not valid.")
                    .build();
        }

        Ride ride = null;
        if (rideId != null) {
            ride = rideRepository.findById(rideId).orElse(null);
        }

        LocalDateTime now = LocalDateTime.now();

        // 1. Check Active Status
        if (!Boolean.TRUE.equals(coupon.getIsActive())) {
            return CouponValidationResponse.builder()
                    .valid(false)
                    .code(code)
                    .title(coupon.getTitle())
                    .errorCode("DISABLED")
                    .message("Promo code '" + code + "' is currently deactivated.")
                    .build();
        }

        // 2. Check Validity Window
        if (coupon.getValidFrom() != null && now.isBefore(coupon.getValidFrom())) {
            return CouponValidationResponse.builder()
                    .valid(false)
                    .code(code)
                    .title(coupon.getTitle())
                    .errorCode("NOT_STARTED")
                    .message("Promo code '" + code + "' is not active yet.")
                    .build();
        }
        if (coupon.getValidUntil() != null && now.isAfter(coupon.getValidUntil())) {
            return CouponValidationResponse.builder()
                    .valid(false)
                    .code(code)
                    .title(coupon.getTitle())
                    .errorCode("EXPIRED")
                    .message("Promo code '" + code + "' has expired.")
                    .build();
        }

        // 3. Check Total Usage Limit
        if (coupon.getTotalUsageLimit() != null && coupon.getTotalUsageLimit() > 0) {
            int redemptions = coupon.getCurrentRedemptions() != null ? coupon.getCurrentRedemptions() : 0;
            if (redemptions >= coupon.getTotalUsageLimit()) {
                return CouponValidationResponse.builder()
                        .valid(false)
                        .code(code)
                        .title(coupon.getTitle())
                        .errorCode("USAGE_LIMIT_EXHAUSTED")
                        .message("Promo code '" + code + "' has reached its maximum total redemptions.")
                        .build();
            }
        }

        // 4. Check Per-User Limit
        if (passenger != null && coupon.getPerUserLimit() != null && coupon.getPerUserLimit() > 0) {
            long userUses = couponRedemptionRepository.countByCouponIdAndUserIdAndStatusNot(
                    coupon.getId(), passenger.getId(), "CANCELLED_RESTORED"
            );
            if (userUses >= coupon.getPerUserLimit()) {
                return CouponValidationResponse.builder()
                        .valid(false)
                        .code(code)
                        .title(coupon.getTitle())
                        .errorCode("USER_LIMIT_EXCEEDED")
                        .message("You have already used promo code '" + code + "' the maximum allowed times.")
                        .build();
            }
        }

        // 5. Check First Ride Only Eligibility
        if (Boolean.TRUE.equals(coupon.getFirstRideOnly()) && passenger != null) {
            long completedOrConfirmed = bookingRepository.countByPassengerIdAndStatusIn(
                    passenger.getId(), List.of("CONFIRMED", "COMPLETED")
            );
            if (completedOrConfirmed > 0) {
                return CouponValidationResponse.builder()
                        .valid(false)
                        .code(code)
                        .title(coupon.getTitle())
                        .errorCode("FIRST_RIDE_ONLY")
                        .message("Promo code '" + code + "' is exclusively valid for your first ride.")
                        .build();
            }
        }

        // 6. Check Minimum Ride Fare
        int requestedSeats = Math.max(1, seats);
        double farePerSeat = (ride != null && ride.getFare() != null) ? ride.getFare() : 0.0;
        double grossBaseFare = farePerSeat * requestedSeats;

        if (coupon.getMinRideFare() != null && coupon.getMinRideFare() > 0) {
            if (grossBaseFare < coupon.getMinRideFare()) {
                return CouponValidationResponse.builder()
                        .valid(false)
                        .code(code)
                        .title(coupon.getTitle())
                        .errorCode("MIN_FARE_NOT_MET")
                        .message(String.format("Minimum ride fare of ₹%.0f required to use '%s' (current base fare: ₹%.0f).",
                                coupon.getMinRideFare(), code, grossBaseFare))
                        .build();
            }
        }

        // 7. Calculate Discount Amount
        double calculatedDiscount = 0.0;
        if ("FIXED".equalsIgnoreCase(coupon.getDiscountType())) {
            calculatedDiscount = coupon.getDiscountValue() != null ? coupon.getDiscountValue() : 0.0;
        } else {
            // PERCENTAGE
            double pct = coupon.getDiscountValue() != null ? coupon.getDiscountValue() : 0.0;
            calculatedDiscount = (grossBaseFare * pct) / 100.0;
            if (coupon.getMaxDiscountCap() != null && coupon.getMaxDiscountCap() > 0) {
                calculatedDiscount = Math.min(calculatedDiscount, coupon.getMaxDiscountCap());
            }
        }

        // Calculate authoritative backend quote with discount applied
        FeeCalculationService.FeeCalculationResult quote = feeCalculationService.calculateQuote(
                ride,
                requestedSeats,
                BigDecimal.valueOf(calculatedDiscount),
                coupon.getCode(),
                coupon.getDiscountType(),
                coupon.getFundingType()
        );

        double actualDiscount = quote.getDiscount().doubleValue();
        double finalPayable = quote.getTotalAmount().doubleValue();
        double platformFee = quote.getRiderPlatformFee().doubleValue();

        return CouponValidationResponse.builder()
                .valid(true)
                .code(coupon.getCode())
                .title(coupon.getTitle())
                .description(coupon.getDescription())
                .discountType(coupon.getDiscountType())
                .discountValue(coupon.getDiscountValue() != null ? coupon.getDiscountValue() : 0.0)
                .discountAmount(actualDiscount)
                .originalBaseFare(quote.getBaseFare().doubleValue())
                .platformFee(platformFee)
                .finalPayable(finalPayable)
                .fundingType(coupon.getFundingType())
                .message(String.format("Coupon %s applied! Saved ₹%.2f on your ride.", coupon.getCode(), actualDiscount))
                .build();
    }

    /**
     * Get list of currently active public promotional offers for passenger app.
     */
    @Transactional(readOnly = true)
    public List<CouponOfferDto> getActiveOffers(User passenger, Long rideId) {
        LocalDateTime now = LocalDateTime.now();
        List<Coupon> activeCoupons = couponRepository.findActiveCoupons(now);

        long passengerCompletedRides = 0;
        if (passenger != null) {
            passengerCompletedRides = bookingRepository.countByPassengerIdAndStatusIn(
                    passenger.getId(), List.of("CONFIRMED", "COMPLETED")
            );
        }

        final long userCompletedCount = passengerCompletedRides;

        return activeCoupons.stream().map(coupon -> {
            boolean eligible = true;
            String reason = null;

            if (passenger != null) {
                if (coupon.getPerUserLimit() != null && coupon.getPerUserLimit() > 0) {
                    long userUses = couponRedemptionRepository.countByCouponIdAndUserIdAndStatusNot(
                            coupon.getId(), passenger.getId(), "CANCELLED_RESTORED"
                    );
                    if (userUses >= coupon.getPerUserLimit()) {
                        eligible = false;
                        reason = "Maximum uses reached";
                    }
                }

                if (eligible && Boolean.TRUE.equals(coupon.getFirstRideOnly()) && userCompletedCount > 0) {
                    eligible = false;
                    reason = "Valid on first ride only";
                }
            }

            return CouponOfferDto.builder()
                    .id(coupon.getId())
                    .code(coupon.getCode())
                    .title(coupon.getTitle())
                    .description(coupon.getDescription())
                    .discountType(coupon.getDiscountType())
                    .discountValue(coupon.getDiscountValue())
                    .maxDiscountCap(coupon.getMaxDiscountCap())
                    .minRideFare(coupon.getMinRideFare())
                    .validUntil(coupon.getValidUntil())
                    .fundingType(coupon.getFundingType())
                    .campaignType(coupon.getCampaignType())
                    .firstRideOnly(coupon.getFirstRideOnly())
                    .isEligibleForUser(eligible)
                    .ineligibleReason(reason)
                    .build();
        }).collect(Collectors.toList());
    }

    /**
     * Atomically apply and redeem a coupon on a confirmed booking inside a pessimistic lock transaction.
     * Prevents race conditions and duplicate redemptions.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CouponRedemption redeemCouponAtomically(String rawCode, Booking booking, User passenger) {
        if (rawCode == null || rawCode.trim().isEmpty() || booking == null || passenger == null) {
            return null;
        }

        String code = rawCode.trim().toUpperCase();

        // Concurrency protection: Pessimistic write lock on coupon row
        Coupon coupon = couponRepository.findByCodeIgnoreCaseForUpdate(code).orElse(null);
        if (coupon == null) {
            log.warn("Attempted to redeem nonexistent coupon '{}' for booking #{}", code, booking.getId());
            return null;
        }

        // Re-validate inside locked transaction
        LocalDateTime now = LocalDateTime.now();
        if (!Boolean.TRUE.equals(coupon.getIsActive())) {
            log.warn("Coupon '{}' is inactive during atomic redemption for booking #{}", code, booking.getId());
            return null;
        }
        if (coupon.getValidUntil() != null && now.isAfter(coupon.getValidUntil())) {
            log.warn("Coupon '{}' is expired during atomic redemption for booking #{}", code, booking.getId());
            return null;
        }
        if (coupon.getTotalUsageLimit() != null && coupon.getTotalUsageLimit() > 0) {
            int current = coupon.getCurrentRedemptions() != null ? coupon.getCurrentRedemptions() : 0;
            if (current >= coupon.getTotalUsageLimit()) {
                log.warn("Coupon '{}' total limit exhausted during atomic redemption for booking #{}", code, booking.getId());
                return null;
            }
        }
        if (coupon.getPerUserLimit() != null && coupon.getPerUserLimit() > 0) {
            long userUses = couponRedemptionRepository.countByCouponIdAndUserIdAndStatusNot(
                    coupon.getId(), passenger.getId(), "CANCELLED_RESTORED"
            );
            if (userUses >= coupon.getPerUserLimit()) {
                log.warn("Coupon '{}' per-user limit exceeded for user #{} during atomic redemption", code, passenger.getId());
                return null;
            }
        }

        // Increment authoritative counter
        int updatedCount = (coupon.getCurrentRedemptions() != null ? coupon.getCurrentRedemptions() : 0) + 1;
        coupon.setCurrentRedemptions(updatedCount);
        couponRepository.save(coupon);

        double discountAmount = booking.getCouponDiscountAmount();
        double originalFare = booking.getBaseFare() + booking.getRiderPlatformFee();
        double finalPayable = booking.getTotalAmount();

        CouponRedemption redemption = CouponRedemption.builder()
                .coupon(coupon)
                .user(passenger)
                .booking(booking)
                .couponCode(coupon.getCode())
                .discountAmount(discountAmount)
                .originalFare(originalFare)
                .finalPayable(finalPayable)
                .fundingType(coupon.getFundingType())
                .status("APPLIED")
                .redeemedAt(LocalDateTime.now())
                .build();

        CouponRedemption saved = couponRedemptionRepository.save(redemption);
        log.info("Coupon '{}' redeemed successfully for booking #{} by user #{}. Discount: ₹{}",
                code, booking.getId(), passenger.getId(), discountAmount);

        // Invalidate Redis active list if total usage limit reached
        if (redisCacheService != null && coupon.getTotalUsageLimit() != null && updatedCount >= coupon.getTotalUsageLimit()) {
            redisCacheService.evictCouponCaches(coupon.getCode());
        }

        return saved;
    }

    /**
     * Restore coupon eligibility when a booking is cancelled before completion.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void restoreCouponOnCancellation(Booking booking) {
        if (booking == null || booking.getId() == null) return;

        Optional<CouponRedemption> redemptionOpt = couponRedemptionRepository.findByBookingId(booking.getId());
        if (redemptionOpt.isEmpty()) return;

        CouponRedemption redemption = redemptionOpt.get();
        if ("CANCELLED_RESTORED".equalsIgnoreCase(redemption.getStatus())) {
            return; // Already restored
        }

        Coupon coupon = couponRepository.findByIdForUpdate(redemption.getCoupon().getId()).orElse(null);
        if (coupon != null) {
            int current = coupon.getCurrentRedemptions() != null ? coupon.getCurrentRedemptions() : 0;
            coupon.setCurrentRedemptions(Math.max(0, current - 1));
            couponRepository.save(coupon);
        }

        redemption.setStatus("CANCELLED_RESTORED");
        redemption.setRestoredAt(LocalDateTime.now());
        couponRedemptionRepository.save(redemption);

        log.info("Restored coupon '{}' redemption for cancelled booking #{}",
                redemption.getCouponCode(), booking.getId());

        if (redisCacheService != null) {
            redisCacheService.evictCouponCaches(redemption.getCouponCode());
        }

        String passengerEmail = booking.getPassenger() != null ? booking.getPassenger().getEmail() : "USER";
        auditLogService.log(
                "SYSTEM",
                "COUPON_RESTORED",
                "COUPON",
                redemption.getCouponCode(),
                String.format("Restored coupon eligibility for %s on cancellation of booking #%d (discount ₹%.2f)",
                        passengerEmail, booking.getId(), redemption.getDiscountAmount()),
                "0.0.0.0"
        );
    }

    /**
     * Mark coupon redemption as completed when ride finishes.
     */
    @Transactional
    public void markRedemptionCompleted(Booking booking) {
        if (booking == null || booking.getId() == null) return;
        couponRedemptionRepository.findByBookingId(booking.getId()).ifPresent(r -> {
            if ("APPLIED".equalsIgnoreCase(r.getStatus())) {
                r.setStatus("COMPLETED");
                couponRedemptionRepository.save(r);
            }
        });
    }

    // ==========================================
    // ADMIN MANAGEMENT & AUDIT METHODS
    // ==========================================

    @Transactional(readOnly = true)
    public Page<CouponDto> getCouponsAdmin(String search, String status, String discountType, String fundingType, Pageable pageable) {
        LocalDateTime now = LocalDateTime.now();
        String searchParam = (search != null && !search.trim().isEmpty())
                ? "%" + search.trim().toLowerCase() + "%"
                : null;
        String statusParam = (status != null && !status.trim().isEmpty() && !"ALL".equalsIgnoreCase(status.trim()))
                ? status.trim().toUpperCase()
                : null;
        String discountTypeParam = (discountType != null && !discountType.trim().isEmpty() && !"ALL".equalsIgnoreCase(discountType.trim()))
                ? discountType.trim().toUpperCase()
                : null;
        String fundingTypeParam = (fundingType != null && !fundingType.trim().isEmpty() && !"ALL".equalsIgnoreCase(fundingType.trim()))
                ? fundingType.trim().toUpperCase()
                : null;

        Page<Coupon> page = couponRepository.findCouponsWithFilters(searchParam, statusParam, discountTypeParam, fundingTypeParam, now, pageable);

        List<CouponDto> dtos = page.getContent().stream().map(this::toDto).collect(Collectors.toList());
        return new PageImpl<>(dtos, pageable, page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public CouponDto getCouponById(Long id) {
        Coupon coupon = couponRepository.findById(id).orElse(null);
        return coupon != null ? toDto(coupon) : null;
    }

    @Transactional
    public CouponDto createCoupon(CouponDto request, String adminEmail, String ipAddress) {
        if (request.getCode() == null || request.getCode().trim().isEmpty()) {
            throw new IllegalArgumentException("Coupon code is required");
        }

        String cleanCode = request.getCode().trim().toUpperCase();
        if (couponRepository.existsByCodeIgnoreCase(cleanCode)) {
            throw new IllegalArgumentException("Coupon code '" + cleanCode + "' already exists");
        }

        Coupon coupon = Coupon.builder()
                .code(cleanCode)
                .title(request.getTitle())
                .description(request.getDescription())
                .discountType(request.getDiscountType() != null ? request.getDiscountType().toUpperCase() : "PERCENTAGE")
                .discountValue(request.getDiscountValue() != null ? request.getDiscountValue() : 0.0)
                .maxDiscountCap(request.getMaxDiscountCap())
                .minRideFare(request.getMinRideFare() != null ? request.getMinRideFare() : 0.0)
                .validFrom(request.getValidFrom() != null ? request.getValidFrom() : LocalDateTime.now())
                .validUntil(request.getValidUntil())
                .totalUsageLimit(request.getTotalUsageLimit() != null ? request.getTotalUsageLimit() : 0)
                .perUserLimit(request.getPerUserLimit() != null ? request.getPerUserLimit() : 1)
                .currentRedemptions(0)
                .firstRideOnly(Boolean.TRUE.equals(request.getFirstRideOnly()))
                .fundingType(request.getFundingType() != null ? request.getFundingType().toUpperCase() : "PLATFORM")
                .campaignType(request.getCampaignType() != null ? request.getCampaignType() : "PROMOTIONAL")
                .eligibleUserGroup(request.getEligibleUserGroup() != null ? request.getEligibleUserGroup() : "ALL")
                .isActive(request.getIsActive() != null ? request.getIsActive() : true)
                .createdBy(adminEmail)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        Coupon saved = couponRepository.save(coupon);

        if (redisCacheService != null) {
            redisCacheService.evictCouponCaches(saved.getCode());
        }

        auditLogService.log(
                adminEmail,
                "CREATE_COUPON",
                "COUPON",
                saved.getCode(),
                String.format("Created coupon '%s': %s (Type: %s, Value: %.2f, Funding: %s)",
                        saved.getCode(), saved.getTitle(), saved.getDiscountType(), saved.getDiscountValue(), saved.getFundingType()),
                ipAddress
        );

        return toDto(saved);
    }

    @Transactional
    public CouponDto updateCoupon(Long id, CouponDto request, String adminEmail, String ipAddress) {
        Coupon coupon = couponRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Coupon not found with ID: " + id));

        coupon.setTitle(request.getTitle());
        coupon.setDescription(request.getDescription());
        if (request.getDiscountType() != null) {
            coupon.setDiscountType(request.getDiscountType().toUpperCase());
        }
        if (request.getDiscountValue() != null) {
            coupon.setDiscountValue(request.getDiscountValue());
        }
        coupon.setMaxDiscountCap(request.getMaxDiscountCap());
        if (request.getMinRideFare() != null) {
            coupon.setMinRideFare(request.getMinRideFare());
        }
        if (request.getValidFrom() != null) {
            coupon.setValidFrom(request.getValidFrom());
        }
        coupon.setValidUntil(request.getValidUntil());
        if (request.getTotalUsageLimit() != null) {
            coupon.setTotalUsageLimit(request.getTotalUsageLimit());
        }
        if (request.getPerUserLimit() != null) {
            coupon.setPerUserLimit(request.getPerUserLimit());
        }
        if (request.getFirstRideOnly() != null) {
            coupon.setFirstRideOnly(request.getFirstRideOnly());
        }
        if (request.getFundingType() != null) {
            coupon.setFundingType(request.getFundingType().toUpperCase());
        }
        if (request.getCampaignType() != null) {
            coupon.setCampaignType(request.getCampaignType());
        }
        if (request.getEligibleUserGroup() != null) {
            coupon.setEligibleUserGroup(request.getEligibleUserGroup());
        }
        if (request.getIsActive() != null) {
            coupon.setIsActive(request.getIsActive());
        }
        coupon.setUpdatedAt(LocalDateTime.now());

        Coupon updated = couponRepository.save(coupon);

        if (redisCacheService != null) {
            redisCacheService.evictCouponCaches(updated.getCode());
        }

        auditLogService.log(
                adminEmail,
                "UPDATE_COUPON",
                "COUPON",
                updated.getCode(),
                String.format("Updated coupon configuration for '%s' (Funding: %s, Active: %s)",
                        updated.getCode(), updated.getFundingType(), updated.getIsActive()),
                ipAddress
        );

        return toDto(updated);
    }

    @Transactional
    public CouponDto toggleStatus(Long id, String adminEmail, String ipAddress) {
        Coupon coupon = couponRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Coupon not found with ID: " + id));

        boolean newStatus = !Boolean.TRUE.equals(coupon.getIsActive());
        coupon.setIsActive(newStatus);
        coupon.setUpdatedAt(LocalDateTime.now());
        Coupon updated = couponRepository.save(coupon);

        if (redisCacheService != null) {
            redisCacheService.evictCouponCaches(updated.getCode());
        }

        auditLogService.log(
                adminEmail,
                newStatus ? "ACTIVATE_COUPON" : "DEACTIVATE_COUPON",
                "COUPON",
                updated.getCode(),
                String.format("Coupon '%s' was %s by admin", updated.getCode(), newStatus ? "activated" : "deactivated"),
                ipAddress
        );

        return toDto(updated);
    }

    @Transactional
    public CouponDto expireCoupon(Long id, String adminEmail, String ipAddress) {
        Coupon coupon = couponRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Coupon not found with ID: " + id));

        coupon.setValidUntil(LocalDateTime.now().minusSeconds(1));
        coupon.setIsActive(false);
        coupon.setUpdatedAt(LocalDateTime.now());
        Coupon updated = couponRepository.save(coupon);

        if (redisCacheService != null) {
            redisCacheService.evictCouponCaches(updated.getCode());
        }

        auditLogService.log(
                adminEmail,
                "EXPIRE_COUPON",
                "COUPON",
                updated.getCode(),
                String.format("Coupon '%s' was immediately expired by admin", updated.getCode()),
                ipAddress
        );

        return toDto(updated);
    }

    @Transactional(readOnly = true)
    public CouponAnalyticsDto getAnalytics() {
        LocalDateTime now = LocalDateTime.now();
        long totalCoupons = couponRepository.count();
        List<Coupon> all = couponRepository.findAll();

        long activeCoupons = all.stream()
                .filter(c -> Boolean.TRUE.equals(c.getIsActive()) && (c.getValidUntil() == null || c.getValidUntil().isAfter(now)))
                .count();

        long expiredCoupons = all.stream()
                .filter(c -> c.getValidUntil() != null && c.getValidUntil().isBefore(now))
                .count();

        long totalRedemptions = couponRedemptionRepository.countByStatusNot("CANCELLED_RESTORED");
        Double totalSpend = couponRedemptionRepository.sumTotalDiscountExpended();
        Double platformSpend = couponRedemptionRepository.sumTotalDiscountByFundingType("PLATFORM");
        Double driverSpend = couponRedemptionRepository.sumTotalDiscountByFundingType("DRIVER");

        return CouponAnalyticsDto.builder()
                .totalCoupons(totalCoupons)
                .activeCoupons(activeCoupons)
                .expiredCoupons(expiredCoupons)
                .totalRedemptions(totalRedemptions)
                .totalDiscountExpenditure(totalSpend != null ? totalSpend : 0.0)
                .platformFundedExpenditure(platformSpend != null ? platformSpend : 0.0)
                .driverFundedExpenditure(driverSpend != null ? driverSpend : 0.0)
                .build();
    }

    @Transactional(readOnly = true)
    public Page<CouponRedemption> getCouponRedemptions(Long couponId, Pageable pageable) {
        return couponRedemptionRepository.findByCouponIdOrderByRedeemedAtDesc(couponId, pageable);
    }

    private CouponDto toDto(Coupon coupon) {
        Double totalExpended = couponRedemptionRepository.sumDiscountAmountByCouponId(coupon.getId());
        LocalDateTime now = LocalDateTime.now();
        boolean isExpired = coupon.getValidUntil() != null && coupon.getValidUntil().isBefore(now);

        return CouponDto.builder()
                .id(coupon.getId())
                .code(coupon.getCode())
                .title(coupon.getTitle())
                .description(coupon.getDescription())
                .discountType(coupon.getDiscountType())
                .discountValue(coupon.getDiscountValue())
                .maxDiscountCap(coupon.getMaxDiscountCap())
                .minRideFare(coupon.getMinRideFare())
                .validFrom(coupon.getValidFrom())
                .validUntil(coupon.getValidUntil())
                .totalUsageLimit(coupon.getTotalUsageLimit())
                .perUserLimit(coupon.getPerUserLimit())
                .currentRedemptions(coupon.getCurrentRedemptions())
                .firstRideOnly(coupon.getFirstRideOnly())
                .fundingType(coupon.getFundingType())
                .campaignType(coupon.getCampaignType())
                .eligibleUserGroup(coupon.getEligibleUserGroup())
                .isActive(coupon.getIsActive())
                .createdBy(coupon.getCreatedBy())
                .createdAt(coupon.getCreatedAt())
                .updatedAt(coupon.getUpdatedAt())
                .totalDiscountExpended(totalExpended != null ? totalExpended : 0.0)
                .isExpired(isExpired)
                .build();
    }
}
