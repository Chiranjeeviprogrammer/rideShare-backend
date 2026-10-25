package com.rideshare.backend.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "payment_refunds",
    indexes = {
        @Index(name = "idx_refund_booking_id", columnList = "booking_id"),
        @Index(name = "idx_refund_passenger_id", columnList = "passenger_id"),
        @Index(name = "idx_refund_status", columnList = "status"),
        @Index(name = "idx_refund_idempotency", columnList = "idempotency_key", unique = true)
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentRefund {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "refund_id", nullable = false, unique = true, length = 64)
    private String refundId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Booking booking;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_order_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private PaymentOrder paymentOrder;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "passenger_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User passenger;

    @Column(nullable = false)
    private double amount;

    @Column(name = "cancellation_fee")
    @Builder.Default
    private Double cancellationFee = 0.0;

    @Column(nullable = false, length = 10)
    @Builder.Default
    private String currency = "INR";

    private String reason;

    /**
     * REFUND_PENDING, REFUND_PROCESSING, REFUNDED, PARTIALLY_REFUNDED, REFUND_FAILED
     */
    @Column(nullable = false, length = 32)
    @Builder.Default
    private String status = "REFUND_PENDING";

    @Column(length = 32)
    @Builder.Default
    private String provider = "RAZORPAY";

    @Column(name = "provider_refund_id")
    private String providerRefundId;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @PrePersist
    public void prePersist() {
        if (this.createdAt == null) this.createdAt = LocalDateTime.now();
        if (this.status == null) this.status = "REFUND_PENDING";
        if (this.currency == null) this.currency = "INR";
        if (this.provider == null) this.provider = "RAZORPAY";
    }
}
