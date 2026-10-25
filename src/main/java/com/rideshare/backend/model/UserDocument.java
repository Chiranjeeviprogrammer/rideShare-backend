package com.rideshare.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "user_documents", indexes = {
    @Index(name = "idx_user_documents_user", columnList = "user_id"),
    @Index(name = "idx_user_documents_type", columnList = "document_type"),
    @Index(name = "idx_user_documents_status", columnList = "verification_status")
})
public class UserDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "user_id", nullable = false)
    @JsonIgnoreProperties({"password", "resetToken", "resetTokenExpiresAt"})
    private User user;

    @Column(name = "document_type", nullable = false, length = 64)
    private String documentType; // AVATAR, ID_CARD_FRONT, ID_CARD_BACK, LICENSE_FRONT, LICENSE_BACK, RC, INSURANCE, PERMIT, FITNESS, ADDRESS_PROOF

    @Column(name = "document_name", nullable = false)
    private String documentName;

    @Column(nullable = false, length = 64)
    private String category; // PROFILE_PHOTO, IDENTITY_PROOF, DRIVER_LICENSE, VEHICLE_REGISTRATION, VEHICLE_INSURANCE, PERMIT_FITNESS, ADDRESS_PROOF

    @Column(name = "file_url", length = 2048)
    private String fileUrl;

    @Column(name = "storage_path", length = 1024)
    private String storagePath;

    @Column(name = "storage_provider", length = 64)
    private String storageProvider = "LOCAL_VAULT"; // LOCAL_VAULT, GOOGLE_DRIVE, SEED_MOCK

    @Column(name = "mime_type", length = 64)
    private String mimeType = "image/jpeg";

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Column(name = "document_number", length = 128)
    private String documentNumber; // e.g. DL number, RC plate, ID number

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    // Status: PENDING, APPROVED, REJECTED, EXPIRED, REPLACEMENT_REQUESTED
    @Column(name = "verification_status", nullable = false, length = 64)
    private String verificationStatus = "PENDING";

    @Column(name = "uploaded_at")
    private LocalDateTime uploadedAt;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "reviewed_by_id")
    @JsonIgnoreProperties({"password", "resetToken", "resetTokenExpiresAt"})
    private User reviewedBy;

    @Column(name = "rejection_reason", length = 1000)
    private String rejectionReason;

    @Column(nullable = false)
    private Integer version = 1;

    @Column(name = "is_current", nullable = false)
    private Boolean isCurrent = true;

    @PrePersist
    public void prePersist() {
        if (this.uploadedAt == null) {
            this.uploadedAt = LocalDateTime.now();
        }
        if (this.verificationStatus == null) {
            this.verificationStatus = "PENDING";
        }
        if (this.version == null) {
            this.version = 1;
        }
        if (this.isCurrent == null) {
            this.isCurrent = true;
        }
        if (this.storageProvider == null) {
            this.storageProvider = "LOCAL_VAULT";
        }
        if (this.mimeType == null) {
            this.mimeType = "image/jpeg";
        }
    }
}
