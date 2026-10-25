package com.rideshare.backend.repository;

import com.rideshare.backend.model.PaymentOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;

public interface PaymentOrderRepository extends JpaRepository<PaymentOrder, Long> {
    Optional<PaymentOrder> findByOrderId(String orderId);
    Optional<PaymentOrder> findByGatewayOrderId(String gatewayOrderId);
    List<PaymentOrder> findByBookingId(Long bookingId);

    long countByStatus(String status);
    long countByStatusIn(List<String> statuses);

    @org.springframework.data.jpa.repository.Query("SELECT COALESCE(SUM(p.amount), 0.0) FROM PaymentOrder p WHERE p.status = 'PAID'")
    Double sumPaidAmount();

    @org.springframework.data.jpa.repository.Query("SELECT COALESCE(SUM(p.amount), 0.0) FROM PaymentOrder p WHERE p.status = 'FAILED'")
    Double sumFailedAmount();

    @org.springframework.data.jpa.repository.Query("SELECT p FROM PaymentOrder p WHERE " +
           "(CAST(:status AS string) IS NULL OR p.status = :status) AND " +
           "(CAST(:search AS string) IS NULL OR LOWER(p.orderId) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) OR " +
           "LOWER(p.gatewayOrderId) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) OR " +
           "LOWER(p.gatewayPaymentId) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) OR " +
           "(p.user IS NOT NULL AND LOWER(p.user.name) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))) OR " +
           "(p.user IS NOT NULL AND LOWER(p.user.email) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')))) " +
           "ORDER BY p.createdAt DESC")
    org.springframework.data.domain.Page<PaymentOrder> searchPaymentOrders(
            @org.springframework.data.repository.query.Param("status") String status,
            @org.springframework.data.repository.query.Param("search") String search,
            org.springframework.data.domain.Pageable pageable);
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("DELETE FROM PaymentOrder p WHERE p.booking.id = :bookingId")
    void deleteByBookingId(@org.springframework.data.repository.query.Param("bookingId") Long bookingId);
}
