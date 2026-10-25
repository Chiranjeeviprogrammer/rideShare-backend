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
    name = "email_workflow_executions",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_workflow_recipient", columnNames = {"workflow_id", "contact_email"})
    },
    indexes = {
        @Index(name = "idx_exec_workflow_id", columnList = "workflow_id"),
        @Index(name = "idx_exec_email", columnList = "contact_email")
    }
)
public class EmailWorkflowExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workflow_id", nullable = false)
    private Long workflowId;

    @Column(name = "contact_email", nullable = false, length = 180)
    private String contactEmail;

    @Column(name = "status", nullable = false, length = 30) // SENT, SKIPPED_UNSUBSCRIBED, FAILED
    private String status;

    @Column(name = "executed_at", nullable = false)
    private LocalDateTime executedAt;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @PrePersist
    public void prePersist() {
        if (this.executedAt == null) {
            this.executedAt = LocalDateTime.now();
        }
    }
}
