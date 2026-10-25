package com.rideshare.backend.repository;

import com.rideshare.backend.model.Ride;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface RideRepository extends JpaRepository<Ride, Long> {
    List<Ride> findByRiderId(Long riderId);
    List<Ride> findByDriverId(Long driverId);
    List<Ride> findByDriverIdIn(List<Long> driverIds);
    List<Ride> findByStatus(String status);
    List<Ride> findByDriverIdAndStatusIn(Long driverId, List<String> statuses);
    Optional<Ride> findFirstByDriverIdAndStatusInOrderByRequestedAtDesc(Long driverId, List<String> statuses);

    List<Ride> findByRecurringScheduleId(Long scheduleId);
    List<Ride> findByRecurringScheduleIdAndStatusIn(Long scheduleId, List<String> statuses);
    Optional<Ride> findFirstByDriverIdAndDepartureTime(Long driverId, LocalDateTime departureTime);
    boolean existsByDriverIdAndDepartureTimeAndStatusIn(Long driverId, LocalDateTime departureTime, List<String> statuses);

    long countByStatus(String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Ride r WHERE r.id = :id")
    Optional<Ride> findByIdForUpdate(@Param("id") Long id);

    @Query(value = "SELECT r FROM Ride r " +
           "LEFT JOIN FETCH r.driver " +
           "LEFT JOIN FETCH r.rider " +
           "WHERE (CAST(:status AS string) IS NULL OR r.status = :status) AND " +
           "(CAST(:search AS string) IS NULL OR LOWER(r.pickupLocation) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) OR " +
           "LOWER(r.dropoffLocation) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) OR " +
           "(r.driver IS NOT NULL AND LOWER(r.driver.name) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))))",
           countQuery = "SELECT COUNT(r) FROM Ride r WHERE " +
           "(CAST(:status AS string) IS NULL OR r.status = :status) AND " +
           "(CAST(:search AS string) IS NULL OR LOWER(r.pickupLocation) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) OR " +
           "LOWER(r.dropoffLocation) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) OR " +
           "(r.driver IS NOT NULL AND LOWER(r.driver.name) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))))")
    Page<Ride> searchRides(@Param("status") String status,
                           @Param("search") String search,
                           Pageable pageable);

    @Query("SELECT DISTINCT r FROM Ride r " +
           "LEFT JOIN FETCH r.driver " +
           "LEFT JOIN FETCH r.rider " +
           "WHERE (r.status IN ('PUBLISHED', 'PENDING') OR r.status IS NULL) " +
           "ORDER BY r.departureTime ASC NULLS LAST, r.id DESC")
    List<Ride> findAllActiveRidesWithDetails();

    @Query("SELECT DISTINCT r FROM Ride r " +
           "LEFT JOIN FETCH r.driver " +
           "LEFT JOIN FETCH r.rider " +
           "WHERE (r.status IN ('PUBLISHED', 'PENDING') OR r.status IS NULL) " +
           "AND (CAST(:fromLoc AS string) IS NULL OR LOWER(r.pickupLocation) LIKE LOWER(CONCAT('%', CAST(:fromLoc AS string), '%'))) " +
           "AND (CAST(:toLoc AS string) IS NULL OR LOWER(r.dropoffLocation) LIKE LOWER(CONCAT('%', CAST(:toLoc AS string), '%'))) " +
           "AND (:minSeats IS NULL OR COALESCE(r.availableSeats, 3) >= :minSeats) " +
           "ORDER BY r.departureTime ASC NULLS LAST, r.id DESC")
    List<Ride> searchPublishedRides(@Param("fromLoc") String fromLoc,
                                   @Param("toLoc") String toLoc,
                                   @Param("minSeats") Integer minSeats);
}
