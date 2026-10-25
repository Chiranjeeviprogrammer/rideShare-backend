package com.rideshare.backend.repository;

import com.rideshare.backend.model.Coupon;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CouponRepository extends JpaRepository<Coupon, Long> {

    Optional<Coupon> findByCodeIgnoreCase(String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Coupon c WHERE LOWER(c.code) = LOWER(:code)")
    Optional<Coupon> findByCodeIgnoreCaseForUpdate(@Param("code") String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Coupon c WHERE c.id = :id")
    Optional<Coupon> findByIdForUpdate(@Param("id") Long id);

    boolean existsByCodeIgnoreCase(String code);

    @Query("SELECT c FROM Coupon c WHERE c.isActive = true " +
           "AND (c.validFrom IS NULL OR c.validFrom <= :now) " +
           "AND (c.validUntil IS NULL OR c.validUntil >= :now) " +
           "AND (c.totalUsageLimit IS NULL OR c.totalUsageLimit = 0 OR c.currentRedemptions < c.totalUsageLimit) " +
           "ORDER BY c.discountValue DESC")
    List<Coupon> findActiveCoupons(@Param("now") LocalDateTime now);

    @Query("SELECT c FROM Coupon c WHERE " +
           "(CAST(:search AS string) IS NULL OR LOWER(c.code) LIKE CAST(:search AS string) OR LOWER(c.title) LIKE CAST(:search AS string)) AND " +
           "(CAST(:status AS string) IS NULL OR " +
           "  (CAST(:status AS string) = 'ACTIVE' AND c.isActive = true AND (c.validUntil IS NULL OR c.validUntil >= :now)) OR " +
           "  (CAST(:status AS string) = 'INACTIVE' AND c.isActive = false) OR " +
           "  (CAST(:status AS string) = 'EXPIRED' AND c.validUntil IS NOT NULL AND c.validUntil < :now)) AND " +
           "(CAST(:discountType AS string) IS NULL OR c.discountType = CAST(:discountType AS string)) AND " +
           "(CAST(:fundingType AS string) IS NULL OR c.fundingType = CAST(:fundingType AS string))")
    Page<Coupon> findCouponsWithFilters(
            @Param("search") String search,
            @Param("status") String status,
            @Param("discountType") String discountType,
            @Param("fundingType") String fundingType,
            @Param("now") LocalDateTime now,
            Pageable pageable
    );
}
