package com.rideshare.backend.repository;

import com.rideshare.backend.model.CommissionLedger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CommissionLedgerRepository extends JpaRepository<CommissionLedger, Long> {

    Optional<CommissionLedger> findByIdempotencyKey(String idempotencyKey);

    @Query("SELECT c FROM CommissionLedger c LEFT JOIN FETCH c.booking LEFT JOIN FETCH c.ride WHERE c.driver.id = :driverId ORDER BY c.createdAt DESC")
    List<CommissionLedger> findByDriverIdOrderByCreatedAtDesc(@Param("driverId") Long driverId);

    List<CommissionLedger> findByDriverIdAndEntryTypeAndStatus(Long driverId, String entryType, String status);

    @Query("SELECT COALESCE(SUM(c.commissionAmount), 0.0) FROM CommissionLedger c WHERE c.driver.id = :driverId AND c.entryType = 'CHARGE' AND c.status = 'OUTSTANDING'")
    Double getOutstandingCommissionByDriverId(@Param("driverId") Long driverId);

    @Query("SELECT COALESCE(SUM(c.commissionAmount), 0.0) FROM CommissionLedger c WHERE c.driver.id = :driverId AND c.entryType = 'SETTLEMENT' AND c.status = 'VERIFIED'")
    Double getSettledCommissionByDriverId(@Param("driverId") Long driverId);

    @Query("SELECT COALESCE(SUM(c.commissionAmount), 0.0) FROM CommissionLedger c WHERE c.driver.id = :driverId AND c.entryType = 'CHARGE'")
    Double getTotalCommissionChargedByDriverId(@Param("driverId") Long driverId);

    @Query("SELECT COALESCE(SUM(c.grossFare), 0.0) FROM CommissionLedger c WHERE c.driver.id = :driverId AND c.entryType = 'CHARGE'")
    Double getTotalCashGrossFareByDriverId(@Param("driverId") Long driverId);

    @Query("SELECT COALESCE(SUM(c.driverNetAmount), 0.0) FROM CommissionLedger c WHERE c.driver.id = :driverId AND c.entryType = 'DRIVER_EARNING'")
    Double getTotalDriverEarningsByDriverId(@Param("driverId") Long driverId);

    @Query("SELECT COALESCE(SUM(c.driverNetAmount), 0.0) FROM CommissionLedger c WHERE c.driver.id = :driverId AND c.entryType = 'DRIVER_EARNING' AND c.status = 'PAYOUT_ELIGIBLE'")
    Double getPayoutEligibleEarningsByDriverId(@Param("driverId") Long driverId);

    @Query("SELECT COALESCE(SUM(c.commissionAmount), 0.0) FROM CommissionLedger c WHERE c.entryType IN ('CHARGE', 'PLATFORM_FEE')")
    Double getTotalPlatformFees();

    @Query("SELECT COALESCE(SUM(c.commissionAmount), 0.0) FROM CommissionLedger c WHERE c.entryType IN ('CHARGE', 'PLATFORM_FEE')")
    Double sumPlatformCommissionCharged();

    @Query("SELECT COALESCE(SUM(c.commissionAmount), 0.0) FROM CommissionLedger c WHERE c.entryType = 'CHARGE' AND c.status = 'SETTLED'")
    Double sumPlatformCommissionSettled();

    @Query("SELECT COALESCE(SUM(c.commissionAmount), 0.0) FROM CommissionLedger c WHERE c.entryType = 'CHARGE' AND c.status = 'OUTSTANDING'")
    Double sumPlatformCommissionOutstanding();

    @Query("SELECT COALESCE(SUM(c.driverNetAmount), 0.0) FROM CommissionLedger c WHERE c.entryType = 'CHARGE'")
    Double sumDriverNetFromCash();

    @Query("SELECT COALESCE(SUM(c.grossFare), 0.0) FROM CommissionLedger c WHERE c.entryType = 'CHARGE'")
    Double sumGrossCash();

    @Query("SELECT COALESCE(SUM(c.driverNetAmount), 0.0) FROM CommissionLedger c WHERE c.entryType = 'DRIVER_EARNING'")
    Double sumDriverEarningsOnline();

    Optional<CommissionLedger> findByBookingIdAndEntryType(Long bookingId, String entryType);

    List<CommissionLedger> findAllByOrderByCreatedAtDesc();

    @Query("SELECT c FROM CommissionLedger c WHERE " +
           "(CAST(:entryType AS string) IS NULL OR c.entryType = :entryType) AND " +
           "(CAST(:status AS string) IS NULL OR c.status = :status) AND " +
           "(CAST(:search AS string) IS NULL OR LOWER(c.idempotencyKey) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) OR " +
           "LOWER(c.notes) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) OR " +
           "(c.driver IS NOT NULL AND LOWER(c.driver.name) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))) OR " +
           "(c.driver IS NOT NULL AND LOWER(c.driver.email) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')))) " +
           "ORDER BY c.createdAt DESC")
    org.springframework.data.domain.Page<CommissionLedger> searchLedger(
            @Param("entryType") String entryType,
            @Param("status") String status,
            @Param("search") String search,
            org.springframework.data.domain.Pageable pageable);
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("DELETE FROM CommissionLedger c WHERE c.ride.id = :rideId")
    void deleteByRideId(@Param("rideId") Long rideId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("DELETE FROM CommissionLedger c WHERE c.booking.id = :bookingId")
    void deleteByBookingId(@Param("bookingId") Long bookingId);
}
