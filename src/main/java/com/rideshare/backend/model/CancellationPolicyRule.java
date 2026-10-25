package com.rideshare.backend.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "cancellation_policy_rules")
public class CancellationPolicyRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "min_hours_before_departure", nullable = false)
    private Double minHoursBeforeDeparture;

    @Column(name = "max_hours_before_departure")
    private Double maxHoursBeforeDeparture;

    @Column(name = "fee_type", nullable = false, length = 30)
    @Builder.Default
    private String feeType = "FIXED"; // FIXED, PERCENTAGE

    @Column(name = "fee_amount", nullable = false)
    @Builder.Default
    private Double feeAmount = 0.0; // Default ₹0.00 as required

    @Column(name = "is_refundable", nullable = false)
    @Builder.Default
    private Boolean isRefundable = true;

    @Column(name = "applies_to", length = 30)
    @Builder.Default
    private String appliesTo = "PASSENGER"; // PASSENGER, DRIVER, ALL

    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    @Column(name = "sort_order")
    @Builder.Default
    private Integer sortOrder = 1;

    @Column(length = 500)
    private String description;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = LocalDateTime.now();
        if (feeAmount == null) feeAmount = 0.0;
        if (active == null) active = true;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
