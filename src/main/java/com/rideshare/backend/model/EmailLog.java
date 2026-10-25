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
    name = "email_logs",
    indexes = {
        @Index(name = "idx_email_recipient", columnList = "recipient"),
        @Index(name = "idx_email_status", columnList = "status"),
        @Index(name = "idx_email_category", columnList = "category"),
        @Index(name = "idx_email_sent_at", columnList = "sent_at")
    }
)
public class EmailLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String recipient;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false)
    private String category; // SIGNUP_OTP, PASSWORD_RESET, BOOKING_CONFIRMATION, RIDE_UPDATE, DRIVER_STATUS, TEST_EMAIL, CONTACT_NOTIFICATION

    @Column(nullable = false)
    private String status; // SENT, FAILED, SIMULATED

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt;

    @PrePersist
    public void prePersist() {
        if (this.sentAt == null) {
            this.sentAt = LocalDateTime.now();
        }
    }
}
