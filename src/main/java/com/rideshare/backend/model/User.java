package com.rideshare.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "users", indexes = {
    @Index(name = "idx_users_email", columnList = "email"),
    @Index(name = "idx_users_role", columnList = "role"),
    @Index(name = "idx_users_status", columnList = "status"),
    @Index(name = "idx_users_phone", columnList = "phoneNumber")
})
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String email;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    private String role; // RIDER or DRIVER
    private String phoneNumber;
    
    @Column(name = "avatar_url")
    private String avatarUrl;
    
    @Column(name = "referral_code", unique = true)
    private String referralCode;
    
    @Column(name = "referred_by")
    private String referredBy; // The code that referred this user

    @Column(name = "verified")
    private Boolean verified = true; // Default true so existing users stay functional

    // Identity Verification Document Fields (for Passenger / Community Trust)
    @Column(name = "id_card_type")
    private String idCardType; // AADHAAR, PASSPORT, DRIVING_LICENSE, VOTER_ID

    @Column(name = "id_card_number")
    private String idCardNumber;

    @Column(name = "id_card_front_url", length = 2048)
    private String idCardFrontUrl;

    @Column(name = "id_card_back_url", length = 2048)
    private String idCardBackUrl;

    @Column(name = "id_verification_status")
    private String idVerificationStatus = "UNVERIFIED"; // UNVERIFIED, PENDING, VERIFIED, REJECTED

    @JsonIgnore
    @Column(name = "reset_token")
    private String resetToken;

    @JsonIgnore
    @Column(name = "reset_token_expires_at")
    private java.time.LocalDateTime resetTokenExpiresAt;

    @Column(name = "status")
    private String status = "ACTIVE"; // ACTIVE, SUSPENDED

    @Column(name = "suspension_reason")
    private String suspensionReason;

    @Column(name = "suspended_at")
    private java.time.LocalDateTime suspendedAt;

    @Column(name = "suspended_by")
    private String suspendedBy;

    @Column(name = "created_at")
    private java.time.LocalDateTime createdAt;

    @Column(name = "preferred_language")
    private String preferredLanguage = "en"; // en, hi

    // Commuter Office Carpooling Preferences
    @Column(name = "home_address")
    private String homeAddress;

    @Column(name = "home_latitude")
    private Double homeLatitude;

    @Column(name = "home_longitude")
    private Double homeLongitude;

    @Column(name = "office_address")
    private String officeAddress;

    @Column(name = "office_latitude")
    private Double officeLatitude;

    @Column(name = "office_longitude")
    private Double officeLongitude;

    @Column(name = "work_company")
    private String workCompany;

    @PrePersist
    public void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = java.time.LocalDateTime.now();
        }
        if (this.status == null) {
            this.status = "ACTIVE";
        }
        if (this.preferredLanguage == null || this.preferredLanguage.isBlank()) {
            this.preferredLanguage = "en";
        }
    }
}
