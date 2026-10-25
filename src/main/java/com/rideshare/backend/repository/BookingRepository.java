package com.rideshare.backend.repository;

import com.rideshare.backend.model.Booking;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingRepository extends JpaRepository<Booking, Long> {
    List<Booking> findByPassengerIdOrderByBookedAtDesc(Long passengerId);
    List<Booking> findByPassengerIdIn(List<Long> passengerIds);
    List<Booking> findByRideId(Long rideId);
    List<Booking> findByRideIdAndPassengerId(Long rideId, Long passengerId);
    List<Booking> findByPassengerIdAndStatus(Long passengerId, String status);
    java.util.Optional<Booking> findByBookingCode(String bookingCode);

    long countByPassengerIdAndStatusIn(Long passengerId, List<String> statuses);

    long countByStatus(String status);
    long countByPaymentStatus(String paymentStatus);
    long countByPaymentMethod(String paymentMethod);
    
    @Query("SELECT COALESCE(SUM(b.totalAmount), 0.0) FROM Booking b WHERE b.status != 'CANCELLED'")
    Double getGrossBookingValue();

    @Query("SELECT COALESCE(SUM(b.riderPlatformFee), 0.0) FROM Booking b WHERE b.paymentStatus = 'PAID' AND b.status NOT IN ('CANCELLED', 'EXPIRED')")
    Double sumPaidRiderPlatformFees();

    @Query("SELECT COALESCE(SUM(b.driverCommissionAmount), 0.0) FROM Booking b WHERE b.paymentStatus = 'PAID' AND b.status NOT IN ('CANCELLED', 'EXPIRED')")
    Double sumPaidDriverCommissions();

    @Query("SELECT COALESCE(SUM(b.riderPlatformFee), 0.0) FROM Booking b WHERE b.paymentStatus = 'REFUNDED'")
    Double sumRefundedRiderPlatformFees();

    @Query("SELECT COALESCE(SUM(b.driverCommissionAmount), 0.0) FROM Booking b WHERE b.paymentStatus = 'REFUNDED'")
    Double sumReversedCommissions();

    @Query("SELECT b FROM Booking b WHERE b.status = 'PENDING' AND b.paymentStatus = 'PENDING' AND b.expiresAt IS NOT NULL AND b.expiresAt < :now")
    List<Booking> findExpiredTemporaryBookings(@Param("now") java.time.LocalDateTime now);

    @Query(value = "SELECT b FROM Booking b " +
           "LEFT JOIN FETCH b.passenger " +
           "LEFT JOIN FETCH b.ride " +
           "WHERE (CAST(:status AS string) IS NULL OR b.status = :status) AND " +
           "(CAST(:paymentStatus AS string) IS NULL OR b.paymentStatus = :paymentStatus) AND " +
           "(CAST(:paymentMethod AS string) IS NULL OR b.paymentMethod = :paymentMethod) AND " +
           "(CAST(:search AS string) IS NULL OR LOWER(b.bookingCode) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) OR " +
           "(b.passenger IS NOT NULL AND LOWER(b.passenger.name) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))) OR " +
           "(b.passenger IS NOT NULL AND LOWER(b.passenger.email) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))))",
           countQuery = "SELECT COUNT(b) FROM Booking b WHERE " +
           "(CAST(:status AS string) IS NULL OR b.status = :status) AND " +
           "(CAST(:paymentStatus AS string) IS NULL OR b.paymentStatus = :paymentStatus) AND " +
           "(CAST(:paymentMethod AS string) IS NULL OR b.paymentMethod = :paymentMethod) AND " +
           "(CAST(:search AS string) IS NULL OR LOWER(b.bookingCode) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) OR " +
           "(b.passenger IS NOT NULL AND LOWER(b.passenger.name) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))) OR " +
           "(b.passenger IS NOT NULL AND LOWER(b.passenger.email) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))))")
    Page<Booking> searchBookings(@Param("status") String status,
                                 @Param("paymentStatus") String paymentStatus,
                                 @Param("paymentMethod") String paymentMethod,
                                 @Param("search") String search,
                                 Pageable pageable);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("DELETE FROM Booking b WHERE b.ride.id = :rideId")
    void deleteByRideId(@Param("rideId") Long rideId);
}
