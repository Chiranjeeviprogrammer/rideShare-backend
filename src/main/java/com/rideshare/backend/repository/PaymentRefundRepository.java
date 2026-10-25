package com.rideshare.backend.repository;

import com.rideshare.backend.model.PaymentRefund;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRefundRepository extends JpaRepository<PaymentRefund, Long> {

    Optional<PaymentRefund> findByRefundId(String refundId);

    Optional<PaymentRefund> findByIdempotencyKey(String idempotencyKey);

    Optional<PaymentRefund> findByProviderRefundId(String providerRefundId);

    List<PaymentRefund> findByBookingId(Long bookingId);

    List<PaymentRefund> findByPassengerIdOrderByCreatedAtDesc(Long passengerId);

    long countByStatus(String status);

    @Query("SELECT COALESCE(SUM(r.amount), 0.0) FROM PaymentRefund r WHERE r.status IN ('REFUNDED', 'PARTIALLY_REFUNDED')")
    Double getTotalRefundedAmount();

    @Query("SELECT r FROM PaymentRefund r WHERE " +
           "(CAST(:status AS string) IS NULL OR r.status = :status) AND " +
           "(CAST(:search AS string) IS NULL OR LOWER(r.refundId) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) OR " +
           "LOWER(r.providerRefundId) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) OR " +
           "(r.passenger IS NOT NULL AND LOWER(r.passenger.name) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))) OR " +
           "(r.passenger IS NOT NULL AND LOWER(r.passenger.email) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')))) " +
           "ORDER BY r.createdAt DESC")
    Page<PaymentRefund> searchRefunds(
            @Param("status") String status,
            @Param("search") String search,
            Pageable pageable);
}
