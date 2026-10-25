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
@Table(name = "platform_settings")
public class PlatformSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Builder.Default
    @Column(name = "support_email")
    private String supportEmail = "support@rideshare.com";

    @Builder.Default
    @Column(name = "support_phone")
    private String supportPhone = "+91 98765 43210";

    @Builder.Default
    @Column(name = "company_name")
    private String companyName = "RideShare Mobility Technologies";

    @Builder.Default
    @Column(name = "company_address")
    private String companyAddress = "100 Tech Park, MG Road, Bengaluru, KA 560001, India";

    @Builder.Default
    @Column(name = "commission_enabled")
    private Boolean commissionEnabled = false;

    @Builder.Default
    @Column(name = "effective_date")
    private LocalDateTime effectiveDate = LocalDateTime.now();

    @Builder.Default
    @Column(name = "commission_rate_percent")
    private Double commissionRatePercent = 0.0;

    @Builder.Default
    @Column(name = "commission_fixed_fee")
    private Double commissionFixedFee = 0.0;

    @Builder.Default
    @Column(name = "platform_fee_type")
    private String platformFeeType = "PERCENTAGE"; // FIXED, PERCENTAGE, PERCENT_PLUS_FIXED

    @Builder.Default
    @Column(name = "platform_fee_amount")
    private Double platformFeeAmount = 0.0;

    // --- Independent Driver Commission Controls ---
    @Builder.Default
    @Column(name = "driver_commission_enabled")
    private Boolean driverCommissionEnabled = false;

    @Builder.Default
    @Column(name = "driver_commission_type")
    private String driverCommissionType = "PERCENTAGE"; // PERCENTAGE, FIXED, PERCENT_PLUS_FIXED

    @Builder.Default
    @Column(name = "driver_commission_rate_percent")
    private Double driverCommissionRatePercent = 0.0;

    @Builder.Default
    @Column(name = "driver_commission_fixed_amount")
    private Double driverCommissionFixedAmount = 0.0;

    @Builder.Default
    @Column(name = "driver_commission_effective_date")
    private LocalDateTime driverCommissionEffectiveDate = LocalDateTime.now();

    // --- Independent Rider Platform Fee Controls ---
    @Builder.Default
    @Column(name = "rider_fee_enabled")
    private Boolean riderFeeEnabled = false;

    @Builder.Default
    @Column(name = "rider_fee_type")
    private String riderFeeType = "FIXED"; // FIXED, PERCENTAGE

    @Builder.Default
    @Column(name = "rider_fee_amount")
    private Double riderFeeAmount = 0.0;

    @Builder.Default
    @Column(name = "rider_fee_percent")
    private Double riderFeePercent = 0.0;

    @Builder.Default
    @Column(name = "rider_fee_effective_date")
    private LocalDateTime riderFeeEffectiveDate = LocalDateTime.now();

    @Builder.Default
    @Column(name = "seat_lock_timeout_minutes")
    private Integer seatLockTimeoutMinutes = 10;

    @Builder.Default
    @Column(name = "outstanding_threshold")
    private Double outstandingThreshold = 500.0;

    @Builder.Default
    @Column(name = "email_notifications_enabled")
    private Boolean emailNotificationsEnabled = true;

    // --- Configurable Ride Cancellation Fee Settings ---
    // Every cancellation fee defaults to ₹0 until an authorized administrator explicitly configures a fee.
    @Builder.Default
    @Column(name = "cancellation_fee_enabled")
    private Boolean cancellationFeeEnabled = false;

    @Builder.Default
    @Column(name = "default_cancellation_fee")
    private Double defaultCancellationFee = 0.0;

    @Builder.Default
    @Column(name = "cancellation_fee_type")
    private String cancellationFeeType = "FIXED"; // FIXED, PERCENTAGE

    @Builder.Default
    @Column(name = "cancellation_grace_minutes")
    private Integer cancellationGraceMinutes = 10;

    @Builder.Default
    @Column(name = "cancellation_policy_notice")
    private String cancellationPolicyNotice = "Standard Carpooling Cancellation Policy: Cancellations are free up to 24 hours prior to departure. Zero platform penalty mode is active.";

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;

    public Boolean getDriverCommissionEnabled() {
        return driverCommissionEnabled != null ? driverCommissionEnabled : false;
    }

    public String getDriverCommissionType() {
        return driverCommissionType != null ? driverCommissionType : "PERCENTAGE";
    }

    public Double getDriverCommissionRatePercent() {
        return driverCommissionRatePercent != null ? driverCommissionRatePercent : 0.0;
    }

    public Double getDriverCommissionFixedAmount() {
        return driverCommissionFixedAmount != null ? driverCommissionFixedAmount : 0.0;
    }

    public Boolean getRiderFeeEnabled() {
        return riderFeeEnabled != null ? riderFeeEnabled : false;
    }

    public String getRiderFeeType() {
        return riderFeeType != null ? riderFeeType : "FIXED";
    }

    public Double getRiderFeeAmount() {
        return riderFeeAmount != null ? riderFeeAmount : 0.0;
    }

    public Double getRiderFeePercent() {
        return riderFeePercent != null ? riderFeePercent : 0.0;
    }

    @PrePersist
    @PreUpdate
    public void prePersist() {
        this.updatedAt = LocalDateTime.now();
        if (Boolean.TRUE.equals(this.commissionEnabled)) {
            if (this.driverCommissionEnabled == null || !this.driverCommissionEnabled) {
                if ((this.commissionRatePercent != null && this.commissionRatePercent > 0) || (this.commissionFixedFee != null && this.commissionFixedFee > 0)) {
                    this.driverCommissionEnabled = true;
                }
            }
            if (this.riderFeeEnabled == null || !this.riderFeeEnabled) {
                if ((this.platformFeeAmount != null && this.platformFeeAmount > 0) || (this.riderFeeAmount != null && this.riderFeeAmount > 0)) {
                    this.riderFeeEnabled = true;
                }
            }
        }
        if (Boolean.TRUE.equals(this.driverCommissionEnabled) || Boolean.TRUE.equals(this.riderFeeEnabled)) {
            this.commissionEnabled = true;
        }
        if (this.commissionRatePercent != null && this.commissionRatePercent > 0 && (this.driverCommissionRatePercent == null || this.driverCommissionRatePercent == 0.0)) {
            this.driverCommissionRatePercent = this.commissionRatePercent;
        }
        if (this.driverCommissionRatePercent != null && this.driverCommissionRatePercent > 0 && (this.commissionRatePercent == null || this.commissionRatePercent == 0.0)) {
            this.commissionRatePercent = this.driverCommissionRatePercent;
        }
        if (this.commissionFixedFee != null && this.commissionFixedFee > 0 && (this.driverCommissionFixedAmount == null || this.driverCommissionFixedAmount == 0.0)) {
            this.driverCommissionFixedAmount = this.commissionFixedFee;
        }
        if (this.driverCommissionFixedAmount != null && this.driverCommissionFixedAmount > 0 && (this.commissionFixedFee == null || this.commissionFixedFee == 0.0)) {
            this.commissionFixedFee = this.driverCommissionFixedAmount;
        }
        if (this.platformFeeAmount != null && this.platformFeeAmount > 0 && (this.riderFeeAmount == null || this.riderFeeAmount == 0.0)) {
            this.riderFeeAmount = this.platformFeeAmount;
        }
        if (this.riderFeeAmount != null && this.riderFeeAmount > 0 && (this.platformFeeAmount == null || this.platformFeeAmount == 0.0)) {
            this.platformFeeAmount = this.riderFeeAmount;
        }
        if (this.platformFeeType != null && this.riderFeeType == null) {
            this.riderFeeType = this.platformFeeType;
        }
        if (this.riderFeeType != null && this.platformFeeType == null) {
            this.platformFeeType = this.riderFeeType;
        }
    }
}
