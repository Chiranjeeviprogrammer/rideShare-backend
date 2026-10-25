package com.rideshare.backend.repository;

import com.rideshare.backend.model.DriverPayout;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DriverPayoutRepository extends JpaRepository<DriverPayout, Long> {

    Optional<DriverPayout> findByPayoutId(String payoutId);

    Optional<DriverPayout> findByIdempotencyKey(String idempotencyKey);

    Optional<DriverPayout> findByProviderPayoutId(String providerPayoutId);

    List<DriverPayout> findByDriverIdOrderByCreatedAtDesc(Long driverId);

    long countByStatus(String status);

    @Query("SELECT COALESCE(SUM(p.amount), 0.0) FROM DriverPayout p WHERE p.status = 'PAID'")
    Double getTotalCompletedPayoutsAmount();

    @Query("SELECT COALESCE(SUM(p.amount), 0.0) FROM DriverPayout p WHERE p.status IN ('PENDING', 'PROCESSING')")
    Double getTotalPendingPayoutsAmount();

    @Query("SELECT COALESCE(SUM(p.amount), 0.0) FROM DriverPayout p WHERE p.driver.id = :driverId AND p.status = 'PAID'")
    Double getTotalPaidAmountByDriverId(@Param("driverId") Long driverId);

    @Query("SELECT p FROM DriverPayout p WHERE " +
           "(CAST(:status AS string) IS NULL OR p.status = :status) AND " +
           "(CAST(:search AS string) IS NULL OR LOWER(p.payoutId) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) OR " +
           "LOWER(p.providerPayoutId) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) OR " +
           "(p.driver IS NOT NULL AND LOWER(p.driver.name) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))) OR " +
           "(p.driver IS NOT NULL AND LOWER(p.driver.email) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')))) " +
           "ORDER BY p.createdAt DESC")
    Page<DriverPayout> searchPayouts(
            @Param("status") String status,
            @Param("search") String search,
            Pageable pageable);
}
