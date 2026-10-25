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
@Table(name = "gst_settings")
public class GstSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Builder.Default
    @Column(name = "business_name", nullable = false)
    private String businessName = "RideShare Mobility Technologies Private Limited";

    @Builder.Default
    @Column(name = "trade_name")
    private String tradeName = "RideShare";

    @Builder.Default
    @Column(name = "business_address", nullable = false, columnDefinition = "TEXT")
    private String businessAddress = "100 Tech Park, MG Road, Bengaluru, Karnataka 560001, India";

    @Builder.Default
    @Column(name = "business_state", nullable = false)
    private String businessState = "Karnataka";

    @Builder.Default
    @Column(name = "business_state_code", nullable = false)
    private String businessStateCode = "29";

    @Builder.Default
    @Column(name = "gstin", nullable = false, length = 32)
    private String gstin = "29ABCDE1234F1Z5";

    @Builder.Default
    @Column(name = "gst_status", nullable = false)
    private String gstStatus = "REGISTERED"; // REGISTERED, COMPOSITION, UNREGISTERED

    @Builder.Default
    @Column(name = "is_enabled", nullable = false)
    private Boolean isEnabled = true;

    @Builder.Default
    @Column(name = "prices_include_gst", nullable = false)
    private Boolean pricesIncludeGst = true; // true = inclusive, false = exclusive

    @Builder.Default
    @Column(name = "gst_rate", nullable = false)
    private Double gstRate = 5.0; // Percentage, e.g. 5.0, 12.0, 18.0

    @Builder.Default
    @Column(name = "hsn_sac_code", nullable = false)
    private String hsnSacCode = "996412"; // Passenger transportation services

    @Builder.Default
    @Column(name = "tax_rule_type", nullable = false)
    private String taxRuleType = "AUTOMATIC_POS"; // AUTOMATIC_POS, ALWAYS_CGST_SGST, ALWAYS_IGST

    @Builder.Default
    @Column(name = "invoice_prefix", nullable = false)
    private String invoicePrefix = "RS-INV-";

    @Builder.Default
    @Column(name = "next_invoice_number", nullable = false)
    private Long nextInvoiceNumber = 1001L;

    @Builder.Default
    @Column(name = "invoice_number_format", nullable = false)
    private String invoiceNumberFormat = "{PREFIX}{YEAR}-{NUMBER}";

    @Builder.Default
    @Column(name = "invoice_footer", columnDefinition = "TEXT")
    private String invoiceFooter = "This is a computer-generated tax invoice. No signature required. Applicable under SAC 996412 for passenger transport / carpooling digital platform services.";

    @Builder.Default
    @Column(name = "contact_email")
    private String contactEmail = "tax@rideshare.com";

    @Builder.Default
    @Column(name = "contact_phone")
    private String contactPhone = "+91 98765 43210";

    @Builder.Default
    @Column(name = "effective_from")
    private LocalDateTime effectiveFrom = LocalDateTime.now();

    @Builder.Default
    @Column(name = "verification_status", nullable = false)
    private String verificationStatus = "VERIFIED"; // VERIFIED, UNVERIFIED, PENDING_VERIFICATION, FAILED

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Column(name = "verified_by")
    private String verifiedBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;

    @PrePersist
    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
