package com.rideshare.backend.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "payment_orders")
public class PaymentOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String orderId; // internal unique order id

    private String gatewayOrderId; // Razorpay/Gateway order ID
    private String gatewayPaymentId; // Razorpay payment ID

    @ManyToOne
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne
    @JoinColumn(name = "ride_id")
    private Ride ride;

    @Column(nullable = false)
    private double amount;

    @Column(name = "platform_fee")
    @Builder.Default
    private double platformFee = 0.0;

    @Column(name = "driver_amount")
    @Builder.Default
    private double driverAmount = 0.0;

    @Column(nullable = false)
    private String currency;

    @Builder.Default
    private String paymentMethod = "ONLINE";

    @Builder.Default
    private String provider = "RAZORPAY";

    @Column(nullable = false)
    private String status; // CREATED, PAID, FAILED, REFUNDED, EXPIRED

    private String failureReason;

    private LocalDateTime createdAt;
    private LocalDateTime paidAt;
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (this.createdAt == null) this.createdAt = LocalDateTime.now();
        if (this.updatedAt == null) this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
