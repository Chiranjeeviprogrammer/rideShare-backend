package com.rideshare.backend.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "driver_payout_profiles",
    indexes = {
        @Index(name = "idx_payout_prof_driver", columnList = "driver_id", unique = true)
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DriverPayoutProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_id", nullable = false, unique = true)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User driver;

    /**
     * BANK_ACCOUNT or UPI
     */
    @Column(name = "account_type", nullable = false)
    @Builder.Default
    private String accountType = "BANK_ACCOUNT";

    @Column(name = "account_holder_name")
    private String accountHolderName;

    /**
     * Masked for privacy & security: e.g. "•••• •••• 5678"
     * Never store raw credentials or CVV/PIN
     */
    @Column(name = "account_number_masked")
    private String accountNumberMasked;

    @Column(name = "ifsc_code")
    private String ifscCode;

    @Column(name = "bank_name")
    private String bankName;

    @Column(name = "upi_id")
    private String upiId;

    /**
     * Gateway marketplace / payout contact reference (e.g. RazorpayX Contact ID)
     */
    @Column(name = "provider_contact_id")
    private String providerContactId;

    /**
     * Gateway fund account reference (e.g. RazorpayX Fund Account ID)
     */
    @Column(name = "provider_fund_account_id")
    private String providerFundAccountId;

    /**
     * PENDING, VERIFIED, REJECTED
     */
    @Column(name = "verification_status", nullable = false)
    @Builder.Default
    private String verificationStatus = "VERIFIED";

    @Column(name = "is_eligible_for_payout", nullable = false)
    @Builder.Default
    private Boolean isEligibleForPayout = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (this.createdAt == null) this.createdAt = LocalDateTime.now();
        if (this.updatedAt == null) this.updatedAt = LocalDateTime.now();
        if (this.verificationStatus == null) this.verificationStatus = "VERIFIED";
        if (this.isEligibleForPayout == null) this.isEligibleForPayout = true;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
