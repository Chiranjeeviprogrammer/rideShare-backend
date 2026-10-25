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
@Table(
    name = "email_audience_contacts",
    indexes = {
        @Index(name = "idx_contact_email", columnList = "email", unique = true),
        @Index(name = "idx_contact_audience_type", columnList = "audience_type"),
        @Index(name = "idx_contact_status", columnList = "status"),
        @Index(name = "idx_contact_unsub_token", columnList = "unsubscribe_token")
    }
)
public class EmailAudienceContact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 180)
    private String email;

    @Column(name = "full_name", length = 120)
    private String fullName;

    @Column(name = "phone_number", length = 30)
    private String phoneNumber;

    @Column(length = 80)
    private String city;

    /**
     * PASSENGER, DRIVER, NEWSLETTER, GENERAL
     */
    @Builder.Default
    @Column(name = "audience_type", nullable = false, length = 30)
    private String audienceType = "PASSENGER";

    /**
     * SUBSCRIBED, UNSUBSCRIBED, BOUNCED
     */
    @Builder.Default
    @Column(nullable = false, length = 30)
    private String status = "SUBSCRIBED";

    /**
     * WAITLIST_FORM, APP_SIGNUP, CSV_IMPORT, ADMIN_MANUAL, REFERRAL
     */
    @Builder.Default
    @Column(nullable = false, length = 50)
    private String source = "WAITLIST_FORM";

    @Builder.Default
    @Column(name = "consent_given", nullable = false)
    private Boolean consentGiven = true;

    @Column(name = "consent_timestamp")
    private LocalDateTime consentTimestamp;

    @Column(name = "unsubscribe_token", length = 80)
    private String unsubscribeToken;

    @Column(name = "unsubscribed_at")
    private LocalDateTime unsubscribedAt;

    @Column(name = "unsubscribe_reason", length = 255)
    private String unsubscribeReason;

    @Column(name = "last_emailed_at")
    private LocalDateTime lastEmailedAt;

    @Builder.Default
    @Column(name = "total_emails_received", nullable = false)
    private Integer totalEmailsReceived = 0;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
        if (this.consentTimestamp == null && Boolean.TRUE.equals(this.consentGiven)) {
            this.consentTimestamp = LocalDateTime.now();
        }
        if (this.unsubscribeToken == null || this.unsubscribeToken.isBlank()) {
            this.unsubscribeToken = java.util.UUID.randomUUID().toString();
        }
        if (this.status == null) {
            this.status = "SUBSCRIBED";
        }
        if (this.totalEmailsReceived == null) {
            this.totalEmailsReceived = 0;
        }
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
