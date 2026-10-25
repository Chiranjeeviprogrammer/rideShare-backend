package com.rideshare.backend.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "commission_ledger",
    indexes = {
        @Index(name = "idx_comm_driver_id", columnList = "driver_id"),
        @Index(name = "idx_comm_booking_id", columnList = "booking_id"),
        @Index(name = "idx_comm_status", columnList = "status"),
        @Index(name = "idx_comm_entry_type", columnList = "entry_type")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommissionLedger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User driver;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id", nullable = true)
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private Booking booking;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ride_id", nullable = true)
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private Ride ride;

    /**
     * CHARGE (commission owed on cash collection) or SETTLEMENT (commission paid by driver)
     */
    @Column(name = "entry_type", nullable = false)
    private String entryType;

    /**
     * Total cash fare collected from rider
     */
    @Column(name = "gross_fare", nullable = false)
    private double grossFare;

    /**
     * Commission rate percent applied (snapshot)
     */
    @Column(name = "commission_rate_percent", nullable = false)
    private double commissionRatePercent;

    /**
     * Fixed fee applied (snapshot)
     */
    @Column(name = "fixed_fee", nullable = false)
    private double fixedFee;

    /**
     * Platform commission amount
     */
    @Column(name = "commission_amount", nullable = false)
    private double commissionAmount;

    /**
     * Net driver retention (grossFare - commissionAmount)
     */
    @Column(name = "driver_net_amount", nullable = false)
    private double driverNetAmount;

    /**
     * Status of this commission entry:
     * For CHARGE: OUTSTANDING, SETTLED, WAIVED
     * For SETTLEMENT: INITIATED, VERIFIED, FAILED
     */
    @Column(name = "status", nullable = false)
    private String status;

    /**
     * Unique idempotency key (e.g. "COMM_CHARGE_BOOKING_{id}" or "COMM_SETTLE_{orderId}")
     */
    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    /**
     * Associated settlement order reference (if paid or linked to a settlement batch)
     */
    @Column(name = "settlement_reference")
    private String settlementReference;

    @Column(name = "settled_at")
    private LocalDateTime settledAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "notes")
    private String notes;
}
