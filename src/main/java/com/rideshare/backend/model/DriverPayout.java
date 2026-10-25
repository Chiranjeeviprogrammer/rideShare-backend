package com.rideshare.backend.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "driver_payouts",
    indexes = {
        @Index(name = "idx_payout_driver_id", columnList = "driver_id"),
        @Index(name = "idx_payout_status", columnList = "status"),
        @Index(name = "idx_payout_idempotency", columnList = "idempotency_key", unique = true)
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DriverPayout {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payout_id", nullable = false, unique = true, length = 64)
    private String payoutId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User driver;

    @Column(nullable = false)
    private double amount;

    @Column(nullable = false, length = 10)
    @Builder.Default
    private String currency = "INR";

    /**
     * PENDING, PROCESSING, PAID, FAILED
     */
    @Column(nullable = false, length = 32)
    @Builder.Default
    private String status = "PENDING";

    @Column(length = 32)
    @Builder.Default
    private String provider = "RAZORPAYX";

    @Column(name = "provider_payout_id")
    private String providerPayoutId;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "account_type", length = 32)
    private String accountType;

    @Column(name = "account_reference")
    private String accountReference; // Masked bank account or UPI handle

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Column(name = "notes")
    private String notes;

    @PrePersist
    public void prePersist() {
        if (this.createdAt == null) this.createdAt = LocalDateTime.now();
        if (this.status == null) this.status = "PENDING";
        if (this.currency == null) this.currency = "INR";
        if (this.provider == null) this.provider = "RAZORPAYX";
    }
}
