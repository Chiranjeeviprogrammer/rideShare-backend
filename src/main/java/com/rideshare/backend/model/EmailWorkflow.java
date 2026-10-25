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
    name = "email_workflows",
    indexes = {
        @Index(name = "idx_workflow_trigger", columnList = "trigger_type"),
        @Index(name = "idx_workflow_enabled", columnList = "enabled")
    }
)
public class EmailWorkflow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    /**
     * WAITLIST_SIGNUP, DRIVER_INTEREST, ONBOARDING_CONFIRMATION, FOLLOWUP_DELAYED, APP_LAUNCH_ALERT
     */
    @Column(name = "trigger_type", nullable = false, length = 60)
    private String triggerType;

    /**
     * ALL, PASSENGER, DRIVER
     */
    @Builder.Default
    @Column(name = "target_audience", nullable = false, length = 50)
    private String targetAudience = "ALL";

    @Column(nullable = false, length = 255)
    private String subject;

    @Column(name = "preview_text", length = 255)
    private String previewText;

    @Column(name = "template_data_json", columnDefinition = "TEXT")
    private String templateDataJson;

    @Column(name = "rendered_html", columnDefinition = "TEXT")
    private String renderedHtml;

    @Builder.Default
    @Column(name = "delay_hours", nullable = false)
    private Integer delayHours = 0;

    @Builder.Default
    @Column(nullable = false)
    private Boolean enabled = true;

    @Builder.Default
    @Column(name = "total_triggered", nullable = false)
    private Integer totalTriggered = 0;

    @Builder.Default
    @Column(name = "total_sent", nullable = false)
    private Integer totalSent = 0;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
        if (this.enabled == null) {
            this.enabled = true;
        }
        if (this.totalTriggered == null) this.totalTriggered = 0;
        if (this.totalSent == null) this.totalSent = 0;
        if (this.delayHours == null) this.delayHours = 0;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
