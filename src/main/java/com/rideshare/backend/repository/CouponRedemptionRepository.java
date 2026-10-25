package com.rideshare.backend.repository;

import com.rideshare.backend.model.CouponRedemption;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CouponRedemptionRepository extends JpaRepository<CouponRedemption, Long> {

    List<CouponRedemption> findByCouponIdOrderByRedeemedAtDesc(Long couponId);

    Page<CouponRedemption> findByCouponIdOrderByRedeemedAtDesc(Long couponId, Pageable pageable);

    Optional<CouponRedemption> findByBookingId(Long bookingId);

    long countByCouponIdAndUserIdAndStatusNot(Long couponId, Long userId, String status);

    long countByCouponIdAndStatusNot(Long couponId, String status);

    long countByCouponIdAndStatus(Long couponId, String status);

    @Query("SELECT COALESCE(SUM(r.discountAmount), 0.0) FROM CouponRedemption r WHERE r.coupon.id = :couponId AND r.status != 'CANCELLED_RESTORED'")
    Double sumDiscountAmountByCouponId(@Param("couponId") Long couponId);

    @Query("SELECT COALESCE(SUM(r.discountAmount), 0.0) FROM CouponRedemption r WHERE r.status != 'CANCELLED_RESTORED'")
    Double sumTotalDiscountExpended();

    @Query("SELECT COALESCE(SUM(r.discountAmount), 0.0) FROM CouponRedemption r WHERE r.status != 'CANCELLED_RESTORED' AND r.fundingType = :fundingType")
    Double sumTotalDiscountByFundingType(@Param("fundingType") String fundingType);

    long countByStatusNot(String status);
}
