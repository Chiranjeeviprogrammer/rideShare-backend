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
    name = "email_campaigns",
    indexes = {
        @Index(name = "idx_campaign_status", columnList = "status"),
        @Index(name = "idx_campaign_type", columnList = "campaign_type"),
        @Index(name = "idx_campaign_created_at", columnList = "created_at")
    }
)
public class EmailCampaign {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 180)
    private String name;

    @Column(nullable = false, length = 255)
    private String subject;

    @Column(name = "preview_text", length = 255)
    private String previewText;

    /**
     * COMING_SOON, PASSENGER_ACCESS, DRIVER_REGISTRATION, NEW_USER_WELCOME, PRODUCT_UPDATE, APP_LAUNCH, RE_ENGAGEMENT, CUSTOM
     */
    @Column(name = "campaign_type", nullable = false, length = 50)
    private String campaignType;

    /**
     * ALL, PASSENGER, DRIVER, NEWSLETTER
     */
    @Column(name = "target_audience", nullable = false, length = 50)
    private String targetAudience;

    /**
     * DRAFT, SCHEDULED, SENDING, SENT, CANCELLED
     */
    @Builder.Default
    @Column(nullable = false, length = 30)
    private String status = "DRAFT";

    @Column(name = "template_data_json", columnDefinition = "TEXT")
    private String templateDataJson;

    @Column(name = "rendered_html", columnDefinition = "TEXT")
    private String renderedHtml;

    @Builder.Default
    @Column(name = "recipient_count")
    private Integer recipientCount = 0;

    @Builder.Default
    @Column(name = "sent_count")
    private Integer sentCount = 0;

    @Builder.Default
    @Column(name = "failed_count")
    private Integer failedCount = 0;

    @Column(name = "scheduled_at")
    private LocalDateTime scheduledAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "created_by", length = 120)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
        if (this.status == null) {
            this.status = "DRAFT";
        }
        if (this.recipientCount == null) this.recipientCount = 0;
        if (this.sentCount == null) this.sentCount = 0;
        if (this.failedCount == null) this.failedCount = 0;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
