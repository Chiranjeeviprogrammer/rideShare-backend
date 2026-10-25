package com.rideshare.backend.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@Entity
@Table(
    name = "audit_logs",
    indexes = {
        @Index(name = "idx_audit_admin", columnList = "admin_email"),
        @Index(name = "idx_audit_action", columnList = "action"),
        @Index(name = "idx_audit_entity", columnList = "entity_type"),
        @Index(name = "idx_audit_timestamp", columnList = "timestamp")
    }
)
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "admin_email", nullable = false)
    private String adminEmail;

    @Column(nullable = false)
    private String action; // USER_SUSPEND, USER_ACTIVATE, DRIVER_APPROVE, DRIVER_REJECT, RIDE_CANCEL, CONFIG_UPDATE, TEST_EMAIL_SENT, TICKET_RESOLVE

    @Column(name = "entity_type", nullable = false)
    private String entityType; // USER, DRIVER_APPLICATION, RIDE, BOOKING, COMMISSION, SUPPORT_TICKET, SETTINGS

    @Column(name = "entity_id")
    private String entityId;

    @Column(length = 2000)
    private String details;

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(nullable = false)
    private LocalDateTime timestamp;

    @PrePersist
    public void prePersist() {
        if (this.timestamp == null) {
            this.timestamp = LocalDateTime.now();
        }
    }

    public AuditLog() {
    }

    public AuditLog(Long id, String adminEmail, String action, String entityType, String entityId, String details, String ipAddress, LocalDateTime timestamp) {
        this.id = id;
        this.adminEmail = adminEmail;
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.details = details;
        this.ipAddress = ipAddress;
        this.timestamp = timestamp;
    }
}
